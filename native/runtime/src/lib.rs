//! LinuxBoot native runtime (Phase 2, work in progress).
//!
//! Implemented: checksum verification, hardened archive extraction, JNI bridge.
//! Not yet implemented: PRoot launch and process management.

pub mod archive;
pub mod extract;
pub mod jni_api;
pub mod verify;
