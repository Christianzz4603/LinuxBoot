# LinuxBoot

Run real Linux distros on Android via PRoot. No emulation, just native ARM64.

## Status

CI (GitHub Actions) builds the APK, builds PRoot from source, and runs the Rust tests and the Kotlin unit tests. **Nothing has been run on a phone yet.** Everything marked "implemented" below compiles and is unit-tested only where stated.

| Area | State |
| --- | --- |
| Firmware-style UI, live system info | Implemented, compiles |
| Storage layout under `getExternalFilesDir()/LinuxBoot` + on-device filesystem test that decides where the rootfs goes | Implemented, compiles |
| In-app install of **Alpine** (resolve, resumable download, SHA-256, safe extract, DNS config, register) | Implemented, compiles |
| Boot Manager: list, real disk usage, remove with confirmation | Implemented, compiles |
| Rust: SHA-256, hardened tar extraction, JNI | Extraction/checksum covered by `cargo test` (passes in CI) |
| PRoot built from source in CI, packaged as `libproot.so` + `libproot-loader.so` | Builds in CI; not executed on a device |
| Run a command in a distro under PRoot (shell test) | Implemented, not executed on a device |
| Desktop install for Alpine (XFCE + TigerVNC, streamed progress) | Implemented, not executed on a device |
| Start Xvnc + XFCE under PRoot (loopback only, random VNC password) | Implemented, not executed on a device |
| Embedded VNC client (RFB 3.8, VNC auth, Raw/CopyRect/DesktopSize) | **Unit-tested in CI** against an in-process fake server |
| Viewer UI: trackpad gestures, double-tap-hold drag, 2-finger scroll/right-click, 3-finger middle click, pinch zoom, pan, direct-touch mode, floating toolbar (minimize/auto-hide/pin/drag), virtual keyboard with modifier latching, hardware keyboard + mouse | Implemented, compiles; gestures untested on a device |
| RAM check before starting a graphical session | Implemented (700 MiB free heuristic) |
| Debian, Ubuntu, Arch, Fedora, Void | **Not in the catalog**: no verified ARM64 source resolved yet (see below) |
| Linux user creation (the desktop currently runs as PRoot's fake root) | Not done |
| Backup/restore, CPU/RAM live monitor, recovery shell | Not done |
| Tested on a real device / low-end phone | **No** |

## Why only Alpine

A distro is listed only after its ARM64 download and checksum source were verified. Alpine's official minirootfs and its `.sha256` file are. Debian's official `dist-arm64v8` branch publishes a checksum but not the tarball at a stable raw URL (its OCI layer sits behind Git LFS), so Debian is not added until a verifiable source is confirmed. Ubuntu Base (official `SHA256SUMS`) is the obvious next candidate.

## PRoot build

CI builds PRoot with [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android), pinned to a signed commit, in an Ubuntu 20.04 container (talloc 2.1.14's waf needs Python 2; the loader step needs gawk) with NDK 23.2.8568313. The APK uses `useLegacyPackaging` so Android extracts the binaries to `nativeLibraryDir`, the only place an app may execute its own files. That recipe was last updated in 2023 (PRoot 0.15), so expect to revisit it.

## Storage

Everything lives in app-specific external storage when possible: `/storage/emulated/0/Android/data/<package>/files/LinuxBoot/` (resolved with `getExternalFilesDir`, never hardcoded).

That storage is usually FUSE-backed and often lacks symlinks and permission bits, which a Linux rootfs needs. LinuxBoot therefore **tests** the filesystem on the device. If the test fails, only the rootfs and home of each distro move to internal app storage; downloads, backups, cache, config, logs and metadata stay external. **System Configuration** shows the test results and the placement chosen.

## Security notes

- The VNC server binds to loopback and requires a random per-installation password, because any app on the device can reach 127.0.0.1.
- Archive extraction rejects `..`, absolute paths and writes through symlinks; deletions never follow symlinks and refuse paths outside LinuxBoot's storage.
- Alpine's checksum and tarball come from the same HTTPS origin; the GPG signature is not verified yet.
- The VNC password is stored in the distro's `metadata.json` in app-specific storage.

## Known limits

- Cancelling during extraction takes effect after the current extraction step finishes.
- Installs run while the app is in the foreground; no background service. An interrupted download resumes on the next Install.
- Extracted-size and RAM checks are heuristics.
- PRoot process launch is in Kotlin, not the Rust runtime.
- The VNC client supports Raw and CopyRect only: fine on loopback, but heavier than compressed encodings. Changing resolution restarts the desktop.
- If the shell test or desktop fails on a device, `PROOT_NO_SECCOMP=1` is the first thing to try; it is not set by default.
