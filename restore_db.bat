@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo Coffee Shop POS - Database Restore Utility
echo ==========================================

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

if not exist "%~dp0backups\coffee_pos.db" (
  echo.
  echo ERROR: No backup file found at "%~dp0backups\coffee_pos.db".
  echo Please run backup_db.bat first to create a backup.
  pause
  exit /b 1
)

echo Push backup files to temporary location on tablet...
"%ADB_EXE%" push "%~dp0backups\coffee_pos.db" /data/local/tmp/coffee_pos_restore.db
if exist "%~dp0backups\coffee_pos.db-shm" "%ADB_EXE%" push "%~dp0backups\coffee_pos.db-shm" /data/local/tmp/coffee_pos_restore.db-shm >nul 2>&1
if exist "%~dp0backups\coffee_pos.db-wal" "%ADB_EXE%" push "%~dp0backups\coffee_pos.db-wal" /data/local/tmp/coffee_pos_restore.db-wal >nul 2>&1

echo Making files readable by app...
"%ADB_EXE%" shell "chmod 666 /data/local/tmp/coffee_pos_restore.db*"

echo Ensuring application database directory exists...
"%ADB_EXE%" shell "run-as com.kape.coffeepos mkdir -p /data/data/com.kape.coffeepos/databases"

echo Copying database files into app sandbox...
"%ADB_EXE%" shell "run-as com.kape.coffeepos cp /data/local/tmp/coffee_pos_restore.db /data/data/com.kape.coffeepos/databases/coffee_pos.db"
"%ADB_EXE%" shell "run-as com.kape.coffeepos cp /data/local/tmp/coffee_pos_restore.db-shm /data/data/com.kape.coffeepos/databases/coffee_pos.db-shm" >nul 2>&1
"%ADB_EXE%" shell "run-as com.kape.coffeepos cp /data/local/tmp/coffee_pos_restore.db-wal /data/data/com.kape.coffeepos/databases/coffee_pos.db-wal" >nul 2>&1

echo Cleaning up temporary files on tablet...
"%ADB_EXE%" shell "rm /data/local/tmp/coffee_pos_restore.db*"

echo.
echo ========================================================
echo SUCCESS: Database has been restored to the app sandbox!
echo Please restart the app on your tablet to see the changes.
echo ========================================================
pause
exit /b 0
