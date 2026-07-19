@echo off
setlocal
set TITLE=NicoCache_nl API Reference
set DOCDIR=..\documents\javadoc
set SRCDIR=%TEMP%\src_nl
if not exist "%SRCDIR%" mkdir "%SRCDIR%"
pushd "%SRCDIR%"
echo Now extracting source files from NicoCache_nl.jar...
jar xf "%~dp0..\NicoCache_nl.jar"
popd
if exist "%DOCDIR%" rmdir /s /q "%DOCDIR%"
javadoc -doctitle "%TITLE%" -windowtitle "%TITLE%" -d "%DOCDIR%" -sourcepath "%SRCDIR%" -link http://java.sun.com/javase/ja/6/docs/ja/api/ dareka dareka.common dareka.common.regex dareka.extensions dareka.processor dareka.processor.impl dareka.processor.util
rmdir /s /q "%SRCDIR%"
