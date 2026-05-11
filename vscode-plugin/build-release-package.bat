@echo off
setlocal

REM Build the VS Code extension with JavaScript obfuscation.
REM Unlike build-and-run-locally.bat this script only produces the .vsix
REM artifact — it does NOT install or launch VS Code.

echo == Building Extension Assets ==
call ..\gradlew.bat :vscode-plugin:assembleExtensionAssets
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

echo == Installing npm dependencies ==
call npm install
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

echo == Done ==
echo Obfuscated VSIX is at: %VSIX_FILE%

endlocal
