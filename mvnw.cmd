@echo off
setlocal EnableExtensions
set "BASE_DIR=%~dp0"
set "PROPERTIES=%BASE_DIR%.mvn\wrapper\maven-wrapper.properties"
for /f "tokens=1,* delims==" %%A in ('findstr /b "distributionUrl=" "%PROPERTIES%"') do set "DIST_URL=%%B"
for /f "tokens=1,* delims==" %%A in ('findstr /b "distributionSha512Sum=" "%PROPERTIES%"') do set "EXPECTED_SHA512=%%B"
for %%F in ("%DIST_URL%") do set "DIST_NAME=%%~nxF"
set "MAVEN_VERSION=%DIST_NAME:apache-maven-=%"
set "MAVEN_VERSION=%MAVEN_VERSION:-bin.tar.gz=%"
if not defined USERPROFILE (echo USERPROFILE is not set 1>&2 & exit /b 1)
set "INSTALL_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "MAVEN=%INSTALL_DIR%\bin\mvn.cmd"
if not exist "%MAVEN%" (
  set "TMP_DIR=%TEMP%\maven-wrapper-%RANDOM%-%RANDOM%"
  mkdir "%TMP_DIR%" || exit /b 1
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $u='%DIST_URL%'; $a=Join-Path '%TMP_DIR%' '%DIST_NAME%'; Invoke-WebRequest -Uri $u -OutFile $a; $h=(Get-FileHash -Algorithm SHA512 $a).Hash.ToLowerInvariant(); if ($h -ne '%EXPECTED_SHA512%') { throw 'Maven distribution SHA-512 mismatch' }; tar -xzf $a -C '%TMP_DIR%'; if (!(Test-Path (Join-Path '%TMP_DIR%' 'apache-maven-%MAVEN_VERSION%\bin\mvn.cmd'))) { throw 'Unexpected Maven archive layout' }; New-Item -ItemType Directory -Force -Path '%INSTALL_DIR%\..' | Out-Null; if (Test-Path '%INSTALL_DIR%') { Remove-Item -Recurse -Force '%INSTALL_DIR%' }; Move-Item (Join-Path '%TMP_DIR%' 'apache-maven-%MAVEN_VERSION%') '%INSTALL_DIR%'; Remove-Item -Recurse -Force '%TMP_DIR%'"
  if errorlevel 1 exit /b 1
)
"%MAVEN%" -f "%BASE_DIR%pom.xml" %*
exit /b %ERRORLEVEL%
