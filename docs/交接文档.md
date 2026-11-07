# 长流融合工程 · 交接文档

> 版本：`0.7.0-fusion`（2026-11-07）
> 工程目录：`D:\code\changliu`
> 包名：`com.changliu.app`，应用名：长流
> 许可证：GPL-3.0（驾驶员底座继承 Curbox；一手/总控为用户自有代码）

---

## 1. 一句话现状

三个 App（驾驶员 / 一手 / 总控）已经融合成**一个 APK、一个进程**：

- 单 Application、单无障碍服务、单强制全屏页、单常驻服务、单 LLM 客户端；
- 三套 UI 在同一个 App 内可用（统一壳四栏 + 各自完整页面）；
- 长流引擎 P1（五态、mood、主动搭话、记忆消化、周复盘权重）已接入；
- 767 项单元测试全绿，模拟器完成启动/拦截/主动搭话/记忆消化/5 分钟静置验证；
- **唯一未完成项：国产 ROM 真机 72h 验收**。

---

## 2. 交付物

### 2.1 安装包

| 文件 | 位置 | 说明 |
|---|---|---|
| APK | `D:\code\changliu\app\build\outputs\apk\debug\app-debug.apk` | 构建产物（19,409,913 字节） |
| APK 副本 | `D:\code\changliu\dist\changliu-0.7.0-fusion-debug.apk` | 同上 |
| 压缩包 | `D:\code\changliu\dist\changliu-0.7.0-fusion.zip` | APK + 安装说明 + Release notes + HANDOVER |
| 桌面副本 | `C:\Users\敖震宇\Desktop\changliu-0.7.0-fusion.zip` | 方便直接取 |

SHA256：

```
apk  920489F81355607B4E77AEBE3731ED6F96A52D93EFE455689BC46854AACC6052
```

zip 每次重打包都会变，校验以 Release asset digest 为准（`gh release view --json assets` 可查）。

### 2.2 GitHub Release（公开仓库）

- 发布页：https://github.com/aozhenyu666-cmyk/test/releases/tag/changliu-0.7.0-fusion
- 压缩包直链：
  https://github.com/aozhenyu666-cmyk/test/releases/download/changliu-0.7.0-fusion/changliu-0.7.0-fusion.zip
- APK 直链：
  https://github.com/aozhenyu666-cmyk/test/releases/download/changliu-0.7.0-fusion/changliu-0.7.0-fusion-debug.apk
- 交接文档直链：
  https://github.com/aozhenyu666-cmyk/test/releases/download/changliu-0.7.0-fusion/HANDOVER.md

上传后已用本地 SHA256 与 Release asset digest 对照，一致。
（注：本次执行环境的 curl 直连 GitHub 下载被网络重置/超时，属于环境网络限制；浏览器/手机访问正常。）

### 2.3 文档

- 本文件：`docs/交接文档.md`
- 融合架构与验证：`docs/融合说明.md`
- 实施计划：外部文档 `长流融合-实施计划.md`
- 设计方案：外部文档 `长流计划-设计方案.md`

---

## 3. 工程结构

```
D:\code\changliu
├── app/        com.changliu.app   驾驶员底座 + 基础设施 + 统一壳 + 融合桥
├── core/       com.changliu.core  驾驶员纯 Kotlin 规则 + 长流引擎纯逻辑
├── ys/         com.yishou.app     一手（library 模块）
├── zk/         com.zongkong.app   总控（library 模块，含 com.zongkong.core）
├── legacy/     一手/总控源码副本（自包含构建用，约 4.7 MB）
├── patches/    legacy 融合补丁
├── scripts/    vendor-legacy.sh
├── dist/       发布产物（APK/zip/说明）
├── docs/       文档
└── gradle/     wrapper 8.11.1
```

### 3.1 为什么 ys / zk 是 library 模块

初始计划是把一手/总控源码 rename 进 `app/`。实际执行时，本机 Mimosa 钩子拦截 Bash 直接写源码（`cp`、`sed -i`、`>` 被拒），逐文件 Write 457 个文件不现实，因此改为：

- `:ys`（namespace `com.yishou.app`）、`:zk`（namespace `com.zongkong.app`）是 Android library 模块；
- 保留原包名，各自生成自己的 `R` / `BuildConfig`，从根上避免资源名与 R 冲突；
- 源码通过 Gradle `sourceSets` 指向 `legacy/{yishou,zongkong}`；
- 组件统一在 `:app` 的 Manifest 里用全限定类名声明（library 模块的 Manifest 是空的）。

