//! Secure, user-bound persistence for small pairing records.
//!
//! On Windows the value is serialized in memory and protected with the current
//! user's DPAPI key before any bytes are written. The on-disk representation is
//! JSON containing only the DPAPI ciphertext.

#[cfg(not(windows))]
use anyhow::anyhow;
use anyhow::Result;
use serde::{de::DeserializeOwned, Serialize};
use std::path::Path;

const MAX_BLOB_BYTES: usize = 1 << 20;

#[cfg(windows)]
mod windows_impl {
    use super::{DeserializeOwned, Path, Result, Serialize, MAX_BLOB_BYTES};
    use anyhow::{anyhow, Context};
    use serde::{Deserialize, Serialize as SerdeSerialize};
    use std::{
        ffi::OsStr,
        fs::{self, File, OpenOptions},
        io::{Read, Write},
        os::windows::ffi::OsStrExt,
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
        time::{SystemTime, UNIX_EPOCH},
    };
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
        },
        Storage::FileSystem::{MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH},
    };
    use zeroize::Zeroize;

    const DPAPI_ENTROPY: &[u8] = b"AuraShare protected store v1";
    const BLOB_VERSION: u32 = 1;
    static TEMP_COUNTER: AtomicU64 = AtomicU64::new(0);

    #[derive(Debug, SerdeSerialize, Deserialize)]
    struct EncryptedBlob {
        version: u32,
        ciphertext: Vec<u8>,
    }

    fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
        CRYPT_INTEGER_BLOB {
            cbData: data.len() as u32,
            pbData: data.as_ptr() as *mut u8,
        }
    }

    fn protect(plaintext: &[u8]) -> Result<Vec<u8>> {
        let input = blob(plaintext);
        let entropy = blob(DPAPI_ENTROPY);
        let mut output = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: std::ptr::null_mut(),
        };
        // DPAPI is explicitly forbidden from displaying UI. The output buffer is
        // released with LocalFree as required by CryptProtectData.
        let ok = unsafe {
            CryptProtectData(
                &input,
                std::ptr::null(),
                &entropy,
                std::ptr::null_mut(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if ok == 0 {
            return Err(anyhow!(
                "DPAPI encryption failed: {}",
                std::io::Error::last_os_error()
            ));
        }
        let result =
            unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
        unsafe {
            LocalFree(output.pbData as *mut _);
        }
        Ok(result)
    }

    fn unprotect(ciphertext: &[u8]) -> Result<Vec<u8>> {
        let input = blob(ciphertext);
        let entropy = blob(DPAPI_ENTROPY);
        let mut output = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: std::ptr::null_mut(),
        };
        let ok = unsafe {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                &entropy,
                std::ptr::null_mut(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if ok == 0 {
            return Err(anyhow!(
                "DPAPI decryption failed: {}",
                std::io::Error::last_os_error()
            ));
        }
        let result =
            unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
        unsafe {
            std::slice::from_raw_parts_mut(output.pbData, output.cbData as usize).zeroize();
            LocalFree(output.pbData as *mut _);
        }
        // The caller receives a deserialized value, not this buffer; clear it as
        // soon as JSON parsing has consumed it.
        Ok(result)
    }

    fn read_bounded(path: &Path) -> Result<Option<Vec<u8>>> {
        let file = match File::open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => {
                return Err(error)
                    .with_context(|| format!("open protected store {}", path.display()))
            }
        };
        let metadata = file.metadata().context("stat protected store")?;
        if metadata.len() > MAX_BLOB_BYTES as u64 {
            return Err(anyhow!("protected store exceeds {} bytes", MAX_BLOB_BYTES));
        }
        let mut bytes = Vec::with_capacity(metadata.len() as usize);
        file.take((MAX_BLOB_BYTES + 1) as u64)
            .read_to_end(&mut bytes)
            .context("read protected store")?;
        if bytes.len() > MAX_BLOB_BYTES {
            return Err(anyhow!("protected store exceeds {} bytes", MAX_BLOB_BYTES));
        }
        Ok(Some(bytes))
    }

    fn temp_path(path: &Path, attempt: u64) -> PathBuf {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let counter = TEMP_COUNTER.fetch_add(1, Ordering::Relaxed);
        let name = format!(
            ".{}.aurashare-{}.{}-{}.tmp",
            path.file_name().and_then(|n| n.to_str()).unwrap_or("store"),
            std::process::id(),
            stamp,
            counter + attempt
        );
        path.parent().unwrap_or_else(|| Path::new(".")).join(name)
    }

    fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).with_context(|| {
                format!("create protected-store directory {}", parent.display())
            })?;
        }
        for attempt in 0..100 {
            let temporary = temp_path(path, attempt);
            let mut file = match OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)
            {
                Ok(file) => file,
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => {
                    return Err(error).with_context(|| {
                        format!(
                            "create protected-store temporary file {}",
                            temporary.display()
                        )
                    })
                }
            };
            let write_result = (|| -> Result<()> {
                file.write_all(bytes).context("write protected store")?;
                file.sync_all().context("flush protected store")?;
                Ok(())
            })();
            drop(file);
            if let Err(error) = write_result {
                let _ = fs::remove_file(&temporary);
                return Err(error);
            }
            let source: Vec<u16> = OsStr::new(&temporary)
                .encode_wide()
                .chain(std::iter::once(0))
                .collect();
            let destination: Vec<u16> = OsStr::new(path)
                .encode_wide()
                .chain(std::iter::once(0))
                .collect();
            let moved = unsafe {
                MoveFileExW(
                    source.as_ptr(),
                    destination.as_ptr(),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
                )
            };
            if moved == 0 {
                let error = std::io::Error::last_os_error();
                let _ = fs::remove_file(&temporary);
                return Err(error).context("atomically replace protected store");
            }
            return Ok(());
        }
        Err(anyhow!(
            "could not allocate a unique protected-store temporary file"
        ))
    }

    pub fn load<T: DeserializeOwned>(path: &Path) -> Result<Option<T>> {
        let Some(bytes) = read_bounded(path)? else {
            return Ok(None);
        };
        let parsed: EncryptedBlob =
            serde_json::from_slice(&bytes).context("parse protected-store envelope")?;
        if parsed.version != BLOB_VERSION || parsed.ciphertext.is_empty() {
            return Err(anyhow!("invalid protected-store envelope"));
        }
        let mut plaintext = unprotect(&parsed.ciphertext)?;
        if plaintext.len() > MAX_BLOB_BYTES {
            plaintext.zeroize();
            return Err(anyhow!(
                "decrypted protected store exceeds {} bytes",
                MAX_BLOB_BYTES
            ));
        }
        let value = serde_json::from_slice(&plaintext).context("parse decrypted protected store");
        plaintext.zeroize();
        Ok(Some(value?))
    }

    pub fn save<T: Serialize>(path: &Path, value: &T) -> Result<()> {
        let mut plaintext = serde_json::to_vec(value).context("serialize protected store")?;
        if plaintext.len() > MAX_BLOB_BYTES {
            plaintext.zeroize();
            return Err(anyhow!("protected store exceeds {} bytes", MAX_BLOB_BYTES));
        }
        let ciphertext = protect(&plaintext);
        plaintext.zeroize();
        let ciphertext = ciphertext?;
        let envelope = EncryptedBlob {
            version: BLOB_VERSION,
            ciphertext,
        };
        let bytes = serde_json::to_vec(&envelope).context("serialize protected-store envelope")?;
        if bytes.len() > MAX_BLOB_BYTES {
            return Err(anyhow!(
                "protected-store envelope exceeds {} bytes",
                MAX_BLOB_BYTES
            ));
        }
        atomic_write(path, &bytes)
    }
}

