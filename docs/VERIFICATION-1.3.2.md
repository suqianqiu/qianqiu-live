# 1.3.2 验证记录

```text
2026-11-03 / Asia/Shanghai
Physical device: Whaley WTV55K1J, Android 5.0.2 API21, WiFi ADB DEVICE_IP:PORT.

Observed issue:
- Old installed live app: 1.0.5 (6). Old anime app: 1.2.0 (4).
- before.png and before-2.png have identical SHA256, confirming an unchanged CCTV-13 frame across captures.
- Opening the old live controls showed its state as playback ended. Old code had only an initial no-video check; live EOF retained the last frame without reconnecting.

Installed updates (existing package data retained, no main app uninstall/clear):
- com.tv.simplelive 1.3.2 (15)
- com.qianq.kandongman 1.6.2 (11)
Both originals backed up as previous-live-1.0.5.apk and previous-anime-1.2.0.apk in this directory.
Installation used the OEM package installer UI and confirmed normal updates. No verification/security system settings or launcher settings changed. Native ADB temporary uploads were removed afterwards.

Implementation:
- Clock events monitored continuously, 2-second watchdog cadence. 12 seconds without clock movement triggers recovery after video output has appeared.
- When native displayed-frame counters have been observed advancing, 18 seconds without output-counter movement also detects a video-only stall. Zero/unsupported counters are ignored; clock monitoring remains available. Counters are not screenshot color differences, so still scenes do not falsely trigger.
- Live EOF reconnects, including HLS with positive window duration. Explicit finite direct input and movie extensions end normally.
- Same-channel reconnect with 1/3/6-second delays, maximum 3 retries per unstable period. 60 seconds of sustained progress replenishes the budget.
- Manual playback resets the budget/cancels queued reconnect; stale callbacks checked by playback generation and channel. Leaving app cancels the watchdog and pending reconnect.
- Auto decode mode can try software on later retries. Explicit hardware/software modes are preserved.
- Stop remains off main thread; detach follows stop on main thread, then release on worker. Safe Surface reuse waits for cleanup; a permanently blocked native stop is not forcibly bypassed.
- D8 receives application classes as a JAR, avoiding Windows batch command length overflow.

Validation on the actual Whaley:
- policy-results.txt: 16 deterministic policy checks passed, including clock/frame stalls, finite versus live EOF, stable-window budget reset, backoff and exhaustion.
- device-recovery.txt: 10 assertions passed. The local HTTP server delivered enough real H264/AAC TS data to begin playback, then held the connection open without data, EOF or error. Production watchdog detected stopped progress, reconnected and resumed without remote input. Also verified channel/fullscreen preservation, cancellation on manual change, exhaustion after 3 attempts with an error message, and cancellation on exit.
- navigation-results.txt: 35 existing regressions passed. Failed/healthy channels, remote Up/Down/Back, initial software fallback, stale error/timeouts, and UI responsiveness while native stop was deliberately blocked past the ANR threshold; injected stop/detach errors contained and subsequent playback resumed.
- Total: 61 policy/device assertions. Both APKs signature-checked by build, installed version metadata verified.
- anime-updated.png: cold launch of latest anime package on Whaley, 464-title virtualized poster home renders correctly. This was a launch/layout smoke check, not a new full anime playback regression.
- live-after-a.png / live-after-b.png: actual current CCTV-13 channel plays changing news footage after update, distinct from the originally frozen frame. TV left playing this channel.
- recovery-events.txt includes actual watchdog reconnect logs plus labelled fault-injection warnings from NavigationTest.
- after-crash.txt contains an unrelated com.helios.classicbluetoothserver/disabled-original-launcher exception; no com.tv.simplelive crash found in the captured crash buffer. No unrelated system packages were changed.
- Temporary recovery/navigation instrumentation apps removed. Tests restored original preferences; existing downloaded content, viewing records and pin preferences were not cleared.

Limits:
- Short device regression and controlled freeze/outage tests; not an all-source or long-duration guarantee. A permanently dead source cannot be repaired by reconnecting: retries stop with a visible message. A native stop that never returns still prevents safely reusing its Surface; remote input remains available.
- No repeat test on Huawei for this new live version; Huawei output policy from 1.3.1 was retained.

Artifacts SHA256:
9c579a819d84b0a79be2e8b2bdd5e5e89c537936ab114b652db0171875a800d8  WORKSPACE/SimpleLiveTV\dist\QianqiuTV-1.3.2.apk
338d564c9ed30eb58363ce1a811d44f19c36b7b5943cfcffb7931dd4cf3fd86e  WORKSPACE/KanDongman\dist\KanDongman-1.6.2.apk

```

## 发布构建核对（2026-11-09）

公开源码完整构建成功。与实机验证的发行APK比较，当前源码生成的全部应用class字节一致；旧本地构建目录另残留20个历史匿名class，因此旧APK的DEX并非本次干净构建的逐字节结果。发行附件保留已实测、已签名的原APK，SHA256见Release校验文件。本次公开构建脚本使用全新classes目录，避免以后继续打包历史class；未改应用运行时代码。源码自行构建使用独立签名，不能覆盖官方发行包。
