// 功能 Tab：MIUIX 毛玻璃搜索 + 虚拟化分类卡片 + 下钻 + 二级配置弹窗

package com.OKK.yes.loader.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Push_pin
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.File_download
import com.composables.icons.materialsymbols.outlined.File_upload
import com.composables.icons.materialsymbols.outlined.Folder_open
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Smart_toy
import com.composables.icons.materialsymbols.outlined.Swap_vert
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Unfold_more
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.OKK.yes.core.hooks.PublicConfigStore
import com.OKK.yes.core.hooks.plugins.JavaScriptEngine
import com.OKK.yes.core.hooks.plugins.JavaPlugin
import com.OKK.yes.loader.ui.configs.AntiRevokeConfigDialog
import com.OKK.yes.loader.ui.configs.AutoLoginConfigDialog
import com.OKK.yes.loader.ui.configs.AvatarConfigDialog
import com.OKK.yes.loader.ui.configs.BubbleConfigDialog
import com.OKK.yes.loader.ui.configs.ConvCardConfigDialog
import com.OKK.yes.loader.ui.configs.ConversationGroupingConfigDialog
import com.OKK.yes.loader.ui.configs.DefaultBarConfigDialog
import com.OKK.yes.loader.ui.configs.DownloadRedirectConfigDialog
import com.OKK.yes.loader.ui.configs.FloatingQuickEntryConfigDialog
import com.OKK.yes.loader.ui.configs.FloatingTabConfigDialog
import com.OKK.yes.loader.ui.configs.JavaPluginConfigDialog
import com.OKK.yes.loader.ui.configs.FinderVideoDownloadConfigDialog
import com.OKK.yes.loader.ui.configs.ChatToolbarConfigDialog
import com.OKK.yes.loader.ui.configs.InputStatsConfigDialog
import com.OKK.yes.loader.ui.configs.LocationConfigDialog
import com.OKK.yes.loader.ui.configs.MemberTitleConfigDialog
import com.OKK.yes.loader.ui.configs.MessageDetailConfigDialog
import com.OKK.yes.loader.ui.configs.ThemeWallpaperConfigDialog
import com.OKK.yes.loader.ui.configs.ConfigDialog
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Folder_open
import com.composables.icons.materialsymbols.outlined.Palette
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Security
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Tune
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun FeatureCategory.icon(): ImageVector = when (this) {
    FeatureCategory.Chat -> MaterialSymbols.Outlined.Chat
    FeatureCategory.Moments -> MaterialSymbols.Outlined.Security
    FeatureCategory.Beauty -> MaterialSymbols.Outlined.Palette
    FeatureCategory.Assist -> MaterialSymbols.Outlined.Auto_awesome
    FeatureCategory.Script -> MaterialSymbols.Outlined.Build_circle
}

// ── 功能页（搜索 + 虚拟化分类列表）──

