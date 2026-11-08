# LinuxBoot

Run real Linux distros on Android via PRoot. No emulation, just native ARM64.

## Status

| Area | State |
| --- | --- |
| Firmware-style home screen, real system info | Implemented (Phase 1), not yet run on a device |
| Rust: SHA-256 verification, hardened tar extraction | Implemented, covered by `cargo test` in CI |
| PRoot binary | **Not yet sourced** (see below) |
| JNI bindings, process launch, installer, desktop viewer | Not started |

### Open items

- **PRoot**: an Android-compatible `proot` (plus loader) must be built or obtained and shipped as `libproot.so` in `jniLibs`. Nothing is bundled yet.
- **Debian rootfs**: the official source is the `dist-arm64v8` branch of `debuerreotype/docker-debian-artifacts`, which publishes `rootfs.tar.xz.sha256`. The tarball download URL still needs to be confirmed before the installer pins it.
