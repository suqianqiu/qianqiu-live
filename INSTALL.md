# 千秋直播安装指南

> **学习用途与免责声明：下载、安装或使用前请先阅读。**
>
> 本项目用于 Android 电视应用开发、网络播放、遥控器适配与音视频解码的学习和技术交流。本项目不托管影视或广播视频；代码开源、地址公开或检测通过，不代表已取得相关内容的传播授权。请仅访问自己有权使用的内容，遵守适用法律、来源服务条款及第三方许可，不得用于侵犯版权或其他权益的用途。
>
> 第三方网页、播放地址及评分数据可能变化；不保证全部内容可播放、长期可用或兼容所有电视。安装、网络调试和自行配置应由设备所有者了解后操作。本声明不免除任何依法应承担的责任。如发现权利或安全问题，请通过本仓库 Issues 提供具体链接和说明，以便核查处理。

## 方式一：电脑上的 Codex、WorkBuddy 等 AI 工具通过局域网安装

适用于**能执行本机终端命令、下载文件并访问本机局域网**的电脑 AI 工具。可在电脑上的 Codex、WorkBuddy 等工具中粘贴下面的提示词；具体能力和执行权限以工具当前配置为准。只有网页对话、无法使用本机终端或局域网的工具，可以提供操作指导，但不能直接替电脑连接电视。

**准备条件：**电脑和 Android 电视 / 盒子连接同一路由器，电视支持安装外部 APK，设备所有者能够在电视端开启并授权 ADB 调试。仅能上网不等于已经开放 ADB；无需 ROOT。三星 Tizen、LG webOS 等非 Android 系统不能直接安装此 APK。

1. 在电视的设置中查看 IP 地址，并找到开发者选项中的 ADB / 网络调试开关。菜单位置、是否支持网络 ADB 及端口由厂商决定；弹出调试授权时，需要在电视上用遥控器确认。
2. 在电脑下载并解压 [Android 官方 SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)，使用其中的 ADB。仅安装 APK 不需要完整 Android Studio。
3. 老 Android 电视如果明确提供网络 ADB，可使用电视显示的地址和端口连接，常见端口是 5555；这不是所有电视都会自动开放的端口。较新的电视若提供“无线调试 / 配对码”，先按电视显示的配对地址执行 `adb pair`，再连接其调试地址；配对端口可能与连接端口不同。仅支持 USB 调试的设备，需要厂商支持的调试连接才能启用 TCP/IP；普通电视 USB 插口不一定是调试口。没有可用调试入口时，直接使用下面的 U 盘方法。
4. 把下面提示词交给电脑 AI 工具，将示例 IP 和端口改成自己的。AI 完成下载、校验、连接、安装及启动后，应报告真实结果。

### 可直接复制给 AI 的提示词

```text
请帮我把千秋直播安装到我自己的 Android 电视。
项目地址：https://github.com/suqianqiu/qianqiu-live
发布版本：1.0.6
APK 下载：https://github.com/suqianqiu/qianqiu-live/releases/download/v1.0.6/QianqiuLive-1.0.6.apk
校验文件：https://github.com/suqianqiu/qianqiu-live/releases/download/v1.0.6/SHA256SUMS.txt
我的电视 ADB 地址：192.168.1.100:5555（请替换成实际地址）。
请先阅读项目首页的学习用途与免责声明，并检查电脑是否有官方 adb。
下载 APK 和 SHA256SUMS.txt，核对 APK 的 SHA-256；校验不符则停止安装。
只连接我指定的电视，不扫描其他设备。若电视需要授权或无线配对，请提示我完成电视端操作。
查询 Android 版本与 CPU ABI，确认符合本项目要求后再安装。
使用 adb -s 指定目标，执行 install -r，保留已有应用数据；若签名不匹配，请说明原因，不要自行卸载。
安装成功后启动 com.tv.simplelive/.MainActivity，并核实包名和版本；若能够截屏，请提供启动界面。
无法连接或厂商禁止安装时，请说明实际报错，并指导我改用 U 盘安装。
不要修改电视系统、开放公网端口、ROOT 或绕过厂商安装限制。
```

