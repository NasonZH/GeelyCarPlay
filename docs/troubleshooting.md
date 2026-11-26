# CarPlay 排障手册

截至2026-11-24。先读 [协作规则](../AGENTS.md)。以下是本项目证据，不是对所有设备的保证。修改和设备操作仍需授权。AI 交接文档只在本地维护，不随仓库分发。

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

## 2.4GHz 与 5GHz 热点信道、WMM 与 802.11n（2026-11-24 实机调整）

音乐偶发断续，日志表现为音频 RTP 序号缺口：每 5 秒 2–4 次、单次缺 1–10 包，`Receive: audio` 的 `seqGapEvents/seqForwardGaps` 持续升高；应用侧 `dropped=0`、`lateOrDuplicate=0`、`trims=0`，同时链路诊断出现 TCP `重传=+15`。据此判定为 2.4GHz 空口丢包，不是音量、ducking 或应用主动丢弃。

厂商包装脚本在 2.4G 分支不改写信道，默认沿用 `channel=6`。`hostapd_cli chan_switch` 被驱动拒绝，`set channel` 只改配置变量、射频仍停在 2437MHz，只有重启 hostapd 才生效。

处理方式：修改 `/system/bin/hostapd` 包装脚本。2.4G 分支读取 `/data/misc/wifi/hotspot.channel` 覆盖 `channel` 并追加 `wmm_enabled=1`；5G 分支读取 `/data/misc/wifi/hotspot.channel5g`（缺省 36），写入 `hw_mode=a`、`channel=$CH5`、`ieee80211n=1`、`wmm_enabled=1`。当前配置：`hotspot.band=5g`、`hotspot.channel5g=149`，2.4G 备用信道 `hotspot.channel=11`。原脚本备份 `/data/local/tmp/hostapd.orig`，旧配置备份 `/data/misc/wifi/hostapd.conf.ch6.bak`，原频段文件备份 `/data/local/tmp/hotspot.band.2.4g.bak`。写入 `/system` 前需 `mount -o remount,rw /system`，完成后恢复只读。

硬件能力：`wl -i wlan0 bands` 返回 `b a`，芯片为 Broadcom BCM4339（SDIO `02D0:4339`），5GHz 可用信道只有 149/153/157/161/165，没有 36。此前 5G 启动失败是厂商脚本写死 `channel=36` 导致 `Could not select hw_mode and channel`，不是硬件不支持。

实测结论：信道 1 不可用，iPhone 加入后 `num_sta[0]=0`、视频静默约 2.7 秒、5 秒无响应断开；2.4GHz 信道 11 稳定（`freq=2462`），音乐缺口约 90 秒 1 次、2 帧（约 0.05%）；5GHz 信道 149 + 802.11n（`freq=5745`、`ieee80211n=1`）在 204 秒窗口内音乐 `gaps=0`、`Receive` 音频 `seqGapEvents=0`，GC 约 0.22 次/秒（平均暂停 18.7ms，最大 61ms），视频 8.6–30fps、1 次 `recoveries`。5GHz 内存占用更高：Dalvik 18.2MB、PSS 约 31.4MB，比 2.4GHz 多约 7MB，主要来自 `VideoFramePool` 上限 8MB。信道与 WMM/HT 同时生效，未单独区分各自贡献；5GHz 覆盖范围更小，不承诺零丢包，导航 Opus 缺口尚未做隐藏。

修改后必须重启热点（应用断开重连会重新启动热点）；仅 reload 不生效。

验证：

```sh
su -c 'hostapd_cli -p /data/misc/wifi/hostapd -i wlan0 status' | grep -E 'state=|freq=|channel=|ieee80211n|num_sta'
su -c 'grep -E "channel=|hw_mode=|wmm_enabled=|ieee80211n=" /data/misc/wifi/hostapd.conf'
```

5GHz 正常应为 `freq=5745`、`channel=149`、`ieee80211n=1`、`num_sta[0]=1`；切回 2.4GHz 后为 `freq=2462`、`channel=11`。

回滚：

```sh
su -c 'echo 2.4g > /data/misc/wifi/hotspot.band'
su -c 'cp /data/local/tmp/hostapd.orig /system/bin/hostapd && chmod 755 /system/bin/hostapd'
```

