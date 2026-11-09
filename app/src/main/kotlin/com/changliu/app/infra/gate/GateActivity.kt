package com.changliu.app.infra.gate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.changliu.app.Brains
import com.changliu.app.Engine
import com.changliu.app.Intercept
import com.changliu.app.Palette
import com.changliu.app.Store
import com.changliu.app.Ui
import com.changliu.app.UsageMonitor
import com.changliu.app.brain.BrainRuntime
import com.changliu.app.hub.ledger.Ledger
import com.changliu.app.hub.policy.CompanionDebt
import com.changliu.app.hub.policy.QuestionBank
import com.changliu.app.ui.ChangliuShellActivity
import com.yishou.app.YishouApp
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardMode
import com.yishou.app.scroll.ScrollActivity
import com.zongkong.app.ZkHost
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.changliu.core.Cog
import com.changliu.core.CogState
import com.changliu.core.ComposeRequest
import com.changliu.core.Gate
import com.changliu.core.Similar
import com.changliu.core.hub.GateLevel
import com.changliu.core.hub.GateMode
import com.changliu.core.hub.GatePlan

/**
 * 长流唯一强制全屏页：无障碍服务拦下 App 后拉起这里。
 *
 * 流程：答对一道有依据的题 → 给这个包写一段放行时间 → 打开那个 App；放弃 → 回桌面。
 * 出题优先用议程 / 上次结论 / 主线目标兜底，配对了大脑就请它出。
 * 本页 `excludeFromRecents`，所以在最近任务里划不掉——这是和"用外部拦截器"最大的区别。
 *
 * 三个 mode 共用一个页面（实施计划 §5.3）：
 *   MODE_GATE  一手入口 / 驾驶员拦截：答题换放行
 *   MODE_BLOCK 总控严管：直接弹回，只说明原因
 *   MODE_HQ    总控时间：说明现在不开放
 * 现阶段三者行为一致（答题放行）；ys/zk 业务搬入后各自接管文案与判据。
 */
class GateActivity : Activity() {
    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_ROUNDS = "rounds"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_CREDIT = "credit"
        private const val EXTRA_COMPANION = "companion"
        private const val EXTRA_REASON = "reason"
        private const val STATE_PASSED = "passed"
        private const val STATE_DRAFT = "draft"
        private const val STATE_Q_TEXT = "q_text"
        private const val STATE_Q_ANCHOR = "q_anchor"
        private const val STATE_Q_MISSING = "q_missing"
        private const val STATE_Q_AGENDA = "q_agenda"
        private const val STATE_Q_SOURCE = "q_source"
        private const val STATE_DONE = "done"

        const val MODE_GATE = "gate"
        const val MODE_BLOCK = "block"
        const val MODE_HQ = "hq"

        /** 草稿超过这个时间就不再接回来：半小时前没答完的门，重新开一道。 */
        private const val DRAFT_TTL_MS = 30 * 60_000L

        /** 拦截页在前台时无障碍不要再拦（否则自己弹自己）。 */
        @Volatile var visible = false

        /** 最近一次真正拉起拦截页的时间，给 UsageStats 兜底和重复事件去重用。 */
        @Volatile var lastShownAt = 0L

        /**
         * 不要加 FLAG_ACTIVITY_CLEAR_TASK：它会清掉已有任务，把正在答的题和输入一起丢掉。
         * singleTask + 独立 taskAffinity 已经能防止叠出好几层；同包再次被拦只会走 onNewIntent。
         */
        fun intent(context: Context, pkg: String, mode: String = MODE_GATE): Intent =
            Intent(context, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_MODE, mode)

        /** 统一编排器算好的关卡：几轮、几分钟、要不要挂伴读，一页说清。 */
        fun intent(context: Context, pkg: String, plan: GatePlan): Intent =
            intent(context, pkg, if (plan.gate == GateMode.HQ) MODE_HQ else if (plan.locked) MODE_BLOCK else MODE_GATE)
                .putExtra(EXTRA_LEVEL, plan.level.n)
                .putExtra(EXTRA_ROUNDS, plan.rounds)
                .putExtra(EXTRA_MINUTES, plan.minutes)
                .putExtra(EXTRA_COMPANION, plan.companion)
                .putExtra(EXTRA_REASON, plan.reason)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var questionView: TextView
    private lateinit var input: EditText
    private lateinit var feedback: TextView
    private lateinit var submit: Button

