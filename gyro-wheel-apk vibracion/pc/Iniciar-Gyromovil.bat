@echo off
cd /d "%~dp0"
title Gyromovil - Servidor
python --version >nul 2>&1
if errorlevel 1 (
  echo No se encontro Python. Instalalo desde python.org y marca "Add python.exe to PATH".
  pause
  exit /b
)
python -c "import vgamepad" >nul 2>&1
if errorlevel 1 python -m pip install -r requirements.txt
python server.py
pause
