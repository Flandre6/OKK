// 日志 Tab（诊断 + 日志控制台）：对齐 wcx LogsPager 风格，精简重复操作

package com.OKK.yes.loader.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.OKK.yes.core.hooks.ModuleLog
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.startup.FeatureHookRegistry
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Refresh
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** ModuleLog 行格式：`级别  HH:mm:ss.SSS  消息` */
private data class LogEntry(
    val level: Char?,
    val time: String?,
    val message: String,
)

private val LOG_LINE_REGEX = Regex("^([IWEVD])  (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})  (.*)$")

private fun parseLog(text: String): List<LogEntry> {
    val out = ArrayList<LogEntry>()
    for (line in text.lineSequence()) {
        if (line.isEmpty() && out.isEmpty()) continue
        val m = LOG_LINE_REGEX.matchEntire(line)
        when {
            m != null -> {
                out.add(LogEntry(m.groupValues[1].first(), m.groupValues[2], m.groupValues[3]))
            }

            out.isNotEmpty() -> {
                val prev = out.removeAt(out.size - 1)
                out.add(prev.copy(message = prev.message + "\n" + line))
            }

            else -> out.add(LogEntry(null, null, line))
        }
    }
    return out
}

private fun levelColor(level: Char): Color = when (level) {
    'E' -> Color(0xFFD32F2F)
    'W' -> Color(0xFFF57C00)
    'I' -> Color(0xFF388E3C)
    'D' -> Color(0xFF1976D2)
    else -> Color(0xFF9E9E9E)
}

private const val LOG_COLLAPSE_LINES = 4

@Composable
fun LogsPager() {
    val context = LocalContext.current
    val act = context as? Activity
    var refreshTick by remember { mutableIntStateOf(0) }
    var logEnabled by remember {
        mutableStateOf(runCatching { ModuleLog.isEnabled() }.getOrDefault(false))
    }

    val logText by produceState(initialValue = "", key1 = logEnabled, key2 = refreshTick) {
        value = runCatching { ModuleLog.text(800) }.getOrDefault("")
    }
    val entries = remember(logText) { parseLog(logText).take(60) }

    // 诊断数据
    val wxVer = remember(refreshTick) {
        runCatching { com.OKK.yes.core.compat.WeChatVersion.resolve(context)?.summary() }.getOrNull()
    }
    val compat = remember(refreshTick) {
        runCatching {
            com.OKK.yes.core.compat.CompatReportStore.lastReport
                ?: com.OKK.yes.core.compat.CompatReportStore.loadReport()
        }.getOrNull()
    }
    val hookSum = remember(refreshTick) {
        runCatching { FeatureHookRegistry.summaryLine() }.getOrDefault("未知")
    }
    val runtimeList = remember(refreshTick) {
        runCatching { FeatureHookRegistry.runtimeSnapshot() }.getOrDefault(emptyList())
    }
    val installPartial = remember(refreshTick) {
        runCatching { FeatureHookRegistry.snapshot().filter { it.status == FeatureHookRegistry.Status.PARTIAL } }.getOrDefault(emptyList())
    }
    val failNames = remember(refreshTick) {
        runCatching { FeatureHookRegistry.failNames() }.getOrDefault(emptyList())
    }
    val issueTotal = failNames.size + installPartial.size + runtimeList.count { it.status != FeatureHookRegistry.Status.OK }

    // 一键复制诊断
    val copyDiagnostic = {
        val full = buildString {
            appendLine("=== OKK 诊断报告 ===")
            appendLine("Module: v1.2.6")
            appendLine("WeChat: $wxVer")
            appendLine("Report: ${compat?.summaryLine()}")
            appendLine("Hooks: $hookSum")
            appendLine()
            appendLine("--- 日志片段 ---")
            appendLine(ModuleLog.text(300))
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OKK-diag", full))
        Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
    }

    MiuixListScaffold(
        title = "日志",
        spacing = 10.dp,
    ) {


        // ── 适配检测 + 功能健康合并为一张卡 ──
        item(key = "diag") {
            SmallTitle(text = "适配与健康")
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(title = "当前微信", summary = wxVer ?: "未检测")
                BasicComponent(title = "探针汇总", summary = compat?.summaryLine() ?: "未生成报告")
                BasicComponent(title = "未按预期加载", summary = run {
                    val nl = failNames + installPartial.map { it.name }
                    if (nl.isEmpty()) "无" else nl.joinToString(", ")
                })
                if (runtimeList.isNotEmpty()) {
                    runtimeList.forEach { rec ->
                        val ok = rec.status == FeatureHookRegistry.Status.OK
                        BasicComponent(
                            title = "${if (ok) "✓" else "⚠"} ${rec.name}",
                            summary = if (rec.detail.isNotBlank()) rec.detail else if (ok) "已生效" else "未生效"
                        )
                    }
                }
            }
        }

        // ── 操作按钮行 ──
        item(key = "actions") {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        ModuleLog.i("用户点击：重新检查适配")
                        act?.let { com.OKK.yes.loader.CompatCheckUi.recheckAndShow(it, it.classLoader, com.OKK.yes.loader.ModulePathHolder.modulePath) }
                        refreshTick++
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("重新检查")
                }
                Button(
                    onClick = { copyDiagnostic() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制诊断")
                }
            }
        }

        // ── 日志控制台（固定区域独立滚动，最新日志自动置底） ──
        item(key = "log_title") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallTitle(text = "模块运行日志 (最新在底部)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "记录", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = logEnabled,
                        onCheckedChange = {
                            logEnabled = it
                            runCatching {
                                ModuleLog.setEnabled(it)
                                PublicConfigStore.putBoolean("module_log_enabled", it, true)
                            }
                        }
                    )
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(MaterialSymbols.Outlined.Refresh, contentDescription = "刷新", tint = MiuixTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = {
                        runCatching { ModuleLog.clear() }
                        refreshTick++
                    }) {
                        Icon(MaterialSymbols.Outlined.Delete, contentDescription = "清空", tint = MiuixTheme.colorScheme.onBackground)
                    }
                }
            }
        }

        item(key = "log_console_box") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无日志。开启右上角「记录」后操作模块，再点刷新。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    val logListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.runtime.LaunchedEffect(entries.size) {
                        if (entries.isNotEmpty()) {
                            logListState.scrollToItem(entries.size - 1)
                        }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(entries, key = { idx, _ -> idx }) { _, entry ->
                            LogEntryCard(entry)
                        }
                    }
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(ContentBottomInset)) }
    }
}

// ── 单条日志卡片（对齐 wcx RunLogCard：级别 chip + 时间 + 可折叠长消息）──

@Composable
private fun LogEntryCard(entry: LogEntry) {
    val lines = remember(entry.message) { entry.message.split("\n") }
    val isLong = lines.size > LOG_COLLAPSE_LINES
    val head = remember(lines) { lines.take(LOG_COLLAPSE_LINES).joinToString("\n") }
    val rest = remember(lines) { lines.drop(LOG_COLLAPSE_LINES).joinToString("\n") }
    var expanded by remember(entry) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "chevron",
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.level?.let { level ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(levelColor(level))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(level.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    entry.time?.let {
                        Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (isLong) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Expand_more,
                            contentDescription = if (expanded) "折叠" else "展开",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }
            }
            if (entry.message.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Column {
                        Text(
                            text = if (isLong) head else entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        if (isLong) {
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Text(
                                    text = rest,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
