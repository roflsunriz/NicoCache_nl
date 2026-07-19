@echo off
setlocal

if "%NICOCACHE_JAVA%"=="" (
  set java=java
) else (
  set java=%NICOCACHE_JAVA%
)
if "%NICOCACHE_OPTS%"=="" (
  set opts=-Xmx128m
) else (
  set opts=%NICOCACHE_OPTS%
)
if "%1"=="debug" (
  set opts=%opts% -Ddareka.debug=true -Ddareka.logfile=debug.log -ea
) else (
  set opts=%opts% %*
)

rem この長いオプションはjava18以降でリフレクションを使うためのもの.
set "opts21=--add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-exports=java.base/java.lang.invoke=ALL-UNNAMED --add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"

cd /d %~dp0

:LOOP
"%java%" %opts21% %opts% -jar %~n0.jar

if not "%ERRORLEVEL%"=="25" goto EXIT
echo waiting 5 seconds for restarting...
ping -n 5 localhost >NUL
goto LOOP

:EXIT
echo exit status is %ERRORLEVEL%
pause
endlocal