改回 `2.4g` 即使用 2.4GHz 信道 11（保留 WMM）；恢复脚本则完全还原厂商行为（无 WMM、无信道覆盖）。

注意：这些改动位于 `/system` 与 `/data`，不在 Git 中，OTA、换车或恢复出厂后会丢失，需要重新应用。

## 通话时自动回到投屏（2026-11-24 调整）

现象：通话接通后车机自带 `com.ecarx.btphone/.ui.CallingActivity` 切到前台，CarPlay 退到后面；手动缩小通话界面才回到 CarPlay。

实现：`CarPlaySessionManager` 在链路看门狗里每 2 秒检查 `AudioManager.mode`，进入 `MODE_IN_CALL` 或 `MODE_IN_COMMUNICATION` 且会话已连接时，用 `FLAG_ACTIVITY_NEW_TASK | REORDER_TO_FRONT | SINGLE_TOP` 拉起 `CarPlayActivity`；投屏页前后台由 `CarPlayActivity.onResume/onPause` 写入 `noteScreenResumed`，每次通话最多尝试 5 次，通话结束或断开后重置。

日志：`通话状态: mode=X inCall=? connected=? screenResumed=?` 与 `通话中拉回投屏 attempt=N success=?`；这两类行被列为关键日志，不受采样限流影响。

限制：覆盖后挂断/拨号界面需要车机物理按键或返回；通话中主动离开投屏不会再次强制拉回；Android 10 手机端后台启动 Activity 可能被系统拦截（车机 4.3 不受影响）。这是产品行为选择，不是协议要求；未做“通话结束自动回到投屏”（车机本身会返回）。当前仍待用户实机验收。

## 首页与连接页的设备在线判断（2026-11-24 调整）

需求：未连接 CarPlay、仅手机蓝牙连接车机时，首页与“选择 iPhone”页也要显示设备在线。

实现：新增 `BluetoothOnline`，按设备地址汇总在线集合：`BluetoothDevice.isConnected` 反射、HFP/A2DP 的已连接设备地址（`getProfileProxy` 后读 `connectedDevices`，后台线程最多等 400ms）、厂商 ANW 已连接槽位。首页用保存地址匹配；连接页先构建一次在线集合，再逐行匹配。

连接页显示：在线行正常；离线行设备名与状态文字置灰、整行 `alpha=0.5`、`isEnabled=false`、不响应点击，避免对未连接手机发起连接。只有一个候选且会话已连接时视为当前设备。

日志：首页每次刷新输出 `设备在线: connected=? hasDevice=? online=? bluetoothOnlineOnly=?`。

注意：profile 绑定只在后台线程执行，主线程调用时跳过；厂商 ANW 查询仅在厂商蓝牙模式开启时执行；不再使用“厂商模式默认在线”的旧判断。

## 选择设备时名称与系统蓝牙列表不同（2026-11-25 调整）

MI 6 系统蓝牙列表显示“周二哥”，应用设备列表及系统蓝牙快照中的原始名称是一串随机字符。系统显示名称与原始名称不同，不属于中文字体或字符编码转换问题。

系统蓝牙设备列表优先反射读取 `BluetoothDevice.getAlias()`；别名为空、空白或读取失败时回退到原始 `name`，原始名称为 null 时沿用未命名提示。用户选择设备时沿用已有保存流程，将显示名称保存供首页使用。吉利原厂 ANW 名称读取、设备地址及连接流程不变。

应用及别名回退场景的测试源码编译成功，未执行测试；本任务新增测试文件已按提交规则移除。APK 已构建并推送至 MI 6 的 `/sdcard/Download/Geely-CarPlay.apk`，本地与手机文件 SHA-256 一致。当前改动待用户手动安装并确认“周二哥”正常显示。

## 蓝牙快速失败

历史IOException为`read failed, socket might closed or timeout, read ret: -1`，同步系统日志出现`sec_id:0 is zero or BTM_SetSecurityLevel failed, remote_scn:1`。发生在本机RFCOMM建立，尚未到认证，不是证书失败证据。用户重启蓝牙/设备后进入下一阶段；当时未加入盲目重试或不安全通道回退。复发需读取同时间段系统蓝牙日志。

## 蓝牙断开后 CarPlay 仍可用

蓝牙只在建立连接时参与 iAP2 配对和热点信息下发，投屏、触摸和音频全部走 Wi-Fi。连接建立后关闭手机蓝牙，热点链路不受影响，CarPlay 应继续工作。

