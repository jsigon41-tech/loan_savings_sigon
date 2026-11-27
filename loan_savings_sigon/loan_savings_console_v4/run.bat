@echo off
rem Builds (first run only) and starts the console application. Requires Java 17+ and Maven.
cd /d "%~dp0"
if not exist target\loan-savings-v4.jar call mvn -q package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar target\loan-savings-v4.jar %*
