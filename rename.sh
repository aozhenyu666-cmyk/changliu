#!/usr/bin/env bash
# 把 com.changliu 包改名为 com.changliu。只动包前缀，不碰 io.github.ccch1mneyyy.dsh_app / dsh-driver-core 插件路径。
set -euo pipefail
cd /d/code/changliu

# 1) 内容替换：所有源码/脚本/文档里的包前缀
while IFS= read -r -d '' f; do
  if grep -q 'com\.dshdriver' "$f"; then
    sed -i 's/com\.dshdriver/com.changliu/g' "$f"
  fi
done < <(find . -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.sh' -o -name '*.md' -o -name '*.properties' \) -not -path '*/build/*' -not -path '*/.gradle/*' -print0)

# 2) 移动包目录 com/dshdriver -> com/changliu
for base in core/src/main/kotlin core/src/test/kotlin app/src/main/kotlin app/src/androidTest/kotlin app/src/debug/kotlin; do
  if [ -d "$base/com/dshdriver" ]; then
    mkdir -p "$base/com/changliu"
    # 移动 dshdriver 下的所有子目录（app / core）
    for sub in "$base/com/dshdriver"/*; do
      [ -d "$sub" ] && mv "$sub" "$base/com/changliu/"
    done
    rmdir "$base/com/dshdriver"
  fi
done

# 3) 工程名与显示名
sed -i 's/rootProject.name = "dsh-driver-app"/rootProject.name = "changliu"/' settings.gradle.kts
sed -i 's/<string name="app_name">思维驾驶员<\/string>/<string name="app_name">长流<\/string>/' app/src/main/res/values/strings.xml
sed -i 's/思维驾驶员 · 拦截娱乐 App/长流 · 拦截娱乐 App/' app/src/main/res/values/strings.xml

echo "=== remaining com.changliu (should be empty) ==="
grep -rn 'com\.dshdriver' --include='*.kt' --include='*.kts' --include='*.xml' --include='*.sh' . | grep -v '/build/' || echo "(none)"
echo "=== new package dirs ==="
find . -type d -name changliu -not -path '*/build/*'
echo "DONE"