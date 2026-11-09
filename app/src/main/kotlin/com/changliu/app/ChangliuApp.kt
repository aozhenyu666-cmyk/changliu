package com.changliu.app

import android.content.Context
import com.yishou.app.YishouApp
import com.changliu.app.infra.keepalive.KeepAliveService
import com.changliu.app.infra.link.FusionLink
import com.changliu.app.infra.link.YsGateLink
import com.changliu.app.infra.link.ZkGateLink
import com.zongkong.app.Shared
import com.zongkong.app.ZkHost
import com.zongkong.app.data.Actions
import com.zongkong.app.data.ChatRepo
import com.zongkong.app.data.ProofStore
import com.zongkong.app.data.Store
import com.zongkong.app.system.CrashShield
import com.zongkong.app.system.Notifications as ZkNotifications
import com.zongkong.app.system.Reminders as ZkReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * 长流唯一 Application。
 *
 * 融合前三套入口：
 *   DriverApp   只做 Engine.init（驾驶员状态机）
 *   YishouApp   手工接 ~30 个单例（数据库、设置、题卡、语音、窗口、提醒……）
 *   ZkApp       store/actions/chat/proofs + CrashShield + 时间/通知/提醒
 *
 * 这里继承 [YishouApp]，一手代码里 150 处 `applicationContext as YishouApp` 原样可用；
 * 总控侧把 ZkApp 的成员抽成 [ZkHost]，由本类实现，总控代码无需改取值方式。
 *
 * 初始化顺序固定：一手存储/设置/业务 → 总控存储/参谋 → 驾驶员引擎 → 通知/提醒。
 * 单例失败不允许拖垮冷启动（一手/总控各自的兜底仍生效）。
 */
class ChangliuApp : YishouApp(), ZkHost {

    // ── 总控（原 ZkApp）──
    override val shared = MutableStateFlow<Shared?>(null)
    override var prompt: String = ""
    override var promptTitle: String = ""
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override var chatDraft: String = ""
    override var proofInbox: List<String> = emptyList()
    override val proofs by lazy { ProofStore(this) }
    override var veinDraft: String = ""

    override lateinit var store: Store
        private set
    override lateinit var actions: Actions
        private set
    override lateinit var chat: ChatRepo
        private set

    override fun onCreate() {
        // 一手：AppDatabase → SettingsStore → RoundEngine/CardEngine/... → 通知/窗口/提醒
        super.onCreate()
        // 统一账本先就位：后面各模块的钩子随时可写
        com.changliu.app.hub.ledger.Ledger.init(this)

        // 总控：一律北京时间；崩溃护盾先装，后面的初始化失败也能记下来
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        runCatching { CrashShield.install(this) }
        store = Store(this)
        actions = Actions(this, store)
        chat = ChatRepo(this, store, actions)
        // 通知/提醒的接线放到后台，别和首次启动抢主线程
        appScope.launch {
            runCatching { ZkNotifications.ensureChannels(this@ChangliuApp) }
            runCatching { ZkReminders.reschedule(this@ChangliuApp) }
            runCatching { ZkReminders.watchdog(this@ChangliuApp) }
        }

        // 驾驶员：状态机 / 存储 / 察觉
        Engine.init(this)

        // 三条拦截决策链挂到唯一无障碍服务上（总控优先，其次一手，最后驾驶员兜底）
        ZkGateLink.install()
        YsGateLink.install()
        FusionLink.install(this)

        // 总控界面/权限页改看唯一服务的真实连接状态
        com.zongkong.app.ui.PermissionStatus.fusionA11y = { ctx ->
            A11yGuard.isOn(ctx) && com.changliu.app.infra.a11y.ChangliuA11yService.running
        }

        // 总控原常驻服务改走唯一 KeepAliveService（实施计划 §5.4）
        com.zongkong.app.system.KeepAliveService.bridge = { ctx ->
            com.changliu.app.infra.keepalive.KeepAliveService.start(ctx)
        }

        // 总控旧健康协议会在融合后误报「无障碍被关」；启动时清掉那条旧提醒
        runCatching { com.zongkong.app.system.Notifications.cancelHq(this) }

        // 常驻服务不在这里启动：Application.onCreate 可能已经很久，前台服务 5 秒内没
        // startForeground 会直接 ANR。由壳页 onResume 再拉（见 ChangliuShellActivity）。
        appScope.launch { runCatching { A11yGuard.ensure(this@ChangliuApp) } }
    }
}

/** 取代各处的 `applicationContext as XxxApp`。 */
val Context.cl: ChangliuApp get() = applicationContext as ChangliuApp
