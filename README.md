<p align="center">
  <img src="./app/src/main/res/drawable/ic_launcher.png" width="108" height="108" alt="OKK Logo">
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

<br>

---

<br>

> ⚠️ **免责声明**  
> 本项目仅供 Android 逆向工程、Hook 技术交流与个人本地学习分析使用。请勿用于任何商业用途或违反相关服务协议的行为。

<br>

## ✨ 功能特性

### 🛡️ 消息保护
- **消息防撤回**：支持拦截文本、图片、语音、视频等消息撤回并显式标记
- **朋友圈防删除**：保留好友已删除的朋友圈动态
- **评论防撤回**：朋友圈互动评论删除拦截与记录
- **自身撤回保留**：支持选择性保留自身撤回的消息内容

<br>

### 💬 聊天增强
- **会话气泡主题**：支持自定义聊天气泡样式与双向配色
- **精准时间显示**：支持毫秒级时间与自定义时间格式
- **快捷操作**：支持长按快速引用、重新编辑已发送消息
- **群聊特征增强**：群成员自定义头衔展示、转账实名尾字提示
- **输入统计**：输入框实时字数与行数统计

<br>

### 📁 会话分组与管理
- **首页多 Tab 分组**：支持在微信首页顶部或悬浮栏快速切换分组
- **智能预设分类**：自动归类置顶、单聊、群聊、公众号与服务号
- **自由自定义分组**：自由创建新分组、拖拽排序、批量添加联系人
- **密友模式**：支持对特定好友与群聊进行隐身与伪装保护

<br>

### 🎨 界面美化
- **悬浮底部栏**：Liquid / 毛玻璃风格悬浮导航栏，支持阻尼手势交互
- **全景壁纸透视**：首页与会话列表纯净壁纸透视与半透明卡片渲染
- **圆角定制**：自定义头像圆角半径、移除列表分割线
- **现代原生 UI**：微信内嵌 Compose / MIUIX 风格设置面板

<br>

### 🧰 实用工具箱
- **存储重定向**：自动将接收的文件重定向转存至系统自定义目录
- **视频号提取**：一键提取并保存视频号高清视频资源
- **位置模拟**：支持地图选点与全局/独立坐标伪装
- **PC 自动登录**：免手机端确认自动同意电脑端登录请求
- **屏蔽热更新**：拦截微信热补丁下发，锁定运行版本

<br>

### 🧩 在线扩展市场
- **动态脚本引擎**：支持 Java / BeanShell 动态脚本扩展
- **官方在线市场**：一键浏览、下载、体验与更新在线插件
- **即时热加载**：无需重启微信即可动态载入与运行脚本

<br>

---

<br>

## 🏗️ 架构设计

```text
OKK/
├── app/       # 宿主应用壳：提供 APK 入口、Xposed Scope 与基础配置
├── loader/    # 核心加载器：libxposed / Xposed 入口、Compose 设置界面
├── core/      # Hook 引擎：DexKit 智能特征匹配、业务 Hook 逻辑
├── monitor/   # 辅助监控模块
├── bsh/       # 动态脚本解释引擎（基于 Beanshell 与 JavaCC）
└── pack.ps1   # 自动化发版与打包脚本
```

<br>

---

<br>

## 🛠️ 构建与编译

### 编译环境
- **JDK**：17+
- **Android SDK**：API 35+
- **Gradle**：8.x

### 执行编译
```powershell
# 推荐使用自带脚本一键打包
.\pack.ps1 -NoInstall

# 或直接通过 Gradle 命令构建
.\gradlew.bat :app:assembleRelease
```

<br>

---

<br>

## 💡 使用方法

1. 在已安装 **LSPosed** 的 Android 环境中安装 **OKK**。
2. 打开 LSPosed 管理器，启用 **OKK** 模块并勾选作用域 **微信 (`com.tencent.mm`)**。
3. 强行停止微信进程后重新启动。
4. 在微信主界面或通过浮动入口进入 **OKK 设置** 开启所需功能。

<br>

---

<br>

## 🤝 致谢

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[WeKit](https://github.com/Ujhhgtg/WeKit)

[HChat](https://t.me/Hchat_ci)

[NewMiko](https://github.com/dartcv/NewMiko)

[DexKit](https://github.com/LuckyPray/DexKit)

[MIUIX-KMP](https://github.com/matsushita-takeo/MIUIX-KMP)

<br>

---

<br>

## 💬 交流反馈

[Telegram 交流群](https://t.me/OKK_YES)

<br>
