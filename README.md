# 锤柄

锤子的手柄。坚果 OS / Smartisan OS 补丁工具（锤子补丁）。需要 Root。当前主要覆盖 TNT 窗口配置、语音指令、系统 WebView。

安装包不进 Git，放在仓库旁的 `BYFBBB`。

## 环境

- 坚果 Pro 2s，Smartisan OS 7.2（Android 8.1）
- Root（APatch / Magisk 等）
- 语音、WebView 相关能力需要 LSPosed 模块注入

## 功能

- 编辑 `/data/system/revone_window_config.xml`
- 按合集管理应用窗口大小、显示状态、拉伸
- TNT 镜像 / 桌面 / 分享切换，以及热切换
- 语音指令与别名
- 系统 WebView 实现切换

首次启动会备份原件。点保存才会写系统 XML。

## 构建

JDK 17，Android SDK。

Linux / macOS：

```sh
./gradlew assembleDebug
```

Windows：

```bat
gradlew.bat assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。对外安装包复制到仓库旁的 `BYFBBB/锤子补丁-<version>-debug.apk`。

不要提交 `local.properties`。本机 SDK 路径由 Android Studio 或本地文件生成。

## 注意

- 会改系统文件。卸载应用不会自动还原 XML。
- 原件备份在应用私有目录，卸载会一起消失。请先在设置里做完整备份或导出。
- 仅在已 Root 的自己的设备上使用。

## License

[MIT](LICENSE)
