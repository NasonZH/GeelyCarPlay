# CarPlay 排障手册

截至2026-11-23。先读 [协作规则](../AGENTS.md)。以下是本项目证据，不是对所有设备的保证。修改和设备操作仍需授权。AI 交接文档只在本地维护，不随仓库分发。

## 先定位阶段

| 日志 | 已确认 | 未确认 |
| --- | --- | --- |
| MfiReady | 提供器就绪 | iPhone接受认证 |
| Bluetooth connect completed | RFCOMM连通 | iAP2、Wi-Fi、投屏成功 |
| IdentificationAccepted | 识别接受 | MFi认证完成 |
| 0xaa05 AuthenticationSucceeded | 本次认证成功 | Wi-Fi或音频已建立 |
| WaitingForWifiJoin | 已发送配置，等会话证据 | 热点一定没有客户端 |
| AirPlay session active | 控制会话建立 | 当前页面显示首帧 |
| SETUP type110 / type130 | 画面流 / 数据控制流 | 音频流 |
| audio stream accepted=true | 音频流被接收 | 输出可听、格式正确 |

音频type100/101/102还需结合audioType、codec、rate、channels。音乐能被压低，不证明导航PCM正确。

## 热点配置读取失败

小米Android9曾返回PERMISSION_DENIED，旧流程即使热点已开也被读配置阻断。当前HotspotSettings保留已有手动值，用户填写与系统一致的名称和密码；UTF-8名称最多32字节，不trim；密码为空表示开放热点，否则8–63字符。已开热点直接继续，不强制再次启动。

最新启动初始化尝试setWifiApEnabled(null,true)，沿用系统配置，但系统可拒绝。它与仍依赖读取配置的CarHotspotTools.start不同。失败仍按时进入首页，不应通过覆盖密码、启用Root绕过。

## 热点显示已开启但手机搜不到

2026-11-23 实机确认：`/system/bin/hostapd` 不是真正的 hostapd，而是厂商包装脚本。它读取 `/data/misc/wifi/hotspot.band`，值为 `5g` 时删除原配置的 `hw_mode`、`channel`、`ieee80211n`，强制写入 `hw_mode=a`、`channel=36`。这台车机网卡在 AP 模式不支持该信道，`hostapd` 报 `Could not select hw_mode and channel` 后退出，但框架和 netd 仍报告 `SoftAP started`：状态栏显示热点已开、`wlan0` 配到 `192.168.43.1`，而接口实际是 DOWN，约 15–26 秒后被收回。

判断依据看 `logcat`：只有反复的 `Unable to setup interface`，没有 `AP-ENABLED`。把 `hotspot.band` 改为 `2.4g` 后重新开启，hostapd 使用 `channel=6`、`hw_mode=g`，日志出现 `AP-ENABLED`，`wlan0` 变为 UP。

系统设置没有频段入口，只能改这个文件（需 root，`su` 在本机可用）。原值备份在 `/data/local/tmp/hotspot.band.bak`。应用已不再自动开启热点或蓝牙，排查时不要把启动初始化当成热点失败原因。

## 蓝牙快速失败

历史IOException为`read failed, socket might closed or timeout, read ret: -1`，同步系统日志出现`sec_id:0 is zero or BTM_SetSecurityLevel failed, remote_scn:1`。发生在本机RFCOMM建立，尚未到认证，不是证书失败证据。用户重启蓝牙/设备后进入下一阶段；当时未加入盲目重试或不安全通道回退。复发需读取同时间段系统蓝牙日志。

## 热点有客户端但等待会话

历史wlan1有192.168.43.1和fe80地址，代码却优先IPv6并用于监听和通知。已有客户端但会话未建立。现WirelessHostAddress优先有效IPv4，没有时保留带接口作用域的IPv6回退，用户明确反馈成功。

不要把wlan0上游地址当热点地址；界面等待不代表物理未接入。ARP/邻居表存在缓存，不能当实时在线列表；Wi-Fi MAC不能直接匹配蓝牙MAC。热点在线iPhone筛选已暂缓。

## Wi-Fi日志出现USB

AttachingNetwork是共用状态，旧文案写死“正在连接USB网络”。现改为“正在建立CarPlay网络连接”。看transport=WIRELESS/WIRED和实际attach路径判断，不用旧文案判断。

## 画面正常，声音仍在iPhone

历史只有110、130流，用户播放时持续NowPlayingUpdate，没有音频SETUP。应先查协商，不能先改扬声器音量或H52路由。

对比来源发现：来源按RECORD_AUDIO权限判断microphoneAvailable，当前入口当时误用默认false的car_microphone_enabled。已恢复权限判断并同时传入声明和引擎，用户确认音乐恢复。这是本次修复证据，不表示所有音乐场景都必然依赖麦克风。

复发核对/info声明、实际microphone、音频SETUP和modesChanged资源值，不盲目删除Opus、telephony或modes。

## 导航杂音，音乐与压低功能正常

历史导航为type101/default/OPUS/48000/单声道，系统OMX.google.opus.decoder输出却配上16000轨道；两段提示丢弃140、98包并多次裁剪。旧代码预先改16kHz，忽略INFO_OUTPUT_FORMAT_CHANGED，存在明确错配。

