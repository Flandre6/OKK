// OKK Compose 设置 UI 自描述功能元数据目录
// 对齐 Nuke x50/xz0 架构：按分类自描述、带依赖声明、配置键绑定与全局统计能力。

package com.OKK.yes.loader.ui

import com.OKK.yes.core.hooks.BottomTabConfig
import com.OKK.yes.core.hooks.DownloadRedirectHook
import com.OKK.yes.core.hooks.FinderVideoDownloadHook
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.hooks.ThemeWallpaperConfig
import com.OKK.yes.core.hooks.CloseFriendStore

/** 功能分类（5 大类：聊天 / 朋友圈 / 美化 / 增强 / Java脚本） */
enum class FeatureCategory(val title: String, val summary: String) {
    Chat("聊天", "防撤回、手势引用、精细时间与聊天统计"),
    Moments("朋友圈", "朋友圈与动态防删、防撤回与广告拦截"),
    Beauty("美化", "会话分组、底栏胶囊、主题壁纸与界面细节"),
    Assist("增强", "虚拟定位、PC 自动登录、热更新屏蔽与文件分流"),
    Script("Java脚本", "执行 Java 脚本，支持多脚本动态加载与独立启停")
}

/**
 * 一个自描述功能定义模型（借鉴 Nuke x50 抽象）。
 * @param key ConfigStore 中的全局布尔配置键
 * @param title 功能显示标题
 * @param summary 功能详细说明
 * @param category 所属分类
 * @param defaultOn 默认开启状态
 * @param hasConfig 是否具备二级参数配置 Dialog
 * @param badge 徽章提示（可选，如 "实验性" / "推荐"）
 * @param badgeWarning 徽章是否显示为警告黄色/红色
 * @param dependsOn 依赖的前置功能 key 集合
 */
data class FeatureDescriptor(
    val key: String,
    val title: String,
    val summary: String,
    val category: FeatureCategory,
    val defaultOn: Boolean = false,
    val hasConfig: Boolean = false,
    val badge: String? = null,
    val badgeWarning: Boolean = false,
    val dependsOn: Set<String> = emptySet()
)

typealias CategoryFeature = FeatureDescriptor

object FeatureCatalog {

    private val chatFeatures = listOf(
        FeatureDescriptor("anti_revoke", "防撤回", "拦截撤回指令，保留原消息与撤回标记", FeatureCategory.Chat, defaultOn = true, hasConfig = true, badge = "核心"),
        FeatureDescriptor("block_typing_report", "禁止输入状态", "输入文字时不向对方显示“正在输入...”状态", FeatureCategory.Chat, defaultOn = true),
        FeatureDescriptor("swipe_quote", "滑动手势引用", "消息左滑快速引用回复", FeatureCategory.Chat, defaultOn = true),
        FeatureDescriptor("quote_delete_clear", "删除键清引用", "输入框为空时按删除键取消引用", FeatureCategory.Chat, defaultOn = false),
        FeatureDescriptor("swipe_delete_keep", "左滑删除存记录", "会话列表左滑删除仅在列表移除，保留聊天记录", FeatureCategory.Chat, defaultOn = true),
        FeatureDescriptor("edit_message", "长按修改消息", "仅修改本地显示文本，对方不可见", FeatureCategory.Chat, defaultOn = false),
        FeatureDescriptor("member_title", "群员头衔", "群昵称旁显示群主/管理员/成员徽章", FeatureCategory.Chat, defaultOn = false, hasConfig = true),
        FeatureDescriptor("real_name_tail", "实名信息", "群聊/个人聊天界面补充显示实名", FeatureCategory.Chat, defaultOn = false),
        FeatureDescriptor("detail_enabled", "消息底部时间", "消息旁显示精细发送时间", FeatureCategory.Chat, defaultOn = true, hasConfig = true),
        FeatureDescriptor("input_stats_enabled", "输入框统计", "输入框实时统计当日已发消息数", FeatureCategory.Chat, defaultOn = true, hasConfig = true),
        FeatureDescriptor("chat_toolbar_enabled", "聊天工具栏", "聊天输入框+号面板自定义，重新排序或隐藏工具", FeatureCategory.Chat, defaultOn = true, hasConfig = true)
    )

    private val momentsFeatures = listOf(
        FeatureDescriptor("anti_moments_delete", "朋友圈防删除", "好友动态删除后本地仍可见", FeatureCategory.Moments, defaultOn = true),
        FeatureDescriptor("anti_moments_comment_revoke", "朋友圈评论防撤回", "对方删除评论后本地保留", FeatureCategory.Moments, defaultOn = true),
        FeatureDescriptor("remove_moments_ads", "去除朋友圈广告", "自动拦截朋友圈 ADInfo 广告", FeatureCategory.Moments, defaultOn = false)
    )

