@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo Coffee Shop POS - Database Backup Utility
echo =========================================

set "LOCAL_SDK=%~dp0tools\android-sdk"
if exist "%LOCAL_SDK%\platform-tools\adb.exe" set "PATH=%LOCAL_SDK%\platform-tools;%PATH%"
set "ADB_EXE=%LOCAL_SDK%\platform-tools\adb.exe"

for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*") do (
  if exist "%%~fD\platform-tools\adb.exe" set "ADB_EXE=%%~fD\platform-tools\adb.exe"
)

if not exist "%ADB_EXE%" (
  echo.
  echo ADB was not found. Please install Android Platform-Tools.
  pause
  exit /b 1
)

"%ADB_EXE%" devices | findstr /R /C:"device$" >nul
if errorlevel 1 (
  echo.
  echo No Android emulator or tablet is connected.
  echo Please connect your tablet with USB debugging enabled.
  pause
  exit /b 1
)

echo Connected device found. Creating backup folder...
if not exist "%~dp0backups" mkdir "%~dp0backups"

echo Copying database files to temporary location on tablet...
"%ADB_EXE%" shell "run-as com.kape.coffeepos cp /data/data/com.kape.coffeepos/databases/coffee_pos.db /data/local/tmp/coffee_pos_backup.db" 2>nul
"%ADB_EXE%" shell "run-as com.kape.coffeepos cp /data/data/com.kape.coffeepos/databases/coffee_pos.db-shm /data/local/tmp/coffee_pos_backup.db-shm" 2>nul
"%ADB_EXE%" shell "run-as com.kape.coffeepos cp /data/data/com.kape.coffeepos/databases/coffee_pos.db-wal /data/local/tmp/coffee_pos_backup.db-wal" 2>nul

echo Making files readable...
"%ADB_EXE%" shell "chmod 666 /data/local/tmp/coffee_pos_backup.db*" 2>nul

echo Pulling backup files to your PC...
"%ADB_EXE%" pull /data/local/tmp/coffee_pos_backup.db "%~dp0backups\coffee_pos.db"
"%ADB_EXE%" pull /data/local/tmp/coffee_pos_backup.db-shm "%~dp0backups\coffee_pos.db-shm" 2>nul
"%ADB_EXE%" pull /data/local/tmp/coffee_pos_backup.db-wal "%~dp0backups\coffee_pos.db-wal" 2>nul

echo Cleaning up temporary files on tablet...
"%ADB_EXE%" shell "rm /data/local/tmp/coffee_pos_backup.db*" 2>nul

if exist "%~dp0backups\coffee_pos.db" (
  echo.
  echo ========================================================
  echo SUCCESS: Database backup created in "%~dp0backups\"
  echo ========================================================
) else (
  echo.
  echo ERROR: Failed to backup coffee_pos.db. 
  echo Is the app installed and has it been launched at least once?
)
pause
exit /b 0
