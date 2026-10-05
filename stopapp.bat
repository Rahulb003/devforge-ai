@echo off
REM ============================================================================
REM  DevForge AI - stop the local environment started by openapp.bat
REM
REM  Kills only the processes listening on DevForge's ports, so unrelated Java
REM  or Node processes on this machine are left alone.
REM ============================================================================

setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo   Stopping DevForge AI...
echo.

call :killport 9001 auth-service
call :killport 9002 project-service
call :killport 9003 task-service
call :killport 9005 git-service
call :killport 9006 review-service
call :killport 9011 notification-service
call :killport 8080 api-gateway
call :killport 4173 frontend

echo.
echo   Done. Local data is kept in .\data\ - delete that folder for a clean slate.
echo.
goto :end

:killport
REM %1 = port, %2 = label
set PORT=%~1
set LABEL=%~2
set FOUND=0
REM The last column of a LISTENING row is the owning PID.
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":%PORT% .*LISTENING"') do (
  if not "%%p"=="0" (
    taskkill /f /pid %%p >nul 2>&1
    if not errorlevel 1 (
      echo     stopped %LABEL% ^(pid %%p, port %PORT%^)
      set FOUND=1
    )
  )
)
if "!FOUND!"=="0" echo     %LABEL% was not running ^(port %PORT%^)
exit /b 0

:end
endlocal
exit /b 0
