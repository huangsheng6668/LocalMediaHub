from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import List
from uuid import uuid4

from fastapi import APIRouter, HTTPException

from server.models import FileTag, FileTagAssociation, TagCreateRequest

router = APIRouter(prefix="/api/v1/tags", tags=["tags"])

_DATA_DIR = Path(__file__).resolve().parent.parent / ".data"
_TAGS_FILE = _DATA_DIR / "tags.json"

_lock = threading.Lock()


def _ensure_data_file() -> None:
    _DATA_DIR.mkdir(parents=True, exist_ok=True)
    if not _TAGS_FILE.exists():
        _TAGS_FILE.write_text(
            json.dumps({"tags": [], "associations": []}, indent=2),
            encoding="utf-8",
        )


def _read_store() -> dict:
    _ensure_data_file()
    with open(_TAGS_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def _write_store(store: dict) -> None:
    _ensure_data_file()
    with open(_TAGS_FILE, "w", encoding="utf-8") as f:
        json.dump(store, f, indent=2, ensure_ascii=False)


@router.get("", response_model=List[FileTag])
async def list_tags():
    with _lock:
        store = _read_store()
    return [FileTag(**t) for t in store.get("tags", [])]


@router.post("", response_model=FileTag, status_code=201)
async def create_tag(request: TagCreateRequest):
    with _lock:
        store = _read_store()
        existing_names = {t["name"].lower() for t in store.get("tags", [])}
        if request.name.lower() in existing_names:
            raise HTTPException(status_code=409, detail="Tag name already exists")

        tag = FileTag(id=str(uuid4()), name=request.name, color=request.color)
        store.setdefault("tags", []).append(tag.model_dump())
        _write_store(store)
    return tag


@router.delete("/{tag_id}", status_code=204)
async def delete_tag(tag_id: str):
    with _lock:
        store = _read_store()
        tags = store.get("tags", [])
        tag_ids = {t["id"] for t in tags}
        if tag_id not in tag_ids:
            raise HTTPException(status_code=404, detail="Tag not found")

        store["tags"] = [t for t in tags if t["id"] != tag_id]
        store["associations"] = [
            a for a in store.get("associations", []) if a["tag_id"] != tag_id
        ]
        _write_store(store)


@router.post("/{tag_id}/files/{path:path}", status_code=201)
async def tag_file(tag_id: str, path: str):
    with _lock:
        store = _read_store()
        tag_ids = {t["id"] for t in store.get("tags", [])}
        if tag_id not in tag_ids:
            raise HTTPException(status_code=404, detail="Tag not found")

        associations = store.get("associations", [])
        for a in associations:
            if a["file_path"] == path and a["tag_id"] == tag_id:
                return {"detail": "Already tagged"}

        association = FileTagAssociation(file_path=path, tag_id=tag_id)
        associations.append(association.model_dump())
        store["associations"] = associations
        _write_store(store)
    return {"detail": "File tagged"}


@router.delete("/{tag_id}/files/{path:path}", status_code=200)
async def untag_file(tag_id: str, path: str):
    with _lock:
        store = _read_store()
        original_len = len(store.get("associations", []))
        store["associations"] = [
            a
            for a in store.get("associations", [])
            if not (a["file_path"] == path and a["tag_id"] == tag_id)
        ]
        if len(store["associations"]) == original_len:
            raise HTTPException(status_code=404, detail="Association not found")
        _write_store(store)
    return {"detail": "Tag removed from file"}


@router.get("/{tag_id}/files", response_model=List[str])
async def get_tagged_files(tag_id: str):
    with _lock:
        store = _read_store()
        tag_ids = {t["id"] for t in store.get("tags", [])}
        if tag_id not in tag_ids:
            raise HTTPException(status_code=404, detail="Tag not found")

        file_paths = [
            a["file_path"]
            for a in store.get("associations", [])
            if a["tag_id"] == tag_id
        ]
    return file_paths
