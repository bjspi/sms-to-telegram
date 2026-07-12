@echo off
REM ===========================================================================
REM  SMS Relayer - Debug-Build bauen und per adb installieren (Windows).
REM  - bricht mit Fehlercode ab, wenn der Gradle-Build fehlschlaegt
REM  - bricht ab, wenn kein adb-Geraet (Status 'device') verbunden ist
REM ===========================================================================
setlocal enabledelayedexpansion
set "PROJ=%~dp0"
cd /d "%PROJ%"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=D:\Temp\SmsRelayerGradle"
if not defined TEMP set "TEMP=D:\Temp\SmsRelayerGradleTmp"
if not defined TMP set "TMP=D:\Temp\SmsRelayerGradleTmp"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%" >nul 2>nul
if not exist "%TEMP%" mkdir "%TEMP%" >nul 2>nul
if not exist "%TMP%" mkdir "%TMP%" >nul 2>nul

REM --- Build-/Temp-Verzeichnisse aus Backups ausschliessen (nur lokal vorhanden) ---
if exist "%PROJ%mark-nobackup.bat" call "%PROJ%mark-nobackup.bat"

REM --- local.properties sicherstellen ---
call "%PROJ%setup-local-properties.bat"

REM --- Build ---
echo.
echo [build] gradlew assembleDebug ...
call "%PROJ%gradlew.bat" assembleDebug
if errorlevel 1 (
    echo.
    echo [FEHLER] BUILD FEHLGESCHLAGEN.
    exit /b 1
)

set "APK=%PROJ%app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo.
    echo [FEHLER] APK nicht gefunden: %APK%
    exit /b 1
)

REM --- adb pruefen ---
where adb >nul 2>nul
if errorlevel 1 (
    echo.
    echo [FEHLER] 'adb' nicht im PATH gefunden.
    exit /b 1
)
adb start-server >nul 2>nul

set /a DEVCOUNT=0
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
    if "%%b"=="device" set /a DEVCOUNT+=1
)
if %DEVCOUNT% EQU 0 (
    echo.
    echo [FEHLER] Kein ADB-Geraet verbunden ^(Status 'device'^).
    echo          Bitte ein Geraet anschliessen ^(USB-Debugging^) oder einen Emulator starten.
    exit /b 1
)
echo [adb] %DEVCOUNT% Geraet^(e^) verbunden.

REM --- Installieren ---
echo.
echo [install] adb install -r ...
adb install -r "%APK%"
if errorlevel 1 (
    echo.
    echo [FEHLER] Installation fehlgeschlagen.
    exit /b 1
)

REM --- App starten (Debug-Build hat das Suffix ".debug" in der Application-ID) ---
echo [start] App starten ...
adb shell am start -n io.github.bjspi.smsrelayer.debug/io.github.bjspi.smsrelayer.app.MainActivity >nul 2>nul

echo.
echo [OK] Build + Installation erfolgreich.
endlocal
