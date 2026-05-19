@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PLUGIN_ROOT=%%~fI"

if defined HEXANA_SERVER_BIN (
    set "SERVER_BIN=%HEXANA_SERVER_BIN%"
) else (
    set "SERVER_BIN=%PLUGIN_ROOT%\server\bin\server.bat"
)

if not exist "%SERVER_BIN%" (
    echo Hexana standalone launcher not found: %SERVER_BIN% 1>&2
    echo Bundle the standalone runtime under "%PLUGIN_ROOT%\server" or set HEXANA_SERVER_BIN to the launcher path. 1>&2
    exit /b 1
)

call "%SERVER_BIN%" %*
exit /b %ERRORLEVEL%
