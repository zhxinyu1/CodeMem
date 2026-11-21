#!/usr/bin/env bash
# 启动「码忆 CodeMem」后端。
# 默认走本机 Clash 代理（127.0.0.1:7897）访问 embedding 服务；
# 可用环境变量覆盖：CODEMEM_PROXY_HOST / CODEMEM_PROXY_PORT；
# 完全不用代理时设 CODEMEM_NO_PROXY=1。
set -e
cd "$(dirname "$0")"

# 若存在 .env，先加载（KEY=VALUE 格式），用于固定对话/向量服务配置
if [ -f ".env" ]; then
  set -a
  . ./.env
  set +a
fi

PROXY_HOST="${CODEMEM_PROXY_HOST:-127.0.0.1}"
PROXY_PORT="${CODEMEM_PROXY_PORT:-7897}"
LOG="logs/run-$(date +%Y%m%d-%H%M%S).log"
mkdir -p logs

if [ "$CODEMEM_NO_PROXY" = "1" ]; then
  echo "不使用代理启动，日志：$LOG"
  nohup java -jar target/codemem-0.1.0-SNAPSHOT.jar > "$LOG" 2>&1 &
else
  echo "经代理 ${PROXY_HOST}:${PROXY_PORT} 启动，日志：$LOG"
  nohup java \
    -Dhttp.proxyHost="$PROXY_HOST" -Dhttp.proxyPort="$PROXY_PORT" \
    -Dhttps.proxyHost="$PROXY_HOST" -Dhttps.proxyPort="$PROXY_PORT" \
    -Dhttp.nonProxyHosts="localhost|127.0.0.1" \
    -jar target/codemem-0.1.0-SNAPSHOT.jar > "$LOG" 2>&1 &
fi

for i in $(seq 1 45); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8080/actuator/health)" = "200" ]; then
    echo "后端已就绪：http://127.0.0.1:8080/"
    exit 0
  fi
  sleep 2
done

echo "启动超时，请查看日志：$LOG"
exit 1