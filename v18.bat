@echo off
del /f /q .git\index.lock 2>nul
set GIT_INDEX_FILE=.git\alt_idx
if exist .git\index copy .git\index .git\alt_idx >nul 2>&1
git add -A
git commit -m "v1.0.18"