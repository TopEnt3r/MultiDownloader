# Build Windows Installer

## Requisiti

- **Windows 10/11** (jpackage non supporta cross-compilation)
- **JDK 21+** - [Download Temurin](https://adoptium.net/temurin/releases/)
- **Maven 3.9+** - [Download Maven](https://maven.apache.org/download.cgi)
- **Python 3.10+** - [Download Python](https://www.python.org/downloads/)
- **ffmpeg** - [Download ffmpeg](https://github.com/BtbN/FFmpeg-Builds/releases)

## Metodo 1: Build Automatico (GitHub Actions)

1. Push un tag con prefisso `v`:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

2. GitHub Actions compilerà automaticamente e creerà:
   - `MultiSiteDownloader-1.0.0.exe` (installer)
   - `MultiSiteDownloader-portable-win64.zip` (versione portable)

3. Scarica da **Releases** su GitHub

## Metodo 2: Build Manuale su Windows

1. Clona il repository su Windows

2. Esegui:
   ```cmd
   cd Downloader\MultiSiteDownloaderFX
   build-windows.bat
   ```

3. Per creare l'installer .exe:
   ```cmd
   jpackage ^
     --type exe ^
     --name "MultiSiteDownloader" ^
     --app-version "1.0.0" ^
     --vendor "TopEnt3r" ^
     --input dist\lib ^
     --main-jar MultiSiteDownloaderFX-0.1.0-SNAPSHOT.jar ^
     --main-class com.topent3r.multi.MainApp ^
     --dest installer ^
     --win-dir-chooser ^
     --win-menu ^
     --win-shortcut ^
     --java-options "-Xmx2g"
   ```

## Struttura Output

```
dist/
├── lib/
│   └── MultiSiteDownloaderFX-0.1.0-SNAPSHOT.jar
├── scripts/
│   ├── altadefinizione_headless.py
│   ├── animeunity_headless.py
│   └── ...
├── StreamingCommunity/
│   └── (libreria Python)
├── ffmpeg/
│   ├── ffmpeg.exe
│   └── ffprobe.exe
└── MultiSiteDownloader.bat
```

## Note

- L'installer includerà automaticamente un JRE embedded
- Dimensione finale: ~150-200 MB
- Python deve essere installato separatamente dall'utente finale
- ffmpeg viene bundled nell'installer

## Versione Portable

La versione portable (ZIP) richiede:
- Java 21+ installato
- Python 3.10+ installato

Basta estrarre e lanciare `MultiSiteDownloader.bat`
