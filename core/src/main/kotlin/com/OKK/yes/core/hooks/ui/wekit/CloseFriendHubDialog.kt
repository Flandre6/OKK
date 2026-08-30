package com.OKK.yes.core.hooks.ui.wekit

import android.app.Activity
import android.content.Context
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.OKK.yes.core.hooks.CloseFriendManagerUi
import com.OKK.yes.core.hooks.CloseFriendStore
import com.OKK.yes.core.hooks.WeChatAvatarHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 密友私密中枢弹窗（长按主页顶部标题唤起）。
 *
 * 核心功能：
 *  1. 密友未读与最新消息实时聚合展示；
 *  2. 点击直接打开微信聊天窗口（ChattingUI）；
 *  3. 长按查看资料页（ContactInfoUI）或移出密友；
 *  4. 右上角「+」快速添加新密友；
 *  5. 一键「临时显现」模式（倒计时自动恢复隐藏）。
 */
object CloseFriendHubDialog {

    fun show(context: Context) {
        showComposeDialog(context, directlyDismissable = true) {
            CloseFriendHubContent(onDismiss = { dialog.dismiss() })
        }
    }
}

@Composable
fun CloseFriendHubContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    var details by remember { mutableStateOf<List<CloseFriendStore.CloseFriendDetail>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isTempUnhidden by remember { mutableStateOf(CloseFriendStore.isTempUnhidden()) }
    var remainingSeconds by remember { mutableStateOf(CloseFriendStore.getTempUnhideRemainingSeconds()) }
    var selectedForAction by remember { mutableStateOf<CloseFriendStore.CloseFriendDetail?>(null) }

    // 加载密友列表与未读数据
    val reloadData = {
        isLoading = true
        Thread {
            val list = CloseFriendStore.queryCloseFriendDetails()
            (context as? Activity)?.runOnUiThread {
                details = list
                isLoading = false
            }
        }.start()
    }

    LaunchedEffect(Unit) {
        reloadData()
    }

    // 倒计时更新
    LaunchedEffect(isTempUnhidden) {
        while (isTempUnhidden) {
            remainingSeconds = CloseFriendStore.getTempUnhideRemainingSeconds()
            if (remainingSeconds <= 0L) {
                isTempUnhidden = false
                break
            }
            delay(1000L)
        }
    }

    val cardBg = if (darkTheme) Color(0xFF1E1F24) else Color.White
    val surfaceVariant = if (darkTheme) Color(0xFF2B2C33) else Color(0xFFF2F4F7)
    val primaryText = if (darkTheme) Color(0xFFE6EDF5) else Color(0xFF1F2328)
    val secondaryText = if (darkTheme) Color(0xFF8B949E) else Color(0xFF656D76)
    val accentGreen = Color(0xFF07C160)
    val unreadRed = Color(0xFFFA5151)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardBg,
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.82f)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            // ── 顶部 Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "密友中心",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = primaryText
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (darkTheme) Color(0xFF343845) else Color(0xFFE8ECF2))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${details.size}人",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = secondaryText,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 临时显现 Chip 按钮
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isTempUnhidden) accentGreen.copy(alpha = 0.15f)
                                else surfaceVariant
                            )
                            .clickable {
                                val next = !isTempUnhidden
                                CloseFriendStore.setTempUnhidden(next)
                                isTempUnhidden = next
                                if (next) {
                                    Toast.makeText(context, "已临时显现密友（5分钟后自动恢复隐藏）", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "已恢复密友隐藏", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isTempUnhidden) "显现中 ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}"
                            else "临时显现",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isTempUnhidden) accentGreen else secondaryText,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 添加密友按钮
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(accentGreen)
                            .clickable {
                                CloseFriendManagerUi.showMemberSelector(context) {
                                    reloadData()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = surfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // ── 主体密友列表 ──
            if (details.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🤫",
                            fontSize = 44.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "暂无密友",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = primaryText,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击右上角「+」添加密友好友",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = secondaryText
                            )
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(details, key = { it.username }) { item ->
                        CloseFriendItemCard(
                            item = item,
                            darkTheme = darkTheme,
                            primaryText = primaryText,
                            secondaryText = secondaryText,
                            unreadRed = unreadRed,
                            onClick = {
                                CloseFriendStore.openChatting(context, item.username)
                                onDismiss()
                            },
                            onLongClick = {
                                selectedForAction = item
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = surfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // ── 底部提示 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "提示：点击直达聊天 · 长按更多操作",
                    style = MaterialTheme.typography.labelSmall.copy(color = secondaryText)
                )
                TextButton(onClick = onDismiss) {
                    Text(text = "关闭", color = secondaryText)
                }
            }
        }
    }

    // ── 长按密友操作对话框（采用统一 Miuix / OKK 扁平精致风格） ──
    selectedForAction?.let { friend ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { selectedForAction = null }
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = cardBg,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 好友大头像与昵称
                    Box(modifier = Modifier.size(54.dp)) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    WeChatAvatarHelper.bindAvatar(this, friend.username)
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = friend.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = primaryText
                        )
                    )

                    Text(
                        text = friend.username,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = secondaryText,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = surfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    // 操作按钮组（统一胶囊样式，对齐 OKK 设计规范）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. 进入私密聊天 (主操作，微信绿)
                        Button(
                            onClick = {
                                val username = friend.username
                                selectedForAction = null
                                CloseFriendStore.openChatting(context, username)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentGreen)
                        ) {
                            Text("进入私密聊天", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }

                        // 2. 查看详细资料 (次操作，浅色中性按钮)
                        Button(
                            onClick = {
                                val username = friend.username
                                selectedForAction = null
                                CloseFriendStore.openContactInfo(context, username)
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (darkTheme) Color(0xFF2C2D35) else Color(0xFFEAECEF)
                            )
                        ) {
                            Text("查看详细资料", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = primaryText)
                        }

                        // 3. 移出密友名单 (警示操作，红底/红字淡雅按钮)
                        Button(
                            onClick = {
                                val username = friend.username
                                selectedForAction = null
                                CloseFriendStore.remove(username)
                                Toast.makeText(context, "已移出密友：${friend.displayName}", Toast.LENGTH_SHORT).show()
                                reloadData()
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (darkTheme) Color(0xFF382326) else Color(0xFFFDE8E8)
                            )
                        ) {
                            Text("移出密友名单", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = unreadRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloseFriendItemCard(
    item: CloseFriendStore.CloseFriendDetail,
    darkTheme: Boolean,
    primaryText: Color,
    secondaryText: Color,
    unreadRed: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val rowBg = if (darkTheme) Color(0xFF262830) else Color(0xFFF7F8FA)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像容器（带未读红点角标）
        Box(modifier = Modifier.size(46.dp)) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        WeChatAvatarHelper.bindAvatar(this, item.username)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
            )

            // 未读红点
            if (item.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(unreadRed)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 昵称与最新消息摘要
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = if (item.digest.isNotBlank()) item.digest else item.username,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (item.unreadCount > 0) unreadRed else secondaryText
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 右侧箭头指示器
        Text(
            text = "›",
            fontSize = 22.sp,
            color = secondaryText,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
