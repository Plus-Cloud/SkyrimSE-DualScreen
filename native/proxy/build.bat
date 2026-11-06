@echo off
rem Builds dinput8.dll (x64, static CRT): loads SkyrimAYN.dll into Skyrim when SKSE isn't installed.
setlocal
call "Z:\Tools\VS2022BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
if not exist build mkdir build
cl /nologo /O2 /MT /EHsc /LD /DUNICODE /D_UNICODE dinput8.cpp /Fobuild\ /Febuild\dinput8.dll /link /DEF:dinput8.def kernel32.lib || exit /b 1
echo Built %~dp0build\dinput8.dll
