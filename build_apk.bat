@echo off
chcp 65001 >nul
title 视频极客压缩 - 一键打包 APK

set JAVA_HOME=D:\APP\java\jdk-17.0.20.1+1
set ANDROID_HOME=D:\APP\AndroidSdk
set ANDROID_SDK_ROOT=D:\APP\AndroidSdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools;%PATH%

cd /d "%~dp0"

echo [1/2] 正在编译打包 APK (cn.northpark.videocompressor)...
call gradlew.bat assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo  打包成功！
    for /f "delims=" %%i in ('dir /b /s "%~dp0app\build\outputs\apk\debug\np-video-compressor-*.apk"') do (
        set "APK_PATH=%%i"
    )
    echo  APK 安装包路径:
    echo  %APK_PATH%
    echo ========================================================
    echo.
    explorer /select,"%APK_PATH%"
) else (
    echo.
    echo [错误] 打包失败，请检查上方日志。
)

pause
