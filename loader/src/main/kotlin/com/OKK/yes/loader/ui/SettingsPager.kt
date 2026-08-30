// 设置 Tab：对齐 wcx SettingsPager — 外观 / 调试 / 配置文件备份与恢复 / 模块信息 / 重置

package com.OKK.yes.loader.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.OKK.yes.core.hooks.ModuleLog
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.hooks.ThemeWallpaperConfig
import com.OKK.yes.core.hooks.ThemeWallpaperController
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.File_upload
import com.composables.icons.materialsymbols.outlined.File_download
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val VERSION = "1.2.6"
private const val KEY_NIGHT = "night_mode"
private const val KEY_NIGHT_FOLLOW = "night_mode_follow"

@Composable
fun SettingsPager(onDarkChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    // ── 外观配色 ──
    var follow by remember {
        mutableStateOf(runCatching { PublicConfigStore.getBoolean(KEY_NIGHT_FOLLOW, true) }.getOrDefault(true))
    }
    var forceNight by remember {
        mutableStateOf(runCatching { PublicConfigStore.getBoolean(KEY_NIGHT, false) }.getOrDefault(false))
    }
    var logEnabled by remember {
        mutableStateOf(runCatching { ModuleLog.isEnabled() }.getOrDefault(false))
    }

    // 导出/导入 JSON 配置逻辑
    val exportConfig = {
        val json = runCatching { PublicConfigStore.exportJson() }.getOrDefault("{}")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("OKK-config", json))
        Toast.makeText(context, "模块配置已导出至剪贴板", Toast.LENGTH_SHORT).show()
    }

    val importConfig = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.startsWith("{") && text.endsWith("}")) {
            val ok = runCatching { PublicConfigStore.importJson(text) }.getOrDefault(false)
            if (ok) {
                Toast.makeText(context, "配置导入成功，重启微信后生效", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "配置格式解析失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "剪贴板无有效的 JSON 配置文件", Toast.LENGTH_SHORT).show()
        }
    }

    MiuixListScaffold(title = "设置") {
        // ── 界面（对齐 wcx "界面" 分区）──
        item(key = "ui_title") {
            MiuixSmallTitle(text = "界面与外观", modifier = Modifier.padding(top = 12.dp))
        }
        item(key = "ui_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "跟随微信深色",
                    summary = if (follow) "已跟随微信系统设置" else "关闭后可手动切换模块配色",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.Brightness_medium) },
                    checked = follow,
                    onCheckedChange = { v ->
                        follow = v
                        runCatching { PublicConfigStore.putBoolean(KEY_NIGHT_FOLLOW, v, true) }
                        if (v) onDarkChange(false)
                    }
                )
                if (!follow) {
                    SwitchPreference(
                        title = "夜间模式",
                        summary = "手动切换 OKK 界面深浅配色",
                        checked = forceNight,
                        onCheckedChange = { v ->
                            forceNight = v
                            runCatching { PublicConfigStore.putBoolean(KEY_NIGHT, v, true) }
                            onDarkChange(v)
                        }
                    )
                }
            }
        }

        // ── 调试与数据备份（借鉴 Nuke 配置流）──
        item(key = "debug_title") {
            MiuixSmallTitle(text = "调试与备份", modifier = Modifier.padding(top = 12.dp))
        }
        item(key = "debug_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "模块日志输出",
                    summary = "开启后记录 Hook 详细行为日志",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.Notifications) },
                    checked = logEnabled,
                    onCheckedChange = { v ->
                        logEnabled = v
                        runCatching {
                            PublicConfigStore.putBoolean("module_log_enabled", v, true)
                            ModuleLog.setEnabled(v)
                        }
                    }
                )
                BasicComponent(
                    title = "导出配置 JSON",
                    summary = "导出当前已启用的配置项到剪贴板，方便跨设备恢复",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.File_upload) },
                    onClick = { exportConfig() }
                )
                BasicComponent(
                    title = "导入配置 JSON",
                    summary = "读取剪贴板中的 JSON 配置文件并覆盖应用",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.File_download) },
                    onClick = { importConfig() }
                )
            }
        }

        // ── 模块信息（对齐 wcx "关于" 分区）──
        item(key = "about_title") {
            MiuixSmallTitle(text = "关于", modifier = Modifier.padding(top = 12.dp))
        }
        item(key = "about_card") {
            val wxVer = remember {
                runCatching { com.OKK.yes.core.compat.WeChatVersion.resolve(context) }.getOrNull()?.summary() ?: "未检测"
            }
            val modBuild = remember {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 28) {
                        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
                    }
                }.getOrDefault(0L)
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(title = "版本", summary = "v$VERSION (Build $modBuild)", icon = MaterialSymbols.Outlined.Label)
                PrefArrow(title = "目标作用域", summary = "com.tencent.mm")
                PrefArrow(title = "适配版本范围", summary = com.OKK.yes.core.compat.WeChatVersion.PRIMARY_RANGE_LABEL)
                PrefArrow(title = "当前运行微信", summary = wxVer)
                PrefArrow(title = "配置文件路径", summary = "/storage/emulated/0/Android/media/com.tencent.mm/OKK/")
            }
        }

        // ── 重置（对齐 wcx "配置" 分区）──
        item(key = "reset_title") {
            MiuixSmallTitle(text = "重置", modifier = Modifier.padding(top = 12.dp))
        }
        item(key = "reset_card") {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "重置所有功能",
                    summary = "将所有设置恢复为默认（不可逆）",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.Delete_forever) },
                    onClick = { showResetDialog = true }
                )
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(ContentBottomInset)) }
    }

    // ── 重置确认弹窗 ──
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = MiuixTheme.colorScheme.surface,
            title = { Text("重置所有功能", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "确定要重置所有功能吗？\n所有开关、壁纸、气泡、虚拟定位等设置将恢复默认，重启微信后完全生效。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    PublicConfigStore.resetAll()
                    ThemeWallpaperConfig.invalidate()
                    ThemeWallpaperController.refreshAll()
                    showResetDialog = false
                    Toast.makeText(context, "已重置所有功能", Toast.LENGTH_SHORT).show()
                }) {
                    Text("重置", color = Color(0xFFE53935))
                }
            },
            dismissButton = {
                Button(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ── 偏好行辅助组件（对齐 wcx PrefArrow / PrefIcon）──

@Composable
private fun PrefArrow(
    title: String,
    summary: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = icon?.let { { PrefIcon(it) } },
    )
}

@Composable
private fun PrefIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}