### 已开放网络 ADB 时的手动命令示例（Windows PowerShell）

先将官方 ADB 加入 PATH，并在当前目录放好已校验的 APK。下面地址只是示例，请替换成电视的真实调试地址。

```powershell
$tvAddress = '192.168.1.100:5555'
adb connect $tvAddress
adb devices -l
adb -s $tvAddress shell getprop ro.build.version.release
adb -s $tvAddress shell getprop ro.product.cpu.abilist
adb -s $tvAddress shell getprop ro.product.cpu.abi
adb -s $tvAddress install -r './QianqiuLive-1.0.6.apk'
adb -s $tvAddress shell am start -n 'com.tv.simplelive/.MainActivity'
adb -s $tvAddress shell dumpsys package com.tv.simplelive
adb disconnect $tvAddress
```

仅在前面的连接状态为 `device`、兼容性检查通过且安装返回 `Success` 时继续后续步骤；不要忽略 `unauthorized`、`offline` 或安装失败信息。`dumpsys package` 输出可核对 `versionName`。安装后无需电脑或 AI 工具持续运行，电视连接网络即可自行使用。完成调试后，可在电视设置中关闭不再使用的网络调试。

连接与配对方式参考 [Android 官方 ADB 文档](https://developer.android.com/tools/adb)。老式 TCP/IP 调试与新版无线配对不同，请按电视实际提供的方式操作。

## 方式二：U 盘安装（不需要 AI 或 ADB）

1. 在电脑打开 [千秋直播下载页](https://github.com/suqianqiu/qianqiu-live/releases/latest)，下载 **`QianqiuLive-1.0.6.apk`**；普通用户不需要下载源码 ZIP。可同时下载 `SHA256SUMS.txt`，在 PowerShell 中运行 `Get-FileHash './QianqiuLive-1.0.6.apk' -Algorithm SHA256`，与校验文件中同名文件的值对比。
2. 把 APK 复制到电视能识别的 U 盘。老电视通常更容易识别 FAT32；请先尝试现有 U 盘，不必为了本应用格式化或清除其中的数据。
3. 将 U 盘插入电视，在电视的文件管理器、媒体中心或厂商提供的本地安装入口中找到该 APK。有些文件管理器默认仅显示照片和视频，需要切换到“全部文件 / 安装包”。
4. 按电视提示，给负责打开 APK 的文件管理器开启“允许安装未知应用 / 外部来源安装”权限；旧系统可能是在安全设置中统一开启。随后选择 APK 并确认安装。菜单名称因品牌和 Android 版本而异。
5. 安装完成后，从应用列表打开千秋直播（电视显示名称为“千秋电视”）。U 盘可拔出，使用在线内容时电视仍需联网。

## 常见问题

- **无法连接 ADB：**检查电脑和电视是否同一局域网、调试开关及电视授权；访客 Wi-Fi / AP 隔离可能阻止设备互访。不要把调试端口映射到公网。无法使用厂商支持的调试方式时，改用 U 盘。
- **U 盘里看不到 APK：**检查是否下载成网页或源码 ZIP、扩展名是否为 `.apk`、文件管理器是否过滤安装包，以及电视是否识别 U 盘。
- **解析包失败 / ABI 不兼容：**核对下载完整性、最低 Android 5.0 和本项目 CPU 架构要求。千秋动画本版仅提供 32 位 ARM 库；千秋直播提供 32 位及 64 位 ARM 库。
- **覆盖安装提示签名不一致：**自行构建的 APK 与发布版可能签名不同。不要直接卸载；先确认是否需要保留历史与配置，再自行决定如何处理。
- **电视禁止安装外部应用：**以上方法依赖系统允许安装，不承诺适用于所有品牌。请使用厂商支持的安装方式，不提供绕过系统限制的操作。