#[cfg(windows)]
pub use windows_impl::{load, save};

#[cfg(not(windows))]
pub fn load<T: DeserializeOwned>(_path: &Path) -> Result<Option<T>> {
    Err(anyhow!("protected store is unsupported on this platform"))
}

#[cfg(not(windows))]
pub fn save<T: Serialize>(_path: &Path, _value: &T) -> Result<()> {
    Err(anyhow!("protected store is unsupported on this platform"))
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use serde::{Deserialize, Serialize};
    use std::{fs, path::PathBuf};
    use tempfile::tempdir;

    #[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
    struct Record {
        name: String,
        secret: String,
    }

    fn path() -> (tempfile::TempDir, PathBuf) {
        let directory = tempdir().expect("temp directory");
        let path = directory.path().join("nested").join("pairing.json");
        (directory, path)
    }

    #[test]
    fn dpapi_roundtrip() {
        let (_directory, path) = path();
        let expected = Record {
            name: "desktop".into(),
            secret: "not-on-disk".into(),
        };
        save(&path, &expected).expect("save");
        assert_eq!(load::<Record>(&path).expect("load"), Some(expected));
        let disk = fs::read_to_string(path).expect("read envelope");
        assert!(!disk.contains("not-on-disk"));
    }

    #[test]
    fn updates_existing_store_atomically() {
        let (_directory, path) = path();
        save(
            &path,
            &Record {
                name: "first".into(),
                secret: "one".into(),
            },
        )
        .expect("first save");
        save(
            &path,
            &Record {
                name: "second".into(),
                secret: "two".into(),
            },
        )
        .expect("second save");
        assert_eq!(load::<Record>(&path).expect("load").unwrap().name, "second");
    }

    #[test]
    fn corrupted_data_is_rejected_without_reset() {
        let (_directory, path) = path();
        save(
            &path,
            &Record {
                name: "kept".into(),
                secret: "value".into(),
            },
        )
        .expect("save");
        fs::write(&path, b"{\"version\":1,\"ciphertext\":[1,2,3]}").expect("corrupt");
        assert!(load::<Record>(&path).is_err());
        assert!(path.exists());
    }
}