@Composable
fun FeaturesPager(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenCategory: (FeatureCategory) -> Unit
) {
    val searching = query.isNotBlank()
    BackHandler(enabled = searching) { onQueryChange("") }

    val allFeatures = remember { FeatureCatalog.allFeatures }
    val matched = remember(query) {
        if (!searching) emptyList()
        else allFeatures.filter {
            it.title.contains(query, true) ||
                it.summary.contains(query, true) ||
                it.key.contains(query, true) ||
                it.category.title.contains(query, true)
        }
    }

    MiuixListScaffold(title = "功能大厅") {
        // 搜索框
        item(key = "search") {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "清空",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            )
        }

        if (searching) {
            // 搜索结果：虚拟化 itemsIndexed（每行独立 item，保持 LazyColumn 性能）
            item(key = "search_info") {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (matched.isEmpty()) "未匹配到任何相关功能" else "找到 ${matched.size} 个相关功能",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }
            if (matched.isNotEmpty()) {
                itemsIndexed(matched, key = { _, item -> item.key }) { index, item ->
                    Column(
                        modifier = Modifier
                            .then(if (index == 0) Modifier.padding(top = 8.dp) else Modifier)
                            .groupedCardItem(index, matched.size)
                    ) {
                        FeatureSwitchRow(feat = item)
                    }
                }
            }
        } else {
            // 分类列表：虚拟化 ArrowPreference
            item(key = "categories") {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    FeatureCatalog.categories.forEach { (cat, feats) ->
                        val onCount = FeatureCatalog.getCategoryOnCount(cat)
                        ArrowPreference(
                            title = cat.title,
                            summary = "${cat.summary} · 已开启 $onCount / ${feats.size}",
                            startAction = {
                                CategoryIconBadge(cat.icon(), tint = MiuixTheme.colorScheme.onBackground)
                            },
                            onClick = { onOpenCategory(cat) }
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(ContentBottomInset)) }
    }
}

// ── 分类下钻页面（虚拟化 FeatureRow + 批量控制）──

@Composable
fun CategoryDetailScreen(
    category: FeatureCategory,
    onBack: () -> Unit
) {
    if (category == FeatureCategory.Script) {
        ScriptCategoryDetailScreen(onBack = onBack)
        return
    }

    var refreshVersion by remember { mutableIntStateOf(0) }
    val features = remember(category, refreshVersion) {
        FeatureCatalog.getCategoryFeatures(category)
    }

    MiuixListScaffold(
        title = category.title,
        navigationIcon = { BackIconButton(onClick = onBack) }
    ) {
        item(key = "category_header") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = category.summary,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        itemsIndexed(features, key = { _, item -> "${item.key}_$refreshVersion" }) { index, feat ->
            Column(
                modifier = Modifier
                    .then(if (index == 0) Modifier.padding(top = 8.dp) else Modifier)
                    .groupedCardItem(index, features.size)
            ) {
                FeatureSwitchRow(feat = feat)
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(ContentBottomInset)) }
    }
}

// ── 在线脚本仓库数据模型与 GitHub 实时同步管理 ──

data class OnlineScript(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val category: String,
    val description: String,
    val downloads: Int,
    val stars: Int,
    val codeContent: String,
    val rawUrl: String? = null
)

object OnlineMarketStore {
    private const val GITHUB_INDEX_URL = "https://raw.githubusercontent.com/angusdevgo/OKK_Script/main/index.json"
    private val GITHUB_PAT = System.getenv("OKK_GITHUB_PAT")?.takeIf { it.isNotBlank() }
    private const val KEY_CACHED_ONLINE_SCRIPTS = "cached_online_scripts_json_v2"

    /**
     * 异步拉取 GitHub 上的公开 index.json（直连 GitHub API 并带时间戳参数防 HTTP 缓存）
     */
    fun fetchOnlineScripts(onResult: (List<OnlineScript>) -> Unit) {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // 优先使用 GitHub API 实时拉取（带 token，且无 CDN 缓存延迟）
        val apiReq = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/angusdevgo/OKK_Script/contents/index.json?_t=${System.currentTimeMillis()}")
            .apply { GITHUB_PAT?.let { header("Authorization", "token $it") } }
            .header("User-Agent", "OKK-Android-Client")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Cache-Control", "no-cache, no-store, must-revalidate")
            .build()

        client.newCall(apiReq).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // API 请求失败时，降级使用 Raw URL（带防缓存参数）
                fetchViaRawUrl(client, onResult)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        fetchViaRawUrl(client, onResult)
                        return
                    }
                    runCatching {
                        val bodyStr = resp.body?.string() ?: ""
                        val obj = org.json.JSONObject(bodyStr)
                        val contentBase64 = obj.optString("content").replace("\n", "").replace("\r", "")
                        val decodedBytes = android.util.Base64.decode(contentBase64, android.util.Base64.DEFAULT)
                        val decodedJson = String(decodedBytes, Charsets.UTF_8)
                        val list = parseScriptsJson(decodedJson)
                        if (list.isNotEmpty()) {
                            PublicConfigStore.put(KEY_CACHED_ONLINE_SCRIPTS, decodedJson, true)
                        }
                        onResult(list)
                    }.onFailure {
                        fetchViaRawUrl(client, onResult)
                    }
                }
            }
        })
    }

    private fun fetchViaRawUrl(client: okhttp3.OkHttpClient, onResult: (List<OnlineScript>) -> Unit) {
        val rawReq = okhttp3.Request.Builder()
            .url("$GITHUB_INDEX_URL?_t=${System.currentTimeMillis()}")
            .header("User-Agent", "OKK-Android-Client")
            .header("Cache-Control", "no-cache, no-store, must-revalidate")
            .build()

        client.newCall(rawReq).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                onResult(getCachedOnlineScripts())
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onResult(getCachedOnlineScripts())
                        return
                    }
                    val jsonStr = resp.body?.string() ?: ""
                    val list = parseScriptsJson(jsonStr)
                    if (list.isNotEmpty()) {
                        PublicConfigStore.put(KEY_CACHED_ONLINE_SCRIPTS, jsonStr, true)
                    }
                    onResult(list)
                }
            }
        })
    }

    /**
     * 在模块内一键上传脚本到 angusdevgo/OKK_Script GitHub 仓库
     */
    fun uploadScriptToGitHub(
        script: OnlineScript,
        onResult: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                val client = okhttp3.OkHttpClient()
                val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

                // 1. 先拉取当前仓库里的 index.json
                val getReq = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/angusdevgo/OKK_Script/contents/index.json")
                    .apply { GITHUB_PAT?.let { header("Authorization", "token $it") } }
                    .header("User-Agent", "OKK-Android-Client")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                var currentIndexList = mutableListOf<OnlineScript>()
                var indexSha: String? = null

                client.newCall(getReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string() ?: ""
                        val obj = org.json.JSONObject(bodyStr)
                        indexSha = obj.optString("sha")
                        val contentBase64 = obj.optString("content").replace("\n", "").replace("\r", "")
                        val decodedBytes = android.util.Base64.decode(contentBase64, android.util.Base64.DEFAULT)
                        val decodedJson = String(decodedBytes, Charsets.UTF_8)
                        currentIndexList = parseScriptsJson(decodedJson).toMutableList()
                    }
                }

                // 2. 将源码文件上传至 scripts/脚本ID/main.java
                val scriptPath = "scripts/${script.id}/main.java"
                val scriptCodeBase64 = android.util.Base64.encodeToString(script.codeContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val scriptFileJson = org.json.JSONObject().apply {
                    put("message", "Add script ${script.name} (${script.id})")
                    put("content", scriptCodeBase64)
                }

                val putScriptReq = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/angusdevgo/OKK_Script/contents/$scriptPath")
                    .apply { GITHUB_PAT?.let { header("Authorization", "token $it") } }
                    .header("User-Agent", "OKK-Android-Client")
                    .header("Accept", "application/vnd.github.v3+json")
                    .put(scriptFileJson.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(putScriptReq).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 422) { // 422可能是已存在，尝试继续更新index
                        onResult(false, "提交脚本源码文件失败 (code: ${resp.code})")
                        return@Thread
                    }
                }

                // 3. 更新 index.json 并提交回仓库
                val rawUrl = "https://raw.githubusercontent.com/angusdevgo/OKK_Script/main/$scriptPath"
                val newScriptItem = script.copy(rawUrl = rawUrl)
                currentIndexList.removeAll { it.id == script.id }
                currentIndexList.add(0, newScriptItem)

                val updatedIndexJsonArray = org.json.JSONArray()
                currentIndexList.forEach { item ->
                    val obj = org.json.JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("author", item.author)
                        put("version", item.version)
                        put("category", item.category)
                        put("description", item.description)
                        put("downloads", item.downloads)
                        put("stars", item.stars)
                        put("raw_url", item.rawUrl ?: "https://raw.githubusercontent.com/angusdevgo/OKK_Script/main/scripts/${item.id}/main.java")
                    }
                    updatedIndexJsonArray.put(obj)
                }

                val indexJsonStr = updatedIndexJsonArray.toString(2)
                val indexBase64 = android.util.Base64.encodeToString(indexJsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                val updateIndexBody = org.json.JSONObject().apply {
                    put("message", "Update index.json for ${script.name}")
                    put("content", indexBase64)
                    if (!indexSha.isNull_or_empty()) {
                        put("sha", indexSha)
                    }
                }

                val putIndexReq = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/angusdevgo/OKK_Script/contents/index.json")
                    .apply { GITHUB_PAT?.let { header("Authorization", "token $it") } }
                    .header("User-Agent", "OKK-Android-Client")
                    .header("Accept", "application/vnd.github.v3+json")
                    .put(updateIndexBody.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(putIndexReq).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 201 || resp.code == 200) {
                        PublicConfigStore.put(KEY_CACHED_ONLINE_SCRIPTS, indexJsonStr, true)
                        onResult(true, "上传成功！已自动提交至官方 GitHub 仓库 OKK_Script")
                    } else {
                        onResult(false, "更新 index.json 失败 (code: ${resp.code})")
                    }
                }
            } catch (e: Exception) {
                onResult(false, "网络或系统异常: ${e.message}")
            }
        }.start()
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.isEmpty()

    private fun getCachedOnlineScripts(): List<OnlineScript> {
        val raw = runCatching { PublicConfigStore.getString(KEY_CACHED_ONLINE_SCRIPTS, "") }.getOrDefault("")
        return parseScriptsJson(raw)
    }

    private fun parseScriptsJson(jsonStr: String): List<OnlineScript> {
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(jsonStr)
            val list = mutableListOf<OnlineScript>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    OnlineScript(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        author = obj.optString("author", "社区开发者"),
                        version = obj.optString("version", "1.0.0"),
                        category = obj.optString("category", "社区插件"),
                        description = obj.optString("description", "无描述"),
                        downloads = obj.optInt("downloads", 1),
                        stars = obj.optInt("stars", 1),
                        codeContent = obj.optString("codeContent", ""),
                        rawUrl = obj.optString("raw_url", null)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }
}

