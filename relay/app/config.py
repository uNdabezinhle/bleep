from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    bleep_region: str = "ZA-JHB"
    database_url: str = "sqlite+aiosqlite:///./bleep.db"
    bleep_token_secret: str = "dev-only-change-me"
    bleep_ops_token: str = "dev-ops"
    bleep_max_ttl_seconds: int = 36 * 3600
    bleep_envelope_max_bytes: int = 8 * 1024 * 1024
    bleep_mailbox_quota_bytes: int = 64 * 1024 * 1024
    bleep_drops_per_hour: int = 400
    bleep_prekey_fetch_per_hour: int = 120
    bleep_handle_min_len: int = 3
    bleep_handle_max_len: int = 24
    bleep_env: str = "dev"
    bleep_public_origin: str = "https://bleep.de5.net"
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_pass: str = ""
    smtp_from: str = ""
    bleep_vapid_public: str = ""
    bleep_vapid_private: str = ""
    bleep_vapid_sub: str = "mailto:hello@bleep.de5.net"


settings = Settings()
