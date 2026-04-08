from __future__ import annotations

from io import BytesIO
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response
from PIL import Image

router = APIRouter(tags=["thumbnails"])


def _get_roots() -> list[str]:
    from server.main import config
    return [str(Path(r).resolve()) for r in config.scan.roots]


@router.get("/api/v1/images/{path:path}/thumbnail")
async def get_thumbnail(path: str):
    from server.main import config
    from server.scanner import resolve_safe_path

    try:
        file_path = resolve_safe_path(path, _get_roots())
    except (ValueError, FileNotFoundError) as e:
        raise HTTPException(status_code=404 if "Not found" in str(e) else 403, detail=str(e))

    if not file_path.is_file():
        raise HTTPException(status_code=404, detail="Not a file")

    cache_dir = Path(config.thumbnail.cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)

    ext = config.thumbnail.format.lower()
    cache_path = cache_dir / f"{_hash_path(path)}.{ext}"

    if cache_path.exists():
        return Response(
            content=cache_path.read_bytes(),
            media_type=f"image/{ext}",
        )

    try:
        img = Image.open(file_path)
        img.thumbnail(
            (config.thumbnail.max_size, config.thumbnail.max_size),
            Image.Resampling.LANCZOS,
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


@router.get("/api/v1/images/{path:path}/original")
async def get_original_image(path: str):
    import aiofiles
    from fastapi.responses import StreamingResponse
    from server.scanner import guess_mime, resolve_safe_path

    try:
        file_path = resolve_safe_path(path, _get_roots())
    except (ValueError, FileNotFoundError) as e:
        raise HTTPException(status_code=404 if "Not found" in str(e) else 403, detail=str(e))

    if not file_path.is_file():
        raise HTTPException(status_code=404, detail="Not a file")

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
        headers={
            "Content-Length": str(file_size),
        },
    )


def _hash_path(path: str) -> str:
    import hashlib
    return hashlib.md5(path.encode()).hexdigest()
