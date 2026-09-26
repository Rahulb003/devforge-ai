@echo off
set MAVEN_HOME=%MAVEN_HOME%
set MAVEN_OPTS=%MAVEN_OPTS%
if "%MAVEN_HOME%"=="" (
  mvn %*
) else (
  "%MAVEN_HOME%\bin\mvn" %*
)
