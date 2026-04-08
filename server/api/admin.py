from __future__ import annotations

from pathlib import Path
from typing import List

import yaml
from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from server.scanner import invalidate_scan_cache

router = APIRouter(tags=["admin"])

_CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.yaml"


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------

class UpdateRootsRequest(BaseModel):
    roots: List[str]


# ---------------------------------------------------------------------------
# HTML admin page (embedded)
# ---------------------------------------------------------------------------

_ADMIN_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>LocalMediaHub - Admin</title>
<style>
  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: #f5f5f7; color: #1d1d1f; line-height: 1.5; padding: 2rem;
  }
  .container { max-width: 720px; margin: 0 auto; }
  h1 { font-size: 1.6rem; margin-bottom: .25rem; }
  .subtitle { color: #6e6e73; margin-bottom: 1.5rem; }
  .card {
    background: #fff; border-radius: 12px; box-shadow: 0 1px 3px rgba(0,0,0,.08);
    padding: 1.25rem 1.5rem; margin-bottom: 1rem;
  }
  .card h2 { font-size: 1rem; margin-bottom: .75rem; color: #1d1d1f; }

  /* Status */
  .status-grid { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem 1.5rem; }
  .status-label { font-weight: 600; }
  .status-value { color: #6e6e73; }

  /* Root list */
  .root-item {
    display: flex; align-items: center; justify-content: space-between;
    padding: .5rem 0; border-bottom: 1px solid #f0f0f0;
  }
  .root-item:last-child { border-bottom: none; }
  .root-path { font-family: "SF Mono", Menlo, Consolas, monospace; font-size: .9rem; word-break: break-all; }
  .btn-remove {
    background: none; border: none; color: #ff3b30; cursor: pointer;
    font-size: .85rem; padding: .25rem .5rem; border-radius: 6px;
  }
  .btn-remove:hover { background: #fff0f0; }

  /* Add root row */
  .add-row { display: flex; gap: .5rem; margin-top: .75rem; }
  .add-row input {
    flex: 1; padding: .45rem .65rem; border: 1px solid #d2d2d7; border-radius: 8px;
    font-size: .9rem;
  }
  .add-row input:focus { outline: none; border-color: #0071e3; }

  /* Buttons */
  .btn {
    display: inline-flex; align-items: center; gap: .35rem;
    padding: .5rem 1rem; border: none; border-radius: 8px; cursor: pointer;
    font-size: .85rem; font-weight: 500; transition: background .15s;
  }
  .btn-primary { background: #0071e3; color: #fff; }
  .btn-primary:hover { background: #0062cc; }
  .btn-primary:disabled { background: #a0c4f0; cursor: not-allowed; }
  .btn-secondary { background: #e8e8ed; color: #1d1d1f; }
  .btn-secondary:hover { background: #dcdce0; }

  .actions { display: flex; gap: .5rem; margin-top: .75rem; }
  .toast {
    position: fixed; bottom: 2rem; left: 50%; transform: translateX(-50%);
    background: #1d1d1f; color: #fff; padding: .65rem 1.25rem; border-radius: 10px;
    font-size: .85rem; opacity: 0; transition: opacity .3s; pointer-events: none;
  }
  .toast.show { opacity: 1; }
  .empty-state { color: #86868b; font-style: italic; padding: .5rem 0; }
</style>
</head>
<body>
<div class="container">
  <h1>LocalMediaHub Admin</h1>
  <p class="subtitle">Configuration &amp; management console</p>

  <!-- Server status -->
  <div class="card">
    <h2>Server Status</h2>
    <div class="status-grid">
      <span class="status-label">Host</span>
      <span class="status-value" id="s-host">-</span>
      <span class="status-label">Port</span>
      <span class="status-value" id="s-port">-</span>
      <span class="status-label">Thumbnail Cache</span>
      <span class="status-value" id="s-thumb">-</span>
      <span class="status-label">Video Extensions</span>
      <span class="status-value" id="s-vid-ext">-</span>
    </div>
  </div>

  <!-- Scan directories -->
  <div class="card">
    <h2>Scan Directories (Roots)</h2>
    <div id="roots-list"></div>
    <div class="add-row">
      <input type="text" id="new-root" placeholder="Enter directory path to add..." />
      <button class="btn btn-secondary" onclick="addRoot()">Add</button>
    </div>
    <div class="actions">
      <button class="btn btn-primary" id="btn-save" onclick="saveRoots()" disabled>Save Changes</button>
      <button class="btn btn-secondary" onclick="loadConfig()">Discard</button>
    </div>
  </div>

  <!-- Rescan -->
  <div class="card">
    <h2>Media Scan</h2>
    <p style="color:#6e6e73;margin-bottom:.5rem;">Trigger a full rescan to refresh the media index.</p>
    <button class="btn btn-primary" id="btn-scan" onclick="triggerScan()">Rescan Now</button>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
let _roots = [];
let _dirty = false;

function toast(msg, duration) {
  duration = duration || 2000;
  var el = document.getElementById('toast');
  el.textContent = msg;
  el.classList.add('show');
  setTimeout(function() { el.classList.remove('show'); }, duration);
}

function setDirty(val) {
  _dirty = val;
  document.getElementById('btn-save').disabled = !val;
}

function renderRoots() {
  var container = document.getElementById('roots-list');
  if (_roots.length === 0) {
    container.innerHTML = '<p class="empty-state">No directories configured.</p>';
    return;
  }
  container.innerHTML = _roots.map(function(r, i) {
    return '<div class="root-item">' +
      '<span class="root-path">' + escHtml(r) + '</span>' +
      '<button class="btn-remove" onclick="removeRoot(' + i + ')">Remove</button>' +
      '</div>';
  }).join('');
}

function escHtml(s) {
  var d = document.createElement('div');
  d.appendChild(document.createTextNode(s));
  return d.innerHTML;
}

function removeRoot(index) {
  _roots.splice(index, 1);
  renderRoots();
  setDirty(true);
}

function addRoot() {
  var input = document.getElementById('new-root');
  var val = input.value.trim();
  if (!val) return;
  if (_roots.indexOf(val) !== -1) {
    toast('Directory already in list');
    return;
  }
  _roots.push(val);
  input.value = '';
  renderRoots();
  setDirty(true);
}

document.getElementById('new-root').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') addRoot();
});

function loadConfig() {
  fetch('/api/v1/admin/config')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      document.getElementById('s-host').textContent = data.server.host;
      document.getElementById('s-port').textContent = data.server.port;
      document.getElementById('s-thumb').textContent =
        data.thumbnail.cache_dir + ' (max ' + data.thumbnail.max_size + 'px, ' + data.thumbnail.format + ')';
      document.getElementById('s-vid-ext').textContent = data.scan.video_extensions.join(', ');
      _roots = data.scan.roots.slice();
      renderRoots();
      setDirty(false);
    })
    .catch(function(e) { toast('Failed to load config: ' + e.message); });
}

function saveRoots() {
  fetch('/api/v1/admin/config', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ roots: _roots })
  })
    .then(function(r) {
      if (!r.ok) return r.json().then(function(d) { throw new Error(d.detail || 'Error'); });
      return r.json();
    })
    .then(function() {
      toast('Configuration saved');
      setDirty(false);
      loadConfig();
    })
    .catch(function(e) { toast('Save failed: ' + e.message); });
}

function triggerScan() {
  var btn = document.getElementById('btn-scan');
  btn.disabled = true;
  btn.textContent = 'Scanning...';
  fetch('/api/v1/admin/scan/trigger', { method: 'POST' })
    .then(function(r) {
      if (!r.ok) return r.json().then(function(d) { throw new Error(d.detail || 'Error'); });
      return r.json();
    })
    .then(function(data) {
      toast(data.message || 'Scan triggered');
    })
    .catch(function(e) { toast('Scan failed: ' + e.message); })
    .finally(function() {
      btn.disabled = false;
      btn.textContent = 'Rescan Now';
    });
}

loadConfig();
</script>
</body>
</html>"""


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@router.get("/admin", response_class=HTMLResponse)
async def admin_page() -> str:
    return _ADMIN_HTML


@router.get("/api/v1/admin/config")
async def get_config() -> dict:
    from server.main import config

    return {
        "server": config.server.model_dump(),
        "scan": config.scan.model_dump(),
        "thumbnail": config.thumbnail.model_dump(),
    }


@router.put("/api/v1/admin/config")
async def update_config(body: UpdateRootsRequest) -> dict:
    from server.main import config

    validated_roots = [str(Path(p).resolve()) for p in body.roots]
    config.scan.roots = validated_roots

    _persist_roots_to_yaml(validated_roots)
    invalidate_scan_cache()

    return {"status": "ok", "roots": config.scan.roots}


@router.post("/api/v1/admin/scan/trigger")
async def trigger_scan() -> dict:
    invalidate_scan_cache()
    from server.scanner import scan_all_media_async
    from server.main import config

    media = await scan_all_media_async(config)
    return {"message": f"Scan complete, {len(media)} media files found."}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _persist_roots_to_yaml(roots: list[str]) -> None:
    data: dict = {}
    if _CONFIG_PATH.exists():
        with open(_CONFIG_PATH, "r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh) or {}

    if "scan" not in data:
        data["scan"] = {}
    data["scan"]["roots"] = roots

    with open(_CONFIG_PATH, "w", encoding="utf-8") as fh:
        yaml.dump(data, fh, default_flow_style=False, allow_unicode=True)
