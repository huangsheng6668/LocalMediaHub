"""System-level browsing endpoints for full filesystem access."""

from __future__ import annotations

import os
import platform
from datetime import datetime
from io import BytesIO
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Query
from fastapi.responses import Response, StreamingResponse

from server.models import Folder, MediaFile

router = APIRouter(prefix="/api/v1/system", tags=["system"])


def _is_windows() -> bool:
    return platform.system() == "Windows"


@router.get("/drives", response_model=list[str])
async def list_drives() -> list[str]:
    """List available drives/root paths on the system."""
    if _is_windows():
        import string
        drives = []
        for letter in string.ascii_uppercase:
            drive = f"{letter}:\\"
            if os.path.exists(drive):
                drives.append(drive)
        return drives
    else:
        # Linux/Mac: return /
        return ["/"]


@router.get("/browse", response_model=dict)
async def browse_path(
    path: str = Query("", description="Directory path to browse. Empty = show drives."),
) -> dict:
    """Browse any directory on the system."""
    from server.main import config

    from server.scanner import _get_media_type, _is_allowed

    if not path:
        # Return drives
        return {"drives": await list_drives(), "folders": [], "files": []}

    target = Path(path).resolve()

    if not target.exists():
        raise HTTPException(status_code=404, detail=f"Path not found: {path}")
    if not target.is_dir():
        raise HTTPException(status_code=400, detail="Not a directory")

    folders = []
    files = []

    try:
        for entry in sorted(target.iterdir(), key=lambda e: e.name.lower()):
            if entry.name.startswith("."):
                continue

            if entry.is_dir():
                stat = entry.stat()
                folders.append(Folder(
                    name=entry.name,
                    path=str(entry),
                    relative_path=str(entry),
                    modified_time=datetime.fromtimestamp(stat.st_mtime),
                ))
            elif entry.is_file():
                ext = entry.suffix.lower()
                media_type = _get_media_type(ext, config)
                if media_type not in ("image", "video"):
                    continue
                stat = entry.stat()
                files.append(MediaFile(
                    name=entry.stem,
                    path=str(entry),
                    relative_path=str(entry),
                    size=stat.st_size,
                    modified_time=datetime.fromtimestamp(stat.st_mtime),
                    media_type=media_type,
                    extension=ext,
                ))
    except PermissionError:
        raise HTTPException(status_code=403, detail=f"Permission denied: {path}")

    return {
        "current_path": str(target),
        "folders": folders,
        "files": files,
    }


def _resolve_system_path(path: str) -> Path:
    """Resolve and validate an absolute file system path."""
    target = Path(path).resolve()
    if not target.exists():
        raise HTTPException(status_code=404, detail=f"Path not found: {path}")
    if not target.is_file():
        raise HTTPException(status_code=400, detail="Not a file")
    return target


@router.get("/thumbnail")
async def system_thumbnail(path: str = Query(..., description="Absolute file path")):
    """Get thumbnail for any image file on the system."""
    import aiofiles
    from PIL import Image as PILImage

    from server.main import config
    from server.scanner import guess_mime

    file_path = _resolve_system_path(path)

    # Only generate thumbnails for images
    mime = guess_mime(file_path)
    if not mime or not mime.startswith("image/"):
        raise HTTPException(status_code=400, detail="Not an image file")

    cache_dir = Path(config.thumbnail.cache_dir) / "system"
    cache_dir.mkdir(parents=True, exist_ok=True)

    ext = config.thumbnail.format.lower()
    import hashlib
    cache_key = hashlib.md5(path.encode()).hexdigest()
    cache_path = cache_dir / f"{cache_key}.{ext}"

    if cache_path.exists():
        return Response(
            content=cache_path.read_bytes(),
            media_type=f"image/{ext}",
        )

    try:
        img = PILImage.open(file_path)
        img.thumbnail(
            (config.thumbnail.max_size, config.thumbnail.max_size),
            PILImage.Resampling.LANCZOS,
        )
        if img.mode in ("RGBA", "P"):
            img = img.convert("RGB")

        buf = BytesIO()
        save_format = "JPEG" if ext == "jpeg" else ext.upper()
        img.save(buf, format=save_format, quality=85)
        thumb_bytes = buf.getvalue()

        cache_path.write_bytes(thumb_bytes)

        return Response(content=thumb_bytes, media_type=f"image/{ext}")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Thumbnail error: {e}")


@router.get("/original")
async def system_original(path: str = Query(..., description="Absolute file path")):
    """Stream the original image file."""
    import aiofiles

    from server.scanner import guess_mime

    file_path = _resolve_system_path(path)
    content_type = guess_mime(file_path)
    file_size = file_path.stat().st_size

    async def _iter():
        async with aiofiles.open(file_path, "rb") as f:
            while True:
                chunk = await f.read(64 * 1024)
                if not chunk:
                    break
                yield chunk

    return StreamingResponse(
        _iter(),
        media_type=content_type,
        headers={"Content-Length": str(file_size)},
    )


@router.get("/stream")
async def system_stream(
    path: str = Query(..., description="Absolute file path"),
    range: str | None = Header(None, alias="Range"),
):
    """Stream any video file with Range support."""
    import aiofiles

    from server.scanner import guess_mime

    file_path = _resolve_system_path(path)
    content_type = guess_mime(file_path)
    file_size = file_path.stat().st_size

    if range is None:
        return StreamingResponse(
            _iter_file(file_path, 0, file_size),
            media_type=content_type,
            headers={
                "Accept-Ranges": "bytes",
                "Content-Length": str(file_size),
            },
        )

    range_spec = range.replace("bytes=", "")
    parts = range_spec.split("-")
    start = int(parts[0]) if parts[0] else 0
    end = int(parts[1]) if parts[1] else file_size - 1
    end = min(end, file_size - 1)
    content_length = end - start + 1

    return StreamingResponse(
        _iter_file(file_path, start, end + 1),
        status_code=206,
        media_type=content_type,
        headers={
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(content_length),
        },
    )


CHUNK_SIZE = 64 * 1024


async def _iter_file(path: Path, start: int, end: int):
    import aiofiles

    async with aiofiles.open(path, "rb") as f:
        await f.seek(start)
        remaining = end - start
        while remaining > 0:
            chunk = await f.read(min(CHUNK_SIZE, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk
