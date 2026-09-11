<div align="center">

[![中文](https://img.shields.io/badge/中文-1C1F26?style=for-the-badge)](README.md)
[![English](https://img.shields.io/badge/English-3D5A80?style=for-the-badge)](README.en.md)

<img src="docs/icon.svg" width="168" alt="锤柄">

# 锤柄

让锤子握着更舒服的手柄

</div>

**锤柄** 是给坚果 OS / Smartisan OS 用的补丁工具，需要 Root。

锤子好不好用，往往不在锤头，而在手柄。这个软件就是那截手柄：把 TNT 窗口、语音指令和系统 WebView 这些别扭的地方，收成能直接点的设置。

它主要做这些事：

- 用图形界面改 TNT 窗口大小、显示状态和拉伸
- 按合集统一管理一类应用的窗口
- 切换 TNT 镜像 / 桌面 / 分享，并打开虚拟触摸板
- 增强虚拟遥控器的语音指令和别名
- 切换系统 WebView 实现

首次启动会备份原件。点保存才会写系统文件。语音和 WebView 相关能力需要 LSPosed 模块注入。

## 注意

- 会改系统文件。卸载应用不会自动还原。
- 只在已 Root 的自己的设备上使用。

## License

[MIT](LICENSE)
