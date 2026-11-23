# 1.3.8 原生分类抽屉回归

只在明确指定的隔离Android TV模拟器运行，不在用户电视安装QA APK。生产包按项目build.ps1正常构建；tests/live137/EngineProgressTest.java仍用于14项巡检引擎回归。

模型检查（先构建生产包，使用PowerShell）：

```powershell
javac -encoding UTF-8 --release 8 -classpath build/app-classes.jar -d build/drawer-checks tests/live138/DrawerChecks.java
java -Dfile.encoding=UTF-8 -cp 'build/drawer-checks;build/app-classes.jar' com.tv.simplelive.DrawerChecks assets/public-cn.m3u
```

原生UI检查：从仓库根目录执行tests/live138/build-checks.ps1，在指定模拟器中先安装生产APK，再安装tests/live138/ui-checks.apk，执行`adb -s EMULATOR_SERIAL shell am instrument -w com.tv.simplelive.live138qa/com.tv.simplelive.UiChecks`。与被测包同一签名；无启动器入口，不作为产品发布。

API36检查38项：即时质量、完成进度、失败/重复计数、独立分组、取消与不同列表隔离、完整/部分断点、焦点与视口、分类左右/确定、顶部按钮/工具栏、原播放索引映射、重复Back开关/onBackPressed不退出、主动桌面退出和原生stop阻塞隔离。测试源为本地夹具，不代表真实直播质量。