    private var question: Gate.Question? = null
    private var target = ""
    private var mode = MODE_GATE
    private var done = false
    private var draftSaver: Runnable? = null
    /** 关掉/换题时自增，挡住已经排进 worker 的旧草稿写入，避免"答完又被写回来"。 */
    @Volatile private var draftToken = 0
    /** 换包时自增，挡住上一个包迟到的出题结果。 */
    @Volatile private var loadToken = 0
    /** 这一题是什么时候真正显示的：给五态估计算响应延迟。 */
    private var questionAt = 0L
    /** 这一题来自一手题库哪张卡（答完回写 CardAttempt）。 */
    private var bankCardId = 0L
    /** 统一编排器给的关卡（没给就是老的单题门）。 */
    private var level = 1
    private var needRounds = 1
    private var passedRounds = 0
    private var minutesOverride = 0
    private var companion = false
    private var planReason = ""

    private val blockedMode: Boolean get() = mode == MODE_BLOCK || mode == MODE_HQ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_PKG).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_GATE
        readPlan(intent)
        if (target.isBlank()) { finish(); return }
        // 已经答完/放弃的门被系统重建时，不要再把旧题显示一遍
        if (savedInstanceState?.getBoolean(STATE_DONE) == true) { finish(); return }
        passedRounds = savedInstanceState?.getInt(STATE_PASSED, 0) ?: 0
        UsageMonitor.clearBlocked(this)
        Ledger.emit("driver", "gate_shown", pkg = target, ref = mode)
        visible = true
        lastShownAt = System.currentTimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vibrate()
        render()
        if (blockedMode) return
        val restored = restore(savedInstanceState)
        if (restored != null) showQuestion(restored.first, restored.second) else loadQuestion()
    }

    /**
     * 同一个包再次被拦时只把页面提到前台，保留原题和已输入的内容；
     * 换了一个包才重新出题。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        val incoming = intent.getStringExtra(EXTRA_PKG).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE) ?: mode
        if (incoming.isBlank() || incoming == target) return
        target = incoming
        draftToken++
        loadToken++
        question = null
        done = false
        lastShownAt = System.currentTimeMillis()
        UsageMonitor.clearBlocked(this)
        render()
        loadQuestion()
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        persistDraft()
        super.onPause()
    }

    override fun onDestroy() {
        visible = false
        draftSaver?.let(main::removeCallbacks)
        if (!done) persistDraft()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_PKG, target)
        outState.putString(EXTRA_MODE, mode)
        outState.putBoolean(STATE_DONE, done)
        outState.putInt(STATE_PASSED, passedRounds)
        outState.putString(STATE_DRAFT, input.text?.toString().orEmpty())
        question?.let { q ->
            outState.putString(STATE_Q_TEXT, q.text)
            outState.putString(STATE_Q_ANCHOR, q.anchor)
            outState.putString(STATE_Q_MISSING, q.missing)
            outState.putString(STATE_Q_AGENDA, q.agendaId)
            outState.putString(STATE_Q_SOURCE, q.source)
        }
    }

    /** 编排器给的关卡信息；老调用方没有这些 extra，就退化成"答一道就放行"。 */
    private fun readPlan(intent: Intent?) {
        if (intent == null) return
        level = intent.getIntExtra(EXTRA_LEVEL, 1).coerceIn(1, 4)
        needRounds = intent.getIntExtra(EXTRA_ROUNDS, 1).coerceIn(1, 5)
        minutesOverride = intent.getIntExtra(EXTRA_MINUTES, 0).coerceAtLeast(0)
        companion = intent.getBooleanExtra(EXTRA_COMPANION, false)
        planReason = intent.getStringExtra(EXTRA_REASON).orEmpty()
    }

    private fun restore(state: Bundle?): Pair<Gate.Question, String>? {
        if (state != null) {
            val text = state.getString(STATE_Q_TEXT)?.takeIf { it.isNotBlank() }
            if (text != null && state.getString(EXTRA_PKG) == target) {
                return Gate.Question(
                    text,
                    state.getString(STATE_Q_ANCHOR),
                    state.getString(STATE_Q_MISSING),
                    state.getString(STATE_Q_AGENDA),
                    state.getString(STATE_Q_SOURCE, "agenda"),
                ) to state.getString(STATE_DRAFT).orEmpty()
            }
        }
        val d = Engine.store.gateDraft() ?: return null
        val now = System.currentTimeMillis()
        if (d.target != target || now - d.at > DRAFT_TTL_MS) {
            runCatching { Engine.store.clearGateDraft() }
            return null
        }
        return Gate.Question(d.text, d.anchor, d.missing, d.agendaId, d.source) to d.draft
    }

    private fun showQuestion(q: Gate.Question, draft: String) {
        question = q
        questionAt = System.currentTimeMillis()
        questionView.text = q.text
        input.setText(draft)
        input.setSelection(input.text?.length ?: 0)
        submit.isEnabled = true
        submit.alpha = 1f
        persistDraft()
    }

    /** 把当前题和草稿落盘，进程被系统杀掉后还能接回来。写盘放到 worker，别卡主线程。 */
    private fun persistDraft() {
        val q = question ?: return
        if (done) return
        val row = Store.GateDraft(
            target = target,
            text = q.text,
            anchor = q.anchor,
            missing = q.missing,
            agendaId = q.agendaId,
            source = q.source,
            draft = input.text?.toString().orEmpty(),
            at = System.currentTimeMillis(),
        )
        val token = draftToken
        Engine.background { if (token == draftToken) runCatching { Engine.store.saveGateDraft(row) } }
    }

    /** 关闭这道门：先让旧草稿写入失效，再删盘上的草稿。 */
    private fun closeDraft() {
        draftToken++
        runCatching { Engine.store.clearGateDraft() }
    }

    private fun scheduleDraftSave() {
        val q = question ?: return
        if (done) return
        draftSaver?.let(main::removeCallbacks)
        val r = Runnable { persistDraft() }
        draftSaver = r
        main.postDelayed(r, 600)
    }

    private fun abandon() {
        done = true
        closeDraft()
        finish()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        // 返回键等同于"不打开了"：清掉草稿，别让下一次开门接着旧题
        abandon()
    }

    private fun render() {
        if (blockedMode) {
            renderBlocked()
            return
        }
        val p = Palette.dark
        ui = Ui(this, p)
        window.statusBarColor = p.bg

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(22), ui.px(88), ui.px(22), ui.px(28))
            setBackgroundColor(p.bg)
        }
        ui.reset(page)

        ui.kicker(if (mode == MODE_GATE) "拦下了 · ${BrainRuntime.moodLabel()}" else "现在不行")
        ui.headline(headline(), 24f)
        ui.lead(lead())

        ui.card(muted = true) {
            ui.kicker("这一问")
            questionView = ui.text("正在出题…", 17f, p.text, top = 2f, bottom = 0f)
        }

        ui.spacer(14f)
        input = ui.field("写下你的依据", multiline = true, lines = 3, noPaste = true)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = scheduleDraftSave()
        })
        feedback = ui.text("", 13f, p.warn, top = 6f, bottom = 0f)
        submit = ui.primary("提交") { check() }.also {
            it.isEnabled = false
            it.alpha = 0.45f
        }
        ui.quiet("不打开了") { abandon() }
        // 证据核验关：说不出来就别硬想，直接进语音陪练把大卡点拆成一步
        if (level >= 3) ui.quiet("去语音陪练拆一步") { partner() }

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }

    private fun headline(): String = when (mode) {
        MODE_BLOCK -> "严管中，先回桌面"
        MODE_HQ -> "总控时间里，不开放"
        else -> "要打开${label(target)}，先答一道题"
    }

    /** 关卡不同，要求不同：这一行是唯一告诉用户"现在到哪一档"的地方。 */
    private fun lead(): String {
        val levelLabel = GateLevel.entries.firstOrNull { it.n == level }?.label
        val base = when (level) {
            2 -> "这一关要连过 $needRounds 轮，答对才有 ${minutesForPass()} 分钟。"
            3 -> "先说明你现在手上的进展和依据；说不出来就去语音陪练拆一步。"
            else -> "说依据就行：因为什么、题干哪一句、什么规律。"
        }
        return if (levelLabel == null) base else "$base（$levelLabel）"
    }

    private fun minutesForPass(): Int {
        val setting = Intercept.minutes(this)
        return if (minutesOverride > 0) minOf(minutesOverride, setting) else setting
    }

    private fun renderBlocked() {
        val p = Palette.dark
        ui = Ui(this, p)
        window.statusBarColor = p.bg
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(22), ui.px(88), ui.px(22), ui.px(28))
            setBackgroundColor(p.bg)
        }
        ui.reset(page)
        ui.kicker(if (mode == MODE_HQ) "总控时间" else "直接拦")
        ui.headline(if (mode == MODE_HQ) "现在不开放" else "这个 App 现在被统一拦住", 24f)
        ui.lead(
            if (planReason.isNotBlank()) planReason
            else if (mode == MODE_HQ) "总控时间进行中：回总控，把这一段做完。"
            else "统一约束里这个 App 设为「直接拦」。需要改规则，去「我的 → 约束」。",
        )
        ui.card(muted = true) {
            ui.kicker("当前")
            ui.text(label(target), 17f, p.text, top = 2f, bottom = 0f)
        }
        ui.spacer(14f)
        ui.primary("回桌面") { finish() }
        ui.quiet("去长流") {
            runCatching { startActivity(Intent(this, ChangliuShellActivity::class.java)) }
            finish()
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }

    /** 今日主线断点：总控当前行动/事项的断点优先。 */
    private fun mainlineQuestion(): String? = runCatching {
        val zk = applicationContext as? ZkHost ?: return null
        val now = System.currentTimeMillis()
        val cur = Focus.current(zk.store.work.value, now, zk.store.zone, zk.store.mainKey(now))
        cur.action?.get(F.BREAK)?.takeIf { it.isNotBlank() }
            ?: cur.thread?.get(F.NEXT)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 素材池里最近的一段：拿它问论点。 */
    private fun materialQuestion(): String? = runCatching {
        val y = applicationContext as? YishouApp ?: return null
        if (!y.ready) return null
        val m = runBlocking { y.database.think().recentMaterials(20) }.randomOrNull() ?: return null
        "「${m.title.take(20)}」这段材料的论点是什么？用一句话说，并指一句原文。"
    }.getOrNull()

    /** 拦截时答的题库题也算进训练：回写 CardAttempt。 */
    private fun writeBankAttempt(pass: Boolean, durationMs: Long) {
        val cardId = bankCardId
        if (cardId <= 0) return
        val y = applicationContext as? YishouApp ?: return
        Engine.background {
            runCatching {
                runBlocking {
                    y.database.cards().insertAttempt(
                        CardAttempt(
                            cardId = cardId,
                            skill = "",
                            difficulty = 0,
                            mode = CardMode.GATE,
                            level = 1,
                            bet = 0,
                            conf = "中",
                            evidenceOk = pass,
                            checks = 0,
                            checksOk = 0,
                            final = if (pass) 1 else 0,
                            firstCorrect = pass,
                            finalCorrect = pass,
                            help = 0,
                            cause = "",
                            durationMs = durationMs.coerceAtLeast(0),
                            verified = false,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    private fun loadQuestion() {
        val token = ++loadToken
        Thread {
            val store = Engine.store
            val agenda = store.agenda()
            val last = store.last()
            val profile = store.profile()
            val recent = store.recentGateQuestions()
            val fallback = Gate.pick(agenda, last?.conclusion, last?.task, profile, seed = recent.size)
            var q = fallback
            bankCardId = 0L
            val mainline = mainlineQuestion()
            val bank = if (mainline == null) runCatching { QuestionBank.pick(this) }.getOrNull() else null
            val material = if (mainline == null && bank == null) materialQuestion() else null
            when {
                mainline != null -> q = Gate.Question(mainline.take(80), null, null, null, "mainline")
                bank != null -> {
                    bankCardId = bank.cardId
                    q = Gate.Question(bank.text.take(80), null, null, null, "bank")
                }
                material != null -> q = Gate.Question(material.take(80), null, null, null, "material")
                else -> Brains.best(store)?.let { brain ->
                val req = ComposeRequest(
                    intent = "gate", cog = CogState(Cog.ACTIVE_FLOW, "gate"), die = null,
                    topic = last?.topic.orEmpty(), task = last?.task.orEmpty(), minute = 1, minutes = 1,
                    breakpoint = fallback.anchor, lastQuestion = fallback.text, missing = fallback.missing,
                    recentReplies = emptyList(), asked = recent.takeLast(3),
                    goals = profile.goals.take(3), habits = profile.habits.take(4), patterns = store.history().patterns(),
                    unresolved = agenda.open().take(3).map { it.text },
                )
                val d = runCatching { brain.compose(req) }.getOrNull()
                if (Gate.acceptsLlm(d) && Similar.maxSimilarity(d!!.text, recent) < 0.6) {
                    q = Gate.Question(d.text.take(80), d.anchor ?: fallback.anchor, d.missing ?: fallback.missing, fallback.agendaId, "llm")
                }
            }
            }
            runCatching { store.pushGateQuestion(q.text) }
            main.post {
                // 已经换包/恢复到一道题/已经答完时，别让迟到的模型结果把题换掉
                if (token != loadToken || isFinishing || question != null || done) return@post
                showQuestion(q, "")
                gateLog("question", mapOf("source" to q.source, "text" to q.text, "pkg" to target, "mode" to mode))
            }
        }.start()
    }

    private fun check() {
        val q = question ?: return
        if (done) return
        val answer = input.text?.toString().orEmpty()
        val v = Gate.judge(answer, q, Engine.store.blocking().minChars)
        gateLog("answer", mapOf("judged" to v.pass, "why" to v.why, "chars" to v.features.chars, "pkg" to target))
        val nowTs = System.currentTimeMillis()
        val delayMs = if (questionAt > 0) nowTs - questionAt else 0L
        BrainRuntime.onAnswer(this, v.pass, delayMs, nowTs)
        writeBankAttempt(v.pass, delayMs)
        Ledger.emit(
            "driver",
            "gate_answer",
            pkg = target,
            num = if (v.pass) 1L else 0L,
            payload = JSONObject()
                .put("ms", if (questionAt > 0) nowTs - questionAt else 0L)
                .put("why", v.why)
                .toString(),
        )
        if (!v.pass) {
            feedback.text = v.why
            persistDraft()
            return
        }
        // 连过几轮才放行：这一轮过了还不能开门，换题接着来
        passedRounds++
        if (passedRounds < needRounds) {
            feedback.text = "这轮过了，还差 ${needRounds - passedRounds} 轮。"
            input.setText("")
            question = null
            persistDraft()
            loadQuestion()
            return
        }
        val minutes = minutesForPass()
        val refusal = Intercept.allow(this, target, minutes)
        if (refusal != null) {
            feedback.text = refusal
            persistDraft()
            gateLog("refused", mapOf("why" to refusal, "pkg" to target))
            return
        }
        done = true
        draftSaver?.let(main::removeCallbacks)
        closeDraft()
        submit.isEnabled = false
        submit.alpha = 0.45f
        q.agendaId?.let { id ->
            runCatching {
                val agenda = Engine.store.agenda()
                agenda.done(id, System.currentTimeMillis())
                Engine.store.saveAgenda(agenda)
            }
        }
        gateLog("pass", mapOf("minutes" to minutes, "pkg" to target, "mode" to mode, "level" to level))
        // 只放行不管 = 等于没拦：二级/三级放行顺手挂上刷屏伴读（要不要看屏由系统弹窗决定）
        // 先立字据再拉页：用户拒了看屏，这笔欠账下次放行扣回来
        if (companion) CompanionDebt.mark(this)
        if (companion && runCatching { startActivity(ScrollActivity.startIntent(this, target)) }.isSuccess) {
            finish()
            return
        }
        launchTarget()
        finish()
    }

    private fun launchTarget() {
        val i = packageManager.getLaunchIntentForPackage(target) ?: return
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 一手的陪练窗口：把卡点拆成能迈出去的最小一步，聊完再回来开这个 App。 */
    private fun partner() {
        val opened = runCatching {
            startActivity(
                Intent(this, com.yishou.app.window.WindowActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("startNow", true),
            )
        }.isSuccess
        if (!opened) feedback.text = "陪练窗口打不开：检查一手的语音权限。"
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("它")

    private fun vibrate() {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun gateLog(kind: String, data: Map<String, Any?>) {
        runCatching { Engine.store.appender("gate")(mapOf("at" to System.currentTimeMillis(), "kind" to kind) + data) }
    }
}
