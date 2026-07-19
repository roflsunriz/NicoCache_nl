@echo off
setlocal
if "%~1"=="" goto USAGE
"%JAVA_HOME%\bin\javac" -Xlint -Xlint:-path -classpath ..;..\NicoCache_nl.jar %*
goto :EOF
:USAGE
echo Usage: build Extension1.java [Extension2.java ...]
