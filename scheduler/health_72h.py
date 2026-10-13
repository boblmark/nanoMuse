#!/usr/bin/env python3
"""MVP-5: 72h 连续运行健康监控
每5分钟由cron调用，检查：
1. nanomuse 服务是否运行
2. 任务执行器是否正常
3. vault 是否可读
结果写入 /home/hatch/.nanomuse/health_72h.log
故障时写入告警标记
"""
import sys
sys.path.insert(0, "/opt/nanomuse-user/scheduler")
from pathlib import Path
from datetime import datetime
import subprocess
import sqlite3

LOG = Path("/home/hatch/.nanomuse/health_72h.log")
ALERT = Path("/home/hatch/.nanomuse/health_alert.flag")
START_FILE = Path("/home/hatch/.nanomuse/mvp5_start.txt")

def log(msg, level="INFO"):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(LOG, "a") as f:
        f.write(f"[{ts}] {level}: {msg}\n")

def check_service():
    r = subprocess.run(["systemctl", "is-active", "nanomuse"],
                       capture_output=True, text=True)
    return r.stdout.strip() == "active"

def check_task_executor():
    try:
        from task_executor import get_stats
        s = get_stats()
        return True
    except Exception as e:
        return False

def check_vault():
    try:
        sys.path.insert(0, "/opt/nanomuse-user")
        from nanomuse.vault.vault import CredentialVault
        v = CredentialVault(Path("/home/hatch/.nanomuse/vault.json"))
        v.get("PIPELINE_TEST_KEY")
        return True
    except Exception:
        return False

def main():
    # 记录启动时间
    if not START_FILE.exists():
        START_FILE.write_text(datetime.now().isoformat())
        log("MVP-5 72h 监控启动")

    checks = {
        "nanomuse_service": check_service(),
        "task_executor": check_task_executor(),
        "vault": check_vault(),
    }

    failed = [k for k, v in checks.items() if not v]
    if failed:
        log(f"故障: {failed}", "ERROR")
        ALERT.write_text(datetime.now().isoformat() + ": " + ",".join(failed))
    else:
        log(f"健康检查通过: {list(checks.keys())}")
        if ALERT.exists():
            ALERT.unlink()

if __name__ == "__main__":
    main()
