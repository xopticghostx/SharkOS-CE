@echo off
rem Double-click this file to install SharkOS CE on your BYD Shark 6.
rem It runs Install-SharkOS-CE.ps1 from the same folder. No admin rights needed.
title SharkOS CE installer
cd /d "%~dp0"
if not exist "%~dp0Install-SharkOS-CE.ps1" (
    echo Install-SharkOS-CE.ps1 is missing. Unzip the whole folder first, then run this file again.
    pause
    exit /b 1
)
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-SharkOS-CE.ps1" %*
echo.
pause
