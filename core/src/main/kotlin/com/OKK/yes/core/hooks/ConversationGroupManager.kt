package com.OKK.yes.core.hooks

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.icu.text.Transliterator
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.OKK.yes.core.hooks.ContactDisplayNames.ContactItem
import com.OKK.yes.core.hooks.ui.StyledDialogs
import com.OKK.yes.core.hooks.ui.wekit.ContactsSelectorContent
import com.OKK.yes.core.hooks.ui.wekit.GroupEditorContent
import com.OKK.yes.core.hooks.ui.wekit.XposedLifecycleOwner
import com.OKK.yes.core.hooks.ui.wekit.setWkLifecycleOwner
import com.OKK.yes.core.hooks.ui.wekit.showComposeDialog
import com.OKK.yes.core.hooks.ui.wekit.theme.WkInjectedTheme
import java.lang.reflect.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 微信头像加载辅助类
 */
object WeChatAvatarHelper {
    private var bindMethod: Method? = null
    private var bindFailed = false

    fun bindAvatar(iv: ImageView, username: String) {
        if (bindFailed) return
        val cl = iv.context.classLoader ?: return
        if (bindMethod == null) {
            val classNames = listOf(
                "com.tencent.mm.pluginsdk.ui.a\$b",
                "com.tencent.mm.pluginsdk.ui.u",
                "com.tencent.mm.pluginsdk.ui.a"
            )
            for (cn in classNames) {
                runCatching {
                    val clazz = Class.forName(cn, false, cl)
                    val m = clazz.declaredMethods.firstOrNull { m ->
                        java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 2 &&
                        ImageView::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                        m.parameterTypes[1] == String::class.java
                    }
                    if (m != null) {
                        m.isAccessible = true
                        bindMethod = m
                        return@runCatching
                    }
                }
            }
            if (bindMethod == null) bindFailed = true
        }
        runCatching {
            bindMethod?.invoke(null, iv, username)
        }
    }
}

/**
 * 分组成员选择器对话框（1:1 照搬 WeKit ContactsSelector UI 与体验）。
 *
 * 核心特征：
 * 1. 真实微信头像（`ImageView` + `WeChatAvatarHelper.bindAvatar`）：真实渲染联系人/群聊/公众号头像。
 * 2. 详细分类 Chips：[🔍 全部 (210)], [👤 好友 (51)], [👥 群聊 (3)], [📢 公众号], [🏢 企业微信], [🏷 其它]
 * 3. 多重排序模式：[Az A-Z] (拼音排序) 与 [🕒 新-旧] (按最近对话时间排序) + [⇅] 顺序切换
 * 4. 三级操作工具栏：[▨ 全选], [▨ 全不选], [➔ 反选]
 * 5. [已选] 置顶与拼音/时间分组列表
 */
object ConversationGroupManager {

    private const val ACCENT = 0xFF07C160.toInt()

    /** 分类枚举 */
    private enum class Category(val displayName: String, val icon: String) {
        FRIENDS("好友", "👤"),
        GROUPS("群聊", "👥"),
        OFFICIALS("公众号", "💬"),
        ENTERPRISE("企业微信", "🏢")
    }

    /** 拼音转换器 */
    private val transliterator by lazy {
        runCatching {
            Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")
        }.getOrNull()
    }

    /** 计算首字母 */
    private fun initialOf(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "#"
        val first = trimmed.first()
        val upper = first.uppercaseChar()
        if (upper in 'A'..'Z') return upper.toString()
        val t = transliterator
        if (t != null) {
            val pinyin = runCatching { t.transliterate(first.toString()) }.getOrNull()
            val c = pinyin?.firstOrNull()?.uppercaseChar() ?: '#'
            if (c in 'A'..'Z') return c.toString()
        }
        return "#"
    }

    /** 精准分类 */


