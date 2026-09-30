@echo off
setlocal
set "gradleTaskJava=java.exe"
if defined JAVA_HOME set "gradleTaskJava=%JAVA_HOME%\bin\java.exe"
"%gradleTaskJava%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
