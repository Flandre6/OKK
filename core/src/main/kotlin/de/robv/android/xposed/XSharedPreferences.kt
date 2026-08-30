package de.robv.android.xposed

class XSharedPreferences(
    private val packageName: String,
    private val prefFileName: String
) {
    fun reload(): Boolean = false
    fun contains(key: String): Boolean = false
    fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    fun getString(key: String, defValue: String?): String? = defValue
    fun getFloat(key: String, defValue: Float): Float = defValue
    fun getInt(key: String, defValue: Int): Int = defValue
}
