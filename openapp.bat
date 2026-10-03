@echo off
REM ============================================================================
REM  DevForge AI - one-click local launcher
REM
REM  Starts auth-service, project-service and the frontend, then opens a browser.
REM
REM  Runs the STANDALONE profile: real application code, real Flyway migrations
REM  and real persistence on an embedded H2 database, so no PostgreSQL, Kafka,
REM  Redis, SMTP or Docker is needed.
REM
REM  NOT for production. The JWT signing key is a known development value,
REM  refresh cookies are not Secure, verification links are printed to the
REM  service log, and data lives in .\data\.
REM
REM  Stop everything again with stopapp.bat
REM ============================================================================

setlocal EnableDelayedExpansion
cd /d "%~dp0"

set AUTH_PORT=9001
set PROJECT_PORT=9002
set TASK_PORT=9003
set GIT_PORT=9005
set NOTIFICATION_PORT=9011
set GATEWAY_PORT=8080
set WEB_PORT=4173
set AUTH_JAR=services\auth-service\target\auth-service-0.1.0.jar
set PROJECT_JAR=services\project-service\target\project-service-0.1.0.jar
set TASK_JAR=services\task-service\target\task-service-0.1.0.jar
set GIT_JAR=services\git-service\target\git-service-0.1.0.jar
set NOTIFICATION_JAR=services\notification-service\target\notification-service-0.1.0.jar
set GATEWAY_JAR=api-gateway\target\api-gateway-0.1.0.jar

echo.
echo ================================================================
echo   DevForge AI - starting local environment
echo ================================================================
echo.

REM ---------------------------------------------------------------- prereqs
where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Java was not found on PATH. JDK 21 or newer is required.
  goto :fail
)
where node >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js was not found on PATH. Node 20 or newer is required.
  goto :fail
)

for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%v
echo   Java : !JAVA_VER!
for /f "tokens=*" %%v in ('node -v') do echo   Node : %%v
echo.

REM ------------------------------------------------- already running check
REM Re-running the launcher while services are up would leave two processes
REM fighting over the same port, so stop rather than stack them.
netstat -ano | findstr /r /c:":%AUTH_PORT% .*LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [WARN] Port %AUTH_PORT% is already in use - DevForge may already be running.
  echo        Run stopapp.bat first, or open http://localhost:%WEB_PORT% directly.
  echo.
  choice /c YN /m "Open the browser and exit"
  if !errorlevel! equ 1 start "" "http://localhost:%WEB_PORT%"
  goto :end
)

REM ---------------------------------------------------------------- build
REM Only builds when a jar is missing. Delete the target folders to force a
REM rebuild, or run: mvn -f backend/pom.xml clean package -DskipTests
if not exist "%AUTH_JAR%" goto :build
if not exist "%PROJECT_JAR%" goto :build
if not exist "%TASK_JAR%" goto :build
if not exist "%GIT_JAR%" goto :build
if not exist "%NOTIFICATION_JAR%" goto :build
if not exist "%GATEWAY_JAR%" goto :build
echo   Jars found - skipping build.
echo   (delete services\*\target to force a rebuild)
goto :deps

:build
echo   Building backend jars - this takes a few minutes the first time...
where mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Maven was not found on PATH and the jars are not built yet.
  echo         Install Maven, or build once with your IDE.
  goto :fail
)
call mvn -B -ntp -f backend\pom.xml -pl ..\services\auth-service,..\services\project-service,..\services\task-service,..\api-gateway,..\services\notification-service,..\services\git-service -am package -DskipTests
if errorlevel 1 (
  echo [ERROR] Backend build failed. Scroll up for the Maven output.
  goto :fail
)
echo   Build complete.

:deps
REM ------------------------------------------------------- frontend deps
if not exist "frontend\node_modules" (
  echo   Installing frontend dependencies...
  pushd frontend
  call npm ci
  if errorlevel 1 (
    echo [ERROR] npm ci failed.
    popd
    goto :fail
  )
  popd
)

REM ---------------------------------------------------------------- launch
REM Each service gets its own window so its log is visible; closing a window
REM stops that service.
echo.
echo   Starting auth-service on port %AUTH_PORT% ...
start "DevForge auth-service" cmd /k "java -jar %AUTH_JAR% --spring.profiles.active=standalone"

