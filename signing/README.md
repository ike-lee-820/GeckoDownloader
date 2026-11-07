# Fixed signing keystore

`ikelee-release.jks` — alias `ikelee`, store password and key password are both `liyikui917`
(RSA 2048, valid until 2056). The release workflow signs every APK with this file.

`keystore_base64.txt` is the same file as one-line base64, kept for convenience if you ever
want to move it into a Secret.

This key exists only so that every build has the same signature and the in-app updater can
install over an existing installation. It is deliberately public — see the Release section of
the root README. Do not use it for anything where the signature is meant to prove who built
the APK.

Rotating this file changes the signature: users with an older build must uninstall before
installing the new one. Keep this file unchanged across releases.
