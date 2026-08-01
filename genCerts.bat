@echo off
setlocal EnableExtensions

if "%NICOCACHE_JAVA%"=="" (
  set java=java
) else (
  set java=%NICOCACHE_JAVA%
)
cd /d "%~dp0"

if not exist "certificate-targets.txt" (
  echo certificate-targets.txt is empty or not found.
  exit /b 1
)
if not exist "NicoCacheCA.jar" (
  echo NicoCacheCA.jar is not found.
  exit /b 1
)

for %%f in (lib\bcprov.jar lib\bcpkix.jar lib\bcutil.jar) do (
  if not exist %%f (
    echo %%f is not found.
    echo;
    echo NicoCacheCA.jar require lib\bcprov.jar and lib\bcpkix.jar and lib\bcutil.jar
    echo Please download Bouncy Castle:
    echo   https://www.bouncycastle.org/latest_releases.html
    exit 1
  )
)

"%java%" -jar NicoCacheCA.jar --headless --targets-file="%~dp0certificate-targets.txt"
exit /b %ERRORLEVEL%
