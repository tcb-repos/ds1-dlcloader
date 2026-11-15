@echo off
rem Builds the DLC loader as an x86 dinput8.dll proxy.
rem Usage: build.bat            - development build (out\dinput8.dll; TestInstall defaults: profile redirect, file logging)
rem        build.bat install    - development build, also copied into ..\TestInstall
rem        build.bat release    - release build (out\release\dinput8.dll; normal save folders, no file logging, no PDB)
setlocal
cd /d "%~dp0"
set MINHOOK=third_party\MinHook
set "VCVARS="
for %%e in (Community Professional Enterprise BuildTools) do if exist "C:\Program Files\Microsoft Visual Studio\2022\%%e\VC\Auxiliary\Build\vcvars32.bat" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\%%e\VC\Auxiliary\Build\vcvars32.bat"
if not defined VCVARS (echo vcvars32.bat not found - install the MSVC x86 build tools & exit /b 1)
call "%VCVARS%" >nul || exit /b 1
set SRC=dlcloader.cpp "%MINHOOK%\buffer.c" "%MINHOOK%\hde32.c" "%MINHOOK%\hook.c" "%MINHOOK%\trampoline.c"
if /i "%~1"=="release" goto release

if not exist out mkdir out
cl /nologo /LD /MT /O2 /Zi /W3 /EHsc /std:c++17 /D_CRT_SECURE_NO_WARNINGS /I "%MINHOOK%" ^
   /Fo"out\\" /Fd"out\\" %SRC% ^
   /link /DEF:dinput8.def /OUT:out\dinput8.dll /PDB:out\dinput8.pdb /IMPLIB:out\dinput8.lib
if errorlevel 1 (echo Build failed & exit /b 1)
echo Built out\dinput8.dll
if /i "%~1"=="install" copy /y out\dinput8.dll "..\TestInstall\dinput8.dll" >nul && echo Installed to TestInstall
exit /b 0

:release
if not exist out\release mkdir out\release
cl /nologo /LD /MT /O2 /GL /W3 /EHsc /std:c++17 /D_CRT_SECURE_NO_WARNINGS /DDLCLOADER_RELEASE /I "%MINHOOK%" ^
   /Fo"out\\release\\" %SRC% ^
   /link /LTCG /DEF:dinput8.def /OUT:out\release\dinput8.dll /IMPLIB:out\release\dinput8.lib /DEBUG:NONE
if errorlevel 1 (echo Build failed & exit /b 1)
echo Built out\release\dinput8.dll
