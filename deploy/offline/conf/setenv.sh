#!/bin/sh
# 由 install.sh 从模板生成，手工改动会在下次安装时被覆盖（会先备份 .bak.<时间戳>）。
# 本文件含数据库口令，权限 0750。

CC_HOME="@CC_HOME@"

JAVA_OPTS="$JAVA_OPTS -server"
JAVA_OPTS="$JAVA_OPTS -Xms@JAVA_XMS@ -Xmx@JAVA_XMX@"
# Tomcat 展开 WAR 与 JSP 编译都在这里，别用 /tmp（有些系统会定期清理）
JAVA_OPTS="$JAVA_OPTS -Djava.io.tmpdir=${CC_HOME}/tmp"
# 容器/虚机上 /dev/random 熵不足会让启动卡在 SecureRandom 初始化
JAVA_OPTS="$JAVA_OPTS -Djava.security.egd=file:/dev/./urandom"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=@TIMEZONE@ -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${CC_HOME}/logs"

# --- 应用配置：后端读的是环境变量，见 application*.yml ---
export SPRING_PROFILES_ACTIVE="@PROFILE@"
export SERVER_DOMAIN="@SERVER_DOMAIN@"

export CACHECLOUD_PRIMARY_URL="@JDBC_URL@"
export CACHECLOUD_PRIMARY_USER="@MYSQL_USER@"
export CACHECLOUD_PRIMARY_PASSWORD="@MYSQL_PASSWORD@"

export CACHECLOUD_REDIS_MAIN_HOST="@REDIS_HOST@"
export CACHECLOUD_REDIS_MAIN_PORT="@REDIS_PORT@"
export CACHECLOUD_REDIS_MAIN_PASSWORD="@REDIS_PASSWORD@"
export CACHECLOUD_REDIS_SENTINEL_MASTER="@REDIS_SENTINEL_MASTER@"
export CACHECLOUD_REDIS_SENTINEL_NODES="@REDIS_SENTINEL_NODES@"
export CACHECLOUD_REDIS_SENTINEL_PASSWORD="@REDIS_SENTINEL_PASSWORD@"

export CACHECLOUD_AI_ENABLED="false"

# 同源部署（nginx 同时托管 SPA 和反代）不需要跨域白名单。
# 确需跨域再打开，且不能填 *（后端开了 allowCredentials）。
# export CACHECLOUD_CORS_ALLOWED_ORIGINS="http://192.168.1.10:8080"

export JAVA_OPTS