    private val SYSTEM_ACCOUNTS = setOf(
        "filehelper", "qqmail", "floatbottle", "shakeapp", "lbsapp",
        "medianote", "newsapp", "qqfriend", "facebookapp", "masssendapp",
        "meishiapp", "blogapp", "officialaccounts", "helper_entry",
        "voiceinputapp", "linkedinplugin", "googlecontact", "qmessage",
        "weixin", "fmessage", "tmessage", "weibo", "mphelper", "notchatroom",
        "voipapp", "sns_momi", "snsuploadapp", "feedsapp", "qqsync", "voicevoipapp", 
        "appbrand_notify_message", "appbrandcustomerservicemsg", "opencustomerservicemsg", 
        "findfriend_entry", "ringtone", "notification_messages"
    )

    private fun classify(item: ContactItem, cls: ContactDisplayNames.ContactClassification): Category? {
        val u = item.username
        // 企业微信优先（@im.chatroom 同时 endsWith @chatroom，必须先判企业微信）
        if (u.endsWith("@im.chatroom") || u.endsWith("@openim")) return Category.ENTERPRISE
        // 普通群聊
        if (u.endsWith("@chatroom") || cls.groupWxIds.contains(u)) return Category.GROUPS
        // 公众号
        if (u.startsWith("gh_") || cls.officialWxIds.contains(u)) return Category.OFFICIALS
        // 好友（friendWxIds = 已添加的真好友，严格 FRIENDS SQL）
        if (cls.friendWxIds.contains(u)) return Category.FRIENDS
        // 非好友/群聊/公众号/企业微信 -> 不显示
        return null
    }

    /**
     * 打开成员管理对话框（1:1 对标 WeKit 样式）。
     */
    fun showMemberManager(
        ctx: Context,
        group: ConversationGroupConfig.ChatGroup,
        onSaved: () -> Unit
    ) {
        showMemberSelectorCompose(ctx, group.members.toSet()) { selectedWxIds ->
            val updated = group.copy(members = selectedWxIds.toList())
            val groups = ConversationGroupConfig.loadGroups()
            val custom = groups.filter { !it.isPreset() }.toMutableList()
            val idx = custom.indexOfFirst { it.id == group.id }
            if (idx >= 0) custom[idx] = updated else custom.add(updated)
            ConversationGroupConfig.saveGroups(custom)
            onSaved()
        }
    }

