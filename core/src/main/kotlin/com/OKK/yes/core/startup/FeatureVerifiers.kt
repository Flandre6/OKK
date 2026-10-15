package com.OKK.yes.core.startup

import com.OKK.yes.core.hooks.AntiRevokeHook
import com.OKK.yes.core.hooks.ChatToolbarHook
import com.OKK.yes.core.hooks.ConversationGroupingHook
import com.OKK.yes.core.hooks.DownloadRedirectHook
import com.OKK.yes.core.hooks.MomentsAdBlockHook
import com.OKK.yes.core.hooks.SwipeDeleteKeepHook

/**
 * 主动干跑自检注册中心。
 *
 * 每个 Verifier **只读**各 Hook 已解析的反射状态（方法是否定位到、入口是否挂上），
 * 绝不 invoke 业务方法、不写数据库，因此可在任意线程 / 任意时刻安全调用。
 *
 * 未注册 Verifier 的功能，审计会落到 `UNVERIFIED`，靠运行时触发计数转 `READY`。
 */
object FeatureVerifiers {
    private const val SWIPE = "SwipeDeleteKeep"
    private const val DOWNLOAD = "DownloadRedirect"
    private const val ANTI_REVOKE = "AntiRevoke"
    private const val CHAT_TOOLBAR = "ChatToolbar"
    private const val GROUPING = "ConversationGrouping"
    private const val MOMENTS_AD = "MomentsAdBlock"

    fun registerAll() {
        registerSwipeDeleteKeep()
        registerDownloadRedirect()
        registerAntiRevoke()
        registerChatToolbar()
        registerConversationGrouping()
        registerMomentsAdBlock()
    }

    private fun registerSwipeDeleteKeep() {
        RuntimeHealthAudit.registerVerifier(SWIPE) {
            val hook = SwipeDeleteKeepHook
            val hasHideMethod = hook.resolvedDelChatContactMethod != null
            val hasStorage = hook.hasStorageRef
            val adapters = hook.resolvedAdapterHookCount
            val strategy = com.OKK.yes.core.hooks.SwipeDeleteKeepPolicy.selectStrategy(
                hasDelChatContact = hasHideMethod,
                hasStorageRef = hasStorage
            )
            val messagesPreserved = !com.OKK.yes.core.hooks.SwipeDeleteKeepPolicy.shouldDeleteMessages
            val detail = buildString {
                append("策略=").append(strategy.name)
                append(" 适配器=").append(adapters)
                append(" storage=").append(if (hasStorage) "✓" else "待捕获")
                append(" 保留记录=").append(if (messagesPreserved) "✓" else "✗")
            }
            when {
                !messagesPreserved -> RuntimeHealthAudit.VerifyOutcome(false, "违规：策略允许删除聊天记录")
                !hasHideMethod ->
                    RuntimeHealthAudit.VerifyOutcome(false, "delChatContact 未解析 · $detail")
                !hasStorage ->
                    // 实例尚未被捕获（ConversationStorage 实例方法还没被调用过），无法判定
                    RuntimeHealthAudit.VerifyOutcome(null, "等待 ConversationStorage 实例捕获 · $detail")
                strategy == com.OKK.yes.core.hooks.SwipeDeleteKeepPolicy.HideStrategy.RAW_RCONVERSATION_DELETE ->
                    RuntimeHealthAudit.VerifyOutcome(true, "降级为兜底 SQL（仍保留聊天记录）· $detail")
                adapters == 0 ->
                    RuntimeHealthAudit.VerifyOutcome(null, "会话适配器未 Hook · $detail")
                else -> RuntimeHealthAudit.VerifyOutcome(true, detail)
            }
        }
    }

    private fun registerDownloadRedirect() {
        RuntimeHealthAudit.registerVerifier(DOWNLOAD) {
            val hooked = DownloadRedirectHook.isSaveEntryHooked
            RuntimeHealthAudit.VerifyOutcome(
                hooked,
                if (hooked) "ScopedStorageUtil 保存入口已拦截" else "保存入口未命中"
            )
        }
    }

    private fun registerAntiRevoke() {
        RuntimeHealthAudit.registerVerifier(ANTI_REVOKE) {
            val hooked = AntiRevokeHook.isSourceHookInstalled
            RuntimeHealthAudit.VerifyOutcome(
                hooked,
                if (hooked) "撤回源头方法已拦截" else "撤回源头方法未命中"
            )
        }
    }

    private fun registerChatToolbar() {
        RuntimeHealthAudit.registerVerifier(CHAT_TOOLBAR) {
            val resolved = ChatToolbarHook.isAppPanelResolved
            RuntimeHealthAudit.VerifyOutcome(
                resolved,
                if (resolved) "AppPanel 面板方法已定位" else "AppPanel 方法未定位"
            )
        }
    }

    private fun registerConversationGrouping() {
        RuntimeHealthAudit.registerVerifier(GROUPING) {
            val resolved = ConversationGroupingHook.isNotifyMethodResolved
            RuntimeHealthAudit.VerifyOutcome(
                resolved,
                if (resolved) "会话列表刷新方法与查询重写已就绪" else "会话列表刷新方法未解析"
            )
        }
    }

    private fun registerMomentsAdBlock() {
        RuntimeHealthAudit.registerVerifier(MOMENTS_AD) {
            val enabled = MomentsAdBlockHook.isEnabled()
            if (!enabled) {
                RuntimeHealthAudit.VerifyOutcome(null, "功能未开启")
            } else {
                val armed = MomentsAdBlockHook.isFullyArmed
                RuntimeHealthAudit.VerifyOutcome(
                    armed,
                    (if (armed) "五层拦截链已就绪" else "拦截链不完整") + " · " + MomentsAdBlockHook.armedDetail
                )
            }
        }
    }
}
