#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
键值分析「扫描成功 + 结果页风险提示」复现脚本。

默认环境：application-test.yml（profile=test）
  MySQL: 由 CC_MYSQL_* 环境变量提供
  辅助 Redis: 由 CC_ASSIST_* 环境变量提供
  推荐业务集群: appId=5 集群模式（139.196.144.53:7001-7006，密码 Redis@1392026）

用法：
  # 1) 先启动监听，再去页面发起键值分析（推荐）
  python3 scripts/repro-key-analysis-risk.py --watch --app-id 5

  # 2) 已知 auditId，立即污染类型统计 key
  python3 scripts/repro-key-analysis-risk.py --app-id 5 --audit-id 28

  # 3) 污染全部统计维度（全空图表场景）
  python3 scripts/repro-key-analysis-risk.py --app-id 5 --audit-id 28 --all-dims
"""
from __future__ import print_function

import argparse
import json
import subprocess
import os
import sys
import time

try:
    import pymysql
except ImportError:
    print("请先安装 pymysql: pip3 install pymysql", file=sys.stderr)
    sys.exit(1)

# 连接信息从环境变量读取，不在源码中硬编码
MYSQL = dict(
    host=os.environ.get("CC_MYSQL_HOST", "127.0.0.1"),
    user=os.environ.get("CC_MYSQL_USER", "root"),
    password=os.environ.get("CC_MYSQL_PASSWORD", ""),
    database=os.environ.get("CC_MYSQL_DB", "redis_manager"),
    port=int(os.environ.get("CC_MYSQL_PORT", "3306")),
)
ASSIST = dict(
    host=os.environ.get("CC_ASSIST_HOST", "127.0.0.1"),
    port=int(os.environ.get("CC_ASSIST_PORT", "6379")),
    password=os.environ.get("CC_ASSIST_PASSWORD", ""),
)

DEFAULT_APP_ID = 5
STAT_SUFFIXES = ("type", "ttl", "idle", "valueSize", "typeMemory", "top")
KEY_ANALYSIS_AUDIT_TYPE = 6


def redis_cli(*args):
    cmd = ["redis-cli", "-h", ASSIST["host"], "-p", str(ASSIST["port"]), "-a", ASSIST["password"], "-n", "0"]
    cmd.extend(args)
    return subprocess.check_output(cmd, stderr=subprocess.STDOUT).decode("utf-8", "ignore").strip()


def stat_key(app_id, audit_id, suffix):
    if suffix == "valueSize":
        return "cc:key:valueSize:%s:%s" % (app_id, audit_id)
    if suffix == "typeMemory":
        return "cc:key:typeMemory:%s:%s" % (app_id, audit_id)
    if suffix == "top":
        return "cc:key:top:%s:%s" % (app_id, audit_id)
    return "cc:key:%s:%s:%s" % (suffix, app_id, audit_id)


def inject_wrongtype(app_id, audit_id, all_dims=False):
    suffixes = STAT_SUFFIXES if all_dims else ("type",)
    injected = []
    for suffix in suffixes:
        key = stat_key(app_id, audit_id, suffix)
        redis_cli("SET", key, "repro-blocked")
        injected.append(key)
    return injected


def print_app_info(app_id):
    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    cur.execute("SELECT app_id, name, status, type, custom_password FROM app_desc WHERE app_id=%s", (app_id,))
    row = cur.fetchone()
    if not row:
        print("appId=%s 不存在" % app_id)
        conn.close()
        return False
    print("业务集群: appId=%s  name=%s  status=%s  type=%s" % row[:4])
    if row[4]:
        print("  业务 Redis 密码(custom_password): %s" % row[4])
    cur.execute("SELECT ip, port, status FROM instance_info WHERE app_id=%s ORDER BY port", (app_id,))
    inst = cur.fetchall()
    for ip, port, st in inst:
        print("  实例: %s:%s  status=%s" % (ip, port, st))
    cur.execute("SELECT password FROM external_redis WHERE app_id=%s", (app_id,))
    ext = cur.fetchone()
    if ext and ext[0]:
        print("  external_redis.password: %s" % ext[0])
    conn.close()
    print("")
    print("辅助 Redis: %s:%s  password=%s" % (ASSIST["host"], ASSIST["port"], ASSIST["password"]))
    print("页面入口: http://127.0.0.1:8080/manage/app/userDetail?appId=%s&tabTag=app_key" % app_id)
    print("  或前台: http://127.0.0.1:8080/admin/app/key?appId=%s" % app_id)
    return True


def fetch_latest_audit(app_id):
    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    cur.execute(
        "SELECT id, status, param3, create_time FROM app_audit WHERE app_id=%s AND type=%s ORDER BY id DESC LIMIT 1",
        (app_id, KEY_ANALYSIS_AUDIT_TYPE),
    )
    row = cur.fetchone()
    conn.close()
    return row


def wait_new_parent_task(app_id, since_task_id):
    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    while True:
        cur.execute(
            "SELECT id, status, param FROM task_queue WHERE app_id=%s AND class_name='AppKeyAnalysisTask' AND id>%s ORDER BY id DESC LIMIT 1",
            (app_id, since_task_id),
        )
        row = cur.fetchone()
        conn.close()
        if row:
            return row
        time.sleep(0.5)
        conn = pymysql.connect(**MYSQL)
        cur = conn.cursor()


def parse_audit_id(param_json):
    try:
        data = json.loads(param_json)
        return int(data.get("auditId", 0))
    except Exception:
        return 0


def wait_child_running(app_id, audit_id, timeout_sec=300):
    deadline = time.time() + timeout_sec
    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    while time.time() < deadline:
        cur.execute(
            "SELECT id, status FROM task_queue WHERE app_id=%s AND class_name='RedisServerKeyCombinedAnalysisTask' "
            "AND param LIKE %s ORDER BY id DESC LIMIT 1",
            (app_id, '%%"auditId":%s%%' % audit_id),
        )
        row = cur.fetchone()
        if row and row[1] == 1:
            conn.close()
            return row[0]
        time.sleep(0.3)
    conn.close()
    return None


def poll_inject(app_id, audit_id, all_dims, duration_sec=180):
    print("开始轮询污染 assist Redis（最多 %ss），auditId=%s ..." % (duration_sec, audit_id))
    deadline = time.time() + duration_sec
    count = 0
    while time.time() < deadline:
        try:
            injected = inject_wrongtype(app_id, audit_id, all_dims=all_dims)
            count += 1
            if count == 1 or count % 20 == 0:
                print("  已 SET: %s" % injected[0])
        except subprocess.CalledProcessError as e:
            print("redis-cli 失败:", e.output if hasattr(e, "output") else e)
            return False
        time.sleep(0.25)
    print("轮询结束（共尝试 %s 次）" % count)
    return True


def show_result(app_id, audit_id):
    print("")
    print("=== 查看结果 ===")
    print("结果页: http://127.0.0.1:8080/admin/app/keyAnalysisResult?appId=%s&auditId=%s&shell=manage" % (app_id, audit_id))
    try:
        risks = redis_cli("LRANGE", "cc:key:risk:%s:%s" % (app_id, audit_id), "0", "-1")
        print("辅助 Redis 风险列表 cc:key:risk:%s:%s =>" % (app_id, audit_id))
        print(risks or "(空，任务可能尚未结束)")
    except Exception as e:
        print("读取风险 key 失败:", e)
    audit = fetch_latest_audit(app_id)
    if audit and audit[0] == audit_id:
        print("app_audit.param3 => %s" % (audit[2] or "(空)"))


def main():
    parser = argparse.ArgumentParser(description="复现键值分析「成功 + 风险提示」")
    parser.add_argument("--app-id", type=int, default=DEFAULT_APP_ID)
    parser.add_argument("--audit-id", type=int, default=0)
    parser.add_argument("--watch", action="store_true", help="监听新任务，请在脚本运行后于页面发起分析")
    parser.add_argument("--all-dims", action="store_true", help="污染全部统计 key（图表全空）")
    args = parser.parse_args()

    print("=== 键值分析风险复现 ===")
    if not print_app_info(args.app_id):
        sys.exit(1)

    if args.watch:
        conn = pymysql.connect(**MYSQL)
        cur = conn.cursor()
        cur.execute("SELECT IFNULL(MAX(id),0) FROM task_queue")
        since_id = cur.fetchone()[0]
        conn.close()
        print("")
        print("【下一步】请在浏览器对 appId=%s 发起「键值分析」，脚本正在监听新任务..." % args.app_id)
        print("（监听起点 task_queue.id > %s）" % since_id)
        task_id, status, param = wait_new_parent_task(args.app_id, since_id)
        audit_id = parse_audit_id(param)
        print("捕获父任务 taskId=%s auditId=%s status=%s" % (task_id, audit_id, status))
        if audit_id <= 0:
            print("无法解析 auditId，退出")
            sys.exit(1)
        child_id = wait_child_running(args.app_id, audit_id)
        if child_id:
            print("子任务 combinedKeyAnalysis 运行中 taskId=%s，开始污染..." % child_id)
        else:
            print("未检测到运行中子任务，仍尝试污染（可能已接近结束）...")
        poll_inject(args.app_id, audit_id, args.all_dims)
        show_result(args.app_id, audit_id)
        return

    if args.audit_id <= 0:
        audit = fetch_latest_audit(args.app_id)
        if not audit:
            print("未找到键值分析记录，请传 --audit-id 或使用 --watch")
            sys.exit(1)
        args.audit_id = audit[0]
        print("使用最新 auditId=%s (status=%s, created=%s)" % (audit[0], audit[1], audit[3]))

    injected = inject_wrongtype(args.app_id, args.audit_id, all_dims=args.all_dims)
    print("已污染 key:")
    for k in injected:
        print(" ", k, "=>", redis_cli("TYPE", k))
    show_result(args.app_id, args.audit_id)


if __name__ == "__main__":
    main()
