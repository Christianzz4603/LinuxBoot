use std::fs;
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};

use tar::{Builder, EntryType, Header};
use xz2::write::XzEncoder;

use crate::extract::Report;

/// Creates a `.tar.xz` of the tree at `root` without following symlinks (they are stored as
/// symlinks). Sockets, FIFOs and device nodes cannot be archived and are counted in
/// `Report::skipped`, never silently dropped. Writes `<out>.part` and renames it on success,
/// so a failed backup never leaves a half-written archive under the final name.
pub fn create_tar_xz(root: &Path, out: &Path) -> io::Result<Report> {
    let mut part_name = out.as_os_str().to_owned();
    part_name.push(".part");
    let part = PathBuf::from(part_name);

    let result = (|| -> io::Result<Report> {
        let file = fs::File::create(&part)?;
        // liblzma's multi-threaded encoder: backups use every core the phone offers.
        let threads = std::thread::available_parallelism().map(|n| n.get() as u32).unwrap_or(1).min(8);
        let stream = xz2::stream::MtStreamBuilder::new()
            .threads(threads)
            .preset(3)
            .check(xz2::stream::Check::Crc64)
            .encoder()
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
        let mut builder = Builder::new(XzEncoder::new_stream(BufWriter::with_capacity(1 << 20, file), stream));
        builder.follow_symlinks(false);
        let mut rep = Report::default();
        walk(&mut builder, root, Path::new(""), &mut rep)?;
        let encoder = builder.into_inner()?;
        let mut file = encoder.finish()?;
        file.flush()?;
        Ok(rep)
    })();

    match result {
        Ok(rep) => {
            fs::rename(&part, out)?;
            Ok(rep)
        }
        Err(e) => {
            let _ = fs::remove_file(&part);
            Err(e)
        }
    }
}

fn walk<W: Write>(b: &mut Builder<W>, root: &Path, rel: &Path, rep: &mut Report) -> io::Result<()> {
    let mut entries = fs::read_dir(root.join(rel))?.collect::<Result<Vec<_>, _>>()?;
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        let name = rel.join(e.file_name());
        let path = e.path();
        let meta = fs::symlink_metadata(&path)?;
        let ft = meta.file_type();
        if ft.is_dir() {
            b.append_dir(&name, &path)?;
            rep.dirs += 1;
            walk(b, root, &name, rep)?;
        } else if ft.is_symlink() {
            let target = fs::read_link(&path)?;
            let mut h = Header::new_gnu();
            h.set_metadata(&meta);
            h.set_entry_type(EntryType::Symlink);
            h.set_size(0);
            b.append_link(&mut h, &name, &target)?;
            rep.symlinks += 1;
        } else if ft.is_file() {
            b.append_path_with_name(&path, &name)?;
            rep.files += 1;
        } else {
            rep.skipped += 1;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::extract::extract_file;
    use std::os::unix::fs::{symlink, PermissionsExt};
    use std::os::unix::net::UnixListener;

    #[test]
    fn roundtrip_preserves_files_symlinks_modes_and_reports_skipped_sockets() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("src");
        fs::create_dir_all(src.join("usr/bin")).unwrap();
        fs::write(src.join("usr/bin/tool"), b"#!/bin/sh\n").unwrap();
        fs::set_permissions(src.join("usr/bin/tool"), fs::Permissions::from_mode(0o755)).unwrap();
        fs::write(src.join("etc.txt"), b"hello").unwrap();
        symlink("usr/bin", src.join("bin")).unwrap();
        symlink("/abs/target", src.join("abs")).unwrap();
        let _sock = UnixListener::bind(src.join("sock")).unwrap();

        let out = tmp.path().join("b.tar.xz");
        let rep = create_tar_xz(&src, &out).unwrap();
        assert_eq!(rep.skipped, 1, "the socket must be reported");
        assert_eq!(rep.symlinks, 2);
        assert_eq!(rep.files, 2);
        assert_eq!(rep.dirs, 2);

        let dst = tmp.path().join("dst");
        extract_file(&out, &dst).unwrap();
        assert_eq!(fs::read(dst.join("etc.txt")).unwrap(), b"hello");
        assert_eq!(fs::read_link(dst.join("bin")).unwrap(), Path::new("usr/bin"));
        assert_eq!(fs::read_link(dst.join("abs")).unwrap(), Path::new("/abs/target"));
        let mode = fs::metadata(dst.join("usr/bin/tool")).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o755);
        assert!(!dst.join("sock").exists());
        assert!(!tmp.path().join("b.tar.xz.part").exists());
    }

    #[test]
    fn failed_backup_leaves_no_archive() {
        let tmp = tempfile::tempdir().unwrap();
        let out = tmp.path().join("b.tar.xz");
        assert!(create_tar_xz(&tmp.path().join("does-not-exist"), &out).is_err());
        assert!(!out.exists());
        assert!(!tmp.path().join("b.tar.xz.part").exists());
    }
}
