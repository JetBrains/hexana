@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT_DIR=%%~fI"

set "VSCODE_CLI="
call :find_vscode_cli
if not defined VSCODE_CLI (
    echo Unable to find a VSCode CLI.
    echo Either register code/code-insiders in PATH, or install VSCode in a standard location.
    exit /b 1
)

echo == Installing npm dependencies ==
cd /d "%SCRIPT_DIR%"
call npm install
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

REM The third-party notices read the production npm dependencies from node_modules, so npm install comes first.
echo == Building Extension Assets and third-party notices ==
call "%ROOT_DIR%\gradlew.bat" :vscode-plugin:assembleExtensionAssets :vscode-plugin:generateThirdPartyNotices
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Building plugin shim ==
call npm run compile

echo == Packaging ==
call npx @vscode/vsce package --allow-missing-repository

for /f "usebackq delims=" %%v in (`node -p "require('./package.json').version"`) do set VSIX_VERSION=%%v
set VSIX_FILE=hexana-wasm-%VSIX_VERSION%.vsix

echo == Verifying third-party notices in the VSIX ==
call node scripts\verify-vsix-notices.js "%VSIX_FILE%"
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Registering the plugin in VSCode ==
"%VSCODE_CLI%" --install-extension "%VSIX_FILE%" --force
start "" "%VSCODE_CLI%" --reuse-window "%ROOT_DIR%"

endlocal
exit /b 0

:find_vscode_cli
where code >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "VSCODE_CLI=code"
    goto :eof
)

where code-insiders >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "VSCODE_CLI=code-insiders"
    goto :eof
)

for %%P in (
    "%LocalAppData%\Programs\Microsoft VS Code\bin\code.cmd"
    "%ProgramFiles%\Microsoft VS Code\bin\code.cmd"
    "%ProgramFiles(x86)%\Microsoft VS Code\bin\code.cmd"
    "%LocalAppData%\Programs\Microsoft VS Code Insiders\bin\code-insiders.cmd"
    "%ProgramFiles%\Microsoft VS Code Insiders\bin\code-insiders.cmd"
    "%ProgramFiles(x86)%\Microsoft VS Code Insiders\bin\code-insiders.cmd"
) do (
    if exist %%~P (
        set "VSCODE_CLI=%%~P"
        goto :eof
    )
)

goto :eof
