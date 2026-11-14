# Third-party notices

This project includes or depends on the following third-party software.

## npm dependencies (Host plugin and tooling)

| Package | License | Notes |
| --- | --- | --- |
| `@deepseek-ai/dsh` and related `@deepseek-ai/*` packages | MIT | Native API bindings locked to `0.2.1-rc.5`; build-time type snapshots only. |
| `typescript` | Apache-2.0 | Build tooling; not distributed to end users. |
| `tsx`, `zod`, `ajv`, `ws`, `jose`, `qrcode` | MIT | Build, authentication and transport dependencies. |
| `yaml` | ISC | Build tooling. |
| `canonicalize` | Apache-2.0 | RFC 8785 canonical JSON. |
| `werift` | MIT | WebRTC transport, pinned to `0.27.0`; local resource-budget patches are applied with exact source hashes. Retain its bundled third-party notices. |

Go dependencies are declared in `services/remote/go.mod`; their licenses are carried by the Go module proxy metadata.

## Shared native encryption engine

| Package | License | Use |
| --- | --- | --- |
| `snow` `0.10.0` | Apache-2.0 OR MIT | Fixed-suite Noise state machine; compiled for Node-API and JNI. |
| `zeroize` `1.8.2` | Apache-2.0 OR MIT | Clearing owned temporary secret buffers. |
| `napi`, `napi-derive` | MIT | Node binding. |
| `jni` `0.21.1` | MIT OR Apache-2.0 | JNI binding. |

Exact transitive Rust dependencies are recorded in `native/noise-carrier/Cargo.lock`.

## Android transport and platform dependencies

| Package | License | Use |
| --- | --- | --- |
| Apache HttpComponents Core 5 | Apache-2.0 | Native HTTP over authenticated carrier sockets. |
| Java-WebSocket | MIT | Public WebSocket codecs over real TLS and encrypted carrier sockets. |
| OkHttp | Apache-2.0 | Platform TLS, proxy configuration and account HTTP. |
| WebRTC | BSD-3-Clause and bundled third-party licenses | Data-only native transport, built from a pinned upstream source with local resource-budget and factory configuration patches. Retain the source and generated distribution notices. |
| AndroidX | Apache-2.0 | Android platform and UI components. |
| Kotlin and kotlinx.coroutines | Apache-2.0 | Language runtime and concurrency. |

Android package versions are recorded in `android/gradle/libs.versions.toml` and Gradle lockfiles. Source-built WebRTC revision, patch and toolchain pins are recorded in `native/webrtc/source.lock.json`. Individual dependencies retain their own license terms; this file does not replace their license texts.
