from __future__ import annotations

from pathlib import Path
from typing import Optional

from fastapi import APIRouter, Query

from server.models import MediaFile, MediaType, PaginatedMediaFiles

router = APIRouter(prefix="/api/v1/images", tags=["images"])


@router.get("", response_model=PaginatedMediaFiles)
async def list_images(
    page: int = Query(1, ge=1),
    page_size: int = Query(50, ge=1, le=200),
):
    from server.main import config
    from server.scanner import scan_all_media_async

    all_media = await scan_all_media_async(config)
    images = [f for f in all_media if f.media_type == MediaType.IMAGE]
    images.sort(key=lambda f: f.name.lower())

    total = len(images)
    start = (page - 1) * page_size
    end = start + page_size
    items = images[start:end]

    return PaginatedMediaFiles(
        items=items,
        total=total,
        page=page,
        page_size=page_size,
        has_more=end < total,
    )
