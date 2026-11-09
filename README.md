# LinuxBoot

Run real Linux distros on Android via PRoot. No emulation, just native ARM64.

## Status

Nothing below has been run on a device yet. Only the Rust extraction/checksum tests have a CI job that executes them.

| Area | State |
| --- | --- |
| Firmware-style UI, real system info | Implemented |
| Storage layout under `getExternalFilesDir()/LinuxBoot`, plus an on-device filesystem test that decides where the rootfs goes | Implemented |
| In-app install of **Alpine** (resolve, resumable download, SHA-256, safe extract, DNS config, register) | Implemented |
| Boot Manager: list, real disk usage, remove with confirmation | Implemented |
| Rust: SHA-256, hardened tar extraction, JNI | Implemented; extraction/checksum covered by `cargo test` |
| **PRoot**: built from source in CI, packaged as `libproot.so` + `libproot-loader.so` | Implemented in CI, unverified |
| Run a command in a distro under PRoot (Boot Manager > distro > Run shell test) | Implemented, unverified |
| Debian, Ubuntu, Arch, Fedora, Void | Not in the catalog: no verified source yet |
| Linux user creation, package and desktop install, VNC viewer, desktop boot | Not started |

## PRoot build

CI builds PRoot with [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android), pinned to a signed commit, using NDK 23.2.8568313 (the version that recipe targets). It produces a static aarch64 PRoot with an unbundled loader. The APK is packaged with `useLegacyPackaging` so Android extracts the files to `nativeLibraryDir`, the only place an app may execute its own binaries. That recipe's last update was in 2023 (PRoot 0.15), so expect to revisit it.

## Storage

Everything lives in app-specific external storage when possible:
`/storage/emulated/0/Android/data/<package>/files/LinuxBoot/` (resolved with `getExternalFilesDir`, never hardcoded).

That storage is usually FUSE-backed and often lacks symlinks and permission bits, which a Linux rootfs needs. LinuxBoot therefore **tests** the filesystem on the device. If the test fails, only the rootfs and home of each distro move to internal app storage; downloads, backups, cache, config, logs and metadata stay external. **System Configuration** shows the test results and the placement chosen.

## Known limits

- Alpine's checksum and tarball come from the same HTTPS origin; the GPG signature is not verified yet.
- Cancelling during extraction takes effect after the current extraction step finishes.
- Installs run while the app is in the foreground; there is no background service. An interrupted download resumes on the next Install.
- Extracted-size checks are estimates.
- PRoot process launch is in Kotlin for now, not the Rust runtime.
- If the shell test fails on a device, first try setting `PROOT_NO_SECCOMP=1`; it is not set by default.
