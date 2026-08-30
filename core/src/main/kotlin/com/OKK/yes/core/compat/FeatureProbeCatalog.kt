package com.OKK.yes.core.compat

/**
 * 与 HookEntry 安装名一一对应的探针表。
 * FAIL → 不安装对应 Hook；PARTIAL / OK → 安装。
 *
 * 所有探针优先使用 DexKit 字符串锚点（methodByStrings / classByStrings），
 * 确保在不同混淆批次下也能正确检测，fallback 为稳定类名 classExists。
 */
object FeatureProbeCatalog {

    /** 与 [all] 顺序一致的展示名（进度条 1/N 用） */
    val titles: List<String> = listOf(
        "设置入口", "加号菜单入口", "隐藏首页分隔线", "折叠顶栏", "底栏图标", "悬浮底栏",
        "圆角头像", "首页头像入口", "侧边栏", "悬浮快捷入口", "主题壁纸", "防撤回", "聊天增强",
        "禁止输入状态", "删除清引用", "修改消息", "输入统计", "朋友圈防删", "朋友圈评论防撤回",
        "朋友圈广告", "资料页 ID", "实名尾字", "群员头衔", "禁用热更新",
        "虚拟定位", "PC 自动登录", "系统相机", "通话无限制", "聊天工具栏", "会话列表卡片",
        "密友功能", "地图选点", "下载重定向", "会话分组", "左滑删除存记录",
        "视频号下载", "Java脚本"
    )

    fun titleOf(probe: FeatureProbe, index: Int): String =
        titles.getOrNull(index) ?: "功能${index + 1}"

