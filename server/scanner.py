from __future__ import annotations

import asyncio
import mimetypes
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

from server.config import AppConfig
from server.models import Folder, MediaFile, MediaType

_scan_cache: list[MediaFile] = []
_cache_timestamp: float = 0.0
_CACHE_TTL = 60.0  # seconds


def _is_allowed(path: Path, roots: list[str]) -> bool:
    resolved = path.resolve()
    return any(resolved.is_relative_to(Path(r).resolve()) for r in roots)


def _get_media_type(extension: str, config: AppConfig) -> Optional[MediaType]:
    ext = extension.lower()
    if ext in config.scan.video_extensions:
        return MediaType.VIDEO
    if ext in config.scan.image_extensions:
        return MediaType.IMAGE
    return None


def scan_directory(
    dir_path: str,
    config: AppConfig,
    roots: list[str],
) -> tuple[list[Folder], list[MediaFile]]:
    path = Path(dir_path).resolve()

    if not _is_allowed(path, roots):
        raise ValueError(f"Path not allowed: {dir_path}")

    if not path.is_dir():
        raise ValueError(f"Not a directory: {dir_path}")

    folders: list[Folder] = []
    files: list[MediaFile] = []

    for entry in sorted(path.iterdir(), key=lambda e: e.name.lower()):
        if entry.name.startswith("."):
            continue

        if entry.is_dir():
            rel = _relative_to_roots(entry, roots)
            stat = entry.stat()
            folders.append(
                Folder(
                    name=entry.name,
                    path=str(entry),
                    relative_path=rel,
                    is_root=_is_root_path(entry, roots),
                    modified_time=datetime.fromtimestamp(stat.st_mtime),
                )
            )
        elif entry.is_file():
            ext = entry.suffix.lower()
            media_type = _get_media_type(ext, config)
            if media_type is not None:
                stat = entry.stat()
                rel = _relative_to_roots(entry, roots)
                files.append(
                    MediaFile(
                        name=entry.stem,
                        path=str(entry),
                        relative_path=rel,
                        size=stat.st_size,
                        modified_time=datetime.fromtimestamp(stat.st_mtime),
                        media_type=media_type,
                        extension=ext,
                    )
                )

    return folders, files


def scan_all_media(config: AppConfig) -> list[MediaFile]:
    all_files: list[MediaFile] = []
    roots = [str(Path(r).resolve()) for r in config.scan.roots]

    for root in roots:
        root_path = Path(root)
        if not root_path.is_dir():
            continue
        for entry in root_path.rglob("*"):
            if entry.is_file() and not entry.name.startswith("."):
                ext = entry.suffix.lower()
                media_type = _get_media_type(ext, config)
                if media_type is not None:
                    stat = entry.stat()
                    rel = _relative_to_roots(entry, roots)
                    all_files.append(
                        MediaFile(
                            name=entry.stem,
                            path=str(entry),
                            relative_path=rel,
                            size=stat.st_size,
                            modified_time=datetime.fromtimestamp(stat.st_mtime),
                            media_type=media_type,
                            extension=ext,
                        )
                    )
    return all_files


async def scan_directory_async(
    dir_path: str, config: AppConfig, roots: list[str]
) -> tuple[list[Folder], list[MediaFile]]:
    return await asyncio.to_thread(scan_directory, dir_path, config, roots)


async def scan_all_media_async(config: AppConfig) -> list[MediaFile]:
    global _scan_cache, _cache_timestamp
    now = time.time()
    if _scan_cache and (now - _cache_timestamp) < _CACHE_TTL:
        return _scan_cache
    result = await asyncio.to_thread(scan_all_media, config)
    _scan_cache = result
    _cache_timestamp = time.time()
    return result


def invalidate_scan_cache() -> None:
    global _scan_cache, _cache_timestamp
    _scan_cache = []
    _cache_timestamp = 0.0


def _relative_to_roots(path: Path, roots: list[str]) -> str:
    resolved = path.resolve()
    for root in roots:
        root_resolved = Path(root).resolve()
        if resolved.is_relative_to(root_resolved):
            return str(resolved.relative_to(root_resolved))
    return str(resolved)


def _is_root_path(path: Path, roots: list[str]) -> bool:
    resolved = path.resolve()
    return any(resolved == Path(r).resolve() for r in roots)


def resolve_safe_path(requested_path: str, roots: list[str]) -> Path:
    path = Path(requested_path)

    if path.is_absolute():
        resolved = path.resolve()
        if resolved.exists():
            return resolved
        raise FileNotFoundError(f"Not found: {requested_path}")

    # When path is empty, check if it matches a root folder name
    if not requested_path:
        for root in roots:
            if Path(root).name == requested_path:
                root_path = Path(root).resolve()
                if root_path.exists():
                    return root_path
        # Default to first root if no name match
        if roots:
            first = Path(roots[0]).resolve()
            if first.exists():
                return first
        raise FileNotFoundError("No roots configured")

    # First: try appending requested_path as a subdirectory under each root
    for root in roots:
        candidate = (Path(root) / requested_path).resolve()
        if candidate.is_relative_to(Path(root).resolve()) and candidate.exists():
            return candidate

    # Second: check if requested_path matches a root directory's name directly
    # (only for single-segment paths that aren't subdirectory paths)
    if "/" not in requested_path and "\\" not in requested_path:
        for root in roots:
            root_resolved = Path(root).resolve()
            if root_resolved.name == requested_path:
                return root_resolved
    raise FileNotFoundError(f"Not found in any root: {requested_path}")

    if not _is_allowed(resolved, roots):
        raise ValueError(f"Access denied: {requested_path}")
    if not resolved.exists():
        raise FileNotFoundError(f"Not found: {requested_path}")
    return resolved


def guess_mime(path: Path) -> str:
    mime, _ = mimetypes.guess_type(str(path))
    return mime or "application/octet-stream"