    /**
     * 联系人行：使用真实微信头像 ImageView + WeChatAvatarHelper.bindAvatar
     */
    private fun contactRow(
        ctx: Context,
        night: Boolean,
        primaryTxt: Int,
        item: ContactItem,
        selected: MutableSet<String>,
        onToggle: () -> Unit
    ): View {
        val subTxt = if (night) 0xFF9A9A9A.toInt() else 0xFF7A7A7A.toInt()
        val isChecked = item.username in selected
        val accentBg = (0x18 shl 24) or (ACCENT and 0xFFFFFF)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(if (isChecked) accentBg else Color.TRANSPARENT)
            }
        }
        val cb = CheckBox(ctx).apply {
            this.isChecked = isChecked
            isClickable = false
            isFocusable = false
            runCatching {
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ACCENT, if (night) 0xFF5A5A5A.toInt() else 0xFFBDBDBD.toInt())
                )
            }
        }

        // 真实微信头像 ImageView
        val avatarIv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 8).toFloat()
                setColor((0x20 shl 24) or (ACCENT and 0xFFFFFF))
            }
            clipToOutline = true
        }

        // 绑定真实微信头像
        WeChatAvatarHelper.bindAvatar(avatarIv, item.username)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 10), 0, 0, 0)
        }
        val name = TextView(ctx).apply {
            text = item.displayName
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(primaryTxt)
            typeface = if (isChecked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val sub = TextView(ctx).apply {
            text = when {
                item.alias.isNotBlank() -> item.alias
                item.username.startsWith("wxid_") -> item.username
                else -> item.username
            }
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(subTxt)
        }
        textCol.addView(name)
        textCol.addView(sub)

        row.addView(cb, ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32))
        row.addView(avatarIv, dp(ctx, 38), dp(ctx, 38))
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.setOnClickListener {
            if (item.username in selected) selected.remove(item.username)
            else selected.add(item.username)
            onToggle()
        }

        return row
    }

    private fun findActivity(ctx: Context): android.app.Activity? {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * 打开分组编辑器对话框 (Compose 1:1 风格)
     */
    fun showGroupEditorDialog(
        ctx: Context,
        targetGroup: ConversationGroupConfig.ChatGroup?,
        onSaved: () -> Unit
    ) {
        val act = findActivity(ctx) ?: ctx
        showComposeDialog(act) {
            GroupEditorContent(
                group = targetGroup,
                onDismiss = { onDismiss() },
                onDelete = if (targetGroup != null && !targetGroup.isPreset()) {
                    {
                        StyledDialogs.confirm(act, "删除分组", "确定删除「${targetGroup.name}」分组吗？", okLabel = "删除", danger = true) {
                            val groups = ConversationGroupConfig.loadGroups()
                            val custom = groups.filter { !it.isPreset() && it.id != targetGroup.id }
                            ConversationGroupConfig.saveGroups(custom)
                            onDismiss()
                            onSaved()
                        }
                    }
                } else null,
                onSave = { updatedGroup ->
                    val groups = ConversationGroupConfig.loadGroups()
                    val custom = groups.filter { !it.isPreset() }.toMutableList()
                    if (targetGroup != null) {
                        val idx = custom.indexOfFirst { it.id == targetGroup.id }
                        if (idx >= 0) custom[idx] = updatedGroup else custom.add(updatedGroup)
                    } else {
                        custom.add(updatedGroup)
                    }
                    ConversationGroupConfig.saveGroups(custom)
                    onDismiss()
                    onSaved()
                },
                onOpenContactSelector = { currentSelected, onConfirmed ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        showMemberSelectorCompose(act, currentSelected, onConfirmed)
                    }
                }
            )
        }
    }

    private fun showMemberSelectorCompose(
        ctx: Context,
        initialSelected: Set<String>,
        onConfirmed: (Set<String>) -> Unit
    ) {
        val act = findActivity(ctx) ?: ctx
        showComposeDialog(act) {
            // 异步加载数据源：避免在主线程执行重 SQL 查询（rcontact/chatroom 全表）导致卡顿、ANR 甚至闪退。
            var loading by remember { mutableStateOf(true) }
            var allContacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }

            LaunchedEffect(Unit) {
                allContacts = withContext(Dispatchers.IO) {
                    val cls = runCatching { ContactDisplayNames.queryClassification() }
                        .getOrDefault(ContactDisplayNames.ContactClassification(emptySet(), emptySet(), emptySet()))
                    runCatching { ContactDisplayNames.queryAllContactsDetailed() }
                        .getOrDefault(emptyList())
                        .filter { classify(it, cls) != null }
                }
                loading = false
            }

            if (loading) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在加载联系人…", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                ContactsSelectorContent(
                    title = "选择对话",
                    allContacts = allContacts,
                    initialSelectedWxIds = initialSelected,
                    onDismiss = { onDismiss() },
                    onConfirm = { selected ->
                        onConfirmed(selected)
                        onDismiss()
                    }
                )
            }
        }
    }

    private fun title(ctx: Context, s: String, color: Int): TextView =
        TextView(ctx).apply {
            text = s
            textSize = 18f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun sectionLabel(ctx: Context, s: String, night: Boolean): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 6))

            val bar = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 2).toFloat()
                    setColor(ACCENT)
                }
            }
            addView(bar, LinearLayout.LayoutParams(dp(ctx, 4), dp(ctx, 13)))

            val tv = TextView(ctx).apply {
                text = s
                textSize = 13f
                setTextColor(if (night) 0xFFD0D7DE.toInt() else 0xFF333333.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(ctx, 8), 0, 0, 0)
            }
            addView(tv)
        }

    private fun pillChip(ctx: Context, s: String, isSel: Boolean, night: Boolean): TextView =
        TextView(ctx).apply {
            text = s
            textSize = 13f
            gravity = Gravity.CENTER
            val selBg = if (night) 0xFF354576.toInt() else 0xFFDBE3FB.toInt()
            val selTxt = if (night) 0xFFE6EEFF.toInt() else 0xFF344579.toInt()
            val unselBg = if (night) 0xFF26282E.toInt() else Color.WHITE
            val unselTxt = if (night) 0xFFA0AEC0.toInt() else 0xFF4A5568.toInt()
            val unselBorder = if (night) 0xFF383C45.toInt() else 0xFFE2E4E8.toInt()

            setTextColor(if (isSel) selTxt else unselTxt)
            typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(if (isSel) selBg else unselBg)
                if (!isSel) setStroke(dp(ctx, 1), unselBorder)
            }
            setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        }

    private fun iconChip(ctx: Context, s: String, night: Boolean): TextView =
        TextView(ctx).apply {
            text = s
            textSize = 14f
            setTextColor(if (night) 0xFFA0AEC0.toInt() else 0xFF4A5568.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(if (night) 0xFF26282E.toInt() else Color.WHITE)
                setStroke(dp(ctx, 1), if (night) 0xFF383C45.toInt() else 0xFFE2E4E8.toInt())
            }
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
        }

    private fun ghostButton(ctx: Context, label: String, txtColor: Int, bg: Int): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(txtColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(bg)
            }
            setPadding(dp(ctx, 20), dp(ctx, 9), dp(ctx, 20), dp(ctx, 9))
        }

    private fun solidButton(ctx: Context, label: String): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(ACCENT)
            }
            setPadding(dp(ctx, 22), dp(ctx, 9), dp(ctx, 22), dp(ctx, 9))
        }

    /**
     * 弹出预设标签开关管理对话框
     */
    fun showPresetTagsDialog(ctx: Context, onUpdated: () -> Unit) {
        val night = isNight(ctx)
        val dialogBg = if (night) 0xFF1E1E1E.toInt() else Color.WHITE
        val primaryTxt = if (night) 0xFFE0E0E0.toInt() else 0xFF191919.toInt()
        val subTxt = if (night) 0xFF909090.toInt() else 0xFF666666.toInt()
        val divider = if (night) 0xFF2D2D2D.toInt() else 0xFFEBEBEB.toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 16))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(dialogBg)
            }
        }

        val titleTv = TextView(ctx).apply {
            text = "预设标签显示"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primaryTxt)
        }
        root.addView(titleTv)

        val descTv = TextView(ctx).apply {
            text = "开启或关闭要在首页顶栏显示的预设标签"
            textSize = 13f
            setTextColor(subTxt)
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 16))
        }
        root.addView(descTv)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val tags = ConversationGroupConfig.ALL_PRESET_TAGS

        tags.forEachIndexed { idx, tag ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
            }

            val textLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(ctx).apply {
                text = tag.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(primaryTxt)
            }
            val subDescTv = TextView(ctx).apply {
                text = tag.desc
                textSize = 12f
                setTextColor(subTxt)
            }
            textLayout.addView(nameTv)
            textLayout.addView(subDescTv)
            row.addView(textLayout)

            val switchBtn = android.widget.Switch(ctx).apply {
                isChecked = ConversationGroupConfig.isPresetEnabled(tag.id)
                isEnabled = tag.canToggle
                setOnCheckedChangeListener { _, isChecked ->
                    ConversationGroupConfig.setPresetEnabled(tag.id, isChecked)
                }
            }
            row.addView(switchBtn)

            container.addView(row)

            if (idx < tags.size - 1) {
                val line = View(ctx).apply {
                    setBackgroundColor(divider)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
                }
                container.addView(line)
            }
        }

        val scroll = ScrollView(ctx).apply {
            addView(container)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 16), 0, 0)
        }

        var dlg: AlertDialog? = null

        val okBtn = solidButton(ctx, "完成").apply {
            setOnClickListener {
                dlg?.dismiss()
                onUpdated()
            }
        }
        btnRow.addView(okBtn)
        root.addView(btnRow)

        dlg = AlertDialog.Builder(ctx)
            .setView(root)
            .setCancelable(true)
            .create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }
    }

    private fun isNight(ctx: Context): Boolean =
        runCatching {
            StyledDialogs.isNight(ctx)
        }.getOrDefault(false)

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}