    private val beautyFeatures = listOf(
        FeatureDescriptor("conversation_grouping_enabled", "会话分组", "微信首页长按标签支持新建、预设标签开关、排序与编辑", FeatureCategory.Beauty, defaultOn = true, hasConfig = false),
        FeatureDescriptor(BottomTabConfig.KEY_HIDE_BAR, "默认底栏", "隐藏原生底栏标题或整个底栏", FeatureCategory.Beauty, defaultOn = false, hasConfig = true),
        FeatureDescriptor(BottomTabConfig.KEY_FLOATING, "悬浮底栏", "圆角胶囊底栏与四个自定义标题", FeatureCategory.Beauty, defaultOn = false, hasConfig = true),
        FeatureDescriptor("floating_quick_entry", "悬浮快捷入口", "首页右下角悬浮按钮，快捷打开常用功能", FeatureCategory.Beauty, defaultOn = true, hasConfig = true),
        FeatureDescriptor("fold_banner_fixed", "折叠置顶固定", "固定首页折叠置顶聊天列表项", FeatureCategory.Beauty, defaultOn = false),
        FeatureDescriptor("hide_home_divider", "屏蔽首页分割线", "隐藏会话行之间的细分割线", FeatureCategory.Beauty, defaultOn = false),
        FeatureDescriptor("conv_card_enabled", "会话列表美化", "会话左右两侧回缩边距与圆角美化", FeatureCategory.Beauty, defaultOn = true, hasConfig = true),
        FeatureDescriptor("profile_id", "资料页显示 ID", "联系人与群资料页补充显示 wxid", FeatureCategory.Beauty, defaultOn = false),
        FeatureDescriptor("home_avatar_entry", "侧边栏", "右滑开启侧边栏", FeatureCategory.Beauty, defaultOn = true),
        FeatureDescriptor("bubble_enabled", "气泡皮肤", "使用自定义 9.png 气泡皮肤", FeatureCategory.Beauty, defaultOn = false, hasConfig = true),
        FeatureDescriptor("round_avatar_enabled", "圆形头像", "自定义头像圆角度（方圆/全圆）", FeatureCategory.Beauty, defaultOn = false, hasConfig = true),
        FeatureDescriptor(ThemeWallpaperConfig.KEY_ENABLED, "主题壁纸", "主界面/设置背景壁纸与透明度", FeatureCategory.Beauty, defaultOn = false, hasConfig = true),
        FeatureDescriptor("auto_dpi_scaling_enabled", "DPI 自适应", "在大屏幕或高 DPI 设备上等比例自适应缩放界面与字体", FeatureCategory.Beauty, defaultOn = true, hasConfig = false)
    )

    private val assistFeatures = listOf(
        FeatureDescriptor(CloseFriendStore.KEY_ENABLED, "密友", "长按通讯录好友行设为密友，在通讯录和主页将其隐藏", FeatureCategory.Assist, defaultOn = false, hasConfig = true),
        FeatureDescriptor("system_camera_enabled", "直调系统相机", "微信内拍照录像直接调用系统相机应用", FeatureCategory.Assist, defaultOn = false, hasConfig = false),
        FeatureDescriptor("remove_call_limits_enabled", "移除通话限制", "突破语音视频通话时无法录音录像或占用等限制", FeatureCategory.Assist, defaultOn = true, hasConfig = false),
        FeatureDescriptor("virtual_location_enabled", "虚拟定位", "改写腾讯定位服务经纬度", FeatureCategory.Assist, defaultOn = false, hasConfig = true),
        FeatureDescriptor("auto_login_win_enabled", "PC 自动登录", "登录确认页自动勾选并提交", FeatureCategory.Assist, defaultOn = false, hasConfig = true),
        FeatureDescriptor("disable_hot_update", "屏蔽热更新", "禁用 Tinker 补丁热更新", FeatureCategory.Assist, defaultOn = false),
        FeatureDescriptor(DownloadRedirectHook.KEY_ENABLED, "下载重定向", "微信接收的聊天文件保存到自定义目录", FeatureCategory.Assist, defaultOn = false, hasConfig = true),
        FeatureDescriptor(FinderVideoDownloadHook.KEY_ENABLED, "视频号下载", "视频号分享菜单添加复制链接/下载", FeatureCategory.Assist, defaultOn = true, hasConfig = true)
    )

    private val scriptFeatures = listOf(
        FeatureDescriptor("java_plugin_enabled", "Java脚本", "执行 Java 脚本，支持多脚本动态加载与独立启停", FeatureCategory.Script, defaultOn = false, hasConfig = false, badge = "引擎")
    )

    val categories: List<Pair<FeatureCategory, List<FeatureDescriptor>>> = listOf(
        FeatureCategory.Chat to chatFeatures,
        FeatureCategory.Moments to momentsFeatures,
        FeatureCategory.Beauty to beautyFeatures,
        FeatureCategory.Assist to assistFeatures,
        FeatureCategory.Script to scriptFeatures
    )

    val allFeatures: List<FeatureDescriptor> = categories.flatMap { it.second }

    fun getCategoryFeatures(category: FeatureCategory): List<FeatureDescriptor> {
        return categories.firstOrNull { it.first == category }?.second.orEmpty()
    }

    fun isFeatureOn(key: String, defaultOn: Boolean): Boolean {
        return runCatching { PublicConfigStore.getBoolean(key, defaultOn) }.getOrDefault(defaultOn)
    }

    fun setFeatureOn(key: String, on: Boolean) {
        runCatching { PublicConfigStore.putBoolean(key, on, true) }
    }

    fun getCategoryOnCount(category: FeatureCategory): Int {
        return getCategoryFeatures(category).count { isFeatureOn(it.key, it.defaultOn) }
    }

    fun getTotalOnCount(): Int {
        return allFeatures.count { isFeatureOn(it.key, it.defaultOn) }
    }

    fun batchSetCategory(category: FeatureCategory, enabled: Boolean) {
        getCategoryFeatures(category).forEach { feat ->
            setFeatureOn(feat.key, enabled)
        }
    }

    fun batchResetCategory(category: FeatureCategory) {
        getCategoryFeatures(category).forEach { feat ->
            setFeatureOn(feat.key, feat.defaultOn)
        }
    }
}
