@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d "C:\Users\bastr\Desktop\whisper Everywhere"
call gradlew.bat bundleRelease
echo.
echo Build completed with exit code: %errorlevel%
pause
