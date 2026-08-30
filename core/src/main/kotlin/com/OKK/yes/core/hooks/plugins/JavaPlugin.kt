package com.OKK.yes.core.hooks.plugins

import java.io.File

data class JavaPluginInfo(
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val updateTime: String? = null,
    val description: String? = null
)

data class JavaPlugin(
    val id: String,
    val dir: File,
    val info: JavaPluginInfo,
    val content: String,
    val isEnabled: Boolean = true
) {
    companion object {
        fun parseInfoPropContent(content: String, fallbackName: String = "未命名"): JavaPluginInfo {
            val props = mutableMapOf<String, String>()
            runCatching {
                content.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val eq = trimmed.indexOf('=')
                        if (eq > 0) {
                            props[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                        }
                    }
                }
            }
            return JavaPluginInfo(
                name = props["name"] ?: fallbackName,
                author = props["author"],
                version = props["version"],
                updateTime = props["updateTime"],
                description = props["description"]
            )
        }

        fun parseInfoProp(file: File): JavaPluginInfo {
            if (!file.exists()) return JavaPluginInfo(name = file.parentFile?.name ?: "未命名")
            val content = runCatching { file.readText() }.getOrDefault("")
            return parseInfoPropContent(content, file.parentFile?.name ?: "未命名")
        }
    }
}
