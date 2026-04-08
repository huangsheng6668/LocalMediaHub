from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Optional

from pydantic import BaseModel


class MediaType(str, Enum):
    VIDEO = "video"
    IMAGE = "image"


class MediaFile(BaseModel):
    name: str
    path: str
    relative_path: str
    size: int
    modified_time: datetime
    media_type: MediaType
    extension: str


class Folder(BaseModel):
    name: str
    path: str
    relative_path: str
    is_root: bool = False
    modified_time: datetime | None = None


class BrowseResult(BaseModel):
    current_path: str
    folders: list[Folder]
    files: list[MediaFile]


class PaginatedMediaFiles(BaseModel):
    items: list[MediaFile]
    total: int
    page: int
    page_size: int
    has_more: bool


class SearchResult(BaseModel):
    query: str
    folders: list[Folder]
    files: list[MediaFile]


class FileTag(BaseModel):
    id: str
    name: str
    color: str = "#808080"


class FileTagAssociation(BaseModel):
    file_path: str
    tag_id: str


class TagCreateRequest(BaseModel):
    name: str
    color: str = "#808080"
