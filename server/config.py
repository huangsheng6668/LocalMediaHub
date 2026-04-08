from __future__ import annotations

from pathlib import Path
from typing import List

import yaml
from pydantic import BaseModel, field_validator


class ServerConfig(BaseModel):
    host: str = "0.0.0.0"
    port: int = 8000


class ScanConfig(BaseModel):
    roots: List[str] = []
    video_extensions: List[str] = [".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv"]
    image_extensions: List[str] = [".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"]

    @field_validator("roots")
    @classmethod
    def resolve_roots(cls, v: List[str]) -> List[str]:
        return [str(Path(p).resolve()) for p in v]


class ThumbnailConfig(BaseModel):
    cache_dir: str = ".cache/thumbnails"
    max_size: int = 300
    format: str = "JPEG"


class AppConfig(BaseModel):
    server: ServerConfig = ServerConfig()
    scan: ScanConfig = ScanConfig()
    thumbnail: ThumbnailConfig = ThumbnailConfig()


def load_config(config_path: str = "config.yaml") -> AppConfig:
    path = Path(config_path)
    if path.exists():
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        return AppConfig(**data)
    return AppConfig()
