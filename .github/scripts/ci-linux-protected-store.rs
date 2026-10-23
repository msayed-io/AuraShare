// Android CI fixture only: the real receiving server exercises its secure
// protocol on Linux, where the desktop product's Windows DPAPI is unavailable.
// This adapter is process-memory-only, writes nothing to disk, and refuses to
// operate unless the fixture's integration-test guard is enabled.
#[cfg(not(windows))]
mod ci_memory_impl {
    use super::{DeserializeOwned, Path, Result, Serialize, MAX_BLOB_BYTES};
    use anyhow::{anyhow, Context};
    use std::{
        collections::HashMap,
        path::PathBuf,
        sync::{Mutex, OnceLock},
    };

    static STORES: OnceLock<Mutex<HashMap<PathBuf, Vec<u8>>>> = OnceLock::new();

    fn stores() -> &'static Mutex<HashMap<PathBuf, Vec<u8>>> {
        STORES.get_or_init(|| Mutex::new(HashMap::new()))
    }

    fn require_test_mode() -> Result<()> {
        if std::env::var("AURASHARE_INTEGRATION_TEST").ok().as_deref() != Some("1") {
            return Err(anyhow!("CI memory protected store requires integration-test mode"));
        }
        Ok(())
    }

    pub fn load<T: DeserializeOwned>(path: &Path) -> Result<Option<T>> {
        require_test_mode()?;
        let bytes = stores()
            .lock()
            .map_err(|_| anyhow!("CI memory protected store lock poisoned"))?
            .get(path)
            .cloned();
        let Some(bytes) = bytes else { return Ok(None) };
        if bytes.len() > MAX_BLOB_BYTES {
            return Err(anyhow!("CI memory protected store exceeds {MAX_BLOB_BYTES} bytes"));
        }
        Ok(Some(serde_json::from_slice(&bytes).context("parse CI memory protected store")?))
    }

    pub fn save<T: Serialize>(path: &Path, value: &T) -> Result<()> {
        require_test_mode()?;
        let bytes = serde_json::to_vec(value).context("serialize CI memory protected store")?;
        if bytes.len() > MAX_BLOB_BYTES {
            return Err(anyhow!("CI memory protected store exceeds {MAX_BLOB_BYTES} bytes"));
        }
        stores()
            .lock()
            .map_err(|_| anyhow!("CI memory protected store lock poisoned"))?
            .insert(path.to_path_buf(), bytes);
        Ok(())
    }
}

#[cfg(not(windows))]
pub use ci_memory_impl::{load, save};
