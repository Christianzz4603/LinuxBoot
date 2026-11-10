//! JNI entry points for linuxboot.runtime.NativeRuntime.
//! Each returns a status string: "OK" or "OK:<report>" on success,
//! "ERR:<message>" on failure. Panics are caught and reported, never unwound into the JVM.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::{archive, extract, verify};

fn read(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(String::from).map_err(|e| e.to_string())
}

fn guarded<F: FnOnce() -> Result<String, String>>(f: F) -> String {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(s)) => s,
        Ok(Err(e)) => format!("ERR:{}", e),
        Err(_) => "ERR:native panic".to_string(),
    }
}

fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_linuxboot_runtime_NativeRuntime_nativeVerifySha256<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    expected: JString<'l>,
) -> jstring {
    let out = guarded(|| {
        let p = read(&mut env, &path)?;
        let e = read(&mut env, &expected)?;
        verify::verify_sha256(Path::new(&p), &e).map_err(|e| e.to_string())?;
        Ok("OK".to_string())
    });
    to_jstring(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_linuxboot_runtime_NativeRuntime_nativeExtract<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    archive: JString<'l>,
    root: JString<'l>,
) -> jstring {
    let out = guarded(|| {
        let a = read(&mut env, &archive)?;
        let r = read(&mut env, &root)?;
        let rep = extract::extract_file(Path::new(&a), Path::new(&r)).map_err(|e| e.to_string())?;
        Ok(format!(
            "OK:{},{},{},{},{},{}",
            rep.dirs, rep.files, rep.symlinks, rep.hardlinks, rep.skipped, rep.hardlink_copies
        ))
    });
    to_jstring(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_linuxboot_runtime_NativeRuntime_nativeBackup<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    root: JString<'l>,
    out: JString<'l>,
) -> jstring {
    let res = guarded(|| {
        let r = read(&mut env, &root)?;
        let o = read(&mut env, &out)?;
        let rep = archive::create_tar_xz(Path::new(&r), Path::new(&o)).map_err(|e| e.to_string())?;
        Ok(format!(
            "OK:{},{},{},{},{},{}",
            rep.dirs, rep.files, rep.symlinks, rep.hardlinks, rep.skipped, rep.hardlink_copies
        ))
    });
    to_jstring(&mut env, res)
}
