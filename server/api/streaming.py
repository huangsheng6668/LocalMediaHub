from __future__ import annotations

import os
from pathlib import Path

import aiofiles
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import Response, StreamingResponse

router = APIRouter(tags=["streaming"])

CHUNK_SIZE = 64 * 1024  # 64KB


def _get_roots() -> list[str]:
    from server.main import config
    return [str(Path(r).resolve()) for r in config.scan.roots]


def _resolve(path: str) -> Path:
    from server.scanner import resolve_safe_path
    return resolve_safe_path(path, _get_roots())


@router.get("/api/v1/videos/{path:path}/stream")
async def stream_video(
    path: str,
    range: str | None = Header(None, alias="Range"),
):
    from server.scanner import guess_mime

    try:
        file_path = _resolve(path)
    except (ValueError, FileNotFoundError) as e:
        raise HTTPException(status_code=404 if "Not found" in str(e) else 403, detail=str(e))

    if not file_path.is_file():
        raise HTTPException(status_code=404, detail="Not a file")

    file_size = file_path.stat().st_size
    content_type = guess_mime(file_path)

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


async def _iter_file(path: Path, start: int, end: int):
    async with aiofiles.open(path, "rb") as f:
        await f.seek(start)
        remaining = end - start
        while remaining > 0:
            chunk = await f.read(min(CHUNK_SIZE, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk
