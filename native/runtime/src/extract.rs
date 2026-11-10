use std::fs;
use std::io::{self, Read};
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::{Component, Path, PathBuf};

use tar::{Archive, EntryType};

/// What was extracted. `skipped` counts entries of unsupported types
/// (devices, FIFOs); they are reported, never silently dropped.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Report {
    pub dirs: u64,
    pub files: u64,
    pub symlinks: u64,
    pub hardlinks: u64,
    pub skipped: u64,
    /// Hard links Android refused (SELinux forbids them in app storage), stored as copies instead.
    pub hardlink_copies: u64,
}

fn bad(msg: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

/// Joins an archive-relative path onto `root`, rejecting `..`, absolute
/// paths and drive prefixes.
fn safe_join(root: &Path, rel: &Path) -> io::Result<PathBuf> {
    let mut out = root.to_path_buf();
    for c in rel.components() {
        match c {
            Component::Normal(p) => out.push(p),
            Component::CurDir => {}
            _ => return Err(bad(format!("unsafe path in archive: {}", rel.display()))),
        }
    }
    Ok(out)
}

/// Refuses to write through a symlinked directory, which could otherwise
/// redirect an entry outside `root`.
fn reject_symlink_ancestors(root: &Path, dest: &Path) -> io::Result<()> {
    let rel = dest
        .strip_prefix(root)
        .map_err(|_| bad(format!("path escapes root: {}", dest.display())))?;
    let mut cur = root.to_path_buf();
    if let Some(parent) = rel.parent() {
        for c in parent.components() {
            cur.push(c);
            if let Ok(md) = fs::symlink_metadata(&cur) {
                if md.file_type().is_symlink() {
                    return Err(bad(format!(
                        "archive writes through symlink: {}",
                        cur.display()
                    )));
                }
            }
        }
    }
    Ok(())
}

fn set_mode(path: &Path, mode: u32, floor: u32) -> io::Result<()> {
    fs::set_permissions(path, fs::Permissions::from_mode((mode & 0o1777) | floor))
}

fn prepare(dest: &Path) -> io::Result<()> {
    if let Some(p) = dest.parent() {
        fs::create_dir_all(p)?;
    }
    if let Ok(md) = fs::symlink_metadata(dest) {
        if !md.is_dir() {
            fs::remove_file(dest)?;
        }
    }
    Ok(())
}

fn ctx_err(e: io::Error, what: &str, path: &Path) -> io::Error {
    io::Error::new(e.kind(), format!("{} {}: {}", what, path.display(), e))
}

/// Adds the operation and path to an error, so a failure on a device says exactly what broke.
fn ctx<T>(r: io::Result<T>, what: &str, path: &Path) -> io::Result<T> {
    r.map_err(|e| ctx_err(e, what, path))
}

enum LinkKind {
    Linked,
    Copied,
}

/// Android's SELinux policy forbids hard links in app storage. When linking fails, keep the
/// content as a copy (same bytes and mode) rather than failing the whole install. The caller
/// counts copies, so this is reported, not hidden.
fn link_or_copy<F: Fn(&Path, &Path) -> io::Result<()>>(src: &Path, dest: &Path, link: F) -> io::Result<LinkKind> {
    match link(src, dest) {
        Ok(()) => Ok(LinkKind::Linked),
        Err(_) => {
            fs::copy(src, dest)?;
            Ok(LinkKind::Copied)
        }
    }
}

/// Extracts an uncompressed tar stream into `root` with path validation.
/// Any unsafe entry aborts extraction with an error.
pub fn extract<R: Read>(reader: R, root: &Path) -> io::Result<Report> {
    ctx(fs::create_dir_all(root), "create", root)?;
    let mut archive = Archive::new(reader);
    let mut rep = Report::default();

    for entry in archive.entries()? {
        let mut entry = entry?;
        let rel = entry.path()?.into_owned();
        let dest = safe_join(root, &rel)?;
        if dest == root {
            continue;
        }
        reject_symlink_ancestors(root, &dest)?;

        let mode = entry.header().mode().unwrap_or(0o644);
        match entry.header().entry_type() {
            EntryType::Directory => {
                ctx(fs::create_dir_all(&dest), "mkdir", &dest)?;
                ctx(set_mode(&dest, mode, 0o700), "chmod", &dest)?;
                rep.dirs += 1;
            }
            EntryType::Regular | EntryType::Continuous => {
                ctx(prepare(&dest), "prepare", &dest)?;
                let mut f = ctx(fs::File::create(&dest), "create", &dest)?;
                ctx(io::copy(&mut entry, &mut f), "write", &dest)?;
                drop(f);
                ctx(set_mode(&dest, mode, 0o600), "chmod", &dest)?;
                rep.files += 1;
            }
            EntryType::Symlink => {
                let target = entry
                    .link_name()?
                    .ok_or_else(|| bad(format!("symlink without target: {}", rel.display())))?
                    .into_owned();
                ctx(prepare(&dest), "prepare", &dest)?;
                ctx(symlink(&target, &dest), "symlink", &dest)?;
                rep.symlinks += 1;
            }
            EntryType::Link => {
                let target = entry
                    .link_name()?
                    .ok_or_else(|| bad(format!("hardlink without target: {}", rel.display())))?
                    .into_owned();
                let src = safe_join(root, &target)?;
                reject_symlink_ancestors(root, &src)?;
                ctx(prepare(&dest), "prepare", &dest)?;
                match link_or_copy(&src, &dest, |a, b| fs::hard_link(a, b)) {
                    Ok(LinkKind::Linked) => rep.hardlinks += 1,
                    Ok(LinkKind::Copied) => {
                        rep.hardlinks += 1;
                        rep.hardlink_copies += 1;
                    }
                    Err(e) => return Err(ctx_err(e, "hardlink", &dest)),
                }
            }
            _ => rep.skipped += 1,
        }
    }
    Ok(rep)
}

/// Extracts a `.tar.xz`, `.tar.gz`/`.tgz` or `.tar` file into `root`.
pub fn extract_file(archive: &Path, root: &Path) -> io::Result<Report> {
    let f = fs::File::open(archive)?;
    let name = archive.file_name().and_then(|n| n.to_str()).unwrap_or("");
    if name.ends_with(".tar.xz") {
        extract(xz2::read::XzDecoder::new(f), root)
    } else if name.ends_with(".tar.gz") || name.ends_with(".tgz") {
        extract(flate2::read::GzDecoder::new(f), root)
    } else if name.ends_with(".tar") {
        extract(f, root)
    } else {
        Err(bad(format!("unsupported archive type: {}", name)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use tar::{Builder, Header};

    /// Appends an entry with a raw, unvalidated name so hostile paths can be built.
    fn raw(b: &mut Builder<Vec<u8>>, name: &str, data: &[u8], ty: EntryType, link: Option<&str>) {
        let mut h = Header::new_gnu();
        h.as_old_mut().name[..name.len()].copy_from_slice(name.as_bytes());
        h.set_size(data.len() as u64);
        h.set_mode(0o644);
        h.set_entry_type(ty);
        if let Some(l) = link {
            h.set_link_name(l).unwrap();
        }
        h.set_cksum();
        b.append(&h, data).unwrap();
    }

    #[test]
    fn extracts_files_and_symlinks() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("root");
        let mut b = Builder::new(Vec::new());
        raw(&mut b, "etc/hello", b"hi", EntryType::Regular, None);
        raw(&mut b, "bin", b"", EntryType::Symlink, Some("usr/bin"));
        let bytes = b.into_inner().unwrap();

        let rep = extract(Cursor::new(bytes), &root).unwrap();
        assert_eq!(rep.files, 1);
        assert_eq!(rep.symlinks, 1);
        assert_eq!(fs::read(root.join("etc/hello")).unwrap(), b"hi");
        assert_eq!(fs::read_link(root.join("bin")).unwrap(), Path::new("usr/bin"));
    }

    #[test]
    fn rejects_parent_directory_traversal() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("root");
        let mut b = Builder::new(Vec::new());
        raw(&mut b, "../evil", b"x", EntryType::Regular, None);
        let err = extract(Cursor::new(b.into_inner().unwrap()), &root).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(!tmp.path().join("evil").exists());
    }

    #[test]
    fn rejects_absolute_paths() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("root");
        let mut b = Builder::new(Vec::new());
        raw(&mut b, "/tmp/linuxboot_abs_test", b"x", EntryType::Regular, None);
        let err = extract(Cursor::new(b.into_inner().unwrap()), &root).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn hardlink_falls_back_to_copy_when_linking_is_refused() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("a");
        let dst = tmp.path().join("b");
        fs::write(&src, b"data").unwrap();
        fs::set_permissions(&src, fs::Permissions::from_mode(0o755)).unwrap();
        let kind = link_or_copy(&src, &dst, |_, _| Err(io::Error::from(io::ErrorKind::PermissionDenied))).unwrap();
        assert!(matches!(kind, LinkKind::Copied));
        assert_eq!(fs::read(&dst).unwrap(), b"data");
        assert_eq!(fs::metadata(&dst).unwrap().permissions().mode() & 0o777, 0o755);
    }

    #[test]
    fn real_hardlinks_still_link_and_are_counted() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("root");
        let mut b = Builder::new(Vec::new());
        raw(&mut b, "a", b"x", EntryType::Regular, None);
        raw(&mut b, "b", b"", EntryType::Link, Some("a"));
        let rep = extract(Cursor::new(b.into_inner().unwrap()), &root).unwrap();
        assert_eq!(rep.hardlinks, 1);
        assert_eq!(fs::read(root.join("b")).unwrap(), b"x");
    }

    #[test]
    fn errors_name_the_operation_and_path() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("root");
        let mut b = Builder::new(Vec::new());
        raw(&mut b, "b", b"", EntryType::Link, Some("missing"));
        let err = extract(Cursor::new(b.into_inner().unwrap()), &root).unwrap_err();
        let msg = err.to_string();
        assert!(msg.contains("hardlink"), "{}", msg);
        assert!(msg.contains("root/b"), "{}", msg);
    }

    #[test]
    fn setuid_and_setgid_bits_are_not_applied() {
        let tmp = tempfile::tempdir().unwrap();
        let f = tmp.path().join("f");
        fs::write(&f, b"x").unwrap();
        set_mode(&f, 0o6755, 0o600).unwrap();
        assert_eq!(fs::metadata(&f).unwrap().permissions().mode() & 0o7000, 0);
    }

    #[test]
    fn rejects_write_through_symlink() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("root");
        let mut b = Builder::new(Vec::new());
        raw(&mut b, "link", b"", EntryType::Symlink, Some("/tmp"));
        raw(&mut b, "link/pwned", b"x", EntryType::Regular, None);
        let err = extract(Cursor::new(b.into_inner().unwrap()), &root).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }
}
