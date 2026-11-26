@echo off
setlocal
set "_vernac_classpath=%~dp0..\lib\*"
if defined VERNAC_PROJECT_CLASSPATH set "_vernac_classpath=%_vernac_classpath%;%VERNAC_PROJECT_CLASSPATH%"
set "_vernac_java=java.exe"
if defined JAVA_HOME set "_vernac_java=%JAVA_HOME%\bin\java.exe"
"%_vernac_java%" -cp "%_vernac_classpath%" org.vernac.compiler.pipeline.ScenarioCli %*
exit /b %ERRORLEVEL%
