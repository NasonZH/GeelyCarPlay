# CarPlay 处理层移植记录

截至2026-11-24。操作规则见 [AGENTS.md](../AGENTS.md)，故障证据见 [排障手册](troubleshooting.md)。AI 交接文档只在本地维护，不随仓库分发。

## 来源与范围

- 主来源：`/home/user/code/geely/DiPlay-Geely-Android43-src-20261123`，保持只读。
- `/home/user/code/DiPlay/` 当时未找到认证资产。
- `/home/user/code/carup/` 包含CarUp 4.0 APK、解包和反编译参考，保持只读。
- 当前工作区：`/home/user/code/geely/geely-carplay`，不通过来源绝对路径参与构建。

保留当前包名、XML/Kotlin界面与主题，接入来源shared/src/main、shared/libs及vendor/jmdns/src/main。保留com.shilapi.xcertplay包名以兼容JNI和反射。BYD辅助代码因内部依赖保留，不代表新增对应产品界面或已验收其功能。

范围包括iAP2、USB/VPN/NCM、MFi、Bonjour、AirPlay、视频、触摸、AAC/Opus、麦克风、位置与H52适配。代码存在不等于实机验证通过。

本次逐文件核对：来源shared/src/main的603个文件无缺失；JmDNS来源61个文件无缺失且字节一致。比较针对来源文件集合，不排除当前项目新增文件。

## 处理层现有六处差异

| 文件，相对于shared/src/main/java/com/shilapi/xcertplay | 修改及依据 |
| --- | --- |
| `media/MicOwnerReleaser.kt` | selfHeal的pm enable补齐包名；Root麦克风操作仍需真车验收 |
| `network/WirelessHostAddress.kt` | 手动热点优先有效IPv4，没有时回退带接口作用域的链路本地IPv6；用户确认修复后连接成功 |
| `media/OpusVoiceDecoder.kt` | 系统Opus输出按48kHz选择轨道，保留无系统后端时的既有libopus语音16kHz路径 |
| `media/AndroidMediaSink.kt` | 记录音频输出格式并检查Opus与轨道一致；Surface切换重置首帧与关键帧请求状态并请求恢复；暴露`lastVideoFrameAt`供链路检测；保留CRLF |
| `airplay/NtpClock.kt` | 记录最近一次对端回包时间`lastPeerPacketAt`，供应用层判断手机是否在线；收发协议和时钟算法未变 |
| `media/MediaAudioBuffer.kt` | 媒体缓冲默认500毫秒，档位500/1000；旧的150档会被sanitize归一，已去掉 |

AirPlayInfoPlist与主来源一致，没有精简格式、telephony、speechRecognition、modes或特性位。JNI、Concentus、队列裁剪和线程优先级沿用来源。不要再写“处理层仅一处差异”。

## 应用入口接入和差异

| 入口 | 当前实现 |
| --- | --- |
| CarPlaySessionManager / Service | 真实会话、代次取消、异步清理、前台通知、退出保护；链路看门狗用时间同步回包与视频帧双信号，双静默5秒判定断开；记录连接时间线，无线连接每5秒输出链路诊断，连接后自动暂停续播 |
| ConnectActivity / HotspotSettings | 已配对蓝牙列表、热点配置回退、VPN授权、连接中取消；没有热点在线筛选；iPhone列表跟随蓝牙通道来源并优先在线设备，快速连接进入后自动开始；会话建立后立即进入投屏，不等待视频参数或稳定收帧 |
| CarPlayActivity | SurfaceView 优先、明确输出初始化错误时回退 TextureView，保留手动兼容选项；触摸、等比显示，当前页面首帧前保留提示；确认连接成功后再断开才回首页，连接失败停留本页；首帧渲染回调转发到界面并记录时间线 |
| HomeActivity | 自动连接、断开、退出确认，清空旧任务栈后从新根页面结束进程；连接手机与快速连接并排，快速连接复查在线设备，连接前检查热点与蓝牙；会话在线时显示当前连接 |
| SplashActivity / StartupConnections | 启动页只做初始化；不再自动开启热点或蓝牙，缺失时由首页连接前检查提示 |
| AirPlayPersistence | 资源引用改到app；H52音频默认开启，但实际依赖OEM能力检测；音乐缓冲默认500毫秒；帧率提供30/60/20fps，视频显示方式持久化 |
| DiPlayBootstrap / DiPlayPreferences | 本地认证、中文提示、上次选择记录和成功连接历史；快速连接按历史找在线设备 |
| DiPlayBluetooth | API23以下尝试adapter地址，其他用安全设置/标识回退；无线控制器另有本地蓝牙地址解析 |
| 麦克风声明 | 已恢复按RECORD_AUDIO权限判断，同时传入能力声明和引擎；此前误用默认false的设置导致与来源不同 |
| 通话模式 | Root抢占由模式1控制；来源模式2的denyTelephonyStreams未接到当前Manager，UI仅0/1 |
| 来源外围功能 | 未完整复制来源CarPlayHostActivity的全部页面、媒体键和仪表扩展；不能说全部产品功能一致 |
| 构建 | app/shared/jmdns，Gradle9.5.0、AGP9.3.0、SDK37、NDK28.2；app minSdk/targetSdk18，Java目标11 |

普通返回保留会话，显式退出结束进程。共用AttachingNetwork文案已改为“正在建立CarPlay网络连接”，不再把无线写成USB。

## 认证

普通构建默认包含：

```text
app/src/main/assets/offline-mfi/identity.pk8
app/src/main/assets/offline-mfi/certificate.p7b
```

来源为`/home/user/code/carup/work/apk/assets/offline-mfi/`，已核对与CarUp-4.0-release.apk一致，证书与私钥公钥匹配。未在文档写入密钥内容，未改认证签名算法。

不需要DIPLAY_AUTH_ASSETS_DIR或外部Sync。其他认证方式需用户配置硬件、路径或服务器。MfiReady只表示提供器就绪，本轮实机收到AuthenticationSucceeded才是iPhone接受认证的证据；不能推及所有未来设备。

## 适配和验证边界

- 默认紧凑资源，宽 ≥1200dp 且可用高 ≥550dp 使用宽屏资源；字体sp，小屏有滚动和首页装饰隐藏。
- 吉利 2019 款星越 300T（GKUI 1.2 / Android 4.3、系统 `06.03.08600.H53.00060`、1920×720）已完成无线连接、认证、画面、音乐与导航音量、断开清理、通话切换的实机验证；小米 MI6 为早期开发验证机，不代表其他车型。
- 2026-11-24 小米 MI6 验收：首页按钮并排与快速连接置灰、连接前热点/蓝牙检查、断开手机蓝牙后继续投屏、关闭热点约5秒回首页、音乐缓冲500/1000档位。
- 启动初始化与退出栈清理已编译、Lint、打包、推送；退出流程尚待用户验证。
- 用户确认音乐电流声已消除、主动断开后无残留播放、来电自动切换手机蓝牙通话；USB、Siri、H52路由、麦克风上行仍需专项验收。
- 测试源码只编译过。最新包摘要、测试文件和命令见AI交接。

## 许可

保留根LICENSE、各文件标记、shared/LIBOPUS_NOTICE.md、shared/BYD_HUD_ICONS_NOTICE.md和vendor/jmdns声明。保留Concentus等组件现有许可，不能将全部继承代码擅自替换成统一许可。

主题切换只重建设置页，首页恢复前台时按需重建，不再清空任务栈；保留向 iPhone 同步昼夜模式。会话关闭来源、视频退出阶段、Surface 挂载与时间同步累计诊断详见排障手册。
