use crate::relay::{
    RelayCredentialStore, RelayCredentials, RelayError, RelayPublisher, credential_path,
    sequence_path,
};
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub enum RelayHostError {
    Relay(RelayError),
}

impl std::fmt::Display for RelayHostError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Relay(error) => write!(formatter, "relay host failed: {error}"),
        }
    }
}

impl std::error::Error for RelayHostError {}

impl From<RelayError> for RelayHostError {
    fn from(value: RelayError) -> Self {
        Self::Relay(value)
    }
}

#[derive(Debug, Clone)]
pub struct RelayHostPaths {
    pub credential_file: PathBuf,
    pub sequence_file: PathBuf,
}

impl RelayHostPaths {
    pub fn in_data_directory(directory: impl AsRef<Path>) -> Self {
        let directory = directory.as_ref();
        Self {
            credential_file: credential_path(directory),
            sequence_file: sequence_path(directory),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayPairingPresentation {
    pub deep_link: String,
    pub topic_hint: String,
}

pub struct RelayHost {
    base_url: String,
    credential_store: RelayCredentialStore,
    publisher: RelayPublisher,
}

impl RelayHost {
    pub fn start(paths: RelayHostPaths, base_url: &str) -> Result<Self, RelayHostError> {
        let credential_store = RelayCredentialStore::new(paths.credential_file);
        let credentials = credential_store.load_or_create(base_url)?;
        let publisher = RelayPublisher::new(credentials, paths.sequence_file)?;
        Ok(Self {
            base_url: base_url.to_string(),
            credential_store,
            publisher,
        })
    }

    pub fn publisher(&self) -> RelayPublisher {
        self.publisher.clone()
    }

    pub fn relay_ready(&self) -> bool {
        true
    }

    pub async fn begin_pairing(&self) -> Result<RelayPairingPresentation, RelayHostError> {
        let credentials = self.credential_store.rotate(&self.base_url)?;
        self.publisher.set_credentials(credentials.clone()).await?;
        presentation(&credentials).map_err(Into::into)
    }

    pub async fn revoke_phone(&self) -> Result<(), RelayHostError> {
        let credentials = self.credential_store.rotate(&self.base_url)?;
        self.publisher.set_credentials(credentials).await?;
        Ok(())
    }

    pub fn shutdown(self) {}
}

fn presentation(credentials: &RelayCredentials) -> Result<RelayPairingPresentation, RelayError> {
    Ok(RelayPairingPresentation {
        deep_link: credentials.pairing_deep_link()?,
        topic_hint: credentials.topic_hint(),
    })
}
