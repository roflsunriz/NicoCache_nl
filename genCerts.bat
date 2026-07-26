@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "DOMAINS="
for /f "usebackq eol=# tokens=* delims=" %%d in ("%~dp0certificate-targets.txt") do (
  set "DOMAINS=!DOMAINS! %%d"
)
if not defined DOMAINS (
  echo certificate-targets.txt is empty or not found.
  exit /b 1
)

if "%NICOCACHE_JAVA%"=="" (
  set java=java
) else (
  set java=%NICOCACHE_JAVA%
)
cd /d %~dp0

for %%f in (lib\bcprov.jar lib\bcpkix.jar lib\bcutil.jar) do (
  if not exist %%f (
    echo %%f is not found.
    echo;
    echo NicoCacheCA.jar require lib\bcprov.jar and lib\bcpkix.jar and lib\bcutil.jar
    echo Please download Bouncy Castle:
    echo   https://www.bouncycastle.org/latest_releases.html
    pause
    exit 1
  )
)

"%java%" -jar NicoCacheCA.jar %DOMAINS%

pause
