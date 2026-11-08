//! LinuxBoot native runtime (Phase 2, work in progress).
//!
//! Implemented: checksum verification and hardened archive extraction.
//! Not yet implemented: PRoot launch, process management, JNI bindings.

pub mod extract;
pub mod verify;
