# 1.3.10 分阶段巡检回归

PipelineChecks.java使用本机可控HTTP服务器和解码桩验证29项管线行为，不表示真实频道质量或电视延迟。EngineProgressTest仍使用tests/live137中的14项回归。先正常构建应用，再用build/app-classes.jar为classpath编译和运行这些测试。

UiChecks.java为41项原生界面检查，只在明确指定的隔离模拟器安装；不得在用户电视安装QA包。测试场景覆盖快检/优选分离、独立缓存、焦点和频道抽屉导航。

本轮使用API30 Google APIs只读模拟器，设置自然方向尺寸1080x1920、density320、font_scale1.0，应用锁定横屏后视口1920x1080，对应960x540dp。为x86模拟器通过根目录build.ps1 -IncludeX86ForTests -OutputApk参数生成额外包含x86_64的模拟器包；正式包保持ARM64/ARMv7，需比较除lib和META-INF外每个条目摘要一致。

从tests/live1310运行build-checks.ps1，再用带明确-s EMULATOR_SERIAL的adb安装测试包和ui-checks.apk，执行`adb -s EMULATOR_SERIAL shell am instrument -w com.tv.simplelive.live1310qa/com.tv.simplelive.UiChecks`。只读模拟器结束后退出，不保存快照。