当前系统Opus路径按48kHz播放，记录解码rate/channels与轨道参数，不匹配则报错停止该Opus流。无系统后端保留libopus/Concentus；libopus真正解码为16kHz再按16kHz播放是正确路径，不等于把48kHz PCM标成16kHz。

核对实际backend，不把库可加载等同于decoder初始化成功。音乐AAC没有因此改采样率。

## 音乐在播放但完全无声

2026-11-23 实机日志：应用以 `stream=23`（`STREAM_CARPLAYAUDIO`）成功拿到焦点，约 6 秒后收到 `AUDIOFOCUS_LOSS(-1)`。旧逻辑在这种情况下把媒体轨道音量设为 0 且不再恢复，于是 AAC 解码和写轨都正常（帧数持续增长）却听不到声音；夺走焦点的是车机自带蓝牙音乐 `com.ecarx.multimedia`。

处理：媒体通道在永久丢失焦点或申请被拒时保持可听，瞬时丢失仍压低音量；导航继续用 `STREAM_NAVI_TTS`，保留独立音量调节。关闭“吉利 H52 音频路由”会改用 `STREAM_MUSIC` 也能避免静音，但导航会失去独立音量。

`peak=0` 不能作为静音证据：该统计只在 Opus 软件解码路径更新，AAC 路径不维护。

## 音乐电流声（2026-11-24 实机确认已修复）

历史上用户确认只播放音乐也有电流声。当时日志显示：

- 输出routeType=8，系统设备bt_a2dp，即手机向蓝牙设备输出。
- AAC输出和轨道均48kHz双声道，不是导航48/16错配。
- 持续窗口多为dropped=0、underruns=0、writeErrors=0、rebuffers=0；曾有decoderDroppedTotal=1，不能独自解释持续电流声。
- 蓝牙轨道缓冲约1121ms，最长write约600ms，与手机扬声器不同，但不是杂音根因证明。

2026-11-24 用户实机验收确认电流声已消除；本轮未针对该现象单独改代码，保留上述证据供回归。若复发，按原路径对比手机扬声器与蓝牙输出，并读取 audio stats。

peak=0不能单独证明静音，需核对该解码路径是否维护此统计。

## 进入CarPlay黑屏很久

历史Surface更换后没有及时请求新关键帧，静态画面需等后续更新；UI又按会话连接提前隐藏提示。

当前VideoDecoder更换Surface后重置首帧和关键帧请求间隔并请求恢复；页面以本TextureView第一次onSurfaceTextureUpdated决定隐藏提示，不能只看isConnected。验收首次进入、返回再进、静态画面、页面重建；比较Surface更新、关键帧和首帧时间。

## 建立连接时无法取消

当前主操作在连接中显示取消含义，调用cancelConnection，使旧generation失效、停服务、禁止本进程自动重连并显示已取消。初始化前后检查代次，旧控制器仍需释放。

用户验收初始化、蓝牙连接、等待Wi-Fi/会话阶段，确认旧回调不会恢复已连接。清理异步执行，不应阻塞UI等待资源退出。

## 退出后重新出现未连接CarPlay

实机日志证明旧进程死亡后ActivityManager为残留CarPlayActivity重建进程。旧方案从非根Home调finishAndRemoveTask后kill，没有清完页面。

最新方案：prepareExit禁止重连/旧回调，断开并等待清理，最多15秒；随后NEW_TASK|CLEAR_TASK启动Home ACTION_EXIT，清空旧页面，让退出页成为根，再结束任务与进程。最新包已推送，仍待用户验证。读取退出附近START、process died、start proc日志，AI不自行点击退出或杀进程测试。

## 性能解释

此前短会话：高通H.264解码2258×848，约14–29fps，接收和显示基本一致，recoveries=0；CPU单核口径快照62%–83%，PSS约115–120MiB。约50秒音频有2次缓冲耗尽、1次重缓冲，最长音频收包717ms，视频间隔超过800ms。

不是基准测试，不证明无泄漏或真车性能。静态画面低fps可以正常；渲染跟随接收时要区分发送端供帧与网络抖动。estimatedQueuedFrames/sampleRate只是轨道排队估算，不是端到端延迟；阻塞write耗时不是解码耗时。

## 读取和分享日志

在用户允许只读检查时，先确认设备，然后读取脱敏标签：

```bash
/home/user/Android/Sdk/platform-tools/adb devices -l
/home/user/Android/Sdk/platform-tools/adb -s <phone-serial> shell pidof com.geely.carplay.ui
/home/user/Android/Sdk/platform-tools/adb -s <phone-serial> logcat -d -v threadtime -t 4000 GeelyCarPlay:V DiPlay-AudioStats:V DiPlay-VideoStats:V '*:S'
```

底层xcertplay-usb可能含热点凭据、设备名称/地址、证书、签名及十六进制协议；需要时先过滤，不打印完整payload/raw-body/head，不导出认证资产。UI报告经过DiagnosticRedactor，分享前仍复查。

本机临时证据：`/tmp/geely-carplay-performance.log`、`/tmp/geely-carplay-navigation-audio.log`、`/tmp/geely-carplay-music-noise.log`。它们不在仓库，可能被清理；下一位AI不能假定一定存在。
