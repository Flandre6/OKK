package com.OKK.yes.core.hooks.plugins

import androidx.annotation.Keep

@Keep
data class OkkFriendInfo(
    val wxid: String,
    val alias: String,
    val nickname: String,
    val remark: String
)
