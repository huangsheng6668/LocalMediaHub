@echo off
cd /d "%~dp0"
echo Starting LocalMediaHub server on http://0.0.0.0:8000 ...
py -m uvicorn server.main:app --host 0.0.0.0 --port 8000
pause
