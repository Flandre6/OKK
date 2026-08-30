package com.OKK.yes.core.hooks.plugins

import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import bsh.BshLambda
import bsh.This
import com.OKK.yes.core.compat.DexKitSupport
import com.OKK.yes.core.compat.WeChatSelfUser
import com.OKK.yes.core.hooks.ContactDisplayNames
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object OkkScriptApi {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun log(msg: Any?) {
        runCatching { XposedBridge.log("[OKK-Script] ${msg?.toString() ?: "null"}") }
    }

    @JvmStatic
    fun toast(msg: Any?) {
        val ctx = DexKitSupport.appContext ?: return
        mainHandler.post {
            Toast.makeText(ctx, msg?.toString() ?: "null", Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun getMyWxid(): String {
        val cl = DexKitSupport.classLoader ?: return ""
        val ctx = DexKitSupport.appContext
        return WeChatSelfUser.resolve(cl, ctx)
    }

    @JvmStatic
    fun sendText(talker: String, content: String): Boolean {
        log("sendText -> talker: $talker, content: ${content.take(60)}")
        return WeMessageSender.sendText(talker, content)
    }

    @JvmStatic
    fun sendImage(talker: String, path: String): Boolean {
        log("sendImage -> talker: $talker, path: $path")
        return WeMessageSender.sendImage(talker, path)
    }

    /**
     * 获取微信好友列表，返回 OKK 专属的 OkkFriendInfo 列表
     */
    @JvmStatic
    fun getFriendList(): List<OkkFriendInfo> {
        return runCatching {
            val db = ContactDisplayNames.getCurrentMainDb()
            if (db != null) {
                val sql = """
                    SELECT r.username, r.alias, r.conRemark, r.nickname, r.type
                    FROM rcontact r
                    WHERE (r.type & 1) != 0
                      AND (r.type & 8) = 0
                      AND (r.type & 32) = 0
                      AND r.verifyFlag = 0
                      AND r.username NOT LIKE '%chatroom'
                      AND r.username NOT LIKE 'gh_%'
                      AND r.username != ''
                      AND r.username != 'notchatroom'
                      AND r.username NOT LIKE '%@app'
                      AND r.username NOT LIKE '%@openim'
                      AND r.username NOT LIKE '%@fakeuser'
                """.trimIndent()
                val raw = (db as? android.database.sqlite.SQLiteDatabase)?.rawQuery(sql, emptyArray())
                    ?: runCatching {
                        val m = db.javaClass.methods.firstOrNull { it.name == "rawQuery" && it.parameterCount == 2 }
                        m?.invoke(db, sql, emptyArray<String>())
                    }.getOrNull()
                val cursor = raw as? Cursor
                if (cursor != null) {
                    val list = mutableListOf<OkkFriendInfo>()
                    try {
                        val iUser = cursor.getColumnIndex("username").takeIf { it >= 0 } ?: 0
                        val iAlias = cursor.getColumnIndex("alias").takeIf { it >= 0 } ?: 1
                        val iRemark = cursor.getColumnIndex("conRemark").takeIf { it >= 0 } ?: 2
                        val iNick = cursor.getColumnIndex("nickname").takeIf { it >= 0 } ?: 3
                        while (cursor.moveToNext()) {
                            val u = if (iUser >= 0) cursor.getString(iUser)?.trim().orEmpty() else ""
                            if (!ContactDisplayNames.isRealFriendUsername(u)) continue
                            val alias = if (iAlias >= 0) cursor.getString(iAlias)?.trim().orEmpty() else ""
                            val conRemark = if (iRemark >= 0) cursor.getString(iRemark)?.trim().orEmpty() else ""
                            val nickname = if (iNick >= 0) cursor.getString(iNick)?.trim().orEmpty() else ""

                            list.add(
                                OkkFriendInfo(
                                    wxid = u,
                                    alias = alias,
                                    nickname = nickname,
                                    remark = conRemark
                                )
                            )
                        }
                    } finally {
                        runCatching { cursor.close() }
                    }
                    if (list.isNotEmpty()) return list
                }
            }

            // 兜底：使用 queryFriendsOnlyFast
            val contacts = ContactDisplayNames.queryFriendsOnlyFast()
            contacts.map { c ->
                OkkFriendInfo(
                    wxid = c.username,
                    alias = c.alias,
                    nickname = c.displayName,
                    remark = c.displayName
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun openConnectionWithRedirects(
        urlStr: String,
        headers: Map<*, *>?,
        method: String = "GET",
        body: String? = null
    ): HttpURLConnection {
        var currentUrl = urlStr
        var redirectCount = 0
        while (redirectCount < 5) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            headers?.forEach { (k, v) ->
                if (k != null && v != null) {
                    conn.setRequestProperty(k.toString(), v.toString())
                }
            }
            if (method == "POST" && !body.isNullOrEmpty()) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, "UTF-8").use {
                    it.write(body)
                    it.flush()
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (!loc.isNullOrEmpty()) {
                    currentUrl = if (loc.startsWith("http://") || loc.startsWith("https://")) {
                        loc
                    } else {
                        URL(URL(currentUrl), loc).toString()
                    }
                    redirectCount++
                    continue
                }
            }
            return conn
        }
        val url = URL(currentUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        return conn
    }

    @JvmStatic
    fun httpGetSync(urlStr: String, headers: Map<*, *>?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnectionWithRedirects(urlStr, headers, "GET")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            log("httpGetSync error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    @JvmStatic
    fun httpGet(urlStr: String, headers: Map<*, *>?, callback: Any?) {
        thread {
            val response = httpGetSync(urlStr, headers)
            if (response != null) {
                invokeCallback(callback, response, isFailure = false)
            } else {
                invokeCallback(callback, "HTTP GET failed or returned null", isFailure = true)
            }
        }
    }

    @JvmStatic
    fun httpPostSync(urlStr: String, headers: Map<*, *>?, body: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnectionWithRedirects(urlStr, headers, "POST", body)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            log("httpPostSync error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    @JvmStatic
    fun httpPost(urlStr: String, headers: Map<*, *>?, body: String?, callback: Any?) {
        thread {
            val response = httpPostSync(urlStr, headers, body)
            if (response != null) {
                invokeCallback(callback, response, isFailure = false)
            } else {
                invokeCallback(callback, "HTTP POST failed or returned null", isFailure = true)
            }
        }
    }

    @JvmStatic
    fun downloadFile(urlStr: String, savePath: String, headers: Map<*, *>?, callback: Any?) {
        thread {
            var conn: HttpURLConnection? = null
            var resultFile: File? = null
            var errorMsg: String? = null
            try {
                val dest = File(savePath)
                dest.parentFile?.mkdirs()
                conn = openConnectionWithRedirects(urlStr, headers, "GET")
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                    resultFile = dest
                    log("downloadFile success: ${dest.absolutePath}, size=${dest.length()}")
                } else {
                    errorMsg = "downloadFile HTTP error: code=${conn.responseCode}"
                    log(errorMsg)
                }
            } catch (e: Exception) {
                errorMsg = "downloadFile error: ${e.message}"
                log(errorMsg)
            } finally {
                conn?.disconnect()
                if (resultFile != null) {
                    invokeCallback(callback, resultFile, isFailure = false)
                } else {
                    invokeCallback(callback, errorMsg ?: "Unknown download error", isFailure = true)
                }
            }
        }
    }

    @JvmStatic
    fun insertSystemMsg(talker: String, text: String, time: Long) {
        runCatching {
            val cl = DexKitSupport.classLoader ?: return
            
            val msgInfoClass = cl.loadClass("com.tencent.mm.storage.MsgInfo")
            val msgInfo = msgInfoClass.newInstance()
            
            val setType = msgInfoClass.methods.firstOrNull { it.name == "setType" && it.parameterTypes.size == 1 }
            setType?.invoke(msgInfo, 10000)
            
            val setTalker = msgInfoClass.methods.firstOrNull { it.name == "setTalker" && it.parameterTypes.size == 1 }
            setTalker?.invoke(msgInfo, talker)
            
            val setContent = msgInfoClass.methods.firstOrNull { it.name == "setContent" && it.parameterTypes.size == 1 }
            setContent?.invoke(msgInfo, text)
            
            val setCreateTime = msgInfoClass.methods.firstOrNull { it.name == "setCreateTime" && it.parameterTypes.size == 1 }
            setCreateTime?.invoke(msgInfo, time)

            val setIsSend = msgInfoClass.methods.firstOrNull { it.name == "setIsSend" && it.parameterTypes.size == 1 }
            setIsSend?.invoke(msgInfo, 1)
            
            val setStatus = msgInfoClass.methods.firstOrNull { it.name == "setStatus" && it.parameterTypes.size == 1 }
            setStatus?.invoke(msgInfo, 3)

            val insertMethod = WeMessageSender.getMsgInfoStorageInsertMethod() ?: return
            val msgInfoStorage = WeMessageSender.getMsgInfoStorageInstance() ?: return
            
            insertMethod.invoke(msgInfoStorage, msgInfo)
            log("insertSystemMsg success for $talker")
        }.onFailure {
            log("insertSystemMsg failed: ${it.message}")
        }
    }

    @JvmStatic
    fun sendQuoteMsg(talker: String, content: String, referContent: String?, msgSvrId: Long) {
        runCatching {
            val quoteText = if (referContent != null) {
                "「$referContent」\n- - - - - - - - - - - - - - -\n$content"
            } else {
                content
            }
            WeMessageSender.sendText(talker, quoteText)
        }.onFailure {
            log("sendQuoteMsg failed: ${it.message}")
        }
    }

    @JvmStatic
    fun revokeMsg(msgId: Long) {
        log("revokeMsg($msgId) called (not implemented)")
    }

    @JvmStatic
    fun sendEmoji(talker: String, path: String): Boolean {
        log("sendEmoji to $talker: $path (not implemented)")
        return true
    }

    @JvmStatic
    fun sendPat(talker: String, chatroom: String): Boolean {
        log("sendPat to $talker in $chatroom (not implemented)")
        return true
    }

    @JvmStatic
    fun sendLocation(talker: String, location: Any): Boolean {
        log("sendLocation to $talker (not implemented)")
        return true
    }

    @JvmStatic
    fun delay(ms: Long, action: Runnable) {
        thread {
            try {
                Thread.sleep(ms)
                action.run()
            } catch (_: Throwable) {}
        }
    }

    fun invokeCallback(callback: Any?, response: Any?, isFailure: Boolean = false) {
        if (callback == null) return
        runCatching {
            when (callback) {
                is BshLambda -> {
                    callback.invoke(arrayOf(response), emptyArray(), Any::class.java)
                }
                is This -> {
                    val methodName = if (isFailure) "onFailure" else "onSuccess"
                    runCatching {
                        callback.invokeMethod(methodName, arrayOf(response))
                    }.onFailure {
                        runCatching {
                            callback.invokeMethod("onError", arrayOf(response))
                        }.onFailure {
                            runCatching {
                                callback.invokeMethod("run", arrayOf(response))
                            }.onFailure {
                                runCatching {
                                    callback.invokeMethod("invoke", arrayOf(response))
                                }.onFailure {
                                    callback.invokeMethod("accept", arrayOf(response))
                                }
                            }
                        }
                    }
                }
                else -> {
                    val methods = callback.javaClass.methods
                    val targetName = if (isFailure) "onFailure" else "onSuccess"
                    var target = methods.firstOrNull {
                        it.name == targetName && it.parameterCount == 1 && !it.isBridge
                    }
                    if (target == null && isFailure) {
                        target = methods.firstOrNull {
                            it.name == "onError" && it.parameterCount == 1 && !it.isBridge
                        }
                    }
                    if (target == null) {
                        target = methods.firstOrNull {
                            it.name in listOf("accept", "run", "invoke", "call", "apply") &&
                            (it.parameterCount == 1 || it.parameterCount == 0) &&
                            !it.isBridge
                        }
                    }
                    if (target != null) {
                        target.isAccessible = true
                        if (target.parameterCount == 1) {
                            target.invoke(callback, response)
                        } else {
                            target.invoke(callback)
                        }
                    } else {
                        log("No matching method on callback: ${callback.javaClass.name} for $targetName")
                    }
                }
            }
        }.onFailure { e ->
            log("invokeCallback failed: ${e.message}")
        }
    }
}
