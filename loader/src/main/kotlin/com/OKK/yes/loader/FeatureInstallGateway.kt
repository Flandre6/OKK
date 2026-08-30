package com.OKK.yes.loader

import android.content.Context
import android.util.Log
import com.OKK.yes.core.compat.CompatReportStore
import com.OKK.yes.core.hooks.AntiMomentsCommentHook
import com.OKK.yes.core.hooks.AntiMomentsDeleteHook
import com.OKK.yes.core.hooks.AntiRevokeHook
import com.OKK.yes.core.hooks.AutoLoginWinHook
import com.OKK.yes.core.hooks.BlockTypingReportHook
import com.OKK.yes.core.hooks.ChatToolbarHook
import com.OKK.yes.core.hooks.SystemCameraHook
import com.OKK.yes.core.hooks.RemoveLimitsDuringCallsHook
import com.OKK.yes.core.hooks.BottomTabConfig
import com.OKK.yes.core.hooks.BottomTabFloatingHook
import com.OKK.yes.core.hooks.BottomTabIconHook
import com.OKK.yes.core.hooks.ChatEnhanceHook
import com.OKK.yes.core.hooks.ConversationGroupingHook
import com.OKK.yes.core.hooks.ConversationListStyleHook
import com.OKK.yes.core.hooks.DisableHotUpdateHook
import com.OKK.yes.core.hooks.DownloadRedirectHook
import com.OKK.yes.core.hooks.EditMessageHook
import com.OKK.yes.core.hooks.FoldBannerPinHook
import com.OKK.yes.core.hooks.HideHomeDividerHook
import com.OKK.yes.core.hooks.FloatingQuickEntryHook
import com.OKK.yes.core.hooks.FinderVideoDownloadHook
import com.OKK.yes.core.hooks.HomeAvatarHook
import com.OKK.yes.core.hooks.HomeDrawerBridge
import com.OKK.yes.loader.ui.OKKSettingsDialog
import com.OKK.yes.core.hooks.InputStatsHook
import com.OKK.yes.core.hooks.MemberTitleHook
import com.OKK.yes.core.hooks.MomentsAdBlockHook
import com.OKK.yes.core.hooks.ProfileIdHook
import com.OKK.yes.core.hooks.SwipeDeleteKeepHook
import com.OKK.yes.core.hooks.CloseFriendHook
import com.OKK.yes.core.hooks.QuoteDeleteClearHook
import com.OKK.yes.core.hooks.RealNameTailHook
import com.OKK.yes.core.hooks.RoundAvatarHook
import com.OKK.yes.core.hooks.ThemeWallpaperConfig
import com.OKK.yes.core.hooks.ThemeWallpaperHook
import com.OKK.yes.core.hooks.VirtualLocationConfig
import com.OKK.yes.core.hooks.VirtualLocationHook
import com.OKK.yes.core.hooks.WeChatMapPickBridge
import com.OKK.yes.core.startup.FeatureHookRegistry
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 业务 Hook 安装网关：探针结束后按结果安装；FAIL 不装。
 */
object FeatureInstallGateway {
    private const val TAG = "OKK-FeatureGate"
    private val featuresInstalled = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var modulePath: String? = null

    fun prepare(context: Context, cl: ClassLoader, mp: String?) {
        appContext = context.applicationContext ?: context
        classLoader = cl
        modulePath = mp
    }

    fun isFeaturesInstalled(): Boolean = featuresInstalled.get()