// ── Java 脚本专属主界面（Hchat 架构：主面板 + 在线插件二级页 + 本地插件管理二级页）──

enum class ScriptScreenPage {
    MAIN,           // 脚本插件主页
    ONLINE_MARKET,  // 在线插件二级页
    LOCAL_MANAGE    // 本地插件管理二级页
}

@Composable
fun ScriptCategoryDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val act = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    
    // 当前页面层级
    var currentPage by remember { mutableStateOf(ScriptScreenPage.MAIN) }

    // 删除弹窗状态
    var deletingPlugin by remember { mutableStateOf<JavaPlugin?>(null) }
    var activeMenuPlugin by remember { mutableStateOf<JavaPlugin?>(null) }
    var renamingPlugin by remember { mutableStateOf<JavaPlugin?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var showAgentDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }

    // 上传弹窗输入框状态
    var uploadName by remember { mutableStateOf("") }
    var uploadAuthor by remember { mutableStateOf("") }
    var uploadDesc by remember { mutableStateOf("") }
    var uploadCode by remember { mutableStateOf("") }

    // 搜索状态（全局/主页/在线/本地）
    var mainSearchQuery by remember { mutableStateOf("") }
    var onlineSearchQuery by remember { mutableStateOf("") }
    var localSearchQuery by remember { mutableStateOf("") }

    // 在线仓库状态
    var onlineScripts by remember { mutableStateOf<List<OnlineScript>>(emptyList()) }
    var isLoadingOnline by remember { mutableStateOf(true) }
    var onlineSortMode by remember { mutableIntStateOf(0) } // 0: 最新发布, 1: 最多下载

    var isEngineEnabled by remember {
        mutableStateOf(FeatureCatalog.isFeatureOn("java_plugin_enabled", false))
    }

    val installedScripts = remember(refreshKey) {
        JavaScriptEngine.listAllScriptEntries()
    }
    val installedIds = remember(installedScripts) {
        installedScripts.map { it.id }.toSet()
    }

    // 本地批量多选状态
    var selectedScriptIds by remember { mutableStateOf(setOf<String>()) }

    // 首次与刷新时从 GitHub 异步拉取列表
    LaunchedEffect(refreshKey) {
        isLoadingOnline = true
        OnlineMarketStore.fetchOnlineScripts { list ->
            onlineScripts = list
            isLoadingOnline = false
        }
    }

    val sortedOnlineScripts = remember(onlineScripts, onlineSortMode) {
        when (onlineSortMode) {
            1 -> onlineScripts.sortedByDescending { it.downloads }
            else -> onlineScripts
        }
    }

    val filteredOnlineScripts = remember(sortedOnlineScripts, onlineSearchQuery) {
        sortedOnlineScripts.filter { script ->
            onlineSearchQuery.isBlank() ||
                script.name.contains(onlineSearchQuery, ignoreCase = true) ||
                script.author.contains(onlineSearchQuery, ignoreCase = true) ||
                script.description.contains(onlineSearchQuery, ignoreCase = true) ||
                script.id.contains(onlineSearchQuery, ignoreCase = true)
        }
    }

    val filteredInstalledScripts = remember(installedScripts, mainSearchQuery) {
        installedScripts.filter { script ->
            mainSearchQuery.isBlank() ||
                script.info.name.contains(mainSearchQuery, ignoreCase = true) ||
                script.id.contains(mainSearchQuery, ignoreCase = true) ||
                (script.info.author?.contains(mainSearchQuery, ignoreCase = true) == true)
        }
    }

    val filteredLocalManageScripts = remember(installedScripts, localSearchQuery) {
        installedScripts.filter { script ->
            localSearchQuery.isBlank() ||
                script.info.name.contains(localSearchQuery, ignoreCase = true) ||
                script.id.contains(localSearchQuery, ignoreCase = true) ||
                (script.info.author?.contains(localSearchQuery, ignoreCase = true) == true)
        }
    }

    // 轮询 SAF 文件夹/ZIP 选取结果
    LaunchedEffect(Unit) {
        while (true) {
            val uriStr = JavaScriptEngine.takePendingImportUri()
            if (uriStr != null) {
                val uri = android.net.Uri.parse(uriStr)
                coroutineScope.launch(Dispatchers.IO) {
                    if (showUploadDialog) {
                        val parsed = JavaScriptEngine.parseScriptFromUri(context, uri)
                        withContext(Dispatchers.Main) {
                            if (parsed != null) {
                                uploadName = parsed.name
                                uploadAuthor = parsed.author
                                uploadDesc = parsed.description
                                uploadCode = parsed.codeContent
                                Toast.makeText(context, "已成功从 ZIP/目录 提取脚本「" + parsed.name + "」！", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "解析 ZIP/目录 失败，请检查是否包含 info.prop 或 main.java", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        val (success, message) = JavaScriptEngine.importScriptFolder(context, uri)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(context, "已成功导入脚本：" + message, Toast.LENGTH_SHORT).show()
                                refreshKey++
                            } else {
                                Toast.makeText(context, "导入脚本失败：" + message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            delay(300)
        }
    }

    // 拦截返回键
    BackHandler(enabled = currentPage != ScriptScreenPage.MAIN) {
        currentPage = ScriptScreenPage.MAIN
    }

    when (currentPage) {
        ScriptScreenPage.MAIN -> {
            // ══════════════════════════════════════════════════════════
            // 1. 主页面：Hchat 架构设计
            // ══════════════════════════════════════════════════════════
            MiuixListScaffold(
                title = "脚本插件",
                navigationIcon = { BackIconButton(onClick = onBack) }
            ) {
                // 搜索功能和插件
                item(key = "main_search_card") {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Search,
                                contentDescription = "搜索",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = mainSearchQuery,
                                onValueChange = { mainSearchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (mainSearchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索功能和插件",
                                            fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                    innerTextField()
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary)
                            )
                            if (mainSearchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { mainSearchQuery = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Close,
                                        contentDescription = "清除",
                                        modifier = Modifier.size(16.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                // 分组标题：脚本插件
                item(key = "section_plugins_title") {
                    SmallTitle(
                        text = "脚本插件",
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
                    )
                }

                // 插件总开关 Card
                item(key = "main_plugin_switch_card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "插件总开关",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF1E88E5).copy(alpha = 0.15f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("单击", fontSize = 10.sp, color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    text = "启动时自动加载已启用插件\n相关说明:\n请确认插件安全再进行加载，否则造成的后果需自行承担。",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp),
                                    lineHeight = 16.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = isEngineEnabled,
                                onCheckedChange = { next ->
                                    isEngineEnabled = next
                                    FeatureCatalog.setFeatureOn("java_plugin_enabled", next)
                                    if (next) {
                                        JavaScriptEngine.reloadAll()
                                        refreshKey++
                                    }
                                }
                            )
                        }
                    }
                }

                // 功能入口 1: 插件 Agent (对齐 Hchat 架构)
                item(key = "entry_agent_plugins") {
                    Spacer(Modifier.height(10.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAgentDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "插件 Agent",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "按需求生成或修改脚本插件",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }

                // 功能入口 2: 在线插件
                item(key = "entry_online_plugins") {
                    Spacer(Modifier.height(10.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentPage = ScriptScreenPage.ONLINE_MARKET
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "在线插件",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "浏览、安装或上传社区脚本插件",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }

                // 功能入口 2: 本地插件管理
                item(key = "entry_local_plugins") {
                    Spacer(Modifier.height(10.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentPage = ScriptScreenPage.LOCAL_MANAGE
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "本地插件管理",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "排序、置顶、导入、导出或批量管理插件",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Chevron_right,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }

                // 分组标题：本地插件列表
                item(key = "section_local_list_title") {
                    SmallTitle(
                        text = "本地插件(" + installedScripts.size + ")",
                        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)
                    )
                }

                if (filteredInstalledScripts.isEmpty()) {
                    item(key = "main_installed_empty") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (mainSearchQuery.isEmpty()) "暂无插件" else "无匹配插件",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(filteredInstalledScripts, key = { _, script -> "main_inst_" + script.id + "_" + refreshKey }) { index, script ->
                        var isEnabled by remember(script.id, refreshKey) { mutableStateOf(script.isEnabled) }
                        val verStr = script.info.version?.takeIf { it.isNotBlank() } ?: "1.0"
                        val displayName = "${script.info.name}($verStr)"

                        Column(
                            modifier = Modifier
                                .then(if (index == 0) Modifier.padding(top = 2.dp) else Modifier)
                                .groupedCardItem(index, filteredInstalledScripts.size)
                                .clickable {
                                    kotlin.concurrent.thread {
                                        val opened = JavaScriptEngine.openScriptSettings(script.id)
                                        if (!opened) {
                                            (context as? android.app.Activity)?.runOnUiThread {
                                                Toast.makeText(context, "插件「${script.info.name}」未提供设置界面", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = displayName,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF1E88E5).copy(alpha = 0.15f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text("单击", fontSize = 10.sp, color = Color(0xFF1E88E5), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(
                                        text = script.id,
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    val author = script.info.author?.takeIf { it.isNotBlank() } ?: "Hchat"
                                    val updateTime = script.info.updateTime?.takeIf { it.isNotBlank() } ?: "未知"
                                    Text(
                                        text = "作者: $author | 更新于: $updateTime",
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                // 右侧操作区域：设置 (齿轮) + 更多 (三点) + 开关 (Switch)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // 1. 设置按钮 (齿轮)
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                kotlin.concurrent.thread {
                                                    val opened = JavaScriptEngine.openScriptSettings(script.id)
                                                    if (!opened) {
                                                        (context as? android.app.Activity)?.runOnUiThread {
                                                            Toast.makeText(context, "插件「${script.info.name}」未提供设置界面", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Settings,
                                            contentDescription = "设置",
                                            modifier = Modifier.size(20.dp),
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }

                                    // 2. 更多操作按钮 (竖向三点)
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                activeMenuPlugin = script
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.More_vert,
                                            contentDescription = "更多操作",
                                            modifier = Modifier.size(20.dp),
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }

                                    // 3. Switch 开关
                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = { next ->
                                            if (JavaScriptEngine.setScriptEnabled(script.id, next)) {
                                                isEnabled = next
                                                JavaScriptEngine.reloadAll()
                                                refreshKey++
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "main_bottom_spacer") { Spacer(Modifier.height(ContentBottomInset)) }
            }
        }

        ScriptScreenPage.ONLINE_MARKET -> {
            // ══════════════════════════════════════════════════════════
            // 2. 在线插件二级页：对齐 Hchat 在线插件设计
            // ══════════════════════════════════════════════════════════
            MiuixListScaffold(
                title = "在线插件",
                navigationIcon = { BackIconButton(onClick = { currentPage = ScriptScreenPage.MAIN }) }
            ) {
                // 顶部搜索框
                item(key = "online_search_bar") {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Search,
                                contentDescription = "搜索",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = onlineSearchQuery,
                                onValueChange = { onlineSearchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (onlineSearchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索插件、作者或目录名",
                                            fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                    innerTextField()
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary)
                            )
                            if (onlineSearchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onlineSearchQuery = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Close,
                                        contentDescription = "清除",
                                        modifier = Modifier.size(16.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                // 分组标题：浏览
                item(key = "section_online_browse_title") {
                    SmallTitle(
                        text = "浏览",
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
                    )
                }

                // 排序方式与刷新 Card
                item(key = "online_browse_actions_card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            // 排序方式
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onlineSortMode = (onlineSortMode + 1) % 2
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "排序方式",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when (onlineSortMode) {
                                            1 -> "最多下载"
                                            else -> "最新发布"
                                        },
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = when (onlineSortMode) {
                                            1 -> "最多下载"
                                            else -> "最新"
                                        },
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Unfold_more,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }

                            // 刷新
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        refreshKey++
                                        Toast.makeText(context, "正在刷新在线插件列表...", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "刷新",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "重新获取当前列表",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }

                // 社区插件说明 Card
                item(key = "online_notice_card") {
                    Spacer(Modifier.height(10.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "社区插件由用户上传，安装前请核对作者、说明和文件内容。下载后的插件默认禁用。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }

                // 分组标题：在线插件列表
                item(key = "section_online_list_title") {
                    SmallTitle(
                        text = "在线插件 (" + filteredOnlineScripts.size + ")",
                        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)
                    )
                }

                if (isLoadingOnline && onlineScripts.isEmpty()) {
                    item(key = "online_loading_card") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "🌐 正在连接 GitHub 仓库同步最新在线脚本...",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else if (filteredOnlineScripts.isEmpty()) {
                    item(key = "online_empty_card") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂无匹配的在线插件\n点击底部「上传本地插件」即可将你的插件发布至 GitHub！",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(filteredOnlineScripts, key = { _, script -> "online_" + script.id }) { index, script ->
                        val installedMatch = installedScripts.firstOrNull { it.id == script.id || it.info.name == script.name }
                        val isInstalled = installedMatch != null

                        Column(
                            modifier = Modifier
                                .then(if (index == 0) Modifier.padding(top = 2.dp) else Modifier)
                                .groupedCardItem(index, filteredOnlineScripts.size)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isInstalled) {
                                            deletingPlugin = installedMatch
                                        } else {
                                            // 点击安装
                                            val rawUrl = script.rawUrl
                                            if (rawUrl != null && rawUrl.isNotEmpty()) {
                                                Toast.makeText(context, "正在从 GitHub 拉取脚本源码...", Toast.LENGTH_SHORT).show()
                                                Thread {
                                                    runCatching {
                                                        val client = okhttp3.OkHttpClient()
                                                        val req = okhttp3.Request.Builder().url(rawUrl).build()
                                                        client.newCall(req).execute().use { resp ->
                                                            val code = resp.body?.string() ?: ""
                                                            if (code.isNotBlank()) {
                                                                val ok = JavaScriptEngine.installScriptCode(
                                                                    id = script.id,
                                                                    name = script.name,
                                                                    author = script.author,
                                                                    version = script.version,
                                                                    code = code
                                                                )
                                                                (context as? Activity)?.runOnUiThread {
                                                                    if (ok) {
                                                                        Toast.makeText(context, "已成功安装插件「" + script.name + "」！", Toast.LENGTH_SHORT).show()
                                                                        refreshKey++
                                                                    } else {
                                                                        Toast.makeText(context, "解析落盘脚本失败", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }.onFailure {
                                                        (context as? Activity)?.runOnUiThread {
                                                            Toast.makeText(context, "下载脚本源码失败: " + it.message, Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }.start()
                                            } else {
                                                val ok = JavaScriptEngine.installScriptCode(
                                                    id = script.id,
                                                    name = script.name,
                                                    author = script.author,
                                                    version = script.version,
                                                    code = script.codeContent
                                                )
                                                if (ok) {
                                                    Toast.makeText(context, "已成功安装插件「" + script.name + "」！", Toast.LENGTH_SHORT).show()
                                                    refreshKey++
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = script.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "作者: " + script.author + " | 版本: " + script.version + " | 下载: " + script.downloads,
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    Text(
                                        text = if (script.description.isNotBlank()) "说明: " + script.description else "更新: 2026-08-22",
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                // 右侧操作按钮：安装 / 删除 (使用自适应 Box 芯片，彻底消除 Miuix Button 挤压截断问题)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isInstalled) Color(0x1CE53935) else MiuixTheme.colorScheme.primary
                                        )
                                        .clickable {
                                            if (isInstalled) {
                                                deletingPlugin = installedMatch
                                            } else {
                                                val rawUrl = script.rawUrl
                                                if (rawUrl != null && rawUrl.isNotEmpty()) {
                                                    Toast.makeText(context, "正在从 GitHub 拉取脚本源码...", Toast.LENGTH_SHORT).show()
                                                    Thread {
                                                        runCatching {
                                                            val client = okhttp3.OkHttpClient()
                                                            val req = okhttp3.Request.Builder().url(rawUrl).build()
                                                            client.newCall(req).execute().use { resp ->
                                                                val code = resp.body?.string() ?: ""
                                                                if (code.isNotBlank()) {
                                                                    val ok = JavaScriptEngine.installScriptCode(
                                                                        id = script.id,
                                                                        name = script.name,
                                                                        author = script.author,
                                                                        version = script.version,
                                                                        code = code
                                                                    )
                                                                    (context as? Activity)?.runOnUiThread {
                                                                        if (ok) {
                                                                            Toast.makeText(context, "已成功安装插件「" + script.name + "」！", Toast.LENGTH_SHORT).show()
                                                                            refreshKey++
                                                                        } else {
                                                                            Toast.makeText(context, "解析落盘脚本失败", Toast.LENGTH_SHORT).show()
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }.onFailure {
                                                            (context as? Activity)?.runOnUiThread {
                                                                Toast.makeText(context, "下载脚本源码失败: " + it.message, Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }.start()
                                                } else {
                                                    val ok = JavaScriptEngine.installScriptCode(
                                                        id = script.id,
                                                        name = script.name,
                                                        author = script.author,
                                                        version = script.version,
                                                        code = script.codeContent
                                                    )
                                                    if (ok) {
                                                        Toast.makeText(context, "已成功安装插件「" + script.name + "」！", Toast.LENGTH_SHORT).show()
                                                        refreshKey++
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isInstalled) MaterialSymbols.Outlined.Delete else MaterialSymbols.Outlined.File_download,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                            tint = if (isInstalled) Color(0xFFE53935) else MiuixTheme.colorScheme.onPrimary
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (isInstalled) "删除" else "安装",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isInstalled) Color(0xFFE53935) else MiuixTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部操作栏：【返回】 + 【上传本地插件】（严格对齐 Hchat 底部按钮）
                item(key = "online_bottom_action_bar") {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { currentPage = ScriptScreenPage.MAIN },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("返回", fontSize = 14.sp)
                        }

                        Button(
                            onClick = { showUploadDialog = true },
                            modifier = Modifier
                                .weight(1.5f)
                                .height(44.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.File_upload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("上传本地插件", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                item(key = "online_bottom_spacer") { Spacer(Modifier.height(ContentBottomInset)) }
            }
        }

        ScriptScreenPage.LOCAL_MANAGE -> {
            // ══════════════════════════════════════════════════════════
            // 3. 本地插件管理二级页：对齐 Hchat 本地插件管理设计
            // ══════════════════════════════════════════════════════════
            MiuixListScaffold(
                title = "本地插件管理",
                navigationIcon = { BackIconButton(onClick = { currentPage = ScriptScreenPage.MAIN }) }
            ) {
                // 顶部搜索框
                item(key = "local_search_bar") {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Search,
                                contentDescription = "搜索",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = localSearchQuery,
                                onValueChange = { localSearchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (localSearchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索本地插件",
                                            fontSize = 14.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                    innerTextField()
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary)
                            )
                            if (localSearchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { localSearchQuery = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Close,
                                        contentDescription = "清除",
                                        modifier = Modifier.size(16.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                // 分组标题：管理
                item(key = "section_manage_actions_title") {
                    SmallTitle(
                        text = "管理",
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
                    )
                }

                // 导入插件与批量多选操作 Card
                item(key = "local_manage_top_card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            // 导入插件
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        act?.let { a ->
                                            runCatching {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                                    type = "*/*"
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                                }
                                                a.startActivityForResult(intent, JavaScriptEngine.REQ_SCRIPT_FOLDER)
                                            }.onFailure { e ->
                                                Toast.makeText(context, "无法打开选择器: " + e.message, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.File_download,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MiuixTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "导入插件",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "从 ZIP 文件或目录导入，导入后默认关闭",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }

                            // 批量全选 / 反选 / 删除操作栏 (自适应芯片按钮，彻底解决截断挤压)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (selectedScriptIds.isEmpty()) "未选择插件" else "已选择 " + selectedScriptIds.size + " 项",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 全选芯片
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                                            .clickable {
                                                selectedScriptIds = installedScripts.map { it.id }.toSet()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "全选",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MiuixTheme.colorScheme.primary
                                        )
                                    }

                                    // 反选芯片
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MiuixTheme.colorScheme.surfaceVariant)
                                            .clickable {
                                                val allIds = installedScripts.map { it.id }.toSet()
                                                selectedScriptIds = allIds - selectedScriptIds
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "反选",
                                            fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
                                    }

                                    // 批量删除芯片
                                    if (selectedScriptIds.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0x1CE53935))
                                                .clickable {
                                                    selectedScriptIds.forEach { id ->
                                                        JavaScriptEngine.deleteScript(id)
                                                    }
                                                    selectedScriptIds = emptySet()
                                                    refreshKey++
                                                    Toast.makeText(context, "已批量删除选中的插件", Toast.LENGTH_SHORT).show()
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "删除(" + selectedScriptIds.size + ")",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFE53935)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 插件列表
                if (filteredLocalManageScripts.isEmpty()) {
                    item(key = "local_manage_empty") {
                        Spacer(Modifier.height(40.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无本地插件",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    itemsIndexed(filteredLocalManageScripts, key = { _, entry -> "loc_manage_" + entry.id + "_" + refreshKey }) { index, entry ->
                        val isSelected = selectedScriptIds.contains(entry.id)
                        Column(
                            modifier = Modifier
                                .then(if (index == 0) Modifier.padding(top = 10.dp) else Modifier)
                                .groupedCardItem(index, filteredLocalManageScripts.size)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedScriptIds = if (isSelected) {
                                            selectedScriptIds - entry.id
                                        } else {
                                            selectedScriptIds + entry.id
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 选中勾选框
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Check_circle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MiuixTheme.colorScheme.onPrimary
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            selectedScriptIds = if (isSelected) {
                                                selectedScriptIds - entry.id
                                            } else {
                                                selectedScriptIds + entry.id
                                            }
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = (index + 1).toString() + ". " + entry.info.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (entry.isEnabled) Color(0x1F4CAF50) else MiuixTheme.colorScheme.surfaceVariant
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (entry.isEnabled) "已启用" else "已禁用",
                                                fontSize = 10.sp,
                                                color = if (entry.isEnabled) Color(0xFF4CAF50) else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            )
                                        }
                                    }
                                    Text(
                                        text = entry.id + (entry.info.version?.let { " · v" + it } ?: ""),
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 启用 / 禁用 Switch 开关
                                    Switch(
                                        checked = entry.isEnabled,
                                        onCheckedChange = { next ->
                                            JavaScriptEngine.setScriptEnabled(entry.id, next)
                                            JavaScriptEngine.reloadAll()
                                            refreshKey++
                                        }
                                    )
                                    // 上移
                                    Box(
                                        modifier = Modifier
                                            .size(width = 36.dp, height = 32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (index > 0) MiuixTheme.colorScheme.surfaceVariant
                                                else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                            )
                                            .clickable(enabled = index > 0) {
                                                JavaScriptEngine.moveScript(entry.id, -1)
                                                refreshKey++
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Arrow_upward,
                                            contentDescription = "上移",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (index > 0) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                                        )
                                    }

                                    // 下移
                                    Box(
                                        modifier = Modifier
                                            .size(width = 36.dp, height = 32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (index < filteredLocalManageScripts.size - 1) MiuixTheme.colorScheme.surfaceVariant
                                                else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                            )
                                            .clickable(enabled = index < filteredLocalManageScripts.size - 1) {
                                                JavaScriptEngine.moveScript(entry.id, 1)
                                                refreshKey++
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Arrow_downward,
                                            contentDescription = "下移",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (index < filteredLocalManageScripts.size - 1) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                                        )
                                    }

                                    // 删除
                                    Box(
                                        modifier = Modifier
                                            .size(width = 36.dp, height = 32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0x1CE53935))
                                            .clickable {
                                                deletingPlugin = entry
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = MaterialSymbols.Outlined.Delete,
                                            contentDescription = "删除",
                                            modifier = Modifier.size(16.dp),
                                            tint = Color(0xFFE53935)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部【返回】按钮
                item(key = "local_manage_bottom_bar") {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { currentPage = ScriptScreenPage.MAIN },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text("返回", fontSize = 14.sp)
                    }
                }

                item(key = "local_manage_bottom_spacer") { Spacer(Modifier.height(ContentBottomInset)) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 插件更多操作弹窗 (对齐 Hchat 底部操作菜单)
    // ══════════════════════════════════════════════════════════
    activeMenuPlugin?.let { plugin ->
        ConfigDialog(
            title = plugin.info.name,
            onDismiss = { activeMenuPlugin = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. 置顶
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable {
                            JavaScriptEngine.pinScript(plugin.id)
                            Toast.makeText(context, "已将「${plugin.info.name}」置顶", Toast.LENGTH_SHORT).show()
                            activeMenuPlugin = null
                            refreshKey++
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Push_pin,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "置顶",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }

                // 2. 重命名
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable {
                            val curName = plugin.info.name
                            renameNewName = curName
                            renamingPlugin = plugin
                            activeMenuPlugin = null
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "重命名",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }

                // 3. 导出
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable {
                            val path = JavaScriptEngine.exportScriptZip(context, plugin.id)
                            activeMenuPlugin = null
                            if (path != null) {
                                Toast.makeText(context, "已成功导出至：$path", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.File_upload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "导出",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }

                // 4. 删除 (红色危险操作)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x1CE53935))
                        .clickable {
                            deletingPlugin = plugin
                            activeMenuPlugin = null
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFFE53935)
                        )
                        Text(
                            text = "删除",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE53935)
                        )
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 重命名插件弹窗
    // ══════════════════════════════════════════════════════════
    renamingPlugin?.let { plugin ->
        ConfigDialog(
            title = "重命名插件",
            onDismiss = { renamingPlugin = null },
            bottomBar = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { renamingPlugin = null },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Text("取消", fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            if (renameNewName.isNotBlank()) {
                                JavaScriptEngine.renameScript(plugin.id, renameNewName.trim())
                                Toast.makeText(context, "已重命名为「$renameNewName」", Toast.LENGTH_SHORT).show()
                                renamingPlugin = null
                                refreshKey++
                            } else {
                                Toast.makeText(context, "插件名称不能为空", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Text("保存", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                TextField(
                    value = renameNewName,
                    onValueChange = { renameNewName = it },
                    label = "插件显示名称",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 插件 Agent 弹窗
    // ══════════════════════════════════════════════════════════
    if (showAgentDialog) {
        ConfigDialog(
            title = "插件 Agent",
            onDismiss = { showAgentDialog = false },
            bottomBar = {
                Button(
                    onClick = { showAgentDialog = false },
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text("知道了", fontSize = 13.sp)
                }
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🤖 OKK 智能插件 Agent",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Text(
                    text = "OKK 支持在微信内使用纯标准 Java / BeanShell 编写强大的增强插件，并已完美兼容 HChat 全量插件生态。\n\n你可以使用 AI 编码助手（如 ChatGPT、Claude、DeepSeek）输入开发需求，Agent 将自动为你生成包含 info.prop 和 main.java 的即用插件包！",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    lineHeight = 18.sp
                )
                Text(
                    text = "📚 插件存放目录：\n/sdcard/Android/media/com.tencent.mm/OKK/scripts/\n\n支持直接将 ZIP 包或脚本文件夹导入本地，或在【在线插件】中一键下载社区分享插件。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 16.sp
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 全局删除确认弹窗
    // ══════════════════════════════════════════════════════════
    deletingPlugin?.let { plugin ->
        ConfigDialog(
            title = "删除脚本",
            onDismiss = { deletingPlugin = null },
            bottomBar = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { deletingPlugin = null },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Text("取消", fontSize = 13.sp)
                    }
                    Button(
                        onClick = {
                            val ok = JavaScriptEngine.deleteScript(plugin.id)
                            deletingPlugin = null
                            if (ok) {
                                Toast.makeText(context, "已成功删除脚本「" + plugin.info.name + "」", Toast.LENGTH_SHORT).show()
                                refreshKey++
                            } else {
                                Toast.makeText(context, "删除失败，请检查文件权限", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Text("确认删除", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                    }
                }
            }
        ) {
            Text(
                text = "确定要永久删除脚本「" + plugin.info.name + "」吗？\n删除后该脚本文件将被从本地存储中彻底移除，不可恢复。",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    // 上传脚本弹窗
    // ══════════════════════════════════════════════════════════
    if (showUploadDialog) {
        var selectedLocalScript by remember { mutableStateOf<JavaPlugin?>(null) }

        ConfigDialog(
            title = "上传脚本至 GitHub 仓库",
            onDismiss = { if (!isUploading) showUploadDialog = false },
            bottomBar = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showUploadDialog = false },
                        enabled = !isUploading,
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Text("取消", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (uploadName.isBlank()) {
                                Toast.makeText(context, "请填写脚本名称", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isUploading = true
                            val scriptId = selectedLocalScript?.id ?: ("script_" + System.currentTimeMillis())
                            val newScript = OnlineScript(
                                id = scriptId,
                                name = uploadName.trim(),
                                author = uploadAuthor.ifBlank { "社区开发者" },
                                version = "1.0.0",
                                category = "社区插件",
                                description = uploadDesc.ifBlank { "无详细描述" },
                                downloads = 1,
                                stars = 1,
                                codeContent = uploadCode.ifBlank {
                                    """
                                        void onHandleMsg(Object msgInfoBean) {
                                            if (msgInfoBean == null) return;
                                            log("新脚本触发消息: " + msgInfoBean.getContent());
                                        }
                                    """.trimIndent()
                                }
                            )

                            OnlineMarketStore.uploadScriptToGitHub(newScript) { success, msg ->
                                (context as? Activity)?.runOnUiThread {
                                    isUploading = false
                                    if (success) {
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        showUploadDialog = false
                                        refreshKey++
                                    } else {
                                        Toast.makeText(context, "上传失败: " + msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isUploading,
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Text(if (isUploading) "上传中..." else "确认上传至 GitHub", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "脚本将直接提交上传至官方 GitHub 开源仓库 OKK_Script：",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )

                TextField(
                    value = uploadName,
                    onValueChange = { uploadName = it },
                    label = "脚本名称 (例: 实时金价)",
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = uploadAuthor,
                    onValueChange = { uploadAuthor = it },
                    label = "作者签名",
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = uploadDesc,
                    onValueChange = { uploadDesc = it },
                    label = "功能简介说明",
                    modifier = Modifier.fillMaxWidth()
                )

                if (installedScripts.isNotEmpty()) {
                    Text(
                        "快捷点选已安装脚本：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(installedScripts) { item ->
                            val isSel = selectedLocalScript?.id == item.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer)
                                    .clickable {
                                        selectedLocalScript = item
                                        uploadName = item.info.name
                                        uploadAuthor = item.info.author ?: "社区开发者"
                                        uploadCode = item.content
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    item.info.name,
                                    fontSize = 11.sp,
                                    color = if (isSel) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 选取 ZIP / 目录解析填充按钮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable {
                            act?.let { a ->
                                runCatching {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    a.startActivityForResult(intent, JavaScriptEngine.REQ_SCRIPT_FOLDER)
                                }.onFailure {
                                    Toast.makeText(context, "无法打开选择器: " + it.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Folder_open,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.primary
                        )
                        Text(
                            "选取本地 ZIP 压缩包或目录填充",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ── 单条功能开关（对齐 wcx FeatureRow：SwitchPreference + 齿轮图标 + hasConfig 点击配置）──

@Composable
fun FeatureSwitchRow(feat: FeatureDescriptor) {
    val activity = LocalContext.current as? Activity
    var on by remember(feat.key) {
        mutableStateOf(FeatureCatalog.isFeatureOn(feat.key, feat.defaultOn))
    }
    var showConfig by remember(feat.key) { mutableStateOf(false) }

    if (feat.hasConfig) {
        // 带配置项：BasicComponent + 齿轮图标 + Switch（对齐 wcx ClickableFeature）
        BasicComponent(
            onClick = { showConfig = true },
            endActions = {
                Switch(
                    checked = on,
                    onCheckedChange = { next ->
                        on = next
                        FeatureCatalog.setFeatureOn(feat.key, next)
                    }
                )
            },
            modifier = Modifier
        ) {
            // 标题行 + 角标 + 齿轮图标
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = feat.title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = BasicComponentDefaults.titleColor().color,
                )
                if (!feat.badge.isNullOrEmpty()) {
                    val badgeBg = if (feat.badgeWarning) Color(0xFFFFF3E0) else MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    val badgeFg = if (feat.badgeWarning) Color(0xFFE65100) else MiuixTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = feat.badge,
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = badgeFg
                        )
                    }
                }
                if (feat.hasConfig) {
                    Spacer(
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Settings,
                        contentDescription = "可配置",
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Text(
                text = feat.summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = BasicComponentDefaults.summaryColor().color,
            )
        }
    } else {
        // 纯开关项：SwitchPreference
        SwitchPreference(
            title = feat.title,
            summary = feat.summary,
            checked = on,
            onCheckedChange = { next ->
                on = next
                FeatureCatalog.setFeatureOn(feat.key, next)
            }
        )
    }

    // 二级配置弹窗
    if (feat.hasConfig && showConfig) {
        ConfigDialogForFeature(feat.key) { showConfig = false }
    }
}

// ── 根据 key 弹出对应的二级配置 Dialog ──

@Composable
fun ConfigDialogForFeature(key: String, onDismiss: () -> Unit) {
    when (key) {
        "anti_revoke" -> AntiRevokeConfigDialog(onDismiss)
        "detail_enabled" -> MessageDetailConfigDialog(onDismiss)
        "input_stats_enabled" -> InputStatsConfigDialog(onDismiss)
        "chat_toolbar_enabled" -> ChatToolbarConfigDialog(onDismiss)
        "member_title" -> MemberTitleConfigDialog(onDismiss)
        "conversation_grouping_enabled" -> ConversationGroupingConfigDialog(onDismiss)
        "bubble_enabled" -> BubbleConfigDialog(onDismiss)
        "round_avatar_enabled" -> AvatarConfigDialog(onDismiss)
        "theme_wallpaper_enabled" -> ThemeWallpaperConfigDialog(onDismiss)
        "virtual_location_enabled" -> LocationConfigDialog(onDismiss)
        "auto_login_win_enabled" -> AutoLoginConfigDialog(onDismiss)
        "download_redirect_enabled" -> DownloadRedirectConfigDialog(onDismiss)
        "finder_video_download_enabled" -> FinderVideoDownloadConfigDialog(onDismiss)
        "java_plugin_enabled" -> JavaPluginConfigDialog(onDismiss)
        "bottom_tab_hide_bar", "bottom_tab_hide_title" -> DefaultBarConfigDialog(onDismiss)
        "bottom_tab_floating" -> FloatingTabConfigDialog(onDismiss)
        "floating_quick_entry" -> FloatingQuickEntryConfigDialog(onDismiss)
        "conv_card_enabled" -> ConvCardConfigDialog(onDismiss)
        "close_friend_enabled" -> com.OKK.yes.loader.ui.configs.CloseFriendConfigDialog(onDismiss)
        else -> onDismiss()
    }
}
