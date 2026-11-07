package com.zongkong.app

import android.app.Application
import com.zongkong.app.data.Actions
import com.zongkong.app.data.ProofStore
import com.zongkong.app.data.Store
import com.zongkong.app.system.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** 从别的 App 分享进来的内容：文字 / 链接 / 截图（已复制到本机的路径）。 */
data class Shared(val text: String, val image: String = "", val at: Long = System.currentTimeMillis())

/**
 * 总控对"应用对象"的全部依赖（融合后由 :app 的 ChangliuApp 实现）。
 *
 * 融合前这里是一个 Application 子类；融合后全局只能有一个 Application，
 * 所以把总控需要的成员抽成接口，ZkApp 保留作参考实现，真正挂载的是 ChangliuApp。
 */
interface ZkHost {
    val shared: MutableStateFlow<Shared?>

    var prompt: String
    var promptTitle: String

    /** 应用级协程：离开页面也要做完的小事（比如通知一手开局）。 */
    val appScope: CoroutineScope

    /** 别的页面要发给参谋的一句话（快捷按钮、"问参谋"），对话页打开时发出。 */
    var chatDraft: String

    /** 分享进来、要当作交付证据的照片（本机路径）：交付页打开时收走。 */
    var proofInbox: List<String>

    /** 交付记录（本机）。 */
    val proofs: ProofStore

    /** 别的页面带进"脉络 → 录入"的材料。 */
    var veinDraft: String

    val store: Store
    val actions: Actions

    /** 参谋（AI 对话）。 */
    val chat: com.zongkong.app.data.ChatRepo
}

/** 总控独立运行时的实现；融合包里不再挂到 manifest，保留以便对照与单测。 */
class ZkApp : Application(), ZkHost {

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
    override lateinit var chat: com.zongkong.app.data.ChatRepo
        private set

    override fun onCreate() {
        super.onCreate()
        // 一律北京时间：时间显示、换日、定时都按它
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        com.zongkong.app.system.CrashShield.install(this)
        store = Store(this)
        actions = Actions(this, store)
        chat = com.zongkong.app.data.ChatRepo(this, store, actions)
        Notifications.ensureChannels(this)
        com.zongkong.app.system.Reminders.reschedule(this)
        com.zongkong.app.system.Reminders.watchdog(this)
    }
}

/**
 * 既实现 [ZkHost]、又是 [android.content.Context] 的薄包装。
 * 总控不少页面会把 `zk` 直接当 Context 用（读资源、起 Activity），
 * 融合后 Application 不由总控提供，用委托包装保持原调用点不变。
 */
class ZkContext internal constructor(host: ZkHost, base: android.content.Context) :
    android.content.ContextWrapper(base), ZkHost by host

val android.content.Context.zk: ZkContext
    get() = ZkContext(applicationContext as ZkHost, applicationContext)

/** 广播接收器里拿应用对象。 */
fun zkApp(context: android.content.Context): ZkContext = context.zk
