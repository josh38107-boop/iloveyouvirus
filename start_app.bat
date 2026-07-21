@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo Coffee Shop POS launcher
echo ========================

set "STUDIO_EXE=C:\Program Files\Android\Android Studio\bin\studio64.exe"
set "USER_JDK=%LOCALAPPDATA%\Programs\Microsoft\jdk-17.0.10.7-hotspot"
set "STUDIO_JDK=C:\Program Files\Android\Android Studio\jbr"
set "GRADLE_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT %GRADLE_OPTS%"
if exist "C:\Users\josh3\AppData\Local\Programs\Microsoft\jdk-17.0.10.7-hotspot\bin\java.exe" set "JAVA_HOME=C:\Users\josh3\AppData\Local\Programs\Microsoft\jdk-17.0.10.7-hotspot"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "%USER_JDK%\bin\java.exe" set "JAVA_HOME=%USER_JDK%"
if not defined JAVA_HOME if exist "%STUDIO_JDK%\bin\java.exe" set "JAVA_HOME=%STUDIO_JDK%"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

set "LOCAL_SDK=%~dp0tools\android-sdk"
if exist "%LOCAL_SDK%\platform-tools\adb.exe" set "PATH=%LOCAL_SDK%\platform-tools;%PATH%"
set "ANDROID_HOME=%LOCAL_SDK%"
set "ANDROID_SDK_ROOT=%LOCAL_SDK%"
set "ADB_EXE=%LOCAL_SDK%\platform-tools\adb.exe"

for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*") do (
  if exist "%%~fD\platform-tools" set "PATH=%%~fD\platform-tools;%PATH%"
  if exist "%%~fD\platform-tools\adb.exe" set "PATH=%%~fD\platform-tools;%PATH%"
  if exist "%%~fD\adb.exe" set "PATH=%%~fD;%PATH%"
)

if not defined JAVA_HOME where java >nul 2>nul
if not defined JAVA_HOME if errorlevel 1 (
  echo.
  echo Java was not found in PATH.
  echo Install Android Studio with JDK 17+, then reopen this launcher.
  if exist "%STUDIO_EXE%" start "" "%STUDIO_EXE%"
  if "%NO_PAUSE%"=="" pause
  exit /b 1
)

if not exist "%ADB_EXE%" (
  echo.
  echo adb was not found at %ADB_EXE%.
  echo Install Android SDK Platform-Tools into tools\android-sdk.
  echo Android Studio will open so you can finish SDK setup if needed.
  if exist "%STUDIO_EXE%" start "" "%STUDIO_EXE%"
  if "%NO_PAUSE%"=="" pause
  exit /b 1
)

if not exist "%~dp0gradlew.bat" (
  echo.
  echo gradlew.bat was not found in this project.
  if "%NO_PAUSE%"=="" pause
  exit /b 1
)

"%ADB_EXE%" devices | findstr /R /C:"device$" >nul
if errorlevel 1 (
  echo.
  echo No Android emulator or device is connected.
  echo Start an emulator in Android Studio, or connect a tablet with USB debugging.
  if "%NO_PAUSE%"=="" pause
  exit /b 1
)

call "%~dp0tools\gradle\gradle-8.10.2\bin\gradle.bat" assembleDebug
if errorlevel 1 (
  echo.
  echo Build failed.
  echo If this is the first run, open the project in Android Studio once so it can download the Android SDK and Gradle files.
  if exist "%STUDIO_EXE%" start "" "%STUDIO_EXE%" "%~dp0"
  if "%NO_PAUSE%"=="" pause
  exit /b 1
)

set "APK_PATH=%~dp0app\build\outputs\apk\debug\app-debug.apk"
set "INSTALL_LOG=%TEMP%\kape-pos-install.log"
if exist "%INSTALL_LOG%" del "%INSTALL_LOG%" >nul 2>nul

"%ADB_EXE%" install -r "%APK_PATH%" >"%INSTALL_LOG%" 2>&1
if errorlevel 1 (
  findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%INSTALL_LOG%" >nul
  if errorlevel 1 (
    type "%INSTALL_LOG%"
    echo.
    echo Install failed.
    if "%NO_PAUSE%"=="" pause
    exit /b 1
  )

  type "%INSTALL_LOG%"
  echo.
  echo An older Coffee POS install on this device uses a different debug signature.
  echo It must be uninstalled before this build can be installed.
  echo This will remove local app data on the connected emulator or device.
  choice /M "Uninstall the existing Coffee POS app and reinstall"
  if errorlevel 2 (
    echo Install cancelled.
    if "%NO_PAUSE%"=="" pause
    exit /b 1
  )

  "%ADB_EXE%" uninstall com.kape.coffeepos
  if errorlevel 1 (
    echo.
    echo Uninstall failed.
    if "%NO_PAUSE%"=="" pause
    exit /b 1
  )

  "%ADB_EXE%" install -r "%APK_PATH%"
  if errorlevel 1 (
    echo.
    echo Reinstall failed.
    if "%NO_PAUSE%"=="" pause
    exit /b 1
  )
)

"%ADB_EXE%" shell monkey -p com.kape.coffeepos -c android.intent.category.LAUNCHER 1
if errorlevel 1 (
  echo.
  echo App installed, but launch failed.
  if "%NO_PAUSE%"=="" pause
  exit /b 1
)

echo.
echo Coffee POS launched.
if "%NO_PAUSE%"=="" pause