因此不能用蓝牙 `isConnected()` 判断会话是否存活。当前看门狗同时观察两个信号：

- 时间同步：`NtpClock` 每秒向 iPhone 发一次请求并等待回包，收到即更新 `lastPeerPacketAt`。
- 视频帧：`AndroidMediaSink.lastVideoFrameAt`，静止画面时作为补充。

两个信号都静默超过 5 秒才判定断开，检查间隔 2 秒，只比较时间戳，不访问蓝牙、不碰音频线程。从未收到时间同步回包时不判定，交给协议层超时，并在 15 秒后记录“未收到手机时间同步回包 断开检测交给协议层”。

2026-11-24 小米 MI6 验收：断开手机蓝牙后画面和声音继续；关闭热点后约 5 秒回首页，日志为“连接已断开: 手机无响应 N 秒”。ARP 表在断开后仍保留 `Flags 0x2`，不能用于在线判断。

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

## 音乐周期性卡顿

2026-11-24 实机出现约每 5 秒卡顿一次。`DiPlay-AudioStats` 每个 5 秒窗口记录：

- `underruns=+2`、`rebuffers` 每窗口 +1，`queue` 在 0–24 之间波动。
- `maxGapMs` 400–1324，`trackBufferFrames=24000`，即轨道约 500 毫秒。
- 同一版本在 14:31 的窗口 `underruns=+0`、`rebuffers=0`、`maxGapMs≈400`、`playbackAdvanceFrames=240000`，说明播放连续时统计正常。

5 秒周期来自统计窗口本身（`STATS_WINDOW_NS`），不是断开检测。1 秒或 2 秒看门狗只读时间戳，不参与音频链路。根因是 Wi-Fi 音频包到达间隔超过缓冲深度，轨道抽干后重新预缓冲。

处理：音乐缓冲默认 300 → 500 毫秒，设置页档位统一为 500/1000，去掉实际无效的 150 档。仍卡顿时选 1000 毫秒，代价是延迟增加。判断修复不要只看单个窗口，需连续多个窗口 `underruns=0`、`rebuffers=0`。

## 画面卡顿与收帧率低

先看 `GeelyCarPlay` 的 `链路诊断`，无线连接每 5 秒一条：

```text
链路诊断: wifi=5200MHz rssi=-54dBm link=433Mbps 接收=96kbps/55包 重传=+2 视频静默=5695ms
```

- `接收` 长期接近 0 而 `重传` 不涨：只能确认本机收到的数据少，不能证明手机没发视频。先确认页面是否静止、是否仍在投屏，以及 iPhone 是否锁屏或退出 CarPlay，再核对视频 TCP 和热点链路。
- `重传` 是本机 TCP 发送方向的统计，不能排除 iPhone 到车机的视频方向丢包，也不能直接当作 wlan1 的无线重试次数。
- `接收` 正常、`视频静默` 很小但 `DiPlay-VideoStats` 的 `shown` 为 0：问题在解码或显示环节。
- `rx` 是收到帧率，`shown` 是显示帧率，两者接近说明渲染跟得上；静止画面 `rx` 低于 1 fps 属于正常。

`链路诊断` 只在无线连接时输出，USB 连接看 `DiPlay-VideoStats` 与 `DiPlay-AudioStats`。

当前 Wi-Fi 连接信息读取的是普通 STA 接口，`wifi=未加入热点` 不代表 iPhone 没连 wlan1。热点需结合 SoftAP 客户端、关联事件及路由判断，ARP 可能残留；时间同步回包持续增加不排除视频连接单独阻塞。

查看连接耗时用 `连接时间线`：

```text
连接时间线汇总: 会话=13414ms 视频参数=13715ms 首包=13738ms 进入=13834ms 页面=13877ms Surface=13905ms 首帧=14038ms 显示=14052ms
```

哪一段差值大，问题就在哪一段：`会话 → 视频参数` 是手机协商，`进入 → 页面` 是 Activity 启动，`Surface → 首帧` 是等关键帧，`首帧 → 显示` 是 TextureView 出画面。

## 进入CarPlay黑屏很久

历史Surface更换后没有及时请求新关键帧，静态画面需等后续更新；UI又按会话连接提前隐藏提示。

