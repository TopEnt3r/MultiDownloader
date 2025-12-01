@echo off
setlocal enabledelayedexpansion

echo ========================================
echo   MultiSiteDownloader Windows Builder
echo ========================================
echo.

:: Check Java
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Java not found. Install JDK 21+
    pause
    exit /b 1
)

:: Check Maven
where mvn >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Maven not found. Install Maven 3.9+
    pause
    exit /b 1
)

:: Check Python
where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Python not found. Install Python 3.10+
    pause
    exit /b 1
)

echo [1/5] Installing Python dependencies...
pip install requests beautifulsoup4 httpx tqdm rich m3u8 --quiet

echo [2/5] Building JAR...
call mvn clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo [ERROR] Maven build failed
    pause
    exit /b 1
)

echo [3/5] Creating bundle directory...
if exist "dist" rmdir /s /q "dist"
mkdir dist
mkdir dist\lib
mkdir dist\scripts
mkdir dist\StreamingCommunity
mkdir dist\ffmpeg

:: Copy JAR
copy "target\MultiSiteDownloaderFX-0.1.0-SNAPSHOT.jar" "dist\lib\" >nul

:: Copy scripts
copy "..\..\..\tmp\*.py" "dist\scripts\" >nul 2>nul

:: Copy StreamingCommunity
xcopy /s /e /q "..\StreamingCommunity\StreamingCommunity-main\*" "dist\StreamingCommunity\" >nul

echo [4/5] Checking for ffmpeg...
if not exist "dist\ffmpeg\ffmpeg.exe" (
    echo [WARNING] ffmpeg.exe not found in dist\ffmpeg\
    echo Download from: https://github.com/BtbN/FFmpeg-Builds/releases
    echo Extract ffmpeg.exe and ffprobe.exe to dist\ffmpeg\
)

echo [5/5] Creating launcher...
(
echo @echo off
echo cd /d "%%~dp0"
echo set PATH=%%~dp0ffmpeg;%%PATH%%
echo java --module-path "%%~dp0lib" --add-modules javafx.controls,javafx.fxml,javafx.web -jar "%%~dp0lib\MultiSiteDownloaderFX-0.1.0-SNAPSHOT.jar"
echo pause
) > "dist\MultiSiteDownloader.bat"

echo.
echo ========================================
echo   Build complete!
echo ========================================
echo.
echo Output: dist\
echo.
echo To create installer, run:
echo   jpackage --type exe --name MultiSiteDownloader --input dist\lib --main-jar MultiSiteDownloaderFX-0.1.0-SNAPSHOT.jar --main-class com.topent3r.multi.MainApp --win-menu --win-shortcut
echo.
pause
