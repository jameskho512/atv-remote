@echo off
rem Builds the debug APK and, if a phone is connected over USB, installs it.
cd /d "%~dp0"

rem Clear build processes left over from interrupted builds (they can hold locks and stall the build).
call gradlew.bat --stop >nul 2>nul
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'KotlinCompileDaemon' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"

call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo Build failed.
    pause
    exit /b 1
)

set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk
echo.
echo Built: %APK%

where adb >nul 2>nul
if errorlevel 1 goto done
adb get-state >nul 2>nul
if errorlevel 1 (
    echo No phone connected over USB; skipping install.
    goto done
)
echo Installing on phone...
adb install -r "%APK%"

:done
pause
