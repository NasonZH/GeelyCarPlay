# CarPlay v1.0.1

发布日期：2026-11-24

## 亮点

- **应用更名为 CarPlay**：应用名与 APK 产物改为 CarPlay，包名改为 `com.carplay`；旧版 `com.geely.carplay.ui` 的设置与配对不会迁移。
- **视频帧缓冲池化**：车机 GC 从约 1.7 次/秒降到 0.17–0.22 次/秒，Dalvik 堆稳定在 14–18MB。
- **5GHz 无线链路**：车机热点改用信道 149 + 802.11n + WMM 后，204 秒窗口音频 `gaps=0`；2.4GHz 备用信道 11。
- **通话自动回到 CarPlay**：通话接通时自动把投屏页拉回前台，最多尝试 5 次。
- **设备在线判断**：按蓝牙实际连接（设备状态、HFP/A2DP 地址、厂商蓝牙槽位）判断，未连 CarPlay 仅蓝牙在线也显示在线；连接页离线设备置灰且不可点击。
- **性能与日志**：iAP2 隧道缓冲重写、未识别消息 5 秒汇总、日志限流 12 条/秒并去重、音频包少 4 次分配、音频缺口 `gaps/gapFrames` 统计。

## 安装包

- 文件：`CarPlay-v1.0.1.apk`
- 包名：`com.carplay`（应用名 CarPlay）
- 大小：7,250,129 字节
- SHA-256：`74AB9D45E68839AA76A7882C200C4E673EFE89B1CAFFF6977F3062224BFA0FB2`
- 要求：Android 4.3+（minSdk 18）
- 升级：包名已变更，不会覆盖旧版 `com.geely.carplay.ui`；旧版可先卸载，或与新版共存
- 构建来源：`main@8f67fb6`（含更名与包名变更；标签 `v1.0.1` 指向 `f08ec33`）
- 说明：仓库不包含 Apple MFi 认证资产，构建需自备 `app/src/main/assets/offline-mfi/`；Release 安装包包含本机认证资产

## 车机系统侧改动（不在仓库）

- `/system/bin/hostapd` 包装脚本：2.4G 信道/WMM、5G 信道 149 + 802.11n；备份、验证与回滚命令见 `docs/troubleshooting.md`。
- 换车、OTA 或恢复出厂后会丢失，需要重新应用。

## 已知问题

- 导航 Opus 仍有偶发 1–2 包缺口，尚未做缺口隐藏。
- 通话自动拉回投屏待实机验收。
- USB/H52 真车音频、Siri、车载麦克风未验收。

完整变更见 [CHANGELOG.md](../CHANGELOG.md)。
