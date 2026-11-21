@echo off
chcp 65001 >nul
cd /d %~dp0
if not exist logs mkdir logs

rem 若存在 .env，先加载其中的 KEY=VALUE 配置（跳过 # 注释行）
if exist ".env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do set "%%A=%%B"
)

set PROXY_HOST=127.0.0.1
set PROXY_PORT=7897
if not "%CODEMEM_PROXY_HOST%"=="" set PROXY_HOST=%CODEMEM_PROXY_HOST%
if not "%CODEMEM_PROXY_PORT%"=="" set PROXY_PORT=%CODEMEM_PROXY_PORT%

echo 正在经代理 %PROXY_HOST%:%PROXY_PORT% 启动码忆后端...
start "codemem" java ^
  -Dhttp.proxyHost=%PROXY_HOST% -Dhttp.proxyPort=%PROXY_PORT% ^
  -Dhttps.proxyHost=%PROXY_HOST% -Dhttps.proxyPort=%PROXY_PORT% ^
  -Dhttp.nonProxyHosts="localhost^|127.0.0.1" ^
  -jar target\codemem-0.1.0-SNAPSHOT.jar

echo 启动窗口已打开，稍等几秒访问 http://127.0.0.1:8080/
pause