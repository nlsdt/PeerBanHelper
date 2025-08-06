@echo off
title PeerBanHelper JVM Crash Error Handler
echo Please wait while the error handler is being started...
setlocal EnableDelayedExpansion

:: 确保传入了 PID 参数
if "%~1"=="" (
    echo Error: PID argument is required.
    echo Usage:  %~nx0 ^<PID^>
    goto :eof
)

set "PID=%~1"
set "DEST_DIR=%LOCALAPPDATA%\PeerBanHelper"
set "CRASH_REPORTS_DIR=%DEST_DIR%\crash-reports"
set "HS_ERR_FILENAME=hs_err_pid%PID%.log"
set "GUI_EXE_NAME=PeerBanHelper-GUI.exe"
set "GUI_PARAM=crashRecovery:%PID%"

:: Generate timestamp for unique crash report naming
for /f "tokens=2 delims==" %%i in ('wmic OS Get localdatetime /value') do set datetime=%%i
set "TIMESTAMP=%datetime:~0,8%_%datetime:~8,6%"

echo.
echo ----------------------------------------------------
echo PeerBanHelper JVM Crash Recovery Handler
echo Crash PID: %PID%
echo Working directory: %cd%
echo Dest directory: %DEST_DIR%
echo Timestamp: %TIMESTAMP%
echo ----------------------------------------------------
echo.

:: 1. 检查并创建目标目录
if not exist "%DEST_DIR%" (
    echo Creating target directory: %DEST_DIR%
    md "%DEST_DIR%"
    if errorlevel 1 (
        echo Error: Unable to create %DEST_DIR%. Permission denied?
        goto :eof
    )
)

:: 2. 创建崩溃报告目录
if not exist "%CRASH_REPORTS_DIR%" (
    echo Creating crash reports directory: %CRASH_REPORTS_DIR%
    md "%CRASH_REPORTS_DIR%"
    if errorlevel 1 (
        echo Warning: Unable to create crash reports directory
    )
)

:: 3. 定义搜索路径列表
set "SEARCH_PATHS[0]=%cd%"
set "SEARCH_PATHS[1]=%TEMP%"
set "SEARCH_PATHS[2]=%TMP%"
set "SEARCH_PATHS[3]=%USERPROFILE%"
set "SEARCH_PATHS[4]=%LOCALAPPDATA%\PeerBanHelper"
set "SEARCH_PATHS[5]=%APPDATA%\PeerBanHelper"
set "SEARCH_PATHS[6]=%SYSTEMROOT%\Temp"

set "FOUND_FILE="
set "SOURCE_PATH="

:: 4. 搜索崩溃文件
for /L %%i in (0,1,6) do (
    if defined SEARCH_PATHS[%%i] (
        set "CURRENT_PATH=!SEARCH_PATHS[%%i]!"
        if exist "!CURRENT_PATH!\%HS_ERR_FILENAME%" (
            echo Found crash file in: !CURRENT_PATH!
            set "FOUND_FILE=!CURRENT_PATH!\%HS_ERR_FILENAME%"
            set "SOURCE_PATH=!CURRENT_PATH!"
            goto :found
        ) else (
            echo Searching in: !CURRENT_PATH! - Not found
        )
    )
)

echo.
echo Warning: No crash file found for PID %PID% in any expected directory.
goto :finish

:found
echo.
echo Successfully located crash file: %FOUND_FILE%

:: 5. 复制到目标目录
echo Copying crash file to destination...
copy "%FOUND_FILE%" "%DEST_DIR%\"
if errorlevel 0 (
    echo Copied %HS_ERR_FILENAME% to %DEST_DIR%
) else (
    echo Error: Failed to copy crash file to destination!
)

:: 6. 如果存在崩溃报告目录，也复制一份带时间戳的版本
if exist "%CRASH_REPORTS_DIR%" (
    set "ARCHIVED_NAME=hs_err_pid%PID%_%TIMESTAMP%.log"
    echo Creating archived copy: !ARCHIVED_NAME!
    copy "%FOUND_FILE%" "%CRASH_REPORTS_DIR%\!ARCHIVED_NAME!"
    if errorlevel 0 (
        echo Archived crash file as: !ARCHIVED_NAME!
    ) else (
        echo Warning: Failed to create archived copy
    )
)

:: 7. 清理旧的崩溃报告（保留最新的10个）
if exist "%CRASH_REPORTS_DIR%" (
    echo Cleaning up old crash reports...
    for /f "skip=10 tokens=*" %%f in ('dir "%CRASH_REPORTS_DIR%\hs_err_*.log" /b /o-d 2^>nul') do (
        echo Removing old crash report: %%f
        del "%CRASH_REPORTS_DIR%\%%f" 2>nul
    )
)

goto :success

:success
echo.
echo ============================================
echo Crash report collection completed successfully!
echo ============================================
echo.

:finish
echo.
echo Attempting to restart PeerBanHelper...
if exist "%cd%\%GUI_EXE_NAME%" (
    echo Starting: %cd%\%GUI_EXE_NAME% with recovery parameter
    start "" "%cd%\%GUI_EXE_NAME%" "%GUI_PARAM%"
    echo PeerBanHelper restart initiated.
) else (
    echo Warning: %GUI_EXE_NAME% not found in work directory: %cd%
    echo Please manually restart PeerBanHelper.
)

echo.
echo Error handler completed. This window will close in 10 seconds...
timeout /t 10 /nobreak >nul

endlocal