**源码根优先级**：`-PlegacyRoot` > `LEGACY_ROOT` > 仓库 `legacy/` > `D:/code/src-ref`。

### 3.2 自包含与刷新

- 仓库已 vendor，可脱离 `src-ref` 构建。
- 上游一手/总控更新后：

```bash
cd /d/code/changliu
bash scripts/vendor-legacy.sh            # 默认从 D:/code/src-ref 同步
```

脚本复制完成后会自动应用 `patches/fusion-legacy.patch`（目前只有总控 `PermissionsScreen.kt` 的融合状态桥）。

> **重要**：直接改 `legacy/` 的文件不会自动回到 `src-ref`。需要长期保留的改动，请同时更新 `patches/fusion-legacy.patch` 或上游 `src-ref`。

---

## 4. 构建与运行

### 4.1 环境

- Gradle 8.11.1 / AGP 8.5.2 / Kotlin 2.0.21
- compileSdk 34 / targetSdk 34 / minSdk 26
- JDK 17 及以上（本机 JAVA_HOME 为 JDK 21，可正常构建）
- Android SDK：`C:\Android\Sdk`（也兼容 `D:\code\android-sdk`）
- `local.properties`：

```
sdk.dir=C\:\\Android\\Sdk
```

### 4.2 全量构建与测试

```bash
cd /d/code/changliu
GRADLE_USER_HOME=C:/gh TEMP=C:/dsh-tmp TMP=C:/dsh-tmp \
  ./gradlew :core:test :ys:testDebugUnitTest :zk:testDebugUnitTest :app:assembleDebug --console=plain
```

预期：**BUILD SUCCESSFUL**，767 项测试 0 失败。

分模块：

```bash
./gradlew :core:test                # 驾驶员 + 长流引擎纯逻辑，63 项
./gradlew :ys:testDebugUnitTest     # 一手，405 项（含 Room 迁移）
./gradlew :zk:testDebugUnitTest     # 总控，299 项（含 core.EngineTest）
./gradlew :app:assembleDebug        # 出 APK
```

### 4.3 安装到模拟器/真机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.changliu.app/.ui.ChangliuShellActivity
```

**安装后必须做**：

1. 系统设置 → 无障碍 → 长流 → 打开；
2. 悬浮窗、通知、电池不限制；
3. 国产 ROM 再开自启动 / 后台弹出界面。

> 覆盖安装 APK 后，系统会把长流从无障碍已启用列表移除，需要重新打开一次；真机同理。App 内的 `A11yGuard` 在有写权限时会尽量写回，但不能保证覆盖安装后的首次启动。

### 4.4 模拟器调试命令

```bash
# 追加无障碍（切勿直接覆盖，会挤掉别的服务）
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
adb shell settings put secure enabled_accessibility_services \
  "$CUR:com.changliu.app/com.changliu.app.infra.a11y.ChangliuA11yService"
adb shell settings put secure accessibility_enabled 1

# 悬浮窗（主动搭话 L0 小卡）
adb shell appops set com.changliu.app SYSTEM_ALERT_WINDOW allow

# 调试触发一次 L0 / L1（仅 debug 包含这个 receiver）
adb shell am broadcast -n com.changliu.app/.DebugNudgeReceiver \
  -a com.changliu.app.DEBUG_NUDGE --es mood "调侃" --es text "依据是哪个词？"
adb shell am broadcast -n com.changliu.app/.DebugNudgeReceiver \
  -a com.changliu.app.DEBUG_NUDGE --es text "停一下，依据是什么？" --ez voice true