    val all: List<FeatureProbe> = listOf(
        p("SettingsEntry", "设置入口") { ctx ->
            // 设置页类名高度稳定，classExists 即可
            when {
                ctx.classExists(WeChatClassNames.SETTING_GROUP_PERSONAL) &&
                    ctx.classExists(WeChatClassNames.MAIN_SETTINGS_UI) ->
                    OK("MainSettingsUI + PersonalInfo")
                ctx.classExists(WeChatClassNames.MAIN_SETTINGS_UI) ||
                    ctx.classExists(WeChatClassNames.LEGACY_SETTINGS_UI) ->
                    PARTIAL("仅部分设置页类")
                else -> FAIL("未找到设置页类")
            }
        },
        p("SettingsEntryPlus", "加号菜单入口") { ctx ->
            // SettingsEntryHook 注入设置页/加号菜单入口，DexKit 字符串锚点全版本命中
            when {
                ctx.methodByStrings("MicroMsg.SettingDataSource") ||
                    ctx.classByStrings("MicroMsg.SettingDataSource") ->
                    OK("SettingDataSource 特征")
                ctx.classExists(WeChatClassNames.MAIN_SETTINGS_UI) ||
                    ctx.classExists(WeChatClassNames.LEGACY_SETTINGS_UI) ->
                    PARTIAL("仅设置页类")
                else -> FAIL("未找到加号菜单锚点")
            }
        },
        // ── 以下 13 个探针统一使用 DexKit 字符串锚点为主路径 ────────────────
        p("HideHomeDivider", "隐藏首页分隔线") { ctx ->
            when {
                ctx.methodByStrings("MicroMsg.LauncherUI") ||
                    ctx.classExists(WeChatClassNames.LAUNCHER_UI) ||
                    ctx.classByStrings("MicroMsg.LauncherUI") ->
                    OK("LauncherUI")
                else -> FAIL("无 LauncherUI")
            }
        },
        p("FoldBannerPin", "折叠顶栏") { ctx ->
            when {
                ctx.methodByStrings("MicroMsg.LauncherUI") ||
                    ctx.classExists(WeChatClassNames.LAUNCHER_UI) ||
                    ctx.classByStrings("MicroMsg.LauncherUI") ->
                    OK("LauncherUI")
                else -> FAIL("无 LauncherUI")
            }
        },
        p("BottomTabIcon", "底栏图标") { ctx ->
            when {
                ctx.methodByStrings("MicroMsg.LauncherUIBottomTabView") ||
                    ctx.classByStrings("MicroMsg.LauncherUIBottomTabView") ||
                    ctx.classExists("com.tencent.mm.ui.LauncherUIBottomTabView") ||
                    ctx.classExists("com.tencent.mm.ui.MainTabUI") ->
                    OK("底栏类")
                ctx.methodByStrings("MicroMsg.LauncherUI") ||
                    ctx.classExists(WeChatClassNames.LAUNCHER_UI) ->
                    PARTIAL("仅 LauncherUI")
                else -> FAIL("无底栏锚点")
            }
        },
        p("BottomTabFloating", "悬浮底栏") { ctx ->
            when {
                ctx.methodByStrings("updateMainTabUnread", "MicroMsg.LauncherUITabView") ||
                    ctx.methodByStrings("[updateFriendTabUnread]") ||
                    ctx.classExists("com.tencent.mm.ui.MainTabUI") ||
                    ctx.classExists(WeChatClassNames.LAUNCHER_UI) ->
                    OK("主界面")
                ctx.methodByStrings("MicroMsg.LauncherUI") ->
                    PARTIAL("仅 LauncherUI 特征")
                else -> FAIL("无主界面锚点")
            }
        },
        p("RoundAvatar", "圆角头像") { _ ->
            OK("通用 Bitmap 路径")
        },
        p("HomeAvatar", "首页头像入口") { ctx ->
            val launcher = ctx.methodByStrings("MicroMsg.LauncherUI") ||
                ctx.classExists(WeChatClassNames.LAUNCHER_UI) ||
                ctx.classByStrings("MicroMsg.LauncherUI")
            val self = ctx.classByStrings("MicroMsg.ConfigStorageLogic") ||
                ReflectCompat.findClass("iy0.z1", ctx.classLoader) != null ||
                ctx.classByStrings("get userinfo fail")
            when {
                launcher && self -> OK("LauncherUI + 用户信息")
                launcher -> PARTIAL("有主界面，用户信息特征弱")
                else -> FAIL("无主界面")
            }
        },
        p("HomeSideDrawer", "侧边栏") { ctx ->
            // 侧边栏由 HomeAvatar 入口打开：LauncherUI + 用户信息特征齐备即认为可注入
            val launcher = ctx.methodByStrings("MicroMsg.LauncherUI") ||
                ctx.classExists(WeChatClassNames.LAUNCHER_UI) ||
                ctx.classByStrings("MicroMsg.LauncherUI")
            val self = ctx.classByStrings("MicroMsg.ConfigStorageLogic") ||
                ctx.classByStrings("get userinfo fail")
            when {
                launcher && self -> OK("LauncherUI + 用户信息")
                launcher -> PARTIAL("有主界面，用户信息特征弱")
                else -> FAIL("无主界面")
            }
        },
        p("FloatingQuickEntry", "悬浮快捷入口") { ctx ->
            val launcher = ctx.classExists(WeChatClassNames.LAUNCHER_UI) ||
                ctx.classExists("com.tencent.mm.ui.MainTabUI") ||
                ctx.classByStrings("MicroMsg.LauncherUI.MainTabUI")
            when {
                launcher -> OK("LauncherUI/MainTabUI 主界面")
                else -> FAIL("无主界面")
            }
        },
        p("ThemeWallpaper", "主题壁纸") { ctx ->
            when {
                ctx.methodByStrings("MicroMsg.LauncherUI") ||
                    ctx.classExists(WeChatClassNames.LAUNCHER_UI) ||
                    ctx.classByStrings("MicroMsg.LauncherUI") ->
                    OK("LauncherUI")
                else -> FAIL("无 LauncherUI")
            }
        },
        p("AntiRevoke", "防撤回") { ctx ->
            when {
                ctx.methodByStrings("doRevokeMsg xmlSrvMsgId=") ||
                    ctx.methodByStrings(WeChatClassNames.DO_REVOKE_LOG) ->
                    OK("doRevokeMsg 特征")
                ReflectCompat.findClass(WeChatClassNames.Obfuscated69.DO_REVOKE, ctx.classLoader) != null ->
                    PARTIAL("仅混淆兜底类")
                else -> FAIL("未找到撤回入口")
            }
        },
        p("ChatEnhance", "聊天增强") { ctx ->
            val footer = ctx.methodByStrings("MicroMsg.ChatFooter") ||
                ctx.classExists(WeChatClassNames.CHAT_FOOTER) ||
                ctx.classByStrings("MicroMsg.ChatFooter")
            val text = ctx.methodByStrings("MicroMsg.MMNeatTextView") ||
                ctx.classExists(WeChatClassNames.MM_NEAT_TEXT) ||
                ctx.classExists(WeChatClassNames.X2C_TEXT) ||
                ctx.classByStrings("MicroMsg.MMNeatTextView")
            when {
                footer && text -> OK("ChatFooter + 文本")
                footer || text -> PARTIAL("聊天控件不完整")
                else -> FAIL("无聊天锚点")
            }
        },
        p("BlockTypingReport", "禁止输入状态") { ctx ->
            // 锚点基于 WeKit DisableTypingStatusUploading：MMTypingSend.Req 上传请求
            when {
                ctx.classByStrings(
                    "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
                    "autoAuth"
                ) || ctx.methodByStrings(
                    "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
                    "autoAuth"
                ) -> OK("MMTypingSend.Req + autoAuth 上传请求")
                ctx.methodByStrings("[doDirectSend] mChattingContext is null!") ||
                    ctx.classByStrings("MicroMsg.SignallingComponent") ->
                    PARTIAL("SignallingComponent 聊天组件兜底")
                else -> FAIL("无正在输入上传锚点")
            }
        },
        p("QuoteDeleteClear", "删除清引用") { ctx ->
            if (ctx.methodByStrings("MicroMsg.ChatFooter") ||
                ctx.classExists(WeChatClassNames.CHAT_FOOTER) ||
                ctx.classByStrings("MicroMsg.ChatFooter")
            ) OK("ChatFooter")
            else FAIL("无 ChatFooter")
        },
        p("EditMessage", "修改消息") { ctx ->
            if (ctx.methodByStrings("MicroMsg.MMNeatTextView") ||
                ctx.classExists(WeChatClassNames.MM_NEAT_TEXT) ||
                ctx.classExists(WeChatClassNames.CHAT_FOOTER) ||
                ctx.classByStrings("MicroMsg.MMNeatTextView")
            ) OK("聊天区")
            else FAIL("无聊天锚点")
        },
        p("InputStats", "输入统计") { ctx ->
            if (ctx.methodByStrings("MicroMsg.ChatFooter") ||
                ctx.classExists(WeChatClassNames.CHAT_FOOTER) ||
                ctx.classByStrings("MicroMsg.ChatFooter")
            ) OK("ChatFooter")
            else FAIL("无 ChatFooter")
        },
        p("AntiMomentsDelete", "朋友圈防删") { ctx ->
            if (ctx.methodByStrings("MicroMsg.SnsInfoStorage") ||
                ctx.classByStrings("MicroMsg.SnsInfoStorage")
            ) OK("SnsInfoStorage")
            else FAIL("无朋友圈存储特征")
        },
        p("AntiMomentsComment", "朋友圈评论防撤回") { ctx ->
            when {
                ctx.methodByStrings("deleteComment", "MicroMsg.SnsInfoStorageLogic") ||
                    ctx.methodByStrings("commentUsername:%s, actionUsername:%s, removeComment:%s") ->
                    OK("deleteComment 特征")
                ctx.classByStrings("MicroMsg.SnsCommentStorage") ||
                    ctx.methodByStrings("set sns del") ->
                    OK("SnsComment 特征")
                ctx.classByStrings("MicroMsg.SnsInfoStorage") ->
                    PARTIAL("仅朋友圈存储，装载时再验证评论点")
                else -> FAIL("无评论防撤锚点")
            }
        },
        p("MomentsAdBlock", "朋友圈广告") { ctx ->
            if (ctx.classByStrings("MicroMsg.SnsInfoStorage") ||
                ctx.methodByStrings("MicroMsg.LauncherUI") ||
                ctx.classExists(WeChatClassNames.LAUNCHER_UI)
            ) OK("朋友圈/主界面锚点")
            else FAIL("无锚点")
        },
        p("ProfileId", "资料页 ID") { ctx ->
            when {
                ctx.methodByStrings("MicroMsg.ContactInfoUI") ||
                    ctx.classExists(WeChatClassNames.CONTACT_INFO_UI) ||
                    ctx.classByStrings("MicroMsg.ContactInfoUI") ->
                    OK("ContactInfoUI")
                else -> FAIL("无 ContactInfoUI")
            }
        },
        p("RealNameTail", "实名尾字") { ctx ->
            if (ctx.methodByStrings("MicroMsg.MMNeatTextView") ||
                ctx.classExists(WeChatClassNames.MM_NEAT_TEXT) ||
                ctx.classExists(WeChatClassNames.CHAT_FOOTER) ||
                ctx.classByStrings("MicroMsg.MMNeatTextView")
            ) OK("聊天文本")
            else FAIL("无聊天锚点")
        },
        p("MemberTitle", "群员头衔") { ctx ->
            if (ctx.methodByStrings("MicroMsg.MMNeatTextView") ||
                ctx.classExists(WeChatClassNames.MM_NEAT_TEXT) ||
                ctx.classByStrings("MicroMsg.MMNeatTextView")
            ) OK("文本控件")
            else FAIL("无文本控件")
        },
        p("DisableHotUpdate", "禁用热更新") { ctx ->
            if (ctx.classExists("com.tencent.tinker.loader.shareutil.ShareTinkerInternals") ||
                ctx.classByStrings("Tinker.TinkerInternals")
            ) OK("Tinker")
            else OK("热更新拦截（通用路径）")
        },
        p("VirtualLocation", "虚拟定位") { _ ->
            OK("系统定位接口")
        },
        p("AutoLoginWin", "PC 自动登录") { ctx ->
            if (ctx.anyClass(
                    "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI",
                    "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI2"
                )
            ) OK("登录页")
            else OK("登录页（动态解析）")
        },
        p("SystemCamera", "系统相机") { ctx ->
            if (ctx.classExists(WeChatClassNames.CHAT_FOOTER) ||
                ctx.classByStrings("MicroMsg.ChatFooter") ||
                ctx.methodByStrings("MicroMsg.ChatFooter")
            ) OK("ChatFooter")
            else OK("系统相机（通用路径）")
        },
        p("RemoveLimitsDuringCalls", "通话无限制") { ctx ->
            if (ctx.methodByStrings("MicroMsg.Voip") ||
                ctx.classByStrings("MicroMsg.Voip") ||
                ctx.classExists("com.tencent.mm.plugin.voip.model.VoipMgr")
            ) OK("VoipMgr")
            else OK("通话无限制（通用路径）")
        },
        p("ChatToolbar", "聊天工具栏") { ctx ->
            if (ctx.methodByStrings("MicroMsg.ChatFooter") ||
                ctx.classExists(WeChatClassNames.CHAT_FOOTER) ||
                ctx.classByStrings("MicroMsg.ChatFooter")
            ) OK("ChatFooter")
            else FAIL("无 ChatFooter 锚点")
        },
        p("ConversationListStyle", "会话列表卡片") { ctx ->
            if (ctx.methodByStrings("sql is null ") ||
                ctx.classByStrings("MicroMsg.MainUI") ||
                ctx.classExists(WeChatClassNames.LAUNCHER_UI)
            ) OK("MainUI / 会话列表")
            else OK("会话列表（通用路径）")
        },
        p("CloseFriend", "密友功能") { ctx ->
            if (ctx.classByStrings("MicroMsg.SnsInfoStorage") ||
                ctx.classByStrings("MicroMsg.ConversationStorage") ||
                ctx.classExists(WeChatClassNames.LAUNCHER_UI)
            ) OK("存储 / 密友过滤")
            else OK("密友（通用路径）")
        },
        p("MapPickBridge", "地图选点") { ctx ->
            if (ctx.methodByStrings("MicroMsg.LauncherUI") ||
                ctx.classExists(WeChatClassNames.LAUNCHER_UI) ||
                ctx.classByStrings("MicroMsg.LauncherUI")
            ) OK("主界面 + 选点桥接")
            else OK("选点桥接")
        },
        p("DownloadRedirect", "下载重定向") { ctx ->
            // 锚点基于 WeKit 逆向（cn76 已验证：AppMsgLogic 日志串 + vfs 特征串均命中）
            when {
                ctx.methodByStrings("summerbig initDownloadAttach msgLocalId[%d], msgXml[%s], downloadPath[%s]") &&
                    ctx.methodByStrings("summerbig initDownloadAttach ret[%b], rowid[%d], field_totalLen[%d], type[%d], isLargeFile[%d], destFile[%s], msgLocalId[%s], stack[%s]") ->
                    OK("init/insert DownloadAttach 特征")
                ctx.methodByStrings("summerbig initDownloadAttach") ||
                    ctx.methodByStrings("VFS.VFSStrategy") ->
                    PARTIAL("仅部分下载锚点")
                else -> FAIL("无下载附件锚点")
            }
        },
        p("ConversationGrouping", "会话分组") { ctx ->
            // 锚点基于 WeKit 逆向（8.0.76/3141 已验证：SQLite wrapper 双串 + MainUI onTabCreate）
            when {
                ctx.methodByStrings("sql is null ") &&
                    (ctx.methodByStrings("onTabCreate, %d") || ctx.classByStrings("MicroMsg.MainUI")) ->
                    OK("查询包装器 + MainUI")
                ctx.methodByStrings("sql is null ") || ctx.classByStrings("MicroMsg.MainUI") ->
                    PARTIAL("仅部分分组锚点")
                else -> FAIL("无分组锚点")
            }
        },
        p("SwipeDeleteKeep", "左滑删除存记录") { ctx ->
            // 锚点参考 WeKit WeConversationApi：ConversationStorage.delChatContact + doDeleteConv 工作方法
            when {
                ctx.classByStrings("MicroMsg.ConversationStorage", "delChatContact username:") &&
                    ctx.methodByStrings("MicroMsg.ConvDelLogic", "oplog modContact user:%s") ->
                    OK("ConversationStorage.delChatContact + doDeleteConv")
                ctx.classByStrings("MicroMsg.ConversationStorage", "delChatContact username:") ->
                    PARTIAL("找到 delChatContact，缺 doDeleteConv")
                else -> FAIL("无左滑删除锚点")
            }
        },
        p("FinderVideoDownload", "视频号下载") { ctx ->
            when {
                ctx.methodByStrings("pos is error ") ||
                    ctx.methodByStrings("[getMoreMenuItemSelectedListener] feed ") ||
                    ctx.methodByStrings("getCreateSecondMoreMenuListener: username=") ||
                    ctx.methodByStrings("button_speedplay") ->
                    OK("Finder 菜单特征")
                else -> OK("Finder 动态检索")
            }
        },
        p("JavaPlugin", "Java脚本") { ctx ->
            OK("BeanShell 2.0 脚本引擎")
        }
    )

    private sealed class R {
        abstract val detail: String
        data class OK(override val detail: String) : R()
        data class PARTIAL(override val detail: String) : R()
        data class FAIL(override val detail: String) : R()
    }

    private fun OK(d: String) = R.OK(d)
    private fun PARTIAL(d: String) = R.PARTIAL(d)
    private fun FAIL(d: String) = R.FAIL(d)

    private fun p(
        id: String,
        title: String,
        block: (ProbeContext) -> R
    ): FeatureProbe = FeatureProbe { ctx ->
        val r = runCatching { block(ctx) }.getOrElse {
            return@FeatureProbe ProbeResult(id, title, ProbeLevel.FAIL, it.message ?: "error")
        }
        val level = when (r) {
            is R.OK -> ProbeLevel.OK
            is R.PARTIAL -> ProbeLevel.PARTIAL
            is R.FAIL -> ProbeLevel.FAIL
        }
        ProbeResult(id, title, level, r.detail)
    }
}
