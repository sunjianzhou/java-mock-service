#!/bin/bash
# ============================================================
# Mock Service 启动脚本
# 统一Mock服务 — 身份认证三方接口模拟工具
# ============================================================

JAR_DIR="$(cd "$(dirname "$0")" && pwd)/mock-boot/target"
JAR_FILE="$JAR_DIR/mock-boot-1.0.0.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] JAR 文件不存在: $JAR_FILE"
    echo "请先执行: mvn clean package -DskipTests"
    exit 1
fi

PORT=${1:-8080}
JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"

echo "============================================================"
echo " Mock Service 启动中..."
echo " JAR: $JAR_FILE"
echo " 端口: $PORT"
echo " 管理端点: http://localhost:$PORT/mock/_admin/routes"
echo "============================================================"

exec $JAVA_CMD -jar "$JAR_FILE" --server.port="$PORT"
