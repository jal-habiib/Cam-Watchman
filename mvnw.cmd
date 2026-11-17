@echo off
rem Maven shim for this machine: there is no `mvn` on PATH, but the Maven wrapper cache
rem already holds distributions. Prefer a 3.9.x, then any found, then PATH.
setlocal enabledelayedexpansion
set "MVN="
for /f "delims=" %%i in ('where /r "%USERPROFILE%\.m2\wrapper\dists" mvn.cmd 2^>nul') do (
  set "CAND=%%i"
  if not defined MVN set "MVN=!CAND!"
  echo !CAND! | findstr /c:"maven-3.9" >nul && set "MVN=!CAND!"
)
if defined MVN goto run
for /f "delims=" %%i in ('where mvn.cmd 2^>nul') do (
  set "MVN=%%i"
  goto run
)
echo [mvnw] No Maven found. Install Maven or seed %%USERPROFILE%%\.m2\wrapper\dists. 1>&2
exit /b 1

:run
rem The DSH sandbox forbids writes outside the workspace, so Maven must not use
rem %USERPROFILE%\.m2\repository. Keep a workspace-local repository instead; it is seeded
rem from the user's cache by tools\seed-repo.cmd the first time.
if not exist "%~dp0.m2repo" mkdir "%~dp0.m2repo"
set "MAVEN_ARGS=-Dmaven.repo.local=%~dp0.m2repo"
call "%MVN%" %*
exit /b %errorlevel%