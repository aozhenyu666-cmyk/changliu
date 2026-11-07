#!/usr/bin/env bash
# 把一手 / 总控源码从 src-ref（或指定目录）vendor 进本仓库的 legacy/，让构建不再依赖外部路径。
#
# 用法：
#   bash scripts/vendor-legacy.sh                 # 默认从 D:/code/src-ref 复制
#   bash scripts/vendor-legacy.sh /path/to/src    # 指定源
#   LEGACY_SRC=/path/to/src bash scripts/vendor-legacy.sh
#
# 复制完成后，:ys / :zk 会自动优先使用 <repo>/legacy（见各模块 build.gradle.kts）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-${LEGACY_SRC:-D:/code/src-ref}}"
DEST="$ROOT/legacy"

# 只允许写仓库内的 legacy/，且源目录必须存在
case "$DEST" in
  "$ROOT"/legacy) ;;
  *) echo "拒绝写入 $DEST（只允许 <repo>/legacy）" >&2; exit 1 ;;
esac
[ -d "$SRC" ] || { echo "源目录不存在：$SRC" >&2; exit 1; }

for p in yishou zongkong; do
  if [ ! -d "$SRC/$p/app/src/main/java" ]; then
    echo "找不到 $SRC/$p/app/src/main/java" >&2
    exit 1
  fi
  rm -rf "$DEST/$p"
  mkdir -p "$DEST/$p/app/src/main"
  cp -a "$SRC/$p/app/src/main/java" "$DEST/$p/app/src/main/"
  [ -d "$SRC/$p/app/src/main/res" ] && cp -a "$SRC/$p/app/src/main/res" "$DEST/$p/app/src/main/"
  if [ -d "$SRC/$p/app/src/test" ]; then
    mkdir -p "$DEST/$p/app/src"
    cp -a "$SRC/$p/app/src/test" "$DEST/$p/app/src/"
  fi
done

# 总控的单元测试与模拟 Notion 共用 sharedTest
if [ -d "$SRC/zongkong/app/src/sharedTest" ]; then
  cp -a "$SRC/zongkong/app/src/sharedTest" "$DEST/zongkong/app/src/"
fi

# 融合补丁：legacy 里对上游文件做的必要改动（例如总控权限状态桥）
if [ -s "$ROOT/patches/fusion-legacy.patch" ]; then
  (cd "$ROOT" && patch -p1 --forward --silent < patches/fusion-legacy.patch) || true
fi

echo "已 vendor 到 $DEST"
echo "现在可以直接：./gradlew :core:test :ys:testDebugUnitTest :zk:testDebugUnitTest :app:assembleDebug"