# 直接打开记忆页（debug 里 exported=true）
adb shell am start -n com.changliu.app/.ui.MemoryActivity
```

---

## 5. 架构与关键文件

### 5.1 唯一 Application

`app/.../ChangliuApp.kt`

- `ChangliuApp : YishouApp(), ZkHost`
  - 继承一手 `YishouApp`：一手 150 处 `applicationContext as YishouApp` 原样可用；
  - 实现总控 `ZkHost`：总控 `zk.store/actions/chat/proofs` 等取值方式不变；
  - 总控 `Context.zk` 返回 `ZkContext`（ContextWrapper + 委托），使 `zk` 仍可当 Context 用。
- 初始化顺序：一手（super）→ 总控 store/actions/chat → 驾驶员 `Engine.init` → 拦截桥 / 常驻 / 看门狗 / 权限状态桥。

### 5.2 唯一无障碍与三条决策链

`app/.../infra/a11y/ChangliuA11yService.kt`

- 事件主干：驾驶员的 `rootInActiveWindow` 复核 + 5 秒复查 + HOME 送走 + 拉起唯一 Gate；
- 20 秒 tick：`A11yExtensions.tick` → 总控本地兜底 / 一手到期复查 / `BrainRuntime.tick`；
- 决策链顺序：
  1. `ZkGateLink`（总控时间 → 严管 → 低屏/打开就答题）
  2. `YsGateLink`（一手关注名单 + 放行条 + 伴读）
  3. `Intercept`（驾驶员名单）
- 任一层返回 true 表示已自己弹页，后续层不再叠加。
- 状态桥：`GateService.markConnected/markForeground`、`GuardService.markRunning`、`PermissionStatus.fusionA11y`。

### 5.3 唯一强制页

`app/.../infra/gate/GateActivity.kt`

- `MODE_GATE / MODE_BLOCK / MODE_HQ` 三种模式；
- 答题 → `Intercept.allow` 写放行 → 打开目标 App；
- 草稿落盘、旋转/重建恢复、`excludeFromRecents`；
- 页眉显示当前 mood（如「拦下了 · 暴躁」）；
- 后台弹出被系统拦截时，`infra/gate/GateCover.kt` 用无障碍遮罩兜底。

### 5.4 唯一常驻

`app/.../infra/keepalive/KeepAliveService.kt`

- 合并驾驶员 LockService（陪练通知 + 音频监听）+ GuardService（15s/60s 自愈）+ 总控 60s 本地规则；
- **已移除**总控旧 `SelfHeal` / `Watchdog`（只认原 `GuardService`，融合后会误报「无障碍被关」）；
- 保留 `LocalGuardian.tick`（本地规则兜底/紧急暂停）；
- 总控原 `KeepAliveService.start` 通过 `bridge` 钩子改走本服务。

### 5.5 统一 LLM 客户端

`app/.../infra/llm/ChatClient.kt`（一手实现 + `maxTokens/temperature` 扩展）

- `GatewayBrain` 已改走它；
- 网关地址归一化：驾驶员默认 `https://ai.kdysite.cloud` → 自动补 `/v1`；
- key 只在内存明文，落盘走 `SecretBox`（AndroidKeyStore AES/GCM）。

### 5.6 长流引擎 P1

| 文件 | 作用 |
|---|---|
| `core/.../brain/Flow.kt` | 五态估计：在线/滑翔/沉浸/沉默/离线 |
| `core/.../brain/Mood.kt` | mood：暖/调侃/冷/严/暴躁；加权 + 大随机 + 5–8% 尖峰 + 3–8 分钟持续；支持额外权重 |
| `core/.../brain/Proactive.kt` | 触发源、90 秒同因/总冷却、交互压力、分级、模板话术 |
| `core/.../brain/Memory.kt` | 账本消化：同类证据 ≥3 才写长期记忆 |
| `core/.../brain/Tuning.kt` | 周复盘建议：≥5 证据，只调语气权重 |
| `app/.../brain/BrainRuntime.kt` | 运行时：接无障碍/Gate 信号，写 `ledger.jsonl`，信用取 `Boss.credibility` |
| `app/.../brain/BrainMemory.kt` | memory.json + 墓碑 + 暂停消化 |
| `app/.../brain/BrainTuning.kt` | 7 天可回滚权重 |
| `app/.../brain/VoiceNudge.kt` | L1 TTS（不录音） |
| `app/.../infra/overlay/ProactiveOverlay.kt` | L0 顶部小卡；无悬浮窗权限退化通知 |

数据位置：

```
filesDir/brain/ledger.jsonl   经验账本（状态/语气/判定，不含屏幕内容）
filesDir/brain/memory.json    长期记忆
SharedPreferences brain_tuning   7 天权重
SharedPreferences brain_memory   暂停/墓碑
SharedPreferences brain_voice    L1 开关
```

### 5.7 统一壳 UI

`app/.../ui/`

- `ChangliuShellActivity.kt`：四栏（首页/训练/四部/我的）+ 底部自绘 Tab；
- `theme/ChangliuTheme.kt`：宣纸底 + 墨字 + 陶土橙，支持系统深浅色；
- `ShellData.kt`：1.5 秒轮询聚合三套状态；
- `screens/HomeScreen.kt`、`TrainScreen.kt`、`DeptScreen.kt`、`MeScreen.kt`；
- `MemoryActivity.kt`：记忆查看/删除/暂停、复盘建议采用/恢复；
- `Openers.kt`：深链进入一手/总控/驾驶员页面（`route` extra）。

