@echo off
REM Convenience wrapper for this machine: uses the locally extracted Gradle 8.9 and a
REM shared Gradle home. On a normal dev machine you can just use .\gradlew instead.
set GRADLE_USER_HOME=D:\work\.tools\gradle-home
cd /d D:\work\Framer\PerfOverlay
call D:\work\.tools\gradle-8.9\bin\gradle.bat --no-daemon --console=plain %*
