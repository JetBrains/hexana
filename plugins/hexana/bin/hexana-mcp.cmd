@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PLUGIN_ROOT=%%~fI"

if defined HEXANA_SERVER_BIN (
    set "SERVER_BIN=%HEXANA_SERVER_BIN%"
) else (
    set "HEXANA_NATIVE_ARCH=%PROCESSOR_ARCHITECTURE%"
    if /I "%HEXANA_NATIVE_ARCH%"=="AMD64" set "HEXANA_NATIVE_ARCH=x64"
    if /I "%HEXANA_NATIVE_ARCH%"=="ARM64" set "HEXANA_NATIVE_ARCH=arm64"
    set "NATIVE_BIN=%PLUGIN_ROOT%\native\windows-%HEXANA_NATIVE_ARCH%\hexana-mcp.exe"
    if exist "%NATIVE_BIN%" (
        set "SERVER_BIN=%NATIVE_BIN%"
    ) else (
        set "SERVER_BIN=%PLUGIN_ROOT%\server\bin\server.bat"
    )
)

if not exist "%SERVER_BIN%" (
    echo Hexana MCP launcher not found: %SERVER_BIN% 1>&2
    echo Bundle a native runtime under "%PLUGIN_ROOT%\native" or the JVM runtime under "%PLUGIN_ROOT%\server", or set HEXANA_SERVER_BIN to the launcher path. 1>&2
    exit /b 1
)

call "%SERVER_BIN%" %*
exit /b %ERRORLEVEL%
