# OKK

OKK 是一个基于 LSPosed / Xposed 的微信增强模块，提供消息保护、聊天增强、会话分组、界面美化、工具能力等功能。

> 仅用于学习、本地分析与个人实验，请勿用于任何未授权场景。

## 功能概览
- 消息保护：防撤回、朋友圈防删除、评论防撤回等
- 聊天增强：气泡、自定义时间、引用、群头衔、实名尾字、输入框统计等
- 会话分组：预设分组 + 自定义分组
- 界面美化：悬浮底栏、浮动入口、主题壁纸、圆角头像等
- 工具能力：下载重定向、视频号下载、虚拟定位、PC 自动登录、屏蔽热更新
- 设置界面：微信内嵌 Compose / MIUIX 风格 UI

## 仓库结构
- `app/`：壳 APK 主模块
- `loader/`：Xposed / libxposed 入口与设置 UI
- `core/`：核心 Hook 业务逻辑
- `monitor/`：辅助监控模块
- `bsh/`：脚本解析支持模块
- `pack.ps1`：Release 打包脚本

## 构建环境
- Android Studio / Android SDK
- JDK 17
- Windows PowerShell

### 本地签名说明
正式签名不包含在仓库中。若需要本地打包，请自行设置环境变量：

- `OKK_STORE_FILE`
- `OKK_STORE_PASSWORD`
- `OKK_KEY_ALIAS`
- `OKK_KEY_PASSWORD`

示例：
```powershell
$env:OKK_STORE_FILE = "release-okk.keystore"
$env:OKK_STORE_PASSWORD = "your-password"
$env:OKK_KEY_ALIAS = "your-alias"
$env:OKK_KEY_PASSWORD = "your-password"
```

## 编译
```powershell
cd .\源码
powershell -File .\pack.ps1 -NoInstall
```

## 开发说明
- 默认 release 构建启用 R8 与资源裁剪
- 请勿提交 `local.properties`、`*.keystore`、日志、缓存与临时脚本
- 根目录的缓存和残留文件已做清理，后续新增临时文件也请勿入库

## 致谢
感谢以下项目与作者提供的参考与启发：
- WAuxiliary：https://github.com/HdShare/WAuxiliary_Public
- WeKit：https://github.com/Ujhhgtg/WeKit
- HChat：https://t.me/Hchat_ci
- NewMiko：https://github.com/dartcv/NewMiko

## 交流群
Telegram：<https://t.me/OKK_YES>
