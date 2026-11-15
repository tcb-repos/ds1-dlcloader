@echo off
rem Starts the Dead Space DLC Loader window. Needs Java 17 or newer (JAVA_HOME, or java on PATH).
setlocal
set "JAVA=java"
set "JAVAW=javaw"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" (set "JAVA=%JAVA_HOME%\bin\java.exe" & set "JAVAW=%JAVA_HOME%\bin\javaw.exe")
set "JV="
for /f "tokens=2 delims==" %%v in ('""%JAVA%" -XshowSettings:properties -version 2>&1 | findstr /c:"java.specification.version""') do set "JV=%%v"
if not defined JV (
  echo Java 17 or newer is needed to run the DLC Loader. Install it, or set JAVA_HOME to its folder.
  pause
  exit /b 1
)
set "JV=%JV: =%"
for /f "tokens=1 delims=." %%m in ("%JV%") do set "JMAJ=%%m"
if %JMAJ% LSS 17 (
  echo This Java is version %JV%. The DLC Loader needs Java 17 or newer.
  pause
  exit /b 1
)
start "" "%JAVAW%" -Xmx3g -Ddlcconv.tools="%~dp0tools" -jar "%~dp0tools\DlcLoader.jar"
