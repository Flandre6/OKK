package com.OKK.yes.core.hooks

object InputStatsConfig {
    @Volatile
    private var lastLoadTime = 0L

    @Volatile
    private var cached = InputStatsOptions()

    fun load(now: Long = System.currentTimeMillis()): InputStatsOptions {
        if (now - lastLoadTime < 5_000L) return cached
        lastLoadTime = now
        cached = InputStatsOptions(
            enabled = PublicConfigStore.getBoolean("input_stats_enabled", true),
            countSend = PublicConfigStore.getBoolean("input_stats_count_send", true),
            template = PublicConfigStore.getString("input_stats_template", DEFAULT_INPUT_STATS_TEMPLATE)
        )
        return cached
    }

    /** 配置变更后清除 5s 缓存，使新模板/开关立即生效 */
    fun invalidateCache() {
        lastLoadTime = 0L
        cached = InputStatsOptions()
    }
}

const val DEFAULT_INPUT_STATS_TEMPLATE = "今日已发\${totalMsg}条"

data class InputStatsOptions(
    val enabled: Boolean = true,
    val countSend: Boolean = true,
    val template: String = DEFAULT_INPUT_STATS_TEMPLATE
)

data class InputStatsSnapshot(
    val dateKey: String = "",
    val totalMsg: Int = 0,
    val textMsg: Int = 0,
    val textWord: Int = 0,
    val emojiMsg: Int = 0,
    val transferMsg: Int = 0,
    val redBagMsg: Int = 0,
    val fileMsg: Int = 0
)
