@echo off
rem Pack Doctor: drag a modpack folder (e.g. a CurseForge instance) onto this file to check it before launching.
rem Works on any PC that has played modern modded Minecraft: it borrows the Java 17/21 that the launchers install.
setlocal EnableExtensions

rem --- Find the Pack Doctor program: next to this file (download), or in the project's build folder (dev).
set "JAR="
for %%F in ("%~dp0packdoctor-cli*.jar") do set "JAR=%%~fF"
if not defined JAR for %%F in ("%~dp0cli\build\libs\packdoctor-cli*.jar") do set "JAR=%%~fF"
if not defined JAR (
    echo Can't find packdoctor-cli.jar. Keep it in the same folder as this file.
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

if /i "%PACK:~-4%"==".zip" (
    echo.
    echo That's a zip. A CurseForge export zip only lists which mods to download, the mods aren't inside it.
    echo Drag the pack's folder instead. In CurseForge: right-click the pack, then "Open Folder".
    echo.
    pause
    exit /b
)

echo.
if not exist "%PACK%\packdoctor" mkdir "%PACK%\packdoctor" 2>nul
"%JAVA%" -jar "%JAR%" scan "%PACK%" --out "%PACK%\packdoctor\check-report.txt"
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

echo.
echo Want to check it against a server? Drag the server's folder or its server pack .zip into this window,
set /p "SERVER=then press Enter (or just press Enter to skip): "
if defined SERVER call :compare

if exist "%PACK%\crash-reports" (
    echo.
    set /p "SHOW=This pack has crashed before. Explain its last crash? (y/n): "
    call :compare
set "SERVER=%SERVER:"=%"
echo.
"%JAVA%" -jar "%JAR%" compare "%PACK%" "%SERVER%"
exit /b

:crash
)
echo.
pause
exit /b

:compare
set "SERVER=%SERVER:"=%"
echo.
"%JAVA%" -jar "%JAR%" compare "%PACK%" "%SERVER%"
exit /b

:crash
if /i "%SHOW%"=="y" "%JAVA%" -jar "%JAR%" crash "%PACK%"
exit /b

:try
if defined JAVA exit /b
if not "%~1"=="java" if not exist "%~1" exit /b
"%~1" -jar "%JAR%" --help >nul 2>&1 && set "JAVA=%~1"
exit /b