echo   Starting project-service on port %PROJECT_PORT% ...
start "DevForge project-service" cmd /k "java -jar %PROJECT_JAR% --spring.profiles.active=standalone"

echo   Starting task-service on port %TASK_PORT% ...
start "DevForge task-service" cmd /k "java -jar %TASK_JAR% --spring.profiles.active=standalone"

echo   Starting git-service on port %GIT_PORT% ...
start "DevForge git-service" cmd /k "java -jar %GIT_JAR% --spring.profiles.active=standalone"

echo   Starting notification-service on port %NOTIFICATION_PORT% ...
start "DevForge notification-service" cmd /k "java -jar %NOTIFICATION_JAR% --spring.profiles.active=standalone"

echo   Starting api-gateway on port %GATEWAY_PORT% ...
start "DevForge api-gateway" cmd /k "java -jar %GATEWAY_JAR% --spring.profiles.active=standalone"

echo   Starting frontend on port %WEB_PORT% ...
start "DevForge frontend" cmd /k "cd /d "%~dp0frontend" && npm run dev"

REM ------------------------------------------------------- wait for health
REM Poll the actuator endpoint rather than sleeping a fixed time: JVM startup
REM varies a lot between a cold and a warm filesystem cache.
echo.
echo   Waiting for services to become healthy...
call :waitfor auth-service    "http://localhost:%AUTH_PORT%/actuator/health"    60
call :waitfor project-service "http://localhost:%PROJECT_PORT%/actuator/health" 60
call :waitfor task-service    "http://localhost:%TASK_PORT%/actuator/health"    60
call :waitfor git-service      "http://localhost:%GIT_PORT%/actuator/health"          60
call :waitfor notification-service "http://localhost:%NOTIFICATION_PORT%/actuator/health" 60
call :waitfor api-gateway     "http://localhost:%GATEWAY_PORT%/actuator/health" 60
call :waitfor frontend        "http://localhost:%WEB_PORT%/"                    45

echo.
echo ================================================================
echo   DevForge AI is running
echo ================================================================
echo.
echo   Web app        http://localhost:%WEB_PORT%
echo   auth-service   http://localhost:%AUTH_PORT%/actuator/health
echo   project-service http://localhost:%PROJECT_PORT%/actuator/health
echo   task-service   http://localhost:%TASK_PORT%/actuator/health
echo   git-service    http://localhost:%GIT_PORT%/actuator/health
echo   notification-service http://localhost:%NOTIFICATION_PORT%/actuator/health
echo   api-gateway    http://localhost:%GATEWAY_PORT%/actuator/health  ^(single entry point^)
echo   Database UI    http://localhost:%AUTH_PORT%/h2-console
echo                  JDBC URL: jdbc:h2:file:./data/devforge-auth
echo                  User: sa     Password: (blank)
echo.
echo   Everything the web app calls goes through the gateway on port %GATEWAY_PORT%,
echo   so that is the only port the browser needs. Sign up at the web app and
echo   sign straight in - there is no email verification step.
echo.
echo   Accounts live in .\data\ and survive a restart. Delete that folder for a
echo   clean slate.
echo.
echo   Stop everything with stopapp.bat
echo.

start "" "http://localhost:%WEB_PORT%"
goto :end

REM ---------------------------------------------------------------- helpers
:waitfor
REM %1 = label, %2 = url, %3 = attempts (roughly seconds x2)
set "LABEL=%~1"
set "URL=%~2"
set /a TRIES=%~3
set /a N=0
:waitloop
set /a N+=1
REM The retry pause happens inside PowerShell rather than via `timeout /t`.
REM timeout.exe is shadowed on any machine with Git's bin directory ahead of
REM System32 on PATH, where GNU timeout rejects /t and the loop spins.
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -Uri '%URL%' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { exit 0 } } catch { }; Start-Sleep -Seconds 2; exit 1" >nul 2>&1
if not errorlevel 1 (
  echo     [OK]      %LABEL%
  exit /b 0
)
if %N% geq %TRIES% (
  echo     [TIMEOUT] %LABEL% did not respond - check its window for errors.
  exit /b 1
)
goto :waitloop

:fail
echo.
echo Startup aborted.
echo.
pause
exit /b 1

:end
endlocal
exit /b 0
