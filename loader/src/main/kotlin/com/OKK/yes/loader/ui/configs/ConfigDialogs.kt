// 二级配置弹窗通用组件 + 所有 render*Config 的 Compose 版
// 采用滚动保护与响应式微型卡片布局，彻底解决弹窗控件错位与溢出遮挡问题。

package com.OKK.yes.loader.ui.configs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.OKK.yes.core.hooks.AntiRevokeLogic
import com.OKK.yes.core.hooks.ChatToolbarHook
import com.OKK.yes.core.hooks.ThemeWallpaperConfig
import com.OKK.yes.core.hooks.BottomTabConfig
import com.OKK.yes.core.hooks.ChatEnhanceHook
import com.OKK.yes.core.hooks.DownloadRedirectHook
import com.OKK.yes.core.hooks.FinderVideoDownloadHook
import com.OKK.yes.core.hooks.InputStatsHook
import com.OKK.yes.core.hooks.MessageDetailConfig
import com.OKK.yes.core.hooks.ModuleLog
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.hooks.RoundAvatarConfig
import com.OKK.yes.core.hooks.plugins.JavaScriptEngine
import com.OKK.yes.core.hooks.plugins.JavaPlugin
import com.OKK.yes.core.hooks.ThemeWallpaperController
import com.OKK.yes.core.hooks.ThemeWallpaperHook
import com.OKK.yes.core.hooks.VirtualLocationConfig
import com.OKK.yes.core.hooks.ui.StyledDialogs
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── 通用防遮挡弹窗壳 ──

