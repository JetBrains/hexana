@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "ROOT_DIR=%%~fI"

echo == Building Extension Assets ==
call "%ROOT_DIR%\gradlew.bat" :vscode-plugin:assembleExtensionAssets
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Building plugin shim ==
cd /d "%SCRIPT_DIR%"
call npm install
call npm run compile

echo == Packaging ==
call npx @vscode/vsce package --allow-missing-repository

for /f "usebackq delims=" %%v in (`node -p "require('./package.json').version"`) do set VSIX_VERSION=%%v
set VSIX_FILE=hexana-wasm-%VSIX_VERSION%.vsix

echo == Registering the plugin in VSCode ==
where code >nul 2>nul
if %ERRORLEVEL% equ 0 (
    code --install-extension %VSIX_FILE%
    start code
) else (
    where code-insiders >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        code-insiders --install-extension %VSIX_FILE%
        start code-insiders
    ) else (
        echo Please register vscode/vscode-insiders in PATH ^(Help -^> Show All Commands -^> path^)
    )
)

endlocal
