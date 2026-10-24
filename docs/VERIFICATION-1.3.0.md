# 1.3.0 验证记录

学习用途与免责声明优先适用，见 [README](../README.md)。本记录仅说明已执行的检查，不代表全部频道长期可用。

- 日期：2026-10-24，Asia/Shanghai。
- APK：1.3.0 / versionCode13，com.tv.simplelive；Android 5.0+；arm64-v8a / armeabi-v7a。
- 构建成功，Android apksigner验证通过。LibVLC官方AAR校验未变化。构建仍有旧Java API及LibVLC可选AndroidX类型提示，未阻止构建，不能视作所有Android版本的兼容性证明。
- 电脑端22项HTTP引擎检查通过：完整传输、候选比选、无效解码拒绝、缓存与断点、取消、完整TS窗口等。
- PlaylistTest通过；新随包检查通过：253个频道键唯一、485候选均匹配目录、已知占位URL排除、TVBS仅按已核实URL纠正、CCTV4明确区域ID分离。
- AM9 PRO / Android14 SDK34 覆盖安装成功，保留包名和签名，未卸载原应用；从设备回读base.apk的SHA256与交付APK完全一致。
- 设备只执行无Activity的engineOnly集成验证，9项通过：原生MediaCodec实际解码30帧、同台去重、真实测试视频HLS两轮8完整分片、候选全比选、坏视频拒绝、AES-128解密、本机成功/失败缓存跳过、暂停不写完成时间、第二轮断点续测。
- 测试媒体为自产测试片，经本机回环HTTP服务提供；不是九个真实电视台全部通过。集成测试恢复设置/检查点，临时测试包已移除。
- 没有打开频道播放界面，没有发送遥控、唤醒或HDMI-CEC指令，也未连接或操作其他电视。安装前盒子报告Asleep，安装和测试后报告Awake，不能声称设备始终休眠；没有将该变化作为电视运行截图验证。
- 本轮没有执行新版UI遥控回归、盒子全量真实频道巡检或长时间直播；22项引擎检查及9项设备检查不能代替这些验证。首次进入应用仍按12小时规则继续本机巡检。

APK SHA256：`1aae2cc606bbf750a0498db40d2951e067ce67e7b16d9db16f37617bbb03014b`。

## 复现

用JDK编译 `Playlist.java`、`SourceAliases.java`、`SourceInspector.java` 与 `tests/PlaylistTest.java`、`tests/SourceInspectorTest.java`、`tests/BundledSourcesTest.java`，运行对应main类，随包检查参数为assets目录。SourceInspectorTest使用JDK自带HttpServer，运行时需要jdk.httpserver模块可用。

先构建应用，再运行 `tests/inspection/build-test.ps1`。确认指定设备已授权后安装正式APK及独立测试APK；运行 `adb -s DEVICE shell am instrument -w -e engineOnly true com.tv.simplelive.inspectiontest/com.tv.simplelive.HeadlessInspectionTest`。engineOnly不启动Activity，其他测试入口可能打开界面；测试/安装仍可能影响系统电源状态。完成后卸载独立测试包。不要对正在使用的其他设备运行此测试。

电脑源集合筛选和限制见 [SOURCE_RULES](SOURCE_RULES.md)，97条目/118线路是电脑网络下的快照，不是盒子运行成绩。