@Composable
fun ConfigDialog(
    title: String,
    onDismiss: () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        val maxHeight = (screenHeight * 0.80f).coerceAtMost(560.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            top.yukonga.miuix.kmp.basic.Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // 顶栏标题与关闭 Icon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "关闭",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    // 内部可滚动布局
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        content()
                    }

                    Spacer(Modifier.height(12.dp))

                    if (bottomBar != null) {
                        bottomBar()
                    } else {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("完成", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigSectionLabel(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
fun ConfigSwitch(
    title: String,
    summary: String? = null,
    key: String,
    default: Boolean = false,
    enabled: Boolean = true,
    onChanged: (() -> Unit)? = null
) {
    var checked by remember(key) {
        mutableStateOf(runCatching { PublicConfigStore.getBoolean(key, default) }.getOrDefault(default))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { v ->
                checked = v
                runCatching { PublicConfigStore.putBoolean(key, v, true) }
                onChanged?.invoke()
            }
        )
    }
}

@Composable
fun VariableChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ConfigColorPicker12(
    label: String,
    key: String,
    default: String,
    onValueChanged: ((String) -> Unit)? = null
) {
    var value by remember(key) {
        mutableStateOf(runCatching { PublicConfigStore.getString(key, default) }.getOrDefault(default))
    }

    // 12 色相环经典标准色彩 + 黑白常用色
    val colorPresets = remember {
        listOf(
            "红色" to "#E53935",
            "橙红" to "#F4511E",
            "橙色" to "#FB8C00",
            "橙黄" to "#FFB300",
            "黄色" to "#FDD835",
            "黄绿" to "#7CB342",
            "绿色" to "#43A047",
            "蓝绿" to "#00897B",
            "蓝色" to "#1E88E5",
            "蓝紫" to "#5E35B1",
            "紫色" to "#8E24AA",
            "紫红" to "#D81B60",
            "纯黑" to "#CC000000",
            "纯白" to "#CCFFFFFF"
        )
    }

    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            // 当前选中颜色预览圆点
            val parsedCurrentColor = runCatching {
                val hex = value.trim()
                Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
            }.getOrDefault(Color.Transparent)

            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(parsedCurrentColor)
                    .border(
                        1.dp,
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                        CircleShape
                    )
            )
        }

        TextField(
            value = value,
            onValueChange = { v ->
                value = v
                runCatching { PublicConfigStore.put(key, v, true) }
                onValueChanged?.invoke(v)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(6.dp))

        // 12 色相环色块快捷选择
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            colorPresets.forEach { (colorName, colorHex) ->
                val parsedColor = runCatching {
                    Color(android.graphics.Color.parseColor(colorHex))
                }.getOrDefault(Color.Gray)

                val isSelected = value.equals(colorHex, ignoreCase = true)

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                        )
                        .border(
                            if (isSelected) 1.5.dp else 0.5.dp,
                            if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            value = colorHex
                            runCatching { PublicConfigStore.put(key, colorHex, true) }
                            onValueChanged?.invoke(colorHex)
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(parsedColor)
                            .border(
                                0.5.dp,
                                Color.Black.copy(alpha = 0.2f),
                                CircleShape
                            )
                    )
                    Text(
                        text = colorName,
                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigTextField(
    label: String,
    key: String,
    default: String,
    onValueChanged: ((String) -> Unit)? = null
) {
    ConfigTextFieldWithChips(
        label = label,
        key = key,
        default = default,
        chips = emptyList(),
        onValueChanged = onValueChanged
    )
}

@Composable
fun ConfigTextFieldWithChips(
    label: String,
    key: String,
    default: String,
    chips: List<Pair<String, String>> = emptyList(),
    isReplaceMode: Boolean = false,
    onValueChanged: ((String) -> Unit)? = null
) {
    var value by remember(key) {
        mutableStateOf(runCatching { PublicConfigStore.getString(key, default) }.getOrDefault(default))
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        TextField(
            value = value,
            onValueChange = { v ->
                value = v
                runCatching { PublicConfigStore.put(key, v, true) }
                onValueChanged?.invoke(v)
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                chips.forEach { (chipLabel, chipValue) ->
                    VariableChip(label = chipLabel) {
                        val next = if (isReplaceMode) chipValue else value + chipValue
                        value = next
                        runCatching { PublicConfigStore.put(key, next, true) }
                        onValueChanged?.invoke(next)
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        colors = if (primary) {
            top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary()
        } else {
            top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors()
        }
    ) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
fun ConfigInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
fun ConfigSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayText: String
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            Text(text = displayText, color = MiuixTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

// ── 防撤回设置 ──

@Composable
fun AntiRevokeConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfigDialog(title = "防撤回设置", onDismiss = onDismiss) {
        ConfigSwitch(title = "显示撤回提示", summary = "在聊天中追加系统撤回提示文案", key = "revoke_notice_enabled", default = true)
        ConfigSwitch(title = "保留自己撤回", summary = "自己撤回的消息也同样拦截保存", key = "anti_revoke_keep_self", default = false)

        ConfigSectionLabel("撤回提示模板 (参考 WA 预设与变量)")
        ConfigTextFieldWithChips(
            label = "模板内容 (点击下方变量/预设自动追加或替换)",
            key = "anti_revoke_notice_text",
            default = AntiRevokeLogic.DEFAULT_NOTICE_TEMPLATE,
            chips = listOf(
                "{name} (撤回者昵称)" to "{name}",
                "默认预设" to "{name}撤回了一条消息",
                "WA经典预设" to "[防撤回] {name}: 撤回了一条消息",
                "尝试撤回" to "{name} 尝试撤回该消息",
                "拦截标记" to "[已拦截撤回] {name}"
            )
        )
        ConfigButton(text = "保存提示模板") {
            Toast.makeText(context, "已保存模板", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 消息底部格式 ──

@Composable
fun MessageDetailConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfigDialog(title = "消息底部格式", onDismiss = onDismiss) {
        ConfigSwitch(title = "点击气泡才显示", summary = "默认隐藏，点击聊天气泡后才展开时间", key = "detail_click_show", default = false)

        ConfigSectionLabel("格式模板")
        ConfigTextFieldWithChips(
            label = "文本格式 (点击下方 Chip 插入变量)",
            key = "detail_template",
            default = "\${time} \${relativeTime}",
            chips = listOf(
                "\${time} (精确时间 12:30:45)" to "\${time}",
                "\${relativeTime} (相对时间 刚才/5分钟前)" to "\${relativeTime}",
                "\${weekday} (星期 周一)" to "\${weekday}",
                "\${type} (消息类型 文本/图片)" to "\${type}",
                "\${msgId} (消息ID)" to "\${msgId}"
            )
        )
        ConfigTextFieldWithChips(
            label = "时间格式 (点击快捷选择)",
            key = "detail_time_pattern",
            default = "MM-dd HH:mm:ss",
            isReplaceMode = true,
            chips = listOf(
                "MM-dd HH:mm:ss" to "MM-dd HH:mm:ss",
                "HH:mm:ss" to "HH:mm:ss",
                "MM-dd HH:mm" to "MM-dd HH:mm",
                "yyyy-MM-dd HH:mm" to "yyyy-MM-dd HH:mm"
            )
        )
        ConfigTextField(label = "字体大小", key = "detail_text_size", default = "12")
        ConfigColorPicker12(label = "日间文字颜色", key = "detail_text_color_light", default = "#E6000000")
        ConfigColorPicker12(label = "夜间文字颜色", key = "detail_text_color_dark", default = "#CCFFFFFF")

        ConfigButton(text = "保存格式设置") {
            MessageDetailConfig.invalidateCache()
            Toast.makeText(context, "已保存格式设置", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 输入框统计 ──

@Composable
fun InputStatsConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfigDialog(title = "输入框提示", onDismiss = onDismiss) {
        ConfigTextFieldWithChips(
            label = "输入提示 (点击下方 Chip 插入变量)",
            key = "input_stats_template",
            default = "今日已发\${totalMsg}条",
            chips = listOf(
                "\${totalMsg} (今日总发数)" to "\${totalMsg}",
                "\${textMsg} (文字条数)" to "\${textMsg}",
                "\${textWord} (文字总字数)" to "\${textWord}",
                "\${emojiMsg} (表情数)" to "\${emojiMsg}"
            )
        )
        ConfigSwitch(title = "统计发送数量", summary = "将发出的消息计入当日统计", key = "input_stats_count_send", default = true)

        ConfigButton(text = "保存提示设置") {
            InputStatsHook.refreshNow()
            Toast.makeText(context, "已保存提示设置", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 群员头衔 ──

@Composable
fun MemberTitleConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfigDialog(title = "群员头衔文案", onDismiss = onDismiss) {
        ConfigSwitch(title = "显示普通成员徽章", summary = "关闭后仅显示群主与管理员", key = "member_title_show_member", default = true)
        ConfigTextField(label = "群主头衔文案", key = "member_title_owner", default = "群主")
        ConfigTextField(label = "管理员头衔文案", key = "member_title_admin", default = "管理员")
        ConfigTextField(label = "成员头衔文案", key = "member_title_member", default = "成员")
        ConfigButton(text = "保存头衔文案") {
            Toast.makeText(context, "已保存文案", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 会话分组 ──

@Composable
fun ConversationGroupingConfigDialog(onDismiss: () -> Unit) {
    ConfigDialog(title = "会话分组", onDismiss = onDismiss) {
        ConfigSectionLabel("功能使用说明")
        Text(
            text = "会话分组管理已完全原生集成在微信主页顶部标签栏中：\n\n" +
                    "• 标签开关管理：长按任意 Tab 标签在弹出菜单中点击「预设标签」，即可开启或关闭好友、群聊、服务号、公众号、未读、企业微信等预设标签的显示。\n" +
                    "• 新建与编辑分组：长按标签选择「新建分组」或「编辑」，可手动选择成员、设置自动归拢规则或自定义 SQL 过滤。\n" +
                    "• 标签排序调整：长按标签选择「排序」，可直接拖拽调整 Tab 顺位，点击右侧 ✔ 确认保存。\n" +
                    "• 快速删除分组：长按自定义标签选择「删除」即可移除对应分组。",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
fun BubbleConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val act = context as? Activity
    ConfigDialog(title = "气泡皮肤设置", onDismiss = onDismiss) {
        ConfigSwitch(title = "启用自定义气泡", summary = "替换微信默认对话气泡背景", key = "bubble_enabled", default = false)

        ConfigSectionLabel("气泡皮肤文件夹")
        val folderPath = remember { PublicConfigStore.getString("bubble_folder", "") }
        Text(
            text = if (folderPath.isBlank()) "（未设置，使用默认目录）" else folderPath,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        ConfigTextFieldWithChips(
            label = "文件夹路径",
            key = "bubble_folder",
            default = "",
            isReplaceMode = true,
            chips = listOf(
                "模块目录" to "/storage/emulated/0/Android/media/com.tencent.mm/OKK",
                "下载" to "/storage/emulated/0/Download/OKK",
                "相册" to "/storage/emulated/0/DCIM/OKK"
            )
        )
        ConfigButton(text = "恢复默认目录", primary = false) {
            PublicConfigStore.put("bubble_folder", "", false)
            Toast.makeText(context, "已恢复默认目录", Toast.LENGTH_SHORT).show()
        }
        Text(
            text = "提示：填写的文件夹内需包含 left.9.png（接收）和 right.9.png（发送），设置后自动读取并生效。",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        ConfigSectionLabel("（可选）单张图片导入")
        ConfigButton(text = "选择左侧气泡 (.9.png)") {
            act?.let { ChatEnhanceHook.startPickBubble(it, false) }
        }
        ConfigButton(text = "选择右侧气泡 (.9.png)", primary = false) {
            act?.let { ChatEnhanceHook.startPickBubble(it, true) }
        }
    }
}

// ── 圆形头像 ──

@Composable
fun AvatarConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val initRadius = remember { PublicConfigStore.getString("round_avatar_radius", "0.50").toFloatOrNull() ?: 0.50f }
    var sliderValue by remember { mutableFloatStateOf(RoundAvatarConfig.clampRadius(initRadius)) }
    val progress = (((sliderValue - 0.05f) * 100f) + 0.5f).toInt().coerceIn(0, 45) / 45f

    val label = when {
        sliderValue <= 0.08f -> "正方"
        sliderValue >= 0.49f -> "圆形"
        sliderValue in 0.34f..0.38f -> "方圆"
        else -> "自定义"
    }

    ConfigDialog(title = "圆形头像设置", onDismiss = onDismiss) {
        Text(
            text = "当前圆度 %.2f · %s".format(sliderValue, label),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Slider(
            value = progress,
            onValueChange = { p -> sliderValue = RoundAvatarConfig.clampRadius(0.05f + p * 0.45f) },
            valueRange = 0f..1f
        )
        ConfigButton(text = "一键设置推荐方圆 0.36", primary = false) {
            sliderValue = RoundAvatarConfig.clampRadius(0.36f)
        }
        ConfigButton(text = "保存头像圆度") {
            PublicConfigStore.put("round_avatar_radius", "%.2f".format(sliderValue), false)
            Toast.makeText(context, "已保存圆度 %.2f".format(sliderValue), Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 主题壁纸 ──

@Composable
fun ThemeWallpaperConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val act = context as? Activity
    ThemeWallpaperConfig.reloadIfNeeded(force = true)
    act?.let { ThemeWallpaperController.rememberActivity(it) }
    val hasImg = remember { ThemeWallpaperConfig.path().isNotEmpty() && java.io.File(ThemeWallpaperConfig.path()).isFile }
    var alpha by remember { mutableFloatStateOf(ThemeWallpaperConfig.alpha()) }

    ConfigDialog(title = "主题壁纸配置", onDismiss = onDismiss) {
        ConfigButton(text = if (hasImg) "更换壁纸图片" else "选择壁纸图片") {
            act?.let { ThemeWallpaperHook.startPickImage(it) }
        }
        ConfigButton(text = "清除壁纸（恢复默认）", primary = false) {
            ThemeWallpaperConfig.clearWallpaper()
            ThemeWallpaperConfig.setEnabled(false)
            ThemeWallpaperController.refreshAll()
            Toast.makeText(context, "已清除壁纸", Toast.LENGTH_SHORT).show()
        }

        ConfigSlider(
            label = "透明度预览",
            value = alpha,
            onValueChange = { a -> alpha = a; ThemeWallpaperConfig.setAlphaLive(a) },
            valueRange = 0f..1f,
            displayText = "%.0f%%".format(alpha * 100)
        )

        ConfigButton(text = "应用并激活壁纸") {
            ThemeWallpaperConfig.invalidate()
            ThemeWallpaperConfig.reloadIfNeeded(force = true)
            ThemeWallpaperConfig.setAlphaPersist(alpha)
            PublicConfigStore.putBoolean(ThemeWallpaperConfig.KEY_ENABLED, true, false)
            act?.let { ThemeWallpaperController.rememberActivity(it) }
            val msg = ThemeWallpaperController.forceApplyNow()
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 虚拟定位 ──

@Composable
fun LocationConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val host = context as? Activity
    val (initLa, initLo) = remember { VirtualLocationConfig.ensureInitialCoords(context) }
    var latText by remember { mutableStateOf(VirtualLocationConfig.fmt(initLa)) }
    var lonText by remember { mutableStateOf(VirtualLocationConfig.fmt(initLo)) }
    var mapPickToken by remember { mutableStateOf(0) }

    // 微信地图选点完成后自动回填经纬度（最长等待 2 分钟）
    LaunchedEffect(mapPickToken) {
        if (mapPickToken == 0) return@LaunchedEffect
        var attempts = 0
        while (attempts < 120) {
            delay(1000)
            attempts++
            val pair = VirtualLocationConfig.readMapPickResult(consume = true)
            if (pair != null) {
                latText = VirtualLocationConfig.fmt(pair.first)
                lonText = VirtualLocationConfig.fmt(pair.second)
                PublicConfigStore.put("virtual_location_latitude", latText, true)
                PublicConfigStore.put("virtual_location_longitude", lonText, true)
                Toast.makeText(context, "已选点 $latText, $lonText", Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
        }
    }

    ConfigDialog(title = "虚拟定位设置", onDismiss = onDismiss) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "纬度 (Latitude)",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            TextField(
                value = latText,
                onValueChange = { v ->
                    latText = v
                    runCatching { PublicConfigStore.put("virtual_location_latitude", v, true) }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "经度 (Longitude)",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            TextField(
                value = lonText,
                onValueChange = { v ->
                    lonText = v
                    runCatching { PublicConfigStore.put("virtual_location_longitude", v, true) }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        ConfigButton(text = "微信地图选点", primary = true) {
            val la = latText.toDoubleOrNull() ?: initLa
            val lo = lonText.toDoubleOrNull() ?: initLo
            // 先同步当前坐标到公共配置，再写选点请求
            VirtualLocationConfig.writePublic(true, la, lo, context, async = false)
            VirtualLocationConfig.requestMapPick()
            // 默认地图中心使用真实系统位置，便于直接选取当前所在地；取不到时回退当前坐标
            val real = VirtualLocationConfig.resolveDeviceLocation(context)
            val centerLat = real?.first ?: la
            val centerLon = real?.second ?: lo
            // 直接拉起微信内置地图 RedirectUI（与 WeChatMapPickBridge 同 Intent/请求码，结果由桥接写回）
            val ok = host?.let { act ->
                runCatching {
                    val clazz = Class.forName(
                        "com.tencent.mm.plugin.location.ui.RedirectUI",
                        false,
                        act.classLoader
                    )
                    val pickIntent = android.content.Intent(act, clazz).apply {
                        putExtra("map_view_type", 8)
                        // 传入初始中心点，避免地图依赖 GPS 定位失败导致“无法定位当前位置”
                        putExtra("kwebmap_slat", centerLat)
                        putExtra("kwebmap_lng", centerLon)
                    }
                    act.startActivityForResult(pickIntent, 0xAC07)
                    true
                }.getOrDefault(false)
            } ?: false
            if (ok) {
                mapPickToken++
                Toast.makeText(context, "已打开微信地图，请选择位置", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "打开微信地图失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }

        ConfigButton(text = "自动填入当前真实位置", primary = false) {
            val pair = VirtualLocationConfig.resolveDeviceLocation(context)
            if (pair == null) {
                Toast.makeText(context, "无法获取当前位置", Toast.LENGTH_SHORT).show()
            } else {
                latText = VirtualLocationConfig.fmt(pair.first)
                lonText = VirtualLocationConfig.fmt(pair.second)
                PublicConfigStore.put("virtual_location_latitude", latText, true)
                PublicConfigStore.put("virtual_location_longitude", lonText, true)
                Toast.makeText(context, "已填入并保存当前坐标", Toast.LENGTH_SHORT).show()
            }
        }
        ConfigButton(text = "重置位置（恢复真实定位）", primary = false) {
            val real = VirtualLocationConfig.resolveDeviceLocation(context)
            if (real == null) {
                Toast.makeText(context, "无法获取真实位置，请开启定位后再试", Toast.LENGTH_SHORT).show()
            } else {
                // 关闭虚拟定位并写回真实设备坐标
                VirtualLocationConfig.resetToDefault(context, async = false)
                latText = VirtualLocationConfig.fmt(real.first)
                lonText = VirtualLocationConfig.fmt(real.second)
                Toast.makeText(context, "已恢复真实定位并关闭虚拟定位", Toast.LENGTH_SHORT).show()
            }
        }
        ConfigButton(text = "保存坐标并生效") {
            val la = latText.toDoubleOrNull() ?: initLa
            val lo = lonText.toDoubleOrNull() ?: initLo
            VirtualLocationConfig.writePublic(true, la, lo, context, async = false)
            Toast.makeText(context, "坐标已保存并激活", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── PC 自动登录 ──

@Composable
fun AutoLoginConfigDialog(onDismiss: () -> Unit) {
    ConfigDialog(title = "PC 登录自动化选项", onDismiss = onDismiss) {
        ConfigSwitch(title = "自动同步最近聊天", summary = "登录确认页自动勾选同步聊天记录", key = "auto_login_win_sync_msg", default = true)
        ConfigSwitch(title = "显示登录设备名称", summary = "登录确认页展示具体的电脑设备名", key = "auto_login_win_show_device", default = true)
        ConfigSwitch(title = "自动点击登录按钮", summary = "页面载入完毕后自动触发点击登录", key = "auto_login_win_auto_click", default = true)
    }
}

// ── 下载重定向 ──

@Composable
fun DownloadRedirectConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val act = context as? Activity
    ConfigDialog(title = "下载重定向设置", onDismiss = onDismiss) {
        ConfigSwitch(title = "启用下载重定向", summary = "微信接收的聊天文件保存到自定义目录", key = DownloadRedirectHook.KEY_ENABLED, default = false)

        ConfigSectionLabel("当前保存目录")
        var dirPath by remember { mutableStateOf(DownloadRedirectHook.saveDir()) }
        // 轮询 SAF 选择结果：宿主 onActivityResult 把新目录暂存到 pendingTreeUri，
        // 这里负责落盘到 KEY_TREE_URI 并刷新显示路径（dialog 关闭时 LaunchedEffect 自动取消）
        LaunchedEffect(Unit) {
            while (true) {
                val uri = DownloadRedirectHook.takePendingTreeUri()
                if (uri != null) {
                    DownloadRedirectHook.setSaveTreeUri(uri)
                    DownloadRedirectHook.setSaveDir(DownloadRedirectHook.formatTreeUriToPath(uri))
                    dirPath = DownloadRedirectHook.saveDir()
                }
                delay(300)
            }
        }
        Text(
            text = dirPath,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        ConfigButton(text = "选择保存目录 (SAF)") {
            act?.let { a ->
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    a.startActivityForResult(intent, 0x0A0D20)
                }
            }
        }
        ConfigButton(text = "恢复默认目录", primary = false) {
            DownloadRedirectHook.setSaveDir(DownloadRedirectHook.DEFAULT_DIR)
            Toast.makeText(context, "已恢复默认目录", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── Java脚本 ──

@Composable
fun JavaPluginConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val act = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var showManageDialog by remember { mutableStateOf(false) }

    val scripts = remember(refreshKey) {
        JavaScriptEngine.listAllScriptEntries()
    }

    // 轮询 SAF 文件夹选取结果
    LaunchedEffect(Unit) {
        while (true) {
            val uriStr = JavaScriptEngine.takePendingImportUri()
            if (uriStr != null) {
                val uri = android.net.Uri.parse(uriStr)
                coroutineScope.launch(Dispatchers.IO) {
                    val (success, message) = JavaScriptEngine.importScriptFolder(context, uri)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "已成功加载脚本：$message", Toast.LENGTH_SHORT).show()
                            refreshKey++
                        } else {
                            Toast.makeText(context, "加载脚本失败：$message", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            delay(300)
        }
    }

    ConfigDialog(title = "Java脚本", onDismiss = onDismiss) {
        // 顶部操作栏：1.加载脚本  2.管理脚本  3.刷新脚本
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    act?.let { a ->
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            }
                            a.startActivityForResult(intent, JavaScriptEngine.REQ_SCRIPT_FOLDER)
                        }.onFailure { e ->
                            Toast.makeText(context, "无法打开文件选择器: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } ?: Toast.makeText(context, "Activity 上下文无效", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("加载脚本", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    showManageDialog = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("管理脚本", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    JavaScriptEngine.reloadAll()
                    refreshKey++
                    Toast.makeText(context, "已刷新脚本", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("刷新脚本", fontSize = 12.sp)
            }
        }

        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无脚本\n点击上方「加载脚本」选择插件文件夹进行导入",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                scripts.forEach { entry ->
                    ScriptItemCard(entry = entry, refreshKey = refreshKey)
                }
            }
        }
    }

    if (showManageDialog) {
        ScriptManageDialog(
            scripts = scripts,
            onDismiss = {
                showManageDialog = false
                refreshKey++
            },
            onReorder = {
                refreshKey++
            },
            onDelete = { id ->
                val ok = JavaScriptEngine.deleteScript(id)
                if (ok) {
                    Toast.makeText(context, "已删除脚本", Toast.LENGTH_SHORT).show()
                    refreshKey++
                } else {
                    Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
fun ScriptManageDialog(
    scripts: List<JavaPlugin>,
    onDismiss: () -> Unit,
    onReorder: () -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    var deletingPlugin by remember { mutableStateOf<JavaPlugin?>(null) }
    var currentList by remember(scripts) { mutableStateOf(scripts) }

    ConfigDialog(title = "管理脚本", onDismiss = onDismiss) {
        if (currentList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无已安装的脚本",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentList.forEachIndexed { index, entry ->
                    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${index + 1}. ${entry.info.name}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${entry.id}${entry.info.version?.let { " · v$it" } ?: ""}",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 上移按钮
                                Button(
                                    onClick = {
                                        if (index > 0) {
                                            JavaScriptEngine.moveScript(entry.id, -1)
                                            currentList = JavaScriptEngine.listAllScriptEntries()
                                            onReorder()
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("↑", fontSize = 12.sp)
                                }

                                // 下移按钮
                                Button(
                                    onClick = {
                                        if (index < currentList.size - 1) {
                                            JavaScriptEngine.moveScript(entry.id, 1)
                                            currentList = JavaScriptEngine.listAllScriptEntries()
                                            onReorder()
                                        }
                                    },
                                    enabled = index < currentList.size - 1,
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("↓", fontSize = 12.sp)
                                }

                                // 删除按钮
                                Button(
                                    onClick = {
                                        deletingPlugin = entry
                                    },
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("删除", fontSize = 11.sp, color = Color(0xFFE53935))
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        ConfigButton(text = "完成管理") {
            onDismiss()
        }
    }

    // 删除二次确认弹窗
    deletingPlugin?.let { plugin ->
        ConfigDialog(
            title = "确认删除脚本",
            onDismiss = { deletingPlugin = null }
        ) {
            Text(
                text = "确定要永久删除脚本「${plugin.info.name}」(${plugin.id}) 及其所有文件吗？此操作不可撤销。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { deletingPlugin = null },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        onDelete(plugin.id)
                        deletingPlugin = null
                        currentList = JavaScriptEngine.listAllScriptEntries()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确认删除", fontSize = 12.sp, color = Color(0xFFE53935))
                }
            }
        }
    }
}

@Composable
fun ScriptItemCard(entry: JavaPlugin, refreshKey: Int) {
    var isEnabled by remember(entry.id, refreshKey) { mutableStateOf(entry.isEnabled) }
    top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.info.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                val subtitle = buildList {
                    add(entry.id)
                    add(if (isEnabled) "已启用" else "已禁用")
                    entry.info.version?.takeIf { it.isNotBlank() }?.let { add("v$it") }
                    entry.info.author?.takeIf { it.isNotBlank() }?.let { add("作者 $it") }
                }.joinToString(" · ")
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            top.yukonga.miuix.kmp.basic.Switch(
                checked = isEnabled,
                onCheckedChange = { next ->
                    if (JavaScriptEngine.setScriptEnabled(entry.id, next)) {
                        isEnabled = next
                    }
                }
            )
        }
    }
}

@Composable
fun FinderVideoDownloadConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val act = context as? Activity
    ConfigDialog(title = "视频号下载设置", onDismiss = onDismiss) {
        ConfigSwitch(title = "启用视频号下载", summary = "视频号分享菜单添加复制链接/下载", key = FinderVideoDownloadHook.KEY_ENABLED, default = false)
        ConfigSectionLabel("当前保存目录")
        var dirPath by remember { mutableStateOf(FinderVideoDownloadHook.saveDir()) }
        LaunchedEffect(Unit) {
            while (true) {
                val uri = FinderVideoDownloadHook.takePendingTreeUri()
                if (uri != null) {
                    val path = DownloadRedirectHook.formatTreeUriToPath(uri)
                    FinderVideoDownloadHook.setSaveDir(path)
                    dirPath = FinderVideoDownloadHook.saveDir()
                }
                delay(300)
            }
        }
        Text(
            text = dirPath,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        ConfigButton(text = "选择保存目录 (SAF)") {
            act?.let { a ->
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    a.startActivityForResult(intent, FinderVideoDownloadHook.REQ_PICK_DIR)
                }
            }
        }
        ConfigTextFieldWithChips(
            label = "手动输入/预设选择",
            key = FinderVideoDownloadHook.KEY_DIR,
            default = FinderVideoDownloadHook.DEFAULT_DIR,
            isReplaceMode = true,
            chips = listOf(
                "下载" to "/storage/emulated/0/Download/OKK",
                "视频" to "/storage/emulated/0/Movies/OKK",
                "微信" to "/storage/emulated/0/Android/data/com.tencent.mm/OKK",
                "DCIM" to "/storage/emulated/0/DCIM/OKK"
            )
        )
        ConfigButton(text = "恢复默认目录", primary = false) {
            FinderVideoDownloadHook.setSaveDir(FinderVideoDownloadHook.DEFAULT_DIR)
            dirPath = FinderVideoDownloadHook.saveDir()
            Toast.makeText(context, "已恢复默认目录", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun DefaultBarConfigDialog(onDismiss: () -> Unit) {
    ConfigDialog(title = "默认底栏设置", onDismiss = onDismiss) {
        ConfigSwitch(title = "隐藏底栏标题", summary = "底栏标签隐藏文字仅保留图标", key = "bottom_tab_hide_title", default = false)
        ConfigSwitch(title = "隐藏默认底栏", summary = "隐藏微信原生底栏，不影响悬浮底栏", key = BottomTabConfig.KEY_HIDE_BAR, default = false)
    }
}

// ── 悬浮底栏 ──

@Composable
fun FloatingTabConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfigDialog(title = "悬浮底栏配置", onDismiss = onDismiss) {
        ConfigSwitch(title = "显示底栏标题", summary = "关闭后仅保留图标", key = BottomTabConfig.KEY_FLOATING_LABELS, default = true)
        ConfigSwitch(title = "显示未读角标", summary = "开启数字/红点未读提醒", key = BottomTabConfig.KEY_FLOATING_BADGE, default = true)

        ConfigSectionLabel("自定义标题")
        ConfigTextField(label = "第 1 格标题", key = BottomTabConfig.KEY_TITLE_CHATS, default = "微信")
        ConfigTextField(label = "第 2 格标题", key = BottomTabConfig.KEY_TITLE_CONTACTS, default = "通讯录")
        ConfigTextField(label = "第 3 格标题", key = BottomTabConfig.KEY_TITLE_DISCOVER, default = "发现")
        ConfigTextField(label = "第 4 格标题", key = BottomTabConfig.KEY_TITLE_ME, default = "我")

        ConfigButton(text = "保存底栏标题") {
            runCatching { BottomTabConfig.invalidate() }
            Toast.makeText(context, "已保存，重启微信生效", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── 悬浮快捷入口 ──

private data class QuickEntryItemState(
    val id: String,
    val name: String,
    val enabled: Boolean
)

@Composable
fun FloatingQuickEntryConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val allPresets = remember { FloatingQuickEntryHook.allPresets }
    val presetById = remember { allPresets.associateBy { it.id } }

    var itemsState by remember {
        mutableStateOf(run {
            val activeEntries = FloatingQuickEntryHook.loadEntries()
            val activeIds = activeEntries.map { it.id }.toSet()
            val result = mutableListOf<QuickEntryItemState>()

            // 1. 先加入当前已启用的条目（保持已调好的排序）
            activeEntries.forEach { entry ->
                result.add(QuickEntryItemState(entry.id, entry.name, true))
            }

            // 2. 补充未启用的预设条目
            allPresets.forEach { preset ->
                if (preset.id !in activeIds) {
                    result.add(QuickEntryItemState(preset.id, preset.name, false))
                }
            }

            // 3. 补充未启用的自定义条目
            val customEntries = FloatingQuickEntryHook.loadCustomEntries()
            customEntries.forEach { custom ->
                if (custom.id !in activeIds) {
                    result.add(QuickEntryItemState(custom.id, custom.name, false))
                }
            }

            result.toList()
        })
    }

    var selectedIcon by remember { mutableStateOf(runCatching { PublicConfigStore.getString(FloatingQuickEntryHook.KEY_ICON, "grid") }.getOrDefault("grid")) }
    var selectedScope by remember { mutableStateOf(runCatching { PublicConfigStore.getString(FloatingQuickEntryHook.KEY_SCOPE, "tab") }.getOrDefault("tab")) }

    ConfigDialog(
        title = "悬浮快捷入口配置",
        onDismiss = onDismiss,
        bottomBar = {
            Button(
                onClick = {
                    val enabledIds = itemsState.filter { it.enabled }.map { it.id }
                    PublicConfigStore.put("floating_quick_entry_items", enabledIds.joinToString(","), false)
                    PublicConfigStore.put(FloatingQuickEntryHook.KEY_ICON, selectedIcon, false)
                    PublicConfigStore.put(FloatingQuickEntryHook.KEY_SCOPE, selectedScope, false)
                    Toast.makeText(context, "已保存快捷入口设置", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary()
            ) {
                Text("保存设置", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) {
        ConfigSectionLabel("显示范围")
        listOf("tab" to "Tab 首页显示", "all" to "全局所有页面").forEach { (scope, name) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                    .clickable { selectedScope = scope }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Switch(checked = selectedScope == scope, onCheckedChange = { if (it) selectedScope = scope })
            }
        }

        ConfigSectionLabel("图标样式")
        listOf("grid" to "九宫格", "menu" to "菜单", "sparkle" to "星芒", "lightning" to "闪电", "logo" to "OKK").forEach { (style, name) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                    .clickable { selectedIcon = style }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Switch(checked = selectedIcon == style, onCheckedChange = { if (it) selectedIcon = style })
            }
        }

        ConfigSectionLabel("预设功能开关与排序")
        itemsState.forEachIndexed { idx, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (item.enabled) MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f)
                        else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f)
                    )
                    .clickable {
                        val list = itemsState.toMutableList()
                        list[idx] = item.copy(enabled = !item.enabled)
                        itemsState = list
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 三横线拖拽/排序指示图标 (≡)
                Text(
                    text = "≡",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.enabled) MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f),
                    modifier = Modifier.padding(end = 10.dp)
                )

                // 功能名称
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.enabled) MiuixTheme.colorScheme.onSurface
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                )

                // 美化上下移动控制组
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    // 上移
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (idx > 0) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable(enabled = idx > 0) {
                                val list = itemsState.toMutableList()
                                val temp = list[idx]
                                list[idx] = list[idx - 1]
                                list[idx - 1] = temp
                                itemsState = list
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↑",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (idx > 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                        )
                    }

                    // 下移
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (idx < itemsState.size - 1) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable(enabled = idx < itemsState.size - 1) {
                                val list = itemsState.toMutableList()
                                val temp = list[idx]
                                list[idx] = list[idx + 1]
                                list[idx + 1] = temp
                                itemsState = list
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↓",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (idx < itemsState.size - 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                        )
                    }
                }

                // 功能开关 Switch
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { isChecked ->
                        val list = itemsState.toMutableList()
                        list[idx] = list[idx].copy(enabled = isChecked)
                        itemsState = list
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ── 便捷类型别名 ──
private typealias FloatingQuickEntryHook = com.OKK.yes.core.hooks.FloatingQuickEntryHook
private typealias ConversationListStyleHook = com.OKK.yes.core.hooks.ConversationListStyleHook

@Composable
fun ConvCardConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var insetDp by remember {
        mutableFloatStateOf(runCatching { PublicConfigStore.getInt(ConversationListStyleHook.KEY_INSET_DP, 10) }.getOrDefault(10).toFloat())
    }

    ConfigDialog(
        title = "会话列表美化配置",
        onDismiss = onDismiss,
        bottomBar = {
            Button(
                onClick = {
                    PublicConfigStore.put(ConversationListStyleHook.KEY_INSET_DP, insetDp.toInt().toString(), false)
                    Toast.makeText(context, "已保存会话列表美化配置", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary()
            ) {
                Text("保存设置", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) {
        ConfigSlider(
            label = "左右回缩边距",
            value = insetDp,
            onValueChange = { insetDp = it },
            valueRange = 4f..30f,
            displayText = "${insetDp.toInt()} dp"
        )
    }
}



@Composable
fun CloseFriendConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var memberCount by remember { mutableStateOf(com.OKK.yes.core.hooks.CloseFriendStore.members().size) }
    ConfigDialog(title = "密友设置", onDismiss = onDismiss) {
        ConfigSwitch(
            title = "密友功能总开关",
            summary = "开启后隐藏指定密友的首页会话与通讯录入口",
            key = com.OKK.yes.core.hooks.CloseFriendStore.KEY_ENABLED,
            default = false
        )
        ConfigSectionLabel("隐藏范围")
        ConfigSwitch(
            title = "隐藏首页会话",
            summary = "微信首页会话列表中隐藏密友会话（仅在列表隐藏，不删除聊天记录）",
            key = com.OKK.yes.core.hooks.CloseFriendStore.KEY_HIDE_CONVERSATION,
            default = true
        )
        ConfigSwitch(
            title = "隐藏通讯录好友",
            summary = "通讯录列表中隐藏密友条目",
            key = com.OKK.yes.core.hooks.CloseFriendStore.KEY_HIDE_CONTACT,
            default = true
        )
        ConfigSectionLabel("密友名单")
        ConfigButton(text = "管理密友名单（当前  人）", primary = true) {
            com.OKK.yes.core.hooks.CloseFriendManagerUi.showMemberSelector(context) {
                memberCount = com.OKK.yes.core.hooks.CloseFriendStore.members().size
            }
        }
        Text(
            text = "说明：长按通讯录好友条目也可快捷添加/移除密友；密友发来消息时不会在首页弹出会话，关闭总开关后即可完全恢复正常显示。",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private data class ToolbarItemEntry(
    val key: String,
    val name: String,
    val icon: String,
    val enabled: Boolean
)

@Composable
fun ChatToolbarConfigDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    val allDefs = remember {
        listOf(
            Triple("相册", "相册", "🖼️"),
            Triple("拍摄", "拍摄", "📷"),
            Triple("系统拍摄", "系统相机", "🎥"),
            Triple("红包", "红包", "🧧"),
            Triple("转账", "转账", "💰"),
            Triple("语音通话", "语音通话", "📞"),
            Triple("视频通话", "视频通话", "📹"),
            Triple("位置", "位置", "📍"),
            Triple("文件", "文件", "📁"),
            Triple("收藏", "收藏", "⭐"),
            Triple("个人名片", "个人名片", "📇"),
            Triple("接龙", "接龙", "📝")
        )
    }

    var selectedMode by remember {
        mutableStateOf(PublicConfigStore.getString(ChatToolbarHook.KEY_DISPLAY_MODE, "icon_and_text"))
    }

    var itemsState by remember {
        val orderStr = PublicConfigStore.getString(ChatToolbarHook.KEY_ORDER, ChatToolbarHook.DEFAULT_ORDER)
        val enabledStr = PublicConfigStore.getString(ChatToolbarHook.KEY_ENABLED_ITEMS, ChatToolbarHook.DEFAULT_ENABLED)

        val orderKeys = orderStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val enabledSet = enabledStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val orderedList = mutableListOf<ToolbarItemEntry>()
        for (k in orderKeys) {
            val def = allDefs.firstOrNull { it.first == k } ?: continue
            orderedList.add(ToolbarItemEntry(def.first, def.second, def.third, k in enabledSet))
        }
        for (def in allDefs) {
            if (orderedList.none { it.key == def.first }) {
                orderedList.add(ToolbarItemEntry(def.first, def.second, def.third, def.first in enabledSet))
            }
        }
        mutableStateOf(orderedList)
    }

    ConfigDialog(
        title = "聊天快捷工具栏设置",
        onDismiss = onDismiss,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val resetList = allDefs.map { ToolbarItemEntry(it.first, it.second, it.third, true) }
                        itemsState = resetList.toMutableList()
                        selectedMode = "icon_and_text"
                        PublicConfigStore.put(ChatToolbarHook.KEY_ORDER, ChatToolbarHook.DEFAULT_ORDER, false)
                        PublicConfigStore.put(ChatToolbarHook.KEY_ENABLED_ITEMS, ChatToolbarHook.DEFAULT_ENABLED, false)
                        PublicConfigStore.put(ChatToolbarHook.KEY_DISPLAY_MODE, "icon_and_text", false)
                        Toast.makeText(context, "已恢复默认工具栏设置", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors()
                ) {
                    Text("恢复默认", fontSize = 14.sp)
                }

                Button(
                    onClick = {
                        val newOrder = itemsState.joinToString(",") { it.key }
                        val newEnabled = itemsState.filter { it.enabled }.joinToString(",") { it.key }
                        PublicConfigStore.put(ChatToolbarHook.KEY_ORDER, newOrder, false)
                        PublicConfigStore.put(ChatToolbarHook.KEY_ENABLED_ITEMS, newEnabled, false)
                        PublicConfigStore.put(ChatToolbarHook.KEY_DISPLAY_MODE, selectedMode, false)
                        Toast.makeText(context, "已保存快捷工具栏设置", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("保存设置", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) {
        ConfigSwitch(
            title = "启用聊天工具栏",
            summary = "在聊天输入框上方挂载横向功能快捷键",
            key = ChatToolbarHook.KEY_ENABLED,
            default = true
        )

        ConfigSectionLabel("显示样式")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val modes = listOf(
                "icon_and_text" to "图标+文字",
                "icon_only" to "仅图标",
                "text_only" to "仅文字"
            )
            for ((modeKey, modeLabel) in modes) {
                val isSelected = selectedMode == modeKey
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.surfaceContainer
                        )
                        .clickable { selectedMode = modeKey }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = modeLabel,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        ConfigSectionLabel("功能排序与开关")
        Text(
            text = "使用 ↑ ↓ 调整显示顺序，使用右侧开关控制显示",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 6.dp)
        )

        itemsState.forEachIndexed { idx, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (item.enabled) MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.75f)
                        else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标
                Text(
                    text = item.icon,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )

                // 功能名称
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (item.enabled) MiuixTheme.colorScheme.onSurface
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                )

                // 上下移动控制
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    // 上移
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (idx > 0) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable(enabled = idx > 0) {
                                val list = itemsState.toMutableList()
                                val temp = list[idx]
                                list[idx] = list[idx - 1]
                                list[idx - 1] = temp
                                itemsState = list
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↑",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (idx > 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                        )
                    }

                    // 下移
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (idx < itemsState.size - 1) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .clickable(enabled = idx < itemsState.size - 1) {
                                val list = itemsState.toMutableList()
                                val temp = list[idx]
                                list[idx] = list[idx + 1]
                                list[idx + 1] = temp
                                itemsState = list
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↓",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (idx < itemsState.size - 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                        )
                    }
                }

                // 开关
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { isChecked ->
                        val list = itemsState.toMutableList()
                        list[idx] = list[idx].copy(enabled = isChecked)
                        itemsState = list
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