当前VideoDecoder更换Surface后重置首帧和关键帧请求间隔并请求恢复；页面等待当前 Surface 首帧输出，TextureView 还需确认纹理更新，不能只看isConnected。验收首次进入、返回再进、静态画面、页面重建；比较Surface更新、关键帧和首帧时间。

自动进入投屏已恢复为会话建立后立即进入，不等待视频参数或收帧稳定，尽早创建 Surface。时间线记录“会话建立后立即进入投屏”。首帧渲染回调已转发到界面层，TextureView 记录首次纹理更新，SurfaceView 仅记录首帧提交，实际显示时间保持未测量。

2026-11-24 已按用户要求撤销连续收帧稳定性等待、全屏加载层及等待视频参数最多 3 秒的逻辑，保留原有首帧等待文字、看门狗及全部诊断。MI 6 日志已确认会话建立后立即进入并提交首帧，不代表后续停顿或断开已修复。

## 建立连接时无法取消

当前主操作在连接中显示取消含义，调用cancelConnection，使旧generation失效、停服务、禁止本进程自动重连并显示已取消。初始化前后检查代次，旧控制器仍需释放。

用户验收初始化、蓝牙连接、等待Wi-Fi/会话阶段，确认旧回调不会恢复已连接。清理异步执行，不应阻塞UI等待资源退出。

## 退出后重新出现未连接CarPlay

实机日志证明旧进程死亡后ActivityManager为残留CarPlayActivity重建进程。旧方案从非根Home调finishAndRemoveTask后kill，没有清完页面。

最新方案：prepareExit禁止重连/旧回调，断开并等待清理，最多15秒；随后NEW_TASK|CLEAR_TASK启动Home ACTION_EXIT，清空旧页面，让退出页成为根，再结束任务与进程。最新包已推送，仍待用户验证。读取退出附近START、process died、start proc日志，AI不自行点击退出或杀进程测试。

## 性能解释

此前短会话：高通H.264解码2258×848，约14–29fps，接收和显示基本一致，recoveries=0；CPU单核口径快照62%–83%，PSS约115–120MiB。约50秒音频有2次缓冲耗尽、1次重缓冲，最长音频收包717ms，视频间隔超过800ms。

不是基准测试，不证明无泄漏或真车性能。静态画面低fps可以正常；渲染跟随接收时要区分发送端供帧与网络抖动。estimatedQueuedFrames/sampleRate只是轨道排队估算，不是端到端延迟；阻塞write耗时不是解码耗时。

## 车机按键无法切歌

2026-11-26 补齐前台投屏页的标准媒体按键入口，复用现有上一首、下一首和播放/暂停映射，经会话控制器发送。已成功入队的首次按下由本页接管，长按重复和松开不再发送；未识别或无法入队的按键仍交系统处理，离开投屏页清除按键配对状态。

只读日志可按 `按键诊断` 筛选，记录 `keyCode`、按下动作、媒体映射、入队结果及会话连接状态，不含协议报文。`入队=true` 只说明发送任务已接收，不代表 iPhone 已执行。标准下一首为 87，上一首为 88；未识别的键会显示 `媒体映射=null`。

本轮不新增后台媒体广播接收或厂商按键适配。若实车操作后没有按键日志，尚不能断言按键未产生，需要继续核对车机是否把事件送达前台页面；有日志但未切歌时，应区分映射、入队和手机执行阶段。实车效果待用户手动安装和操作验收，AI 未执行按键注入或连接测试。

## 读取和分享日志

在用户允许只读检查时，先确认设备，然后读取脱敏标签：

```bash
/home/user/Android/Sdk/platform-tools/adb devices -l
/home/user/Android/Sdk/platform-tools/adb -s <phone-serial> shell pidof com.carplay
/home/user/Android/Sdk/platform-tools/adb -s <phone-serial> logcat -d -v threadtime -t 4000 GeelyCarPlay:V DiPlay-AudioStats:V DiPlay-VideoStats:V '*:S'
```

底层xcertplay-usb可能含热点凭据、设备名称/地址、证书、签名及十六进制协议；需要时先过滤，不打印完整payload/raw-body/head，不导出认证资产。UI报告经过DiagnosticRedactor，分享前仍复查。

