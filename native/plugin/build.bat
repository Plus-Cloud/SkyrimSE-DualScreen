@echo off
rem Builds SkyrimAYN.dll (x64, static CRT) against SKSE 2.3.1 source.
rem One-time: builds and installs ianpatt/common into ..\..\deps\extern.
setlocal
call "Z:\Tools\VS2022BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
set PATH=Z:\Android\Sdk\cmake\3.30.5\bin;%PATH%
set DEPS=%~dp0..\..\deps
cd /d "%~dp0"

if not exist "%DEPS%\extern\lib\cmake" (
  cmake -G Ninja -B "%DEPS%\common\build" -S "%DEPS%\common" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="%DEPS%\extern" || exit /b 1
  cmake --build "%DEPS%\common\build" --target install || exit /b 1
)

cmake -G Ninja -B build -S . -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="%DEPS%\extern" || exit /b 1
cmake --build build --target SkyrimAYN || exit /b 1
echo Built %~dp0build\SkyrimAYN.dll
