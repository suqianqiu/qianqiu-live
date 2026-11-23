# 1.3.7 巡检逐条显示与退出验收

EngineProgressTest使用本地HTTP夹具和解码桩验证逐源事件、中断及续测，不能据此评价真实视频质量。

UiChecks用于隔离的Android TV模拟器，覆盖两种列表、焦点、部分质量恢复和桌面退出；运行时修改测试应用设置，不应安装到个人电视。请先用build.ps1 -IncludeX86ForTests构建模拟器专用应用，再运行本目录build-checks.ps1，在明确指定的测试模拟器安装测试APK并执行am instrument。测试APK无桌面入口，验收后移除。

本轮API36的截图和验证边界见../../docs/VERIFICATION-1.3.7.md。

从项目根目录运行引擎测试：

```powershell
javac -encoding UTF-8 --release 8 -d build/inspection137-tests tests/live137/EngineProgressTest.java src/com/tv/simplelive/SourceInspector.java src/com/tv/simplelive/SourceAliases.java src/com/tv/simplelive/Playlist.java
java -cp build/inspection137-tests com.tv.simplelive.EngineProgressTest
```