触摸报文（`airplay touch report sent`）、RTSP 收发摘要（`airplay rx/tx`）和 iAP2 逐帧 trace（`IAP2 RX`/`IAP2 TX`，含参数清单与 raw-body 十六进制）默认不输出，先在设置页“诊断”分组打开“详细日志”再采集。开关立即生效，无需重连；统计、阶段、`会话关闭诊断`、`event 退出诊断` 和 `IAP2 CLOSE` 不受影响。需要 wire 级十六进制时另把 `AirPlayTrace.enabled` 改为 true，它和该开关相互独立。

本机临时证据：`/tmp/geely-carplay-performance.log`、`/tmp/geely-carplay-navigation-audio.log`、`/tmp/geely-carplay-music-noise.log`。它们不在仓库，可能被清理；下一位AI不能假定一定存在。

## 首次连接卡顿与重连对比诊断

新增诊断已在 MI 6 采集到记录，诊断本身不改变连接、超时、关键帧或 Surface 时机。`会话诊断` 用进程内递增的 `session` 编号关联创建、现有 5 秒链路采样和首次关闭；触摸计数属于本会话，`touchWithContacts` 区分带触点的成功发送与页面分离时的空报告，发送成功不代表手机已处理。`ntpTx/ntpResponsesRx/ntpMatched/ntpAccepted/ntpClockUpdates` 分别表示请求发送、响应接收、请求匹配、有效往返样本和时钟调整累计次数；快照跨线程采样，不是原子快照。

`会话关闭诊断` 只由首次实际关闭者记录来源和调用栈位置；`event 退出诊断` 区分对端 EOF、加密状态清空、解密失败和读写异常，并记录当时会话是否已关闭。`视频读取退出` 区分头部 EOF、负载读取结束、长度超限和异常，并记录阶段及本地关闭状态；它与 `Receive` 同带会话编号。`Surface 诊断` 记录挂载与分离，不参与放行或控制连接。不要把清理后出现的 event 关闭当成最初原因，也不要用 wlan0 指标或视频中未提供序号的 `seqForwardGaps=0` 排除 wlan1 丢包。

## 2.4GHz 视频负载对比

2026-11-24 设置页帧率追加 20fps 档，保留原有 30/60fps 顺序、默认选择和已保存值。帧率校验下限同步降至 20，设置经持久化传入显示配置并用于 `/info` 的 `maxFPS` 声明。用户选择后需要重新建立会话才能协商新上限；声明不代表手机一定严格遵守，应对照实际视频统计。此版本只增加帧率选项，不改变分辨率、热点设置、连接行为及诊断，2.4GHz 效果待用户手动安装验收。

## SurfaceView 优先与 TextureView 兼容

2026-11-24 视频显示方式默认为“自动 · SurfaceView 优先”，设置页可选择“TextureView · 兼容模式”，下次创建投屏页生效，不修改会话参数。SurfaceView 创建失败、Surface 无效、绑定异常或解码器所有配置尝试失败时，当前页面最多回退一次 TextureView。回退检查具体 Surface 和页面，旧回调不触发新页面切换；没有视频包、视频静默和普通输入数据错误不触发回退。自动回退不保存为用户偏好，下次创建页面仍按用户选择尝试。

两种输出复用同一解码器和触摸映射。SurfaceView 的输出由 SurfaceHolder 管理，销毁前通过视频线程队列确认分离，停止会话和视频流结束时同步确认不再使用旧输出；TextureView 只释放自己创建的 Surface 包装对象。等待底层解码器释放的耗时仍需真车验证。首帧按当前 Surface 的解码输出回调判断；TextureView 还核对纹理更新，SurfaceView 只能记录“首帧已提交”，时间线的实际显示时间保持未测量，不冒充显示回调。此改动待用户手动安装验收，不能据此认定网络长停顿得到修复。

## 主题切换与投屏页面

2026-11-24 主题切换不再以 `NEW_TASK | CLEAR_TASK` 启动首页，仅重建当前设置页。首页在下次返回前台时检查主题是否变化，必要时只重建首页；投屏页不加入主题重建逻辑，避免清空任务栈连带销毁 Surface。保留主题持久化及向 iPhone 同步昼夜模式，不改变连接、视频参数和诊断。待用户手动安装验收；用户自行离开投屏页时由系统可见性或生命周期引发的 Surface 销毁仍可能发生，不能保证后台 Surface 始终存在。

## MI 6 显示与链路对照记录

