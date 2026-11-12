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
| Debian 13, Ubuntu 24.04, Arch Linux ARM, Fedora 44, Void (Linux Containers images; SHA-256 from SHA256SUMS) | In the catalog; not installed or run on a device. Desktop recipe for Debian/Ubuntu only |
| Linux user creation (the desktop currently runs as PRoot's fake root) | Not done |
| Backup/restore, CPU/RAM live monitor, recovery shell | Not done |
| Tested on a real device / low-end phone | **No** |

## Where distros come from

Alpine uses its own official minirootfs and `.sha256` file. Debian, Ubuntu, Arch, Fedora and Void use the arm64 `rootfs.tar.xz` images published by the Linux Containers project (images.linuxcontainers.org), because Debian's own `dist-arm64v8` branch does not publish a usable tarball URL. These are third-party builds of each distribution, not the distribution's own release. LinuxBoot reads the newest complete build and its SHA-256 from the `SHA256SUMS` next to it at install time; the checksum comes from the same server, so it detects corruption but not a compromised server, and the `.asc` signatures are not verified yet. I checked Debian, Ubuntu, Arch and Void builds exist; Fedora 44 was not individually checked (the resolver reports an error if its build is incomplete). Arch, Fedora and Void install the rootfs and shell only: their desktop recipes need package-manager workarounds under PRoot that I have not verified.

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

- Picture sharpness: the desktop is created at half your screen's resolution and drawn at exactly 2x with no smoothing, so pixels are crisp blocks rather than blurred. "Native" in the resolution sheet gives one desktop pixel per screen pixel (sharpest, but small and heavier). "Smooth scaling" in the desktop settings turns interpolation back on.

- Auto-keyboard in the desktop viewer works from the pointer shape (VNC does not report keyboard focus): tapping while the pointer is an I-beam opens the keyboard, tapping elsewhere closes it if it was opened that way. It will not open for a field that is focused but not under the pointer, and it also triggers over read-only text. Recognition is a heuristic tested on synthetic shapes. If your theme's I-beam is missed, open the desktop settings, put the pointer over a text field, and tap "Remember the current pointer as text"; the settings also show a picture of the pointer shape the server sent. It can be switched off in the same place.

- This PRoot build (5.1.0-mod-0.15) has no `--sysvipc`, and Android blocks SysV shared memory, so the X server runs with MIT-SHM disabled (slower drawing, but no crashes from blocked syscalls).
- Stopping a session relies on `--kill-on-exit`; if a desktop was left running, stop it from the distribution screen before removing or backing it up.

- Android's SELinux policy forbids hard links in app storage. Extraction stores each hard link as a copy of the file (extra disk space) and reports how many; setuid/setgid bits are not applied.

- Cancelling during extraction takes effect after the current extraction step finishes.
- Installs run while the app is in the foreground; no background service. An interrupted download resumes on the next Install.
- Extracted-size and RAM checks are heuristics.
- PRoot process launch is in Kotlin, not the Rust runtime.
- The VNC client supports Raw and CopyRect only: fine on loopback, but heavier than compressed encodings. Changing resolution restarts the desktop.
- If the shell test or desktop fails on a device, `PROOT_NO_SECCOMP=1` is the first thing to try; it is not set by default.
