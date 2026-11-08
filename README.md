# LinuxBoot

Run real Linux distros on Android via PRoot. No emulation, just native ARM64.

## Status

| Area | State |
| --- | --- |
| Firmware-style UI, real system info | Implemented, not yet run on a device |
| Storage layout under `getExternalFilesDir()/LinuxBoot` | Implemented, not yet run on a device |
| On-device filesystem test (symlink, chmod, hardlink, case) deciding where the rootfs goes | Implemented, not yet run on a device |
| In-app install of **Alpine** (resolve, resumable download, SHA-256, safe extract, DNS config, register) | Implemented, not yet run on a device |
| Boot Manager: list, real disk usage, remove with confirmation | Implemented, not yet run on a device |
| Rust: SHA-256, hardened tar extraction, JNI | Extraction and checksum covered by `cargo test` in CI; JNI untested |
| **PRoot** | **Not bundled** - nothing can boot yet |
| Debian, Ubuntu, Arch, Fedora, Void | Not in the catalog: no verified source yet |
| Package/desktop install, Linux user creation, VNC viewer | Not started (need PRoot) |

## Storage

Everything lives in app-specific external storage when possible:
`/storage/emulated/0/Android/data/<package>/files/LinuxBoot/` (resolved with `getExternalFilesDir`, never hardcoded).

That storage is usually FUSE-backed and often lacks symlinks and permission bits, which a Linux rootfs needs. LinuxBoot therefore **tests** the filesystem on the device. If the test fails, only the rootfs and home of each distro move to internal app storage; downloads, backups, cache, config, logs and metadata stay external. **System Configuration** shows the test results and the placement chosen.

## Known limits

- Alpine's checksum and tarball come from the same HTTPS origin; the GPG signature is not verified yet.
- Cancelling during extraction takes effect after the current extraction step finishes.
- Installs run while the app is in the foreground; there is no background service. An interrupted download resumes on the next Install.
- Extracted-size checks are estimates.
