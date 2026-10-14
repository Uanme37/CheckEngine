@echo off
rem Check Engine: drag a modpack folder (e.g. a CurseForge instance) onto this file to check it before launching.
rem Works on any PC that has played modern modded Minecraft: it borrows the Java 17/21 that the launchers install.
setlocal EnableExtensions

rem --- Find the Check Engine program: next to this file (download), or in the project's build folder (dev).
set "JAR="
for %%F in ("%~dp0checkengine-cli*.jar") do set "JAR=%%~fF"
if not defined JAR for %%F in ("%~dp0cli\build\libs\checkengine-cli*.jar") do set "JAR=%%~fF"
if not defined JAR (
    echo Can't find checkengine-cli.jar. Keep it in the same folder as this file.
    pause
    exit /b 2
)

rem --- Find a Java that can run it (17 or newer). The first one that works wins.
set "JAVA="
for %%J in (
    "%USERPROFILE%\curseforge\minecraft\Install\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\java.exe"
    "%USERPROFILE%\curseforge\minecraft\Install\runtime\java-runtime-epsilon\windows-x64\java-runtime-epsilon\bin\java.exe"
    "%USERPROFILE%\curseforge\minecraft\Install\runtime\java-runtime-gamma\windows-x64\java-runtime-gamma\bin\java.exe"
    "%APPDATA%\.minecraft\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\java.exe"
    "%APPDATA%\.minecraft\runtime\java-runtime-gamma\windows-x64\java-runtime-gamma\bin\java.exe"
    "%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\java.exe"
    "%JAVA_HOME%\bin\java.exe"
    "java"
) do call :try %%J
if not defined JAVA (
    for /d %%D in ("%USERPROFILE%\.gradle\jdks\*21*") do call :try "%%D\bin\java.exe"
)
if not defined JAVA (
    echo Couldn't find Java 17 or newer. Launch any Minecraft 1.20 or 1.21 modpack once in CurseForge so it installs it, then try again.
    pause
    exit /b 2
)

set "PACK=%~1"
if "%PACK%"=="" (
    echo Drag a modpack folder onto "Check Pack.bat", or paste its path here.
    set /p "PACK=Pack folder: "
)
set "PACK=%PACK:"=%"

rem --- A pack export (.zip / .mrpack): check it before uploading.
set "EXPORT="
if /i "%PACK:~-4%"==".zip" set "EXPORT=1"
if /i "%PACK:~-7%"==".mrpack" set "EXPORT=1"
if defined EXPORT (
    echo.
    "%JAVA%" -jar "%JAR%" exportcheck "%PACK%"
    echo.
    pause
    exit /b
)

if exist "%PACK%\checkengine\strip-mine.properties" call :stripcheck
if exist "%PACK%\checkengine\undo-new-mods.properties" call :putback
echo.
if not exist "%PACK%\checkengine" mkdir "%PACK%\checkengine" 2>nul
"%JAVA%" -jar "%JAR%" scan "%PACK%" --out "%PACK%\checkengine\check-report.txt"
if errorlevel 2 (
    echo.
    echo Couldn't check that folder. Is it a pack folder with a mods folder inside?
) else if errorlevel 1 (
    echo.
    echo Fix the problems above before launching, or the game will probably crash while loading.
) else (
    echo.
    echo Looks good to launch.
)

rem --- Mods that flood the game log (only shown when some do).
"%JAVA%" -jar "%JAR%" logs "%PACK%" > "%TEMP%\checkengine-logs.txt" 2>nul
if errorlevel 1 (
    echo.
    type "%TEMP%\checkengine-logs.txt"
)

echo.
echo Want to check it against a server? Drag the server's folder or its server pack .zip into this window,
set /p "SERVER=then press Enter (or just press Enter to skip): "
if defined SERVER call :compare

if exist "%PACK%\crash-reports\*.txt" call :crash

echo.
set "HELP="
set /p "HELP=Asking someone for help? Make one file with the reports and logs to send them (y/n): "
if /i "%HELP%"=="y" call :export
echo.
pause
exit /b

:export
echo.
"%JAVA%" -jar "%JAR%" export "%PACK%"
if not errorlevel 1 start "" "%PACK%\checkengine"
exit /b

:compare
set "SERVER=%SERVER:"=%"
echo.
"%JAVA%" -jar "%JAR%" compare "%PACK%" "%SERVER%"
exit /b

:crash
echo.
set "SHOW="
set /p "SHOW=This pack has crashed before. Explain its last crash? (y/n): "
if /i not "%SHOW%"=="y" exit /b
"%JAVA%" -jar "%JAR%" crash "%PACK%"
echo.
"%JAVA%" -jar "%JAR%" newmods "%PACK%" list && goto dig
set "UNDO="
set /p "UNDO=Switch these off, so the pack is back to how it last worked? Nothing is deleted (y/n): "
if /i "%UNDO%"=="y" "%JAVA%" -jar "%JAR%" newmods "%PACK%" off & exit /b
echo.
:dig
set "DIG="
set /p "DIG=Not sure which mod causes it? Strip Mine can find it, one launch per round (y/n): "
if /i "%DIG%"=="y" "%JAVA%" -jar "%JAR%" stripmine "%PACK%"
exit /b

:putback
echo.
echo Undo new mods switched some mods off in this pack.
set "BACK="
set /p "BACK=Switch them back on? (y/n): "
if /i "%BACK%"=="y" "%JAVA%" -jar "%JAR%" newmods "%PACK%" putback
exit /b

:stripcheck
findstr /b /c:"status=RUNNING" /c:"status=PENDING" "%PACK%\checkengine\strip-mine.properties" >nul 2>&1 || exit /b
echo.
echo A Strip Mine search is running for this pack.
"%JAVA%" -jar "%JAR%" stripmine "%PACK%"
echo.
pause
exit

:try
if defined JAVA exit /b
if not "%~1"=="java" if not exist "%~1" exit /b
"%~1" -jar "%JAR%" --help >nul 2>&1 && set "JAVA=%~1"
exit /b
