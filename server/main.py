from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from server.api import admin, folders, images, search, streaming, system, tags, thumbnails, videos
from server.config import load_config
from server.mdns import MDnsRegistrar

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

config = load_config(
    str(Path(__file__).parent / "config.yaml")
)

_mdns_registrar = MDnsRegistrar(port=config.server.port)


@asynccontextmanager
async def _lifespan(app: FastAPI):
    # Startup: register mDNS service
    logger.info("Starting LocalMediaHub server on port %d", config.server.port)
    _mdns_registrar.register()
    yield
    # Shutdown: unregister mDNS service
    _mdns_registrar.unregister()
    logger.info("LocalMediaHub server stopped")


app = FastAPI(
    title="LocalMediaHub",
    description="本地媒体资源管理系统 API",
    version="0.1.0",
    lifespan=_lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(folders.router)
app.include_router(search.router)
app.include_router(videos.router)
app.include_router(images.router)
app.include_router(streaming.router)
app.include_router(thumbnails.router)
app.include_router(admin.router)
app.include_router(tags.router)
app.include_router(system.router)


@app.get("/")
async def root() -> dict:
    return {"name": "LocalMediaHub", "version": "0.1.0"}


if __name__ == "__main__":
    uvicorn.run(
        "server.main:app",
        host=config.server.host,
        port=config.server.port,
        reload=True,
    )
