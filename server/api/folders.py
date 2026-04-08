from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import List

from fastapi import APIRouter, HTTPException

from server.models import BrowseResult, Folder

router = APIRouter(prefix="/api/v1/folders", tags=["folders"])


def _get_roots() -> List[str]:
    from server.main import config
    return [str(Path(r).resolve()) for r in config.scan.roots]


@router.get("", response_model=List[Folder])
async def list_folders():
    roots = _get_roots()
    folders: list[Folder] = []
    for root in roots:
        p = Path(root)
        if p.is_dir():
            stat = p.stat()
            folders.append(
                Folder(
                    name=p.name,
                    path=str(p),
                    relative_path="",
                    is_root=True,
                    modified_time=datetime.fromtimestamp(stat.st_mtime),
                )
            )
    return folders


@router.get("/{path:path}/browse", response_model=BrowseResult)
async def browse_folder(path: str):
    from server.main import config
    from server.scanner import scan_directory_async, resolve_safe_path

    roots = _get_roots()
    if not roots:
        raise HTTPException(status_code=404, detail="No root folders configured")

    try:
        resolved = resolve_safe_path(path, roots)
    except (ValueError, FileNotFoundError) as e:
        raise HTTPException(
            status_code=404 if "Not found" in str(e) else 403,
            detail=str(e),
        )

    if not resolved.is_dir():
        raise HTTPException(status_code=400, detail="Not a directory")

    try:
        folders, files = await scan_directory_async(str(resolved), config, roots)
    except ValueError as e:
        raise HTTPException(status_code=403, detail=str(e))

    return BrowseResult(
        current_path=path,
        folders=folders,
        files=files,
    )
