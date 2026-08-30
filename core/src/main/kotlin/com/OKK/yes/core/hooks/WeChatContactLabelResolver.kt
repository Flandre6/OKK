package com.OKK.yes.core.hooks

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * 微信联系人与微信原生标签 (ContactLabel) 解析服务。
 * 对应 HChat 中的 `g8/i` 标签操作与联系人查询解耦层。
 *
 * 特性：
 * 1. 自动适配多版本微信表名: ContactLabel / contactlabel
 * 2. 自动适配多版本列名: labelID/labelId/labelid, labelName/labelname, contactLabelIds/contactLabelids
 * 3. 动态从 rcontact / chatroom 提取分类联系人与群聊信息，提供给会话分组的标签自动匹配。
 */
object WeChatContactLabelResolver {
    private const val TAG = "OKK-ContactLabel"

    private val TABLES_LABEL = arrayOf("ContactLabel", "contactlabel")
    private val COLS_LABEL_ID = arrayOf("labelID", "labelId", "labelid")
    private val COLS_LABEL_NAME = arrayOf("labelName", "labelname")
    private val COLS_CONTACT_LABEL_IDS = arrayOf("contactLabelIds", "contactLabelids", "labelIds")

    data class ContactLabel(
        val id: String,
        val name: String
    )

    data class SimpleContact(
        val username: String,
        val nickname: String,
        val remark: String,
        val isGroup: Boolean,
        val labelIds: String
    )

    @Volatile private var cachedLabels: List<ContactLabel>? = null
    private val labelCacheLock = Any()

    /**
     * 从 SQLiteDatabase 中动态识别表名与列名并获取所有标签
     */
    fun queryAllLabels(db: SQLiteDatabase): List<ContactLabel> {
        return runCatching {
            var labelTable: String? = null
            for (t in TABLES_LABEL) {
                if (hasTable(db, t)) {
                    labelTable = t
                    break
                }
            }
            if (labelTable == null) return emptyList()

            val idCol = findExistingCol(db, labelTable, COLS_LABEL_ID) ?: return emptyList()
            val nameCol = findExistingCol(db, labelTable, COLS_LABEL_NAME) ?: return emptyList()

            val list = mutableListOf<ContactLabel>()
            val sql = "SELECT $idCol AS lid, $nameCol AS lname FROM $labelTable"
            db.rawQuery(sql, null).use { cursor ->
                val idxId = cursor.getColumnIndex("lid")
                val idxName = cursor.getColumnIndex("lname")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idxId) ?: ""
                    val name = cursor.getString(idxName) ?: ""
                    if (id.isNotBlank() && name.isNotBlank()) {
                        list.add(ContactLabel(id, name))
                    }
                }
            }
            synchronized(labelCacheLock) {
                cachedLabels = list
            }
            list
        }.getOrElse {
            XposedBridge.log("[$TAG] queryAllLabels failed: ${it.message}")
            emptyList()
        }
    }

    /**
     * 根据标签 ID 集合查询具有指定标签的所有联系人 username
     */
    fun queryUsernamesByLabelIds(db: SQLiteDatabase, labelIds: List<String>): List<String> {
        if (labelIds.isEmpty()) return emptyList()
        return runCatching {
            val labelCol = findExistingCol(db, "rcontact", COLS_CONTACT_LABEL_IDS) ?: "labelIds"
            val result = mutableSetOf<String>()
            for (labelId in labelIds) {
                val cleanId = labelId.trim()
                if (cleanId.isBlank()) continue
                // SQL like 模糊匹配 ，如 %labelId%
                val sql = "SELECT username FROM rcontact WHERE $labelCol LIKE ?"
                db.rawQuery(sql, arrayOf("%$cleanId%")).use { cursor ->
                    while (cursor.moveToNext()) {
                        val un = cursor.getString(0)
                        if (!un.isNull_or_blank()) {
                            result.add(un)
                        }
                    }
                }
            }
            result.toList()
        }.getOrElse {
            XposedBridge.log("[$TAG] queryUsernamesByLabelIds failed: ${it.message}")
            emptyList()
        }
    }

    /**
     * 查询所有可用做分组包含的群聊列表（`@chatroom` / `@im.chatroom`）
     */
    fun queryAllGroups(db: SQLiteDatabase): List<SimpleContact> {
        return runCatching {
            val list = mutableListOf<SimpleContact>()
            val sql = "SELECT username, nickname, conRemark FROM rcontact WHERE (username LIKE '%@chatroom' OR username LIKE '%@im.chatroom')"
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val un = cursor.getString(0) ?: ""
                    val nick = cursor.getString(1) ?: ""
                    val remark = cursor.getString(2) ?: ""
                    if (un.isNotBlank()) {
                        list.add(SimpleContact(un, nick, remark, true, ""))
                    }
                }
            }
            list
        }.getOrElse { emptyList() }
    }

    private fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        return runCatching {
            val sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=? LIMIT 1"
            db.rawQuery(sql, arrayOf(tableName)).use { cursor ->
                cursor.count > 0
            }
        }.getOrDefault(false)
    }

    private fun findExistingCol(db: SQLiteDatabase, tableName: String, candidateCols: Array<String>): String? {
        return runCatching {
            db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val colsInTable = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    colsInTable.add(cursor.getString(nameIdx))
                }
                for (col in candidateCols) {
                    if (colsInTable.contains(col)) return col
                }
                null
            }
        }.getOrNull()
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
