# geely-carplay

面向吉利 GKUI 1.2 / Android 4.3 的 CarPlay 接收端，保留 XML + Kotlin 界面，接入 DiPlay 真实连接、投屏、音频和触摸处理层。小米 MI 6 已有无线连接、认证、投屏和音乐成功记录，不代表全部车机及 USB 场景已验收。

## 文档入口

| 文档 | 用途 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | AI 工作授权、沟通、验证及设备操作规则 |
| [排障手册](docs/troubleshooting.md) | 热点、蓝牙、音频、黑屏和退出故障 |
| [移植记录](docs/carplay-migration.md) | 来源、源码差异、认证和验收边界 |

截至 2026-11-24：当前为基线版本 v1.0.0（versionName 1.0.0）。音乐电流声尚未解决，主动断开后仍有音乐声的问题待复现定位。

## 功能特性

- 无线蓝牙 iAP2 + 热点、AirPlay 画面与触摸、AAC/Opus 音频。
- 处理层包含 USB + VPN/NCM、麦克风上行、位置上报和 H52 音频，需逐项实机验收。
- 前台服务保留会话，连接中可取消，支持断开、可选自动连接及开机启动。
- 启动时后台尝试开启热点和蓝牙，失败仍按时进入首页，系统可能拒绝请求。
- 首页退出在连接中/已连接时确认，随后清理服务、清空任务栈并结束进程。
- 本地认证默认内置，保留 CH341 USB、I2C、远程认证配置。
- 横屏、小屏滚动适配、深浅主题；可用高度 ≥550dp 且宽度 ≥1200dp 时采用车机宽屏资源（本车机可用高度 590dp）。

## 软件架构

| 模块 | 职责 |
| --- | --- |
| `app` | 界面、启动初始化、会话管理、前台服务、设置及日志 |
| `shared` | 协议、传输、网络、媒体、JNI 和设备兼容 |
| `vendor/jmdns` | 带 Android 4.3 兼容修复的 Bonjour |

包名 `com.geely.carplay.ui`，minSdk/targetSdk 均为 18，compileSdk 37，Java 目标 11，启用 multidex。Kotlin 使用 AGP 内置支持。

## 快速开始

依赖：JDK 17+、Gradle 9.5.0、AGP 9.3.0、SDK 37、NDK 28.2.13676358。通过 `local.properties` 或 `ANDROID_HOME` 指定 SDK。新环境需先安装工具和下载依赖；离线构建要求缓存齐备。

```bash
./gradlew :app:assembleDebug --offline --console=plain
```

APK：`app/build/outputs/apk/debug/geely-carplay-v<版本>.apk`（当前 v1.0.0）。仓库**不包含** Apple MFi 认证资产，构建前需自备 `app/src/main/assets/offline-mfi/identity.pk8` 与 `certificate.p7b`；缺少时仍可编译，但本地 MFi 认证不可用。GitHub Releases 提供预构建安装包。

## 使用指南

1. 用户手动安装并启动。启动页约 1.8 秒后进首页，不再自动开启热点或蓝牙，请在车机系统设置中手动开启。
2. 系统中配对 iPhone；若热点未自动开启，手动开启。读取热点配置受限时，在连接页填写与系统一致的名称和密码。
3. 选择已配对 iPhone 连接。当前列表不是热点在线设备列表。
4. 连接中可取消；进入 CarPlay 后，当前页面显示首帧前保留等待提示。
5. 普通返回或切页保留后台会话；“退出应用”会断开并结束进程。主动断开后本进程不立即自动重连。

H52 原厂蓝牙需在设置显式开启后重新选设备；普通小米没有 H52 OEM 音频能力。小米蓝牙连接车机/音箱时，输出仍受 Android A2DP 路由影响，不能把 H52 路由开关理解成向远端车机发送声音。

连接设置通常重连后生效，主题可立即同步。设置可导出诊断报告，界面显示保存位置。`docs/screenshots/` 为原型截图，不代表最新实机验收。

## 来源与许可

处理层来源：[DiPlay-Geely-Android43](https://github.com/xikai6282/DiPlay-Geely-Android43)。保留 LICENSE、源文件标记及 libopus、Concentus、JmDNS、BouncyCastle 等第三方声明。详细来源和差异见移植记录。
