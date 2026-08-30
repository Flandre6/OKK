<p align="center">
  <img src="./app/src/main/res/drawable/ic_launcher.png" width="120" height="120" alt="OKK Logo">
</p>

<h1 align="center">OKK</h1>

<p align="center">
  <b>基于 LSPosed / Xposed 的微信全能体验增强模块</b>
</p>

<p align="center">
  提供消息保护 · 聊天增强 · 会话分组 · 界面美化 · 实用工具 · 在线插件扩展
</p>

<p align="center">
  <a href="https://www.android.com/"><img src="https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square&logo=android" alt="Platform"></a>
  <a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20libxposed-blue.svg?style=flat-square" alt="Framework"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Language-Kotlin-purple.svg?style=flat-square&logo=kotlin" alt="Language"></a>
  <a href="https://github.com/matsushita-takeo/MIUIX-KMP"><img src="https://img.shields.io/badge/UI-Compose%20%26%20MIUIX-ff69b4.svg?style=flat-square" alt="UI Style"></a>
  <a href="https://t.me/OKK_YES"><img src="https://img.shields.io/badge/Telegram-%E5%AE%98%E6%96%B9%E4%BA%A4%E6%B5%81%E7%BE%A4-2CA5E0.svg?style=flat-square&logo=telegram" alt="Telegram"></a>
</p>

---

> ⚠️ **免责声明**  
> 本项目仅供 Android 逆向工程、Hook 技术交流与个人本地学习分析使用。请勿用于任何商业用途或违反微信用户服务协议的行为。

---

## ✨ 核心特性

<table>
  <tr>
    <td width="50%">
      <b>🛡️ 消息保护</b>
      <ul>
        <li><b>消息防撤回</b>：支持拦截文本/图片/语音/视频撤回并显式标记</li>
        <li><b>朋友圈防删除</b>：保留好友已删除的动态内容</li>
        <li><b>评论防撤回</b>：朋友圈评论删除拦截与记录</li>
        <li><b>自身撤回保留</b>：可配置自己撤回的消息本地保留</li>
      </ul>
    </td>
    <td width="50%">
      <b>💬 聊天增强</b>
      <ul>
        <li><b>会话气泡</b>：支持气泡主题与双向样式定制</li>
        <li><b>精确时间</b>：毫秒级时间、自定义时间格式显示</li>
        <li><b>快捷引用与编辑</b>：消息长按快捷引用与重新编辑</li>
        <li><b>群头衔 & 实名尾字</b>：群聊成员身份与转账实名特征展示</li>
        <li><b>输入统计</b>：输入框实时字数与行数统计</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <b>📁 会话分组与管理</b>
      <ul>
        <li><b>多 Tab 分组栏</b>：微信首页顶部/底部会话分组</li>
        <li><b>智能预设</b>：置顶、单聊、群聊、公众号、服务号自动归类</li>
        <li><b>自定义分组</b>：自由创建分组、拖拽排序、批量拉人</li>
        <li><b>密友模式</b>：支持好友与群聊隐身伪装</li>
      </ul>
    </td>
    <td width="50%">
      <b>🎨 界面美化</b>
      <ul>
        <li><b>悬浮底部栏</b>：Liquid / 毛玻璃悬浮导航，支持阻尼手势</li>
        <li><b>全景壁纸透视</b>：首页/会话列表全覆盖壁纸与半透明卡片</li>
        <li><b>圆角头像与卡片</b>：自定义圆角半径、去除分割线</li>
        <li><b>原生 Compose UI</b>：微信内嵌 MIUIX 风格设置页</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td width="50%">
      <b>🧰 实用工具箱</b>
      <ul>
        <li><b>下载重定向</b>：自动转存接收文件至自定义存储目录</li>
        <li><b>视频号无水印下载</b>：一键提取并保存视频号资源</li>
        <li><b>虚拟位置模拟</b>：支持地图选点与坐标伪装</li>
        <li><b>PC 自动登录</b>：免手机手动确认自动同意登录</li>
        <li><b>屏蔽热更新</b>：拦截补丁包下发，锁定当前运行环境</li>
      </ul>
    </td>
    <td width="50%">
      <b>🧩 在线扩展市场</b>
      <ul>
        <li><b>Java / BSH 脚本引擎</b>：支持动态脚本扩展功能</li>
        <li><b>官方在线市场</b>：一键浏览、下载、更新在线插件</li>
        <li><b>热加载执行</b>：无需重启微信即可动态载入脚本能力</li>
      </ul>
    </td>
  </tr>
