@ECHO OFF
SET DIRNAME=%~dp0
IF "%DIRNAME%"=="" SET DIRNAME=.
SET APP_HOME=%DIRNAME%
SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

IF NOT "%JAVA_HOME%"=="" IF EXIST "%JAVA_HOME%\bin\java.exe" SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
IF "%JAVA_EXE%"=="" SET JAVA_EXE=java.exe

"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
