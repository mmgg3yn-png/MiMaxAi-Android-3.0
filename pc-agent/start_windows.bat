@echo off
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 mimax_pc_agent.py
) else (
  python mimax_pc_agent.py
)
pause