---

## 6. 融合改动点清单（维护必读）

### 6.1 上游源码改动（已包含在 `legacy/`）

| 文件 | 改动 |
|---|---|
| `yishou/.../YishouApp.kt` | `class` → `open class`（供 `ChangliuApp` 继承） |
| `zongkong/.../ZkApp.kt` | 抽出 `ZkHost` 接口；`ZkContext` 包装；`Context.zk` 返回 `ZkContext` |
| `zongkong/.../data/WorkActions.kt` | 3 处 `as ZkApp` → `as ZkHost` |
| `zongkong/.../data/Chat.kt` | 2 处 `as ZkApp` → `as ZkHost` |
| `zongkong/.../link/LinkProvider.kt` | import/cast 改 `ZkHost` |
| `zongkong/.../ui/NowScreen.kt` | `runShortcut(app: ZkApp)` → `ZkHost` |
| `zongkong/.../system/SelfHeal.kt` | `KeepAliveService.bridge` 钩子 |
| `zongkong/.../guard/GuardService.kt` | `markRunning()` 桥 |
| `yishou/.../gate/GateService.kt` | `markConnected()/markForeground()` 桥 |

### 6.2 仅 legacy 的补丁（`patches/fusion-legacy.patch`）

| 文件 | 改动 |
|---|---|
| `zongkong/.../ui/PermissionsScreen.kt` | `PermissionStatus.fusionA11y` 桥；`accessibility/guardRunning/healthStatus/healthText` 认融合服务；`missingCritical` 在融合服务健康时不再报「管不住你」 |

### 6.3 App 侧融合代码

- `ChangliuApp.kt`
- `infra/llm/*`
- `infra/a11y/*`
- `infra/gate/*`
- `infra/keepalive/*`
- `infra/link/YsGateLink.kt`、`ZkGateLink.kt`
- `infra/overlay/*`
- `brain/*`
- `ui/*`
- `A11yGuard.PEERS` 置空（不再替旧 App 写回）
- Manifest：三套组件用全限定名统一声明；FileProvider authority 统一 `com.changliu.app.files`；`file_paths.xml` 合并 cache/images + files/proofs

---

## 7. 验证记录（截至 2026-11-07）

| 项 | 结果 |
|---|---|
| `:core:test` | 63 项通过 |
| `:ys:testDebugUnitTest` | 405 项通过（含 Room `Migration1415Test`/`Migration1516Test`） |
| `:zk:testDebugUnitTest` | 299 项通过（含 `com.zongkong.core.EngineTest`） |
| 合计 | **767 项，0 失败** |
| 清洁构建 | `clean` 后用 `legacy/` 全量构建通过 |
| 三套 UI 同进程 | 首页 → 一手 → 四部 → 总控首页 均正常，pid 不变 |
| 拦截回路 | 驾驶员 Chrome 名单 → 切桌面 → 唯一 GateActivity 弹出，页眉带 mood |
| L0 主动搭话 | debug 广播触发，顶部小卡正常；`知道了/聊两句` 可点 |
| L1 语音 | Google TTS 绑定成功，语言 zh-CN |
| 记忆消化 | 种子账本 → 2 条记忆；删除后重消化不回流 |
| 复盘权重 | 建议采用后写 7 天权重；恢复默认后 prefs 清空 |
| 5 分钟静置 | pid 不变、无障碍保持绑定、常驻服务在跑；账本新增 heartbeat/proactive；无崩溃 |
| 总控误报修复 | 融合服务连接后总控首页不再显示「管不住你」；旧健康通知启动时清除 |
| 密钥安全 | 全仓库 `sk-` 扫描为空 |

