@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo   Key Sequence Studio - Windows Installer
echo ==========================================
echo.

where gradle >nul 2>nul
if errorlevel 1 (
    echo Gradle was not found on PATH.
    echo.
    echo Open this folder in IntelliJ IDEA, reload the Gradle project,
    echo then run: Tasks ^> distribution ^> packageWindowsInstaller
    echo.
    pause
    exit /b 1
)

gradle --no-daemon packageWindowsInstaller
if errorlevel 1 (
    echo.
    echo Installer build failed. See the Gradle output above.
    pause
    exit /b 1
)

echo.
echo Installer created in:
echo   build\installer\
echo.
explorer "%~dp0build\installer"
pause
