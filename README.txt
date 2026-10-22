学习用途与免责声明
**学习用途与免责声明：下载、安装或使用前请先阅读。**

本项目用于 Android 电视应用开发、网络播放、遥控器适配与音视频解码的学习和技术交流。本项目不托管影视或广播视频；代码开源、地址公开或检测通过，不代表已取得相关内容的传播授权。请仅访问自己有权使用的内容，遵守适用法律、来源服务条款及第三方许可，不得用于侵犯版权或其他权益的用途。

第三方网页、播放地址及评分数据可能变化；不保证全部内容可播放、长期可用或兼容所有电视。安装、网络调试和自行配置应由设备所有者了解后操作。本声明不免除任何依法应承担的责任。如发现权利或安全问题，请通过本仓库 Issues 提供具体链接和说明，以便核查处理。

完整电脑 AI 联网安装与 U 盘安装步骤见 INSTALL.md；项目图文说明见 README.md。

千秋电视 / SimpleLiveTV 1.0.6

专为当前微鲸 WTV55K1J（Android 5.0.2 / API 21）制作的轻量电视播放器。
支持 Android 5.0+；APK 包含 arm64-v8a 和 armeabi-v7a。

操作
  每次打开应用先显示全屏电视频道选择页，默认焦点在频道列表。
  内置193个频道显示中文名称；节目内容仍以源站实际播出为准。
  确定：打开频道列表；在列表中按确定播放。
  上下：全屏播放时换台。
  左右：全屏时打开解码选择。
  菜单：设置、导入列表、直接播放、测试片。
  返回：列表与全屏切换；未播放时返回退出。
  HOME：退出前台并停止播放，避免后台占用解码器。

播放列表
  支持 UTF-8 M3U 和“频道名,http(s)://地址”TXT。
  支持网络订阅或系统文件选择器导入本地文件。
  首次启动载入随包的合并公开列表，不自动联网下载或播放。
  2026-10-22扩充：1106条候选地址，269条通过实际视频解码，合并同台线路后193个频道。
  大陆及原有中文频道171个，香港8个，台湾14个；澳门27条候选地址本轮没有通过项。
  来源包括 iptv-org、YueChan、fanmingming、Guovin、Free-TV，具体来源见 THIRD_PARTY.txt。
  旧版52条清单备份保留在 validation-20261022/previous-52-zh.m3u。
  此版随包列表已在电脑上进行 HTTP、媒体探测及三帧视频解码检查。
  内置列表的“恢复/重新加载”使用筛选后的随包副本，不重新拉取未筛选全量表。
  结果只表示检查当时能播放，不保证频道身份、长期稳定性或电视解码兼容性。
  升级时，如果当前使用的是我们的公开列表，将替换旧的随包/缓存列表；
  用户自行导入的其他网络列表或本地列表不会被替换。
  频道列表会缓存在应用私有目录；网络加载失败保留上次列表。
  默认演示片 Big Buck Bunny 是测试视频，不是直播频道。

解码
  自动：请求硬解，允许内核回退；错误或20秒无视频画面时仅重试一次软解。
  仅硬解：强制请求硬件解码。
  仅软解：关闭硬件解码请求，使用 LibVLC 软件解码。
  “优先硬解”表示请求策略，不代表已确认芯片实际使用某个解码器。
  网络失效也会造成无画面；软解无法修复失效地址。
  老电视CPU的软件解码性能有限，不保证4K/HEVC软解流畅。
  只接受 HTTP/HTTPS；不包含DRM、付费鉴权、内嵌账号或自定义鉴权头功能。
  HTTPS列表下载使用系统信任库；没有禁用证书校验。

构建
  环境：JDK 21、Android SDK platform 35、build-tools 35.0.0、PowerShell 7。
  运行：pwsh -File build.ps1
  安装：pwsh -File build.ps1 -Install -Device <your-adb-device>
  依赖：https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.5/libvlc-all-3.6.5.aar
  使用官方 AAR 原样包含的 JNI 库，不修改 LibVLC；src 中为完整应用源码。
  player-local.jks 仅供当前本地测试应用更新，勿当作商业发行签名。

电脑端筛选工具
  tools/validate_streams.py：HTTP、媒体轨道和实际视频帧解码检查。
  依赖 Python 3、curl、ffprobe、ffmpeg，不访问 ADB 或电视。
  运行示例：
  py -3 tools/validate_streams.py input.m3u --output validation --workers 8
  验证结果为 JSON/TXT/M3U，保留每个源的失败原因。
  tools/finalize_expanded.py：合并验证结果，统一中文名称，按频道选取一条通过的线路。
  本轮报告：validation-20261022/report.txt；逐地址结果：results-expanded.json。

第三方说明
  VideoLAN LibVLC 3.6.5，LGPL 2.1；协议和源码信息见 THIRD_PARTY.txt、licenses/。
  原生库通过 JNI 动态链接；可修改应用源码/替换兼容的 LibVLC 后重新构建。
  本应用源码以 MIT 许可提供，见 LICENSE.txt。
  未包含炫彩电视的代码、数据或接口。