2026-11-24 用户反馈 5GHz 下进入快且滑动流畅，2.4GHz 多次出现停帧与延迟；这支持链路条件相关，但未区分带宽、干扰、重传或无线调度。部分会话为 event 对端 EOF 先发生，随后本地关闭视频流及分离 Surface，不能倒置关闭顺序，也不能仅凭 EOF 判定 iPhone 内部关闭原因。

用户降低分辨率后，17:48 会话实际视频由 1920×720 降为 1344×504，Surface 就绪至首帧提交约 202ms，后段收帧与输出约 19fps、触摸窗口均值 94ms。单次重连和操作内容不同，不足以证明分辨率是断开根因。

用户确认 18:01:30 重连前已关闭 CarPlay 内的“智能缩放显示”，后续一直关闭，主观滑动明显改善；实际视频仍为 1920×720，没有证据显示改善来自降低分辨率。该开关不在本应用设置源码中，日志没有开关事件。不同操作内容下的码率不能直接比较为开关收益。

后续 18:04:14 的 session=5 出现 event EOF，但用户确认期间在 iPhone 上修改了设置，不能计作无人操作下的自动断开，也未证明设置操作必然导致 EOF。18:05 后 session=6 截至 18:08:05 已持续约 162 秒，无新的关闭记录；滑动时三个窗口触摸均值为 62/21/42ms，最大为 213/39/109ms，收帧与输出基本一致，recoveries=0。停手期间长静默不能单独认定卡顿。本次观察不是长期稳定性验收，event EOF 根因仍未确认。

TouchLatencyProbe 为全局对象，待测触摸未在新会话开始时清零；已出现跨会话 12 秒及 95 秒等残留样本，不能用于开关前后或真实触摸响应比较。诊断统计的是触摸发送到下一视频帧，不证明该帧包含触摸效果。本轮仅记录此问题，未修改探针。

主题修改包已通过安装摘要核对。17:58:18 Surface 分离发生在进入设置页之前，返回投屏后同一 session=1 重新挂载并出帧，未见该时段清空任务栈的跳转；但未捕获明确主题切换重建事件，主题切换路径仍需单独验收。SurfaceView 已实机出帧且未见自动回退，厂商兼容及错误回退仍待验证。

验证范围：相关 APK 构建和测试源码编译成功，未由 AI 执行单元测试或启动实机测试；APK 只推送，由用户手动安装和连接。未实施触摸后请求关键帧、自动降画质、自动重连或热点参数修改。

## 连接中途 event EOF 与 iAP2 link ended 记录

2026-11-24 18:40–18:42 小米 MI 6 记录到三次断开。`连接失败: iAP2 link ended` 是结果而不是原因，链条为：iPhone 关闭 AirPlay event 连接（对端 EOF）→ 车机关闭整场会话 → 视频 socket 本地关闭（`SocketException`，`localClosed=true`）→ type-130 隧道结束 → `Iap2LinkChannel` 在没有具体原因时抛出默认文案（`shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LinkChannel.kt:250`）。判断断开原因必须先看同一时刻的 `event 退出诊断` 与 `会话关闭诊断`，不能只读这条兜底消息。

三次断开：

| 时间 | 对象 | 现象 |
| --- | --- | --- |
| 18:40:19.76 | session=1 | `event 退出诊断: 原因=对端 EOF`，随后会话关闭 |
| 18:41:07.36 | attempt=2 建立阶段 | `IAP2 RX [wireless-rfcomm] FAILED: bt socket closed, read return: -1`，蓝牙引导通道被关闭 |
| 18:42:01.35 | session=2 | `event 退出诊断: 原因=对端 EOF` |

断开前的证据：session=1 视频 `readMaxMs=17025`（17 秒没读到字节），`video stats rx=0.6fps shown=0.0fps kbps=18`，期间每秒 `Video recovery: requested keyframe sent=true` 但无帧；session=2 由 `rx=12.4fps kbps=589` 降到 `链路诊断 接收=1kbps/8包`、`视频静默=11685ms`。两次统计都是 `seqForwardGaps=0 lateOrDuplicate=0`，车机侧没有丢包或乱序证据。同一时段 `ntpTx` 与 `ntpResponsesRx` 持续同步增长（session=2 由 6/5 到 26/21），`ntpRequestSendErrors=0`，UDP 往返一直正常。

