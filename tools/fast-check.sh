#!/usr/bin/env bash
# Fast local verification script for ARM64 PRoot / dev environments.
# Avoids multiple cold-starts, skips heavy spotless scans, and guarantees no zombie Gradle Daemons.
set -eo pipefail

cd "$(dirname "$0")/.."

# 1. 彻底清理可能残留的挂起/busy daemon，防止内存与进程锁死
./gradlew --stop >/dev/null 2>&1 || true

# 2. 若支持 taskset 大核绑定则使用（匹配 workers.max=4）
TASKSET_PREFIX=""
if command -v taskset >/dev/null 2>&1; then
    TASKSET_PREFIX="taskset -c 4-7"
fi

# 3. 单次 Gradle 调用合并执行编译与指定单测（~15-35s，严禁使用 --daemon）
EXTRA_TASKS="$@"
if [ -z "$EXTRA_TASKS" ]; then
    echo "==> [Fast Check] Running :app:compileGithubDebugKotlin..."
    $TASKSET_PREFIX ./gradlew :app:compileGithubDebugKotlin --no-daemon --console=plain
else
    echo "==> [Fast Check] Running compilation + specified tests: $EXTRA_TASKS"
    $TASKSET_PREFIX ./gradlew :app:compileGithubDebugKotlin $EXTRA_TASKS --no-daemon --console=plain
fi
