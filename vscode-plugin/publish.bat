@echo off
setlocal

REM Publish the Hexana VS Code extension to the VS Code Marketplace and Open VSX.
REM
REM Prerequisites:
REM   1. Build the .vsix first:  build-release-package.bat
REM   2. Set the required environment variables (see below).
REM
REM Environment variables:
REM   VSCE_PAT          - Personal Access Token for the VS Code Marketplace.
REM                       Create one at https://dev.azure.com (Marketplace > Manage).
REM   OVSX_PAT          - Personal Access Token for Open VSX.
REM                       Create one at https://open-vsx.org/user-settings/tokens.
REM   PUBLISH_TARGET    - (optional) "vscode", "openvsx", or "all" (default: "all").

REM Resolve .vsix file name from package.json
for /f "delims=" %%N in ('node -p "require('./package.json').name"') do set "PACKAGE_NAME=%%N"
for /f "delims=" %%V in ('node -p "require('./package.json').version"') do set "PACKAGE_VERSION=%%V"
set "VSIX_FILE=%PACKAGE_NAME%-%PACKAGE_VERSION%.vsix"

if not exist "%VSIX_FILE%" (
    echo ERROR: %VSIX_FILE% not found.
    echo Run build-release-package.bat first to produce the .vsix artifact.
    exit /b 1
)

if "%PUBLISH_TARGET%"=="" set "PUBLISH_TARGET=all"

if "%PUBLISH_TARGET%"=="vscode" goto :publish_vscode_only
if "%PUBLISH_TARGET%"=="openvsx" goto :publish_openvsx_only
if "%PUBLISH_TARGET%"=="all" goto :publish_all

echo ERROR: Unknown PUBLISH_TARGET '%PUBLISH_TARGET%'. Use 'vscode', 'openvsx', or 'all'.
exit /b 1

:publish_all
call :publish_vscode
set "VSCE_EXIT=%ERRORLEVEL%"
call :publish_openvsx
set "OVSX_EXIT=%ERRORLEVEL%"
if %VSCE_EXIT% neq 0 (
    echo WARNING: VS Code Marketplace publish failed.
    exit /b 1
)
if %OVSX_EXIT% neq 0 (
    echo WARNING: Open VSX publish failed.
    exit /b 1
)
goto :done

:publish_vscode_only
call :publish_vscode
exit /b %ERRORLEVEL%

:publish_openvsx_only
call :publish_openvsx
exit /b %ERRORLEVEL%

:publish_vscode
if "%VSCE_PAT%"=="" (
    echo ERROR: VSCE_PAT is not set. Skipping VS Code Marketplace publish.
    exit /b 1
)
echo == Publishing %VSIX_FILE% to VS Code Marketplace ==
call npx @vscode/vsce publish --pat "%VSCE_PAT%" --packagePath "%VSIX_FILE%"
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
echo == Published to VS Code Marketplace ==
exit /b 0

:publish_openvsx
if "%OVSX_PAT%"=="" (
    echo ERROR: OVSX_PAT is not set. Skipping Open VSX publish.
    exit /b 1
)
echo == Publishing %VSIX_FILE% to Open VSX ==
call npx ovsx publish "%VSIX_FILE%" --pat "%OVSX_PAT%"
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
echo == Published to Open VSX ==
exit /b 0

:done
echo == All publish targets succeeded ==
endlocal