因此不能判定为 iAP2 协议错误或整条 Wi-Fi 掉线；现象是 iPhone 到车机的视频 TCP 没有数据，而小包仍通。现有日志无法区分“iPhone 侧停止编码发流”与“接收方向 TCP 被 Wi-Fi 压住”。该时段用户是否在 iPhone 上有操作（锁屏、切换应用、断开 CarPlay、来电）尚未确认，确认前不得把三次断开归因为自动断链。

后续区分办法（本轮未实施，未修改代码）：打开设置页“详细日志”复现并对照 EOF 前的 `airplay rx/tx`；在设置里降帧率或分辨率做同条件对比；只读检查 wlan1 的 TCP 重传统计与热点频段信道；必要时取 iPhone 侧 sysdiagnose。以上均待验证，不构成已确认根因。

## 性能优化待办（未实测）

以下按预期收益排序，全部来自静态阅读源码，没有做过 A/B 实测。实施任何一项之前先用现有诊断确认收益，不能用估算代替验证；改完只算“源码编译通过”，效果仍需实机对比。

已完成（2026-11-24）：触摸报文、RTSP 收发摘要和 iAP2 逐帧 trace 已归入设置页“详细日志”开关并默认静默；日志时间格式复用同一个 `SimpleDateFormat`；`DiagnosticRedactor` 合并两条丢弃正则，并在整行既无冒号也无点号时跳过地址替换。下面几项都没改动。

1. 触摸逐事件发送。`CarPlayActivity.kt:117-121` 对每个 MotionEvent 调用 `CarPlaySessionManager.sendTouch`，`AirPlaySession.sendTouch:225` 每次做坐标缩放、HID 报文、bplist 编码、ChaCha 加密和 socket 写，滑动时 60–120 次/秒全部走一遍。日志已不是负担：触摸报文只在会话首条输出且需要打开详细日志。改法是合并同一帧周期内的 `ACTION_MOVE` 只发最后一个，或限频到 60Hz。风险在跟手性，需要用 `TouchLatencyProbe` 对比改动前后耗时；该探针是全局对象且不在新会话清零，跨会话样本不能直接比较。

2. 视频每帧新建 AEAD 实例。`AirPlayCrypto.chachaOpen:90-103` 每帧 `new ChaCha20Poly1305()`、`init`、分配输出数组，长度不符时再 `copyOf`。改法是复用实例或按密钥缓存。注意 BouncyCastle 每次 `init` 仍会重设密钥与状态，收益主要来自对象分配和多余拷贝，不是省掉加解密计算本身；纯 Java 的 ChaCha20-Poly1305 计算仍是主要开销，收益不足时只能考虑 JNI 实现，工程量大，未经评估不做。验证用 `DiPlay-VideoStats` 的 rx/shown、recoveries 和 CPU 单核快照。

3. `VideoDecodeQueue` 每帧全队列扫描。`VideoDecodeQueue.kt:35-46` 每帧 `filterIsInstance<VideoJob.Frame>()` 分配一次列表再做 `sumOf`，队列上限 60 帧，30–60 次/秒。改法是维护 frameCount 与 frameBytes 两个计数器，在入队和 `discardFrames` 时同步更新，对外行为不变。`shared/src/test/.../VideoSurfaceDetachQueueTest.kt` 覆盖 Surface 分离路径，改动后应确认它仍然符合预期。

4. 音频每包数组分配。`AudioStream.kt:105` 每包 `wire.copyOf`，`:124-128` 再三次 `copyOfRange` 和 `ByteArray(12)` nonce，另外每包新建 AEAD 实例，50–100 包/秒。改法是复用接收缓冲、nonce 与实例；包会被异步队列消费，复用缓冲前必须先确认所有权。验证用 `DiPlay-AudioStats` 的 underruns、queue、authFailures 和实际听感。

5. 杂项。视频解码线程没有设置优先级：`AndroidMediaSink.kt:738` 创建 `carplay-video` 线程，而音频在 `:1262` 设了 `THREAD_PRIORITY_AUDIO`；视频线程可以试 `THREAD_PRIORITY_DISPLAY`，但要实测是否反过来抢占音频。`ScreenStream.readFully:138-148` 每帧分配 128 字节头部，可复用，收益很小。

不在验证收益前做：JNI 重写 ChaCha20、修改协议参数、为性能新增设置项。
