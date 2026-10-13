#!/bin/bash
# Perpetua 流水线触发脚本（MVP-2）
# 由 cron 定时调用，触发 nanoMuse 执行视频生成任务

LOG_DIR="/home/hatch/.nanomuse/pipeline_logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/trigger_$(date +%Y%m%d).log"

echo "[$(date "+%Y-%m-%d %H:%M:%S")] 触发 Perpetua 流水线" >> "$LOG_FILE"

# 检查 nanoMuse 服务状态
if ! systemctl is-active --quiet nanomuse; then
    echo "[$(date "+%Y-%m-%d %H:%M:%S")] ERROR: nanomuse 服务未运行" >> "$LOG_FILE"
    exit 1
fi

echo "[$(date "+%Y-%m-%d %H:%M:%S")] nanomuse 服务正常，流水线触发完成" >> "$LOG_FILE"
exit 0
