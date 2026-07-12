@echo off
REM Erstellt local.properties (sdk.dir) fuer den Android-Build, falls noch nicht vorhanden.
setlocal enabledelayedexpansion
set "PROJ=%~dp0"
set "LP=%PROJ%local.properties"

if exist "%LP%" (
    echo [setup] local.properties existiert bereits - unveraendert.
) else (
    set "SDK="
    if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms" set "SDK=%ANDROID_SDK_ROOT%"
    if not defined SDK if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms" set "SDK=%ANDROID_HOME%"
    if not defined SDK set "SDK=D:\Temp\AndroidCaches\Sdk"

    if not exist "!SDK!\platforms" (
        echo [setup][WARN] Kein "platforms"-Ordner unter "!SDK!" gefunden - trage Pfad trotzdem ein.
    )

    set "SDKFWD=!SDK:\=/!"
    > "%LP%" echo sdk.dir=!SDKFWD!
    echo [setup] local.properties erstellt mit sdk.dir=!SDKFWD!
)

endlocal
