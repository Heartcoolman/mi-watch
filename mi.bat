@echo off
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "BIN=%DIR%core\build\install\core\bin\core.bat"

if not exist "%BIN%" (
    echo [mi] Core CLI not built yet, building...
    call "%DIR%gradlew.bat" :core:installDist --console=plain -q
    if errorlevel 1 (
        echo [mi] Build failed
        exit /b 1
    )
)

rem Use JAVA_HOME if set and valid, otherwise rely on PATH
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "PATH=%JAVA_HOME%\bin;%PATH%"
    )
)

call "%BIN%" %*
exit /b %errorlevel%
