@echo off
rem Royal Crest Connect - starts the WhatsApp bridge and the fee PDF worker.
rem Needs bridge.env next to this file (see README.md).
cd /d "%~dp0"
if not exist bridge.env (
  echo bridge.env is missing. Copy bridge.env.example to bridge.env and fill it in.
  pause
  exit /b 1
)
python rca_supervisor.py
pause
