from __future__ import annotations

from datetime import datetime
from pathlib import Path

from fastapi import APIRouter, Query

from server.models import MediaFile, Folder, SearchResult

router = APIRouter(prefix="/api/v1/search", tags=["search"])


def _get_roots() -> list[str]:
    from server.main import config
    return [str(Path(r).resolve()) for r in config.scan.roots]


@router.get("", response_model=SearchResult)
async def search(
    q: str = Query(..., min_length=1, description="Search query"),
    path: str = Query("", description="Restrict search to this directory"),
    limit: int = Query(50, ge=1, le=200),
) -> SearchResult:
    import asyncio
    from server.main import config
    from server.scanner import _get_media_type, _relative_to_roots, resolve_safe_path

    roots = _get_roots()
    query = q.lower()

    # Determine search scope
    if path:
        try:
            search_root = resolve_safe_path(path, roots)
        except (ValueError, FileNotFoundError):
            return SearchResult(query=q, folders=[], files=[])
        search_dirs = [search_root] if search_root.is_dir() else []
    else:
        search_dirs = [Path(r) for r in roots if Path(r).is_dir()]

    folders: list[Folder] = []
    files: list[MediaFile] = []

    def _scan():
        for search_dir in search_dirs:
            for entry in search_dir.rglob("*"):
                if entry.name.startswith("."):
                    continue
                if query not in entry.name.lower():
                    continue

                if entry.is_dir():
                    rel = _relative_to_roots(entry, roots)
                    stat = entry.stat()
                    folders.append(Folder(
                        name=entry.name,
                        path=str(entry),
                        relative_path=rel,
                        modified_time=datetime.fromtimestamp(stat.st_mtime),
                    ))
                elif entry.is_file():
                    ext = entry.suffix.lower()
                    media_type = _get_media_type(ext, config)
                    if media_type is not None:
                        stat = entry.stat()
                        rel = _relative_to_roots(entry, roots)
                        files.append(MediaFile(
                            name=entry.stem,
                            path=str(entry),
                            relative_path=rel,
                            size=stat.st_size,
                            modified_time=datetime.fromtimestamp(stat.st_mtime),
                            media_type=media_type,
                            extension=ext,
                        ))

                if len(folders) + len(files) >= limit:
                    return

    await asyncio.to_thread(_scan)
    return SearchResult(query=q, folders=folders[:limit], files=files[:limit])
