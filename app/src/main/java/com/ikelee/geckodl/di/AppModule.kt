package com.ikelee.geckodl.di

import android.content.Context
import androidx.room.Room
import com.ikelee.geckodl.data.db.AppDatabase
import com.ikelee.geckodl.data.db.ChunkDao
import com.ikelee.geckodl.data.db.DownloadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "gdl.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDownloadDao(db: AppDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideChunkDao(db: AppDatabase): ChunkDao = db.chunkDao()

    /**
     * One shared client for the download engine and the updater.
     *
     * OkHttp defaults to maxRequestsPerHost = 5, which would silently cap a 128-thread
     * download to 5 concurrent ranges. Both limits are raised here; the connection pool
     * grows with them. No read timeout so long-running chunk streams are not cut off.
     */
    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 512
                    maxRequestsPerHost = 128
                }
            )
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(128, 5, TimeUnit.MINUTES))
            .build()
}