    /** 按当前 [CompatReportStore] 报告安装全部业务功能（只一次） */
    fun installFeaturesOnce(reason: String) {
        if (!featuresInstalled.compareAndSet(false, true)) {
            xlog("features already installed, skip ($reason)")
            return
        }
        val ctx = appContext ?: return
        val cl = classLoader ?: return
        val mp = modulePath
        xlog("install features via $reason")

        // 全局绑定桥接入口，无视是否开启 HomeAvatar
        HomeDrawerBridge.openSettings = { act ->
            OKKSettingsDialog.show(act)
        }
        HomeDrawerBridge.openAbout = { act ->
            OKKSettingsDialog.show(act, EmbeddedSettingsUi.StartTarget.Settings)
        }
        HomeDrawerBridge.openVirtualLocation = { act ->
            OKKSettingsDialog.show(
                act,
                EmbeddedSettingsUi.StartTarget.FeatureDetail(VirtualLocationConfig.KEY_ENABLED)
            )
        }
        HomeDrawerBridge.openFloatingBar = { act ->
            OKKSettingsDialog.show(
                act,
                EmbeddedSettingsUi.StartTarget.FeatureDetail(BottomTabConfig.KEY_FLOATING)
            )
        }
        HomeDrawerBridge.openTheme = { act ->
            OKKSettingsDialog.show(
                act,
                EmbeddedSettingsUi.StartTarget.FeatureDetail(ThemeWallpaperConfig.KEY_ENABLED)
            )
        }

        installIfOk("HomeAvatar") {
            HomeAvatarHook.install(ctx, cl, mp)
        }

        installIfOk("HideHomeDivider") {
            HideHomeDividerHook.install(ctx, cl, mp)
        }
        installIfOk("FoldBannerPin") {
            FoldBannerPinHook.install(ctx, cl, mp)
        }
        installIfOk("BottomTabIcon") {
            BottomTabIconHook.install(ctx, cl, mp)
        }
        installLenient("BottomTabFloating", "探针误判时仍尝试安装，失败由隔离器兜底") {
            BottomTabFloatingHook.install(ctx, cl, mp)
        }
        installIfOk("RoundAvatar") {
            RoundAvatarHook.install(ctx, cl, mp)
        }
        installIfOk("ThemeWallpaper") {
            ThemeWallpaperHook.install(ctx, cl, mp)
        }
        installIfOk("AntiRevoke") {
            AntiRevokeHook.install(ctx, cl, mp)
        }
        installIfOk("BlockTypingReport") {
            BlockTypingReportHook.install(ctx, cl, mp)
        }
        installLenient("ChatEnhance", "探针误判时仍尝试安装，失败由隔离器兜底") {
            ChatEnhanceHook.install(ctx, cl, mp)
        }
        installIfOk("QuoteDeleteClear") {
            QuoteDeleteClearHook.install(ctx, cl, mp)
        }
        installLenient("SwipeDeleteKeep", "探针误判时仍尝试安装，失败由隔离器兜底") {
            SwipeDeleteKeepHook.install(ctx, cl, mp)
        }
        installLenient("ConversationListStyle", "会话列表卡片美化与置顶染色") {
            ConversationListStyleHook.install(ctx, cl, mp)
        }
        installLenient("CloseFriend", "探针误判时仍尝试安装，失败由隔离器兜底") {
            CloseFriendHook.install(ctx, cl, mp)
        }
        installIfOk("EditMessage") {
            EditMessageHook.install(ctx, cl, mp)
        }
        installIfOk("InputStats") {
            InputStatsHook.install(ctx, cl)
        }
        installIfOk("AntiMomentsDelete") {
            AntiMomentsDeleteHook.install(ctx, cl, mp)
        }
        installIfOk("AntiMomentsComment") {
            AntiMomentsCommentHook.install(ctx, cl, mp)
        }
        installIfOk("MomentsAdBlock") {
            MomentsAdBlockHook.install(ctx, cl, mp)
        }
        installIfOk("ProfileId") {
            ProfileIdHook.install(ctx, cl, mp)
        }
        installIfOk("RealNameTail") {
            RealNameTailHook.install(ctx, cl, mp)
        }
        installIfOk("MemberTitle") {
            MemberTitleHook.install(ctx, cl, mp)
        }
        installIfOk("DisableHotUpdate") {
            DisableHotUpdateHook.install(ctx, cl, mp)
        }
        installIfOk("VirtualLocation") {
            VirtualLocationHook.install(ctx, cl, mp)
        }
        installIfOk("AutoLoginWin") {
            AutoLoginWinHook.install(ctx, cl, mp)
        }
        installIfOk("SystemCamera") {
            SystemCameraHook.install(ctx, cl)
        }
        installIfOk("RemoveLimitsDuringCalls") {
            RemoveLimitsDuringCallsHook.install(ctx, cl, mp)
        }
        installIfOk("ChatToolbar") {
            ChatToolbarHook.install(ctx, cl, mp)
        }
        installIfOk("AutoDpi") {
            // DPI scaling is handled dynamically in Compose UI themes and settings
        }
        installIfOk("MapPickBridge") {
            WeChatMapPickBridge.install(cl)
        }
        installIfOk("DownloadRedirect") {
            DownloadRedirectHook.install(ctx, cl, mp)
        }
        installLenient("FinderVideoDownload", "视频号分享菜单探针特征可能随微信版本偏移，仍尝试安装，失败由隔离器兜底") {
            FinderVideoDownloadHook.install(ctx, cl, mp)
        }
        installIfOk("FloatingQuickEntry") {
            FloatingQuickEntryHook.install(ctx, cl, mp)
        }
        installLenient("ConversationGrouping", "探针误判时仍尝试安装，失败由隔离器兜底") {
            ConversationGroupingHook.install(ctx, cl, mp)
        }
        
        installLenient("JavaPlugin", "无需探针，通过动态脚本拓展核心功能") {
            com.OKK.yes.core.hooks.plugins.JavaPluginHook.install(ctx, cl, mp)
        }
        
        FeatureHookRegistry.persist()
        xlog("features done ${FeatureHookRegistry.summaryLine()}")
    }

    private fun installIfOk(name: String, block: () -> Unit) {
        if (!CompatReportStore.isInstallable(name)) {
            val detail = CompatReportStore.lastReport?.byId(name)?.detail ?: "compat FAIL"
            FeatureHookRegistry.markSkip(name, "不适配: $detail")
            xlog("skip $name ($detail)")
            return
        }
        FeatureHookRegistry.installIsolated(name, block)
    }

    private fun installLenient(name: String, reason: String, block: () -> Unit) {
        if (!CompatReportStore.isInstallable(name)) {
            val detail = CompatReportStore.lastReport?.byId(name)?.detail ?: "compat FAIL"
            xlog("force install $name despite compat report ($detail): $reason")
        }
        FeatureHookRegistry.installIsolated(name, block)
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