</table>

---

## 🏗️ 架构与模块划分

```text
OKK/
├── app/       # 壳应用：提供 APK 宿主入口、Xposed Scope 与基础配置
├── loader/    # 核心加载器：libxposed / Xposed 入口、Compose 设置界面
├── core/      # Hook 引擎：DexKit 智能特征匹配、业务 Hook 逻辑实现
├── monitor/   # 辅助监控模块
├── bsh/       # 动态脚本解释引擎（基于 Beanshell 与 JavaCC）
└── pack.ps1   # 自动化发版打包脚本（含 R8 混淆、签名与验证）
```

---

## 🛠️ 构建与编译

### 前置要求
- **操作系统**：Windows 10/11、macOS 或 Linux
- **JDK**：OpenJDK 17
- **Android SDK**：API Level 35+，Build-Tools 35.0.0+
- **构建工具**：Gradle 8.x

### 1. 克隆仓库
```bash
git clone https://github.com/angusdevgo/OKK.git
cd OKK
```

### 2. 配置本地签名（可选）
仓库出于安全原因不包含正式发布 Keystore。在本地打包 Release 版本前，可通过环境变量注入你的私有密钥配置：

```powershell
# PowerShell
$env:OKK_STORE_FILE = "C:\path\to\your-release.keystore"
$env:OKK_STORE_PASSWORD = "your-store-password"
$env:OKK_KEY_ALIAS = "your-key-alias"
$env:OKK_KEY_PASSWORD = "your-key-password"
```

```bash
# Bash
export OKK_STORE_FILE="/path/to/your-release.keystore"
export OKK_STORE_PASSWORD="your-store-password"
export OKK_KEY_ALIAS="your-key-alias"
export OKK_KEY_PASSWORD="your-key-password"
```

> *注：未配置签名环境变量时，Release 构建将自动生成 Unsigned（未签名）APK。*

### 3. 执行编译
```powershell
# 使用专用打包脚本（推荐，自动启用 R8 与优化）
.\pack.ps1 -NoInstall

# 或直接通过 Gradle 构建
.\gradlew.bat :app:assembleRelease
```

---

## 💡 使用指南

1. 安装已激活 **LSPosed**（或支持现代 Xposed API 的框架）的环境。
2. 编译或下载安装 **OKK** APK。
3. 在 LSPosed 管理器中启用 OKK 模块，作用域勾选 **微信 (`com.tencent.mm`)**。
4. 强行停止并重新打开微信。
5. 在微信主界面或通过浮动入口进入 **OKK 设置** 开启所需功能。

---

## 🤝 致谢

OKK 的诞生与演进离不开开源社区与先驱项目提供的优秀设计思路与技术启发，特别致谢以下项目与作者：

- [WAuxiliary](https://github.com/HdShare/WAuxiliary_Public) — 强大的微信模块开源标杆
- [WeKit](https://github.com/Ujhhgtg/WeKit) — 极致的界面美化与手势交互参考
- [HChat](https://t.me/Hchat_ci) — 优秀的增强功能与稳定性实践
- [NewMiko](https://github.com/dartcv/NewMiko) — 经典 Hook 逻辑与架构设计启蒙
- [DexKit](https://github.com/LuckyPray/DexKit) — 高效可靠的运行时 Dex 特征匹配库
- [MIUIX-KMP](https://github.com/matsushita-takeo/MIUIX-KMP) — 优雅的 Compose 组件库

---

## 💬 社区与交流

- **Telegram 交流群**：[加入 OKK 官方交流群](https://t.me/OKK_YES)
- **问题反馈**：欢迎通过 [GitHub Issues](https://github.com/angusdevgo/OKK/issues) 提交建议与 Bug 反馈。

---

<div align="center">
  <sub>Made with ❤️ by Angus & Open Source Community</sub>
</div>
