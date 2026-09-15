#!/bin/sh
set -eu

CONFIG=/data/sentinel.conf
TEMPLATE=/etc/redis/sentinel.conf.template

escaped_password=$(printf '%s' "$REDIS_PASSWORD" | sed 's/\\/\\\\/g; s/"/\\"/g; s/[&|]/\\&/g')
if [ ! -f "$CONFIG" ]; then
  sed "s|@REDIS_PASSWORD@|$escaped_password|g" "$TEMPLATE" > "$CONFIG"
else
  sed -i "s|^sentinel auth-pass cachecloud-master .*|sentinel auth-pass cachecloud-master \"$escaped_password\"|" "$CONFIG"
fi

exec redis-server "$CONFIG" --sentinel
