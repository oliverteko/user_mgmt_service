from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_version: str = "0.1.0"
    log_level: str = "INFO"
    database_url: str
    mysql_ssl_disabled: bool = True
    # Path to the CA certificate of the managed MySQL cluster (DigitalOcean
    # enforces TLS). When set, the server certificate is verified against it.
    mysql_ssl_ca: str | None = None


@lru_cache
def get_settings() -> Settings:
    return Settings()
