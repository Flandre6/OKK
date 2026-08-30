package com.OKK.yes.core.hooks.ui.wekit

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.OKK.yes.core.hooks.ConversationGroupConfig
import com.OKK.yes.core.hooks.ContactDisplayNames

@Composable
fun GroupEditorContent(
    group: ConversationGroupConfig.ChatGroup?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (ConversationGroupConfig.ChatGroup) -> Unit,
    onOpenContactSelector: (selectedWxIds: Set<String>, onConfirmed: (Set<String>) -> Unit) -> Unit
) {
    var name by remember(group) { mutableStateOf(group?.name ?: "") }
    var members by remember(group) { mutableStateOf(group?.members?.toSet().orEmpty()) }
    var type by remember(group) { mutableStateOf(group?.type ?: "MANUAL") }
    var whereClause by remember(group) { mutableStateOf(group?.whereClause ?: "") }
    var typeExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (group != null) "编辑分组" else "新建分组",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分组名称") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "归拢模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { typeExpanded = !typeExpanded }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = when (type) {
                                "MANUAL" -> "手动选择"
                                "PRESET_UNREAD" -> "自动所有未读"
                                "PRESET_FRIENDS" -> "自动所有好友"
                                "PRESET_GROUPS" -> "自动所有群聊"
                                "PRESET_SERVICES" -> "自动所有服务号"
                                "PRESET_OFFICIALS" -> "自动所有公众号"
                                "PRESET_OPENIM" -> "自动所有企业微信"
                                "SQL" -> "自定义 SQL 规则"
                                else -> "手动选择"
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = if (typeExpanded) "▲" else "▼",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = typeExpanded) {
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            listOf(
                                "MANUAL" to "手动选择",
                                "PRESET_UNREAD" to "自动所有未读",
                                "PRESET_FRIENDS" to "自动所有好友",
                                "PRESET_GROUPS" to "自动所有群聊",
                                "PRESET_SERVICES" to "自动所有服务号",
                                "PRESET_OFFICIALS" to "自动所有公众号",
                                "PRESET_OPENIM" to "自动所有企业微信",
                                "SQL" to "自定义 SQL 规则"
                            ).forEach { (modeKey, modeTitle) ->
                                val isSelected = type == modeKey
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            type = modeKey
                                            typeExpanded = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = modeTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) Color(0xFF07C160) else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSelected) {
                                        Text("✓", color = Color(0xFF07C160), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            when (type) {
                "MANUAL" -> {
                    Text(
                        text = "已选择 ${members.size} 个对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            onOpenContactSelector(members) { updated ->
                                members = updated
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("选择 / 管理对话")
                    }
                }
                "SQL" -> {
                    OutlinedTextField(
                        value = whereClause,
                        onValueChange = { whereClause = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("WHERE 条件") },
                        placeholder = { Text("例如：rconversation.unReadCount > 0") },
                        singleLine = false,
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                else -> {
                    val label = when (type) {
                        "PRESET_UNREAD" -> "未读"
                        "PRESET_FRIENDS" -> "好友"
                        "PRESET_GROUPS" -> "群聊"
                        "PRESET_SERVICES" -> "服务号"
                        "PRESET_OFFICIALS" -> "公众号"
                        "PRESET_OPENIM" -> "企业微信"
                        else -> ""
                    }
                    Text(
                        text = "自动归拢所有${label}对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("删除")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                TextButton(onClick = onDismiss) {
                    Text("取消")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        val groupId = group?.id ?: "custom_${System.currentTimeMillis()}"
                        val newGroup = ConversationGroupConfig.ChatGroup(
                            id = groupId,
                            name = name.ifBlank { "新建分组" },
                            members = members.toList(),
                            type = type,
                            whereClause = whereClause
                        )
                        onSave(newGroup)
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160))
                ) {
                    Text("确定", color = Color.White)
                }
            }
        }
    }
}
