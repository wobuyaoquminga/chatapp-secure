# Third-party software

This development prototype uses the official Signal libsignal library but is not affiliated with or endorsed by Signal.

- @signalapp/libsignal-client 0.103.0 — AGPL-3.0-only, https://github.com/signalapp/libsignal/tree/v0.103.0 . Corresponding upstream source is included in the source archive under vendor/.
- Electron 44.4.5 — MIT and third-party Chromium notices retained in the Windows distribution.
- ws 8.22.0 — MIT; dependency license retained.
- io.github.webrtc-sdk:android 150.7871.01 — BSD-3-Clause as declared by its Maven artifact; WebRTC has additional third-party notices, https://github.com/webrtc-sdk/android . Android assets contain the platform-specific notices.
- Spring Boot 3.5.16 — Apache-2.0; the server also uses Spring Security, JDBC, Flyway, H2, PostgreSQL JDBC and Nimbus JOSE JWT. These dependencies retain their respective licenses in their upstream distributions and nested dependency JARs; this project notice is not a replacement for their licenses.
- Android also uses org.signal:libsignal-client and org.signal:libsignal-android 0.103.0 (AGPL-3.0-only), OkHttp 4.12.0 (Apache-2.0), and the Android WebRTC SDK listed above. Android assets contain a separate platform-specific notice.

Application source and reproducible dependency lockfiles are supplied separately in Chat-Source.zip. Do not distribute the native client without its notices and corresponding source availability.
