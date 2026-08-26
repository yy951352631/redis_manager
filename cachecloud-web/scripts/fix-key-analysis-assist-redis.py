#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清理键值分析在辅助 Redis 上的脏 key（WRONGTYPE），并可选清除 app_audit.param3 风险文案。

用法（在能连上辅助 Redis 的机器上执行，本地需先开 SSH 隧道时见 application-test.yml 注释）：
  python3 scripts/fix-key-analysis-assist-redis.py --app-id 3 --audit-id 2
  python3 scripts/fix-key-analysis-assist-redis.py --app-id 3 --audit-id 2 --clear-param3

连接信息全部通过环境变量传入，不在源码中硬编码：
  CC_ASSIST_HOST / CC_ASSIST_PORT / CC_ASSIST_PASSWORD
  CC_MYSQL_HOST / CC_MYSQL_PORT / CC_MYSQL_USER / CC_MYSQL_PASSWORD / CC_MYSQL_DB

示例：
  export CC_MYSQL_HOST=10.0.0.1 CC_MYSQL_PASSWORD='***'
  export CC_ASSIST_PASSWORD='***'
  python3 scripts/fix-key-analysis-assist-redis.py --app-id 3 --audit-id 2
"""
from __future__ import print_function

import argparse
import os
import subprocess
import sys

try:
    import pymysql
except ImportError:
    pymysql = None

# 连接信息一律从环境变量读取：源码里写死凭据会随仓库一起外泄
ASSIST = dict(
    host=os.environ.get("CC_ASSIST_HOST", "127.0.0.1"),
    port=int(os.environ.get("CC_ASSIST_PORT", "6379")),
    password=os.environ.get("CC_ASSIST_PASSWORD", ""),
)
MYSQL = dict(
    host=os.environ.get("CC_MYSQL_HOST", "127.0.0.1"),
    port=int(os.environ.get("CC_MYSQL_PORT", "3306")),
    user=os.environ.get("CC_MYSQL_USER", "root"),
    password=os.environ.get("CC_MYSQL_PASSWORD", ""),
    database=os.environ.get("CC_MYSQL_DB", "redis_manager"),
)

STAT_KEYS = (
    "cc:key:type:{app_id}:{audit_id}",
    "cc:key:ttl:{app_id}:{audit_id}",
    "cc:key:idle:{app_id}:{audit_id}",
    "cc:key:valueSize:{app_id}:{audit_id}",
    "cc:key:typeMemory:{app_id}:{audit_id}",
    "cc:key:top:{app_id}:{audit_id}",
    "cc:key:risk:{app_id}:{audit_id}",
)


def redis_cli(*args):
    cmd = [
        "redis-cli",
        "-h", ASSIST["host"],
        "-p", str(ASSIST["port"]),
        "-a", ASSIST["password"],
        "--no-auth-warning",
    ]
    cmd.extend(args)
    return subprocess.check_output(cmd, stderr=subprocess.STDOUT).decode("utf-8", "ignore").strip()


def cleanup_keys(app_id, audit_id):
    cleaned = []
    try:
        if audit_id > 0:
            targets = [pattern.format(app_id=app_id, audit_id=audit_id) for pattern in STAT_KEYS]
        else:
            raw = redis_cli("KEYS", "cc:key:*:%s:*" % app_id)
            targets = [line.strip() for line in raw.splitlines() if line.strip()]
        for key in targets:
            key_type = redis_cli("TYPE", key)
            if key_type == "none":
                continue
            redis_cli("DEL", key)
            cleaned.append("%s (was %s)" % (key, key_type))
    except subprocess.CalledProcessError as e:
        print("清理失败: %s" % getattr(e, "output", e), file=sys.stderr)
        return False
    return cleaned


def clear_param3(app_id, audit_id):
    if pymysql is None:
        print("未安装 pymysql，跳过 param3 清理", file=sys.stderr)
        return
    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    cur.execute(
        "UPDATE app_audit SET param3=NULL, modify_time=NOW() WHERE app_id=%s AND id=%s",
        (app_id, audit_id),
    )
    conn.commit()
    conn.close()
    print("已清除 app_audit(id=%s).param3" % audit_id)


def main():
    parser = argparse.ArgumentParser(description="清理键值分析 assist Redis 脏 key")
    parser.add_argument("--app-id", type=int, required=True)
    parser.add_argument("--audit-id", type=int, default=0, help="0 表示清理该 app 下全部 cc:key:*:appId:*")
    parser.add_argument("--clear-param3", action="store_true", help="同时清除 MySQL 风险文案")
    args = parser.parse_args()

    try:
        ping = redis_cli("PING")
        print("辅助 Redis PING =>", ping)
    except Exception as e:
        print("无法连接辅助 Redis %s:%s" % (ASSIST["host"], ASSIST["port"]), file=sys.stderr)
        print("本地请先执行: ssh -N -L 6379:127.0.0.1:6379 root@<你的服务器>", file=sys.stderr)
        print("或在 <你的服务器> 上直接运行 redis-cli", file=sys.stderr)
        sys.exit(1)

    cleaned = cleanup_keys(args.app_id, args.audit_id)
    if not cleaned:
        print("无需清理（key 不存在或已为空）")
    else:
        print("已删除:")
        for line in cleaned:
            print(" ", line)

    if args.clear_param3:
        clear_param3(args.app_id, args.audit_id)

    print("")
    print("下一步：在页面重新发起一次键值分析（appId=%s）" % args.app_id)


if __name__ == "__main__":
    main()
