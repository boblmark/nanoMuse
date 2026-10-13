"""nanoMuse 任务执行器（MVP-3）"""
import sqlite3
import json
from pathlib import Path
from datetime import datetime

DB_PATH = Path("/home/hatch/.nanomuse/tasks.db")

def init_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("""CREATE TABLE IF NOT EXISTS tasks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        status TEXT DEFAULT 'pending',
        created_at TEXT,
        started_at TEXT,
        completed_at TEXT,
        retry_count INTEGER DEFAULT 0,
        max_retries INTEGER DEFAULT 3,
        result TEXT,
        error TEXT
    )""")
    conn.commit()
    conn.close()

def submit_task(name, max_retries=3):
    init_db()
    conn = sqlite3.connect(str(DB_PATH))
    cur = conn.execute(
        "INSERT INTO tasks (name, max_retries, created_at) VALUES (?, ?, ?)",
        (name, max_retries, datetime.now().isoformat())
    )
    tid = cur.lastrowid
    conn.commit()
    conn.close()
    return tid

def get_pending():
    init_db()
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    q = "SELECT * FROM tasks WHERE status = 'pending' AND retry_count < max_retries ORDER BY id"
    rows = conn.execute(q).fetchall()
    conn.close()
    return [dict(r) for r in rows]

def mark_running(tid):
    init_db()
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("UPDATE tasks SET status = 'running', started_at = ? WHERE id = ?",
                 (datetime.now().isoformat(), tid))
    conn.commit()
    conn.close()

def mark_completed(tid, result):
    init_db()
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("UPDATE tasks SET status = 'completed', completed_at = ?, result = ? WHERE id = ?",
                 (datetime.now().isoformat(), json.dumps(result), tid))
    conn.commit()
    conn.close()

def mark_failed(tid, error):
    init_db()
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("UPDATE tasks SET status = 'pending', retry_count = retry_count + 1, error = ? WHERE id = ?",
                 (str(error), tid))
    conn.commit()
    conn.close()

def get_stats():
    init_db()
    conn = sqlite3.connect(str(DB_PATH))
    stats = {}
    for s in ('pending', 'running', 'completed'):
        cur = conn.execute("SELECT COUNT(*) FROM tasks WHERE status = ?", (s,))
        stats[s] = cur.fetchone()[0]
    cur = conn.execute("SELECT COUNT(*) FROM tasks WHERE status = 'pending' AND retry_count >= max_retries")
    stats['failed'] = cur.fetchone()[0]
    conn.close()
    return stats
