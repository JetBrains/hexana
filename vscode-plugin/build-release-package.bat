@echo off
setlocal

REM Build the VS Code extension with JavaScript obfuscation.
REM Unlike build-and-run-locally.bat this script only produces the .vsix
REM artifact — it does NOT install or launch VS Code.

echo == Installing npm dependencies ==
call npm install
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

REM The third-party notices read the production npm dependencies from node_modules, so npm install comes first.
echo == Building Extension Assets and third-party notices ==
call ..\gradlew.bat :vscode-plugin:assembleExtensionAssets :vscode-plugin:generateThirdPartyNotices
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Compiling TypeScript ==
call npm run compile
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Obfuscating JavaScript bundles ==
call npm run obfuscate
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Packaging VSIX ==
call npx @vscode/vsce package
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

for /f "usebackq delims=" %%v in (`node -p "require('./package.json').version"`) do set VSIX_VERSION=%%v
set VSIX_FILE=hexana-wasm-%VSIX_VERSION%.vsix

echo == Verifying third-party notices in the VSIX ==
call node scripts\verify-vsix-notices.js "%VSIX_FILE%"
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Done ==
echo Obfuscated VSIX is at: %VSIX_FILE%

endlocal
