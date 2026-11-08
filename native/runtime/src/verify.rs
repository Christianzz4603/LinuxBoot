use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

/// Streams a file through SHA-256 and returns the lowercase hex digest.
pub fn sha256_file(path: &Path) -> io::Result<String> {
    let mut f = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize().iter().map(|b| format!("{:02x}", b)).collect())
}

/// Errors with `InvalidData` unless the file's SHA-256 equals `expected` (hex).
pub fn verify_sha256(path: &Path, expected: &str) -> io::Result<()> {
    let actual = sha256_file(path)?;
    if actual.eq_ignore_ascii_case(expected.trim()) {
        Ok(())
    } else {
        Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("checksum mismatch: expected {}, got {}", expected.trim(), actual),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    const ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    fn file_with(data: &[u8]) -> tempfile::NamedTempFile {
        let mut f = tempfile::NamedTempFile::new().unwrap();
        f.write_all(data).unwrap();
        f.flush().unwrap();
        f
    }

    #[test]
    fn accepts_matching_digest() {
        let f = file_with(b"abc");
        assert_eq!(sha256_file(f.path()).unwrap(), ABC);
        verify_sha256(f.path(), ABC).unwrap();
    }

    #[test]
    fn rejects_wrong_digest() {
        let f = file_with(b"abd");
        let err = verify_sha256(f.path(), ABC).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }
}
