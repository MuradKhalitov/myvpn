@echo off
set "G=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat"
if exist "%G%" (call "%G%" %* & exit /b %ERRORLEVEL%)
where gradle >nul 2>nul || (echo Install Gradle 8.14 or Android Studio.& exit /b 1)
gradle %*