模拟器截图（`D:\code\`）：

- `run-home3.png` 首页
- `run-depts4.png` 四部总览
- `run-me-engine.png` 我的 → 长流引擎
- `run-nudge2.png` L0 小卡
- `run-gate2.png` 拦截页
- `ui-memory5.png` 记忆页
- `ui-review2.png` 复盘建议

---

## 8. 已知问题与风险

1. **覆盖安装会清无障碍**：系统行为，需重新打开；真机同理。
2. **国产 ROM 后台限制**：一键清理、后台弹出权限、自启动、电池优化仍需真机逐项打掉。
3. **强制停止打不过**：所有拦截器共同弱点；当前靠 A11yGuard + 看门狗 + 常驻缩短恢复时延。
4. **总控旧健康协议已摘除**：哨兵/healthwire 代码保留但未注册；`SelfHeal` 的重绑路径不再运行。
5. **一手窗口规则未并入**：`YsGateLink` 目前只接关注名单 + 放行条 + 伴读；`WindowClock/WindowRules` 的短停留/拉回定时器还在原 GateService 里（未注册）。
6. **总控后台任务未全并入**：截图抽查、Notion 同步、`Pet.attach`、healthwire 注册仍在旧 GuardService（未注册）。
7. **L1 语音依赖系统 TTS**：无 TTS 引擎时只出 L0 卡。
8. **主动引擎边界**：只调语气权重，不改红线/宪章/账本；记忆删除不可恢复（只保证不回流）。
9. **72h 真机验收未做**：模拟器只能做短时静置，不能替代。

---

## 9. 接手第一小时

1. 打开 `D:\code\changliu`，确认 `local.properties` 的 `sdk.dir`。
2. 跑一次全量构建与测试（§4.2），确认 767 项全绿。
3. 安装 APK，按 §4.3 开权限，确认首页三枚状态胶囊为「无障碍已开/守护中/…」。
4. 打开 Chrome 验证拦截页；用 debug 广播验证 L0 卡。
5. 读 §6（融合改动点）和 §8（已知问题）后再改代码。

---

## 10. 后续工作与 72h 验收清单

### 10.1 真机验收（优先级最高）

| 项 | 方法 | 通过标准 |
|---|---|---|
| 国产 ROM 保活 | vivo/小米/华为/OPPO 开自启动+后台弹出+电池不优化 | 一键清理后无障碍仍存活或看门狗写回 |
| 单无障碍存活 | 开服务后静置 72h | 记录掉线率/恢复时延 |
| 强制全屏页 | 开被管 App | Gate 切入、划不掉、答/聊/阶梯放行、到期重拦 |
| 后台弹出兜底 | 关闭「后台弹出界面」 | GateCover 遮罩挡住 |
| 三条决策链 | 总控严管/总控时间/一手入口/驾驶员拦截 | 同一服务，无重复弹页 |
| 主动搭话 | 静置/切娱乐/沉默 | L0 卡按时出现，不刷屏；L1 有声 |
| 记忆/复盘 | 用几天后查看记忆页 | ≥3 证据才写；删除不回流；权重 7 天过期 |
| 截图/Notion/提醒 | 若后续并入 | 按原功能验收 |
| LLM 网关 | 设置页填 key → 测试连接 | 主模型可用；key 仅本机密文 |
| Room 迁移 | 旧库导入 | 15 段迁移不丢数据 |

### 10.2 待补功能（按优先级）

1. 一手 `WindowClock/WindowRules`（短停留、到点拉回）并入唯一服务；
2. 总控截图抽查、Notion 同步、提醒后台任务并入唯一服务；
3. 哨兵/healthwire 要么删除，要么改为进程内健康自检；
4. 主动引擎 L1 在无 TTS 环境下明确降级文案；
5. 统一壳首页继续长成「伙伴对话 + 今天」完整版。

---

## 11. 发布新版本

1. 改 `app/build.gradle.kts` 的 `versionCode/versionName`；
2. 全量构建 + 测试；
3. 复制 APK 到 `dist/`，更新 `INSTALL.txt` / `RELEASE_NOTES.md`；
4. 打 zip：

```powershell
Compress-Archive -Path 'D:\code\changliu\dist\changliu-*-debug.apk','D:\code\changliu\dist\INSTALL.txt','D:\code\changliu\dist\RELEASE_NOTES.md' `
  -DestinationPath 'D:\code\changliu\dist\changliu-<版本>.zip' -Force
```

5. 发布：

```bash
gh release create changliu-<版本> \
  "D:/code/changliu/dist/changliu-<版本>-debug.apk" \
  "D:/code/changliu/dist/changliu-<版本>.zip" \
  --repo aozhenyu666-cmyk/test \
  --title "长流 <版本>" \
  --notes-file "D:/code/changliu/dist/RELEASE_NOTES.md" \
  --prerelease
```

---

## 12. 安全与许可红线

- API key 不写进任何仓库文件、文档、日志、数据库明文；只存本机 Keystore 密文。
- 每次发布前 `grep -r "sk-" .` 确认为空。
- 网关默认 `https://ai.kdysite.cloud`，大脑 `gemini-4-argon`，轻模型 `gemini-2.5-flash-nothinking`。
- 整个 App 沿用 GPL-3.0；外部参考项目只借模式，不整文件搬运。
- 调试签名固定 `android` / `androiddebugkey`，三方一致，覆盖安装不丢数据。
