# Android dependency notices

Chat uses official Signal libsignal 0.103.0 (AGPL-3.0-only). It is not affiliated with Signal. Corresponding source is supplied in Chat-Source.zip under vendor/. Chat's license is LICENSE-Chat.txt.

Android dependencies include OkHttp 4.12.0, Okio, Kotlin runtime and desugar JDK libraries. Their Apache-2.0 and additional notices are included in the adjacent LICENSE files.

io.github.webrtc-sdk:android 150.7871.01 declares BSD-3-Clause in its Maven POM. WebRTC's BSD license, third-party notices and the SDK repository's MIT license are included separately. The Maven artifact's declared license is distinct from the SDK repository license.

Upstream sources:
- https://github.com/signalapp/libsignal/tree/v0.103.0
- https://github.com/square/okhttp/tree/parent-4.12.0
- https://github.com/square/okio
- https://github.com/JetBrains/kotlin
- https://github.com/google/desugar_jdk_libs
- https://webrtc.googlesource.com/src/
- https://github.com/webrtc-sdk/android

This Android distribution does not contain Electron or ws. General project information is in NOTICE-Chat.md; platform licenses here apply to Android's actual dependencies.