package com.changliu.app.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.ui.screens.DataScreen
import com.changliu.app.ui.screens.PlanningScreen
import com.changliu.app.ui.screens.HomeScreen
import com.changliu.app.ui.screens.MeScreen
import com.changliu.app.ui.screens.TrainScreen
import com.changliu.app.ui.theme.ChangliuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 长流统一壳（阶段四第一版）。
 *
 * 四栏：首页（今天 + 陪练 + 三套状态）/ 训练（一手）/ 四部（总控）/ 我的（状态与权限）。
 * 壳只做聚合展示和入口，业务仍由各模块自己的完整页面承担。
 */
class ChangliuShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 深色模式下状态栏/导航栏图标要转为浅色（旧主题里写死 windowLightStatusBar=true）
        val darkDetector: (android.content.res.Resources) -> Boolean = { res ->
            (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                darkDetector,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                darkDetector,
            ),
        )
        setContent {
            ChangliuTheme {
                ChangliuShell()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Application 初始化完、界面已经起来后再拉前台服务，避免 5 秒 startForeground 超时 ANR
        runCatching { com.changliu.app.infra.keepalive.KeepAliveService.sync(this) }
        runCatching { com.changliu.app.WatchdogJob.schedule(this) }
    }
}

private val TAB_LABELS = listOf("首页", "训练", "规划", "数据", "我的")

@Composable
private fun ChangliuShell() {
    val ctx = LocalContext.current
    val snap by produceState(initialValue = ShellSnap(), ctx) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching { ShellData.read(ctx) }.getOrDefault(value)
            }
            delay(1_500)
        }
    }
    var tab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            ShellTabBar(selected = tab, onSelect = { tab = it })
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> HomeScreen(
                    snap = snap,
                    onOpenDriver = { Openers.driver(ctx) },
                    onGoTrain = { tab = 1 },
                    onGoDept = { tab = 2 },
                    onOpenZkChat = { ctx.startActivity(android.content.Intent(ctx, ConductorActivity::class.java)) },                )
                1 -> TrainScreen(snap)
                2 -> PlanningScreen()
                3 -> DataScreen()
                else -> MeScreen(snap)
            }
        }
    }
}

@Composable
private fun ShellTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(58.dp),
            ) {
                TAB_LABELS.forEachIndexed { i, label ->
                    val active = selected == i
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .width(18.dp)
                                .height(2.dp)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(50),
                                ),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
