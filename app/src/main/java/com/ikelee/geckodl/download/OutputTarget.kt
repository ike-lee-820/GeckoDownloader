package com.ikelee.geckodl.download

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access writer for either an app file path or a SAF document URI.
 * Positional writes let parallel chunks write into one preallocated file.
 */
class OutputTarget private constructor(private val pfd: ParcelFileDescriptor) : Closeable {

    private val channel: FileChannel = FileOutputStream(pfd.fileDescriptor).channel

    fun ensureSize(size: Long) {
        if (size > 0 && channel.size() < size) {
            channel.write(ByteBuffer.wrap(byteArrayOf(0)), size - 1)
        }
    }

    fun write(position: Long, buffer: ByteBuffer) {
        var pos = position
        while (buffer.hasRemaining()) {
            pos += channel.write(buffer, pos)
        }
    }

    fun truncate() = channel.truncate(0)

    fun sizeBytes(): Long = channel.size()

    fun appendFrom(file: File) {
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            var pos = channel.size()
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                write(pos, ByteBuffer.wrap(chunk, 0, n))
                pos += n
            }
        }
    }

    override fun close() {
        runCatching { channel.force(true) }
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }

    companion object {
        fun open(context: Context, uri: String): OutputTarget {
            val pfd = if (uri.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "rw")
                    ?: throw IllegalStateException("Cannot open $uri")
            } else {
                ParcelFileDescriptor.open(
                    File(uri),
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
                )
            }
            return OutputTarget(pfd)
        }

        /**
         * Returns a unique output URI for a new file.
         * Plain files get " (1)", " (2)" suffixes. SAF names use the same rule via DocumentFile.
         */
        fun allocate(context: Context, dirUri: String?, fileName: String, defaultDir: File): String {
            if (dirUri == null) {
                defaultDir.mkdirs()
                return uniqueFile(defaultDir, fileName).absolutePath
            }
            val tree = DocumentFile.fromTreeUri(context, android.net.Uri.parse(dirUri))
                ?: throw IllegalStateException("Download folder is not accessible")
            val existing = tree.listFiles().mapNotNull { it.name }.toSet()
            val name = uniqueName(fileName) { it !in existing }
            val doc = tree.createFile(mimeFor(name), name)
                ?: throw IllegalStateException("Cannot create $name")
            return doc.uri.toString()
        }

        private fun uniqueFile(dir: File, fileName: String): File {
            val name = uniqueName(fileName) { !File(dir, it).exists() }
            return File(dir, name)
        }

        private fun uniqueName(fileName: String, free: (String) -> Boolean): String {
            if (free(fileName)) return fileName
            val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
            val base = fileName.substring(0, dot)
            val ext = fileName.substring(dot)
            var i = 1
            while (true) {
                val candidate = "$base ($i)$ext"
                if (free(candidate)) return candidate
                i++
            }
        }

        private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

        /** Used only so the import stays referenced when DocumentsContract is not needed on a path. */
        internal fun documentIdOf(uri: String): String? =
            runCatching { DocumentsContract.getDocumentId(android.net.Uri.parse(uri)) }.getOrNull()
    }
}
