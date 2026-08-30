// 主页 Tab：标题区 + 左侧 OKK 品牌卡 + 右侧已启用/全部功能双方块 + 运行状态 Hero + 设备信息

package com.OKK.yes.loader.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.OKK.yes.core.startup.FeatureHookRegistry
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Phone_android
import com.composables.icons.materialsymbols.outlined.Smartphone
import com.composables.icons.materialsymbols.outlined.Sports_esports
import com.composables.icons.materialsymbols.outlined.Person
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val OKK_VERSION = "1.2.6"

@Composable
fun HomePager() {
    val context = LocalContext.current
    val total = remember { FeatureCatalog.allFeatures.size }
    val onCount = remember { FeatureCatalog.getTotalOnCount() }

    val wxVer = remember {
        runCatching { com.OKK.yes.core.compat.WeChatVersion.resolve(context)?.summary() }.getOrNull()
    }
    val issueTotal = remember {
        val runtimeList = runCatching { FeatureHookRegistry.runtimeSnapshot() }.getOrDefault(emptyList())
        val installPartial = runCatching { FeatureHookRegistry.snapshot().filter { it.status == FeatureHookRegistry.Status.PARTIAL } }.getOrDefault(emptyList())
        val failNames = runCatching { FeatureHookRegistry.failNames() }.getOrDefault(emptyList())
        failNames.size + installPartial.size + runtimeList.count { it.status != FeatureHookRegistry.Status.OK }
    }
    val heroOk = issueTotal == 0
    val heroColor = if (heroOk) Color(0xFF2E7D32) else Color(0xFFE65100)
    val heroTitle = if (heroOk) "模块运行正常" else "$issueTotal 项功能需关注"
    val heroSub = if (heroOk) "LSPosed · ${wxVer ?: "微信"} · v$OKK_VERSION" else "部分功能未生效或加载失败"

    MiuixListScaffold(title = "Welcome OKK!") {
        // ── 主卡片区域：左侧 OKK 放大美化卡片 + 右侧 (已启用 / 全部功能) 上下双方块 ──
        item(key = "main_banner") {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 左侧 OKK 独立美化大卡片
                OkkBrandCard(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // 右侧：已启用功能 和 全部功能 两个方块上下排列
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CountCard(
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Sports_esports,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        value = onCount.toString(),
                        label = "已启用功能"
                    )
                    CountCard(
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Smartphone,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        value = total.toString(),
                        label = "全部功能"
                    )
                }
            }
        }

        // ── 模块运行状态 Hero 卡片（移动至设备信息上面） ──
        item(key = "hero_status") {
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "● $heroTitle",
                        color = heroColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = heroSub,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // ── 设备信息标题 ──
        item(key = "info_title") {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "设备信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── 设备信息卡 ──
        item(key = "info") {
            SystemInfoCard()
        }

        item(key = "bottom_spacer") {
            Spacer(Modifier.height(ContentBottomInset))
        }
    }
}


// ── 左侧 OKK 独立品牌卡片 ──

@Composable
private fun OkkBrandCard(modifier: Modifier = Modifier) {
    val accentColor = MiuixTheme.colorScheme.primary

    Card(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(accentColor.copy(alpha = 0.07f))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 放大比例的 OKK 标题
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "OKK",
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            color = accentColor,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "YES",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = accentColor
                            )
                        }
                    }
                    Text(
                        text = "OKK Enhancement",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor.copy(alpha = 0.7f)
                    )
                }

                // 状态指示
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check_circle,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "模块已激活",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "v$OKK_VERSION · 即时生效",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

// ── 右侧统计小方块卡片 ──

@Composable
private fun CountCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    value: String,
    label: String,
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ── 设备信息卡 ──

@Composable
private fun SystemInfoCard() {
    val context = LocalContext.current
    val wxVer = remember {
        runCatching { com.OKK.yes.core.compat.WeChatVersion.resolve(context)?.summary() }.getOrNull()
            ?: "未检测"
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
    // 读取 wxid（如有）
    val wxid = remember {
        runCatching {
            val sp = context.getSharedPreferences("achat_config", android.content.Context.MODE_PRIVATE)
            sp.getString("self_wx_id", null)
        }.getOrNull()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!wxid.isNullOrBlank()) {
                InfoRow(
                    icon = { Icon(MaterialSymbols.Outlined.Person, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                    title = "微信 ID",
                    content = wxid,
                    showDivider = true,
                )
            }
            InfoRow(
                icon = { Icon(MaterialSymbols.Outlined.Smartphone, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                title = "微信版本",
                content = wxVer,
                showDivider = true,
            )
            InfoRow(
                icon = { Icon(MaterialSymbols.Outlined.Build_circle, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                title = "模块版本",
                content = "v$OKK_VERSION (Build $modBuild)",
                showDivider = true,
            )
            InfoRow(
                icon = { Icon(MaterialSymbols.Outlined.Phone_android, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                title = "Android 版本",
                content = "${Build.VERSION.RELEASE.orEmpty().ifEmpty { "未知" }} (API ${Build.VERSION.SDK_INT})",
                showDivider = true,
            )
            InfoRow(
                icon = { Icon(MaterialSymbols.Outlined.Smartphone, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                title = "设备型号",
                content = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "未知" },
                showDivider = true,
            )
            InfoRow(
                icon = { Icon(MaterialSymbols.Outlined.Smartphone, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                title = "系统架构",
                content = Build.SUPPORTED_ABIS?.joinToString(", ")?.ifEmpty { "未知" } ?: "未知",
                showDivider = false,
            )
        }
    }
}

// ── 信息行（带分割线）──

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    title: String,
    content: String,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.size(24.dp)) {
                icon()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = content,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 54.dp)
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)),
            )
        }
    }
}
