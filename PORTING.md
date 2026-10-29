# jussichords → Windows 桌面版移植记录

目标：基于上游 jussichords（Kotlin + Jetpack Compose 安卓版网易云第三方客户端），
用 Compose Multiplatform Desktop 做出可在 Windows 上解压即跑的桌面版。原始仓库在 ~/workspace/jussichords（不改动），
本目录是工作副本，所有改动只在这里。

**基线（2026-10-22 用户拍板更换）**：上游 **v2.4.5 正式版，commit `ebceb48`（versionCode 45）**，
副本已切到该 tag（本地分支 `desktop-port`）。此前副本误用了 canary 基线 `60104f0`
（v2.4.5-canary.20261012，versionCode 44）；换正式版的原因：上游这版含大屏/横屏适配
（新增 `constants/AdaptiveLayout.kt`、`animation/` 包）与播放重试续播逻辑
（PlaybackService +184 行、PlayerState.onPlayerError 加 `isRetryableNetworkError` 守卫、
新增 NetworkErrors.kt / PlaybackReloadBus.kt）。桌面垫片已按新版核对：Player 接口面未变，
垫片无需改；新增 NetworkErrors.kt 无 android import，可原样纳入编译。
换基线前副本里继承自原克隆的 Hana 安卓定制改动已存档为补丁
（~/workspace/jussichords-desktop-backup/inherited-hana-mods-60104f0.patch），桌面工作
（desktop/、PORTING.md、tools-dl/）为未跟踪文件，切基线无损保留。

## 1. 耦合盘点（2026-10-22 实测，v2.4.5 正式版基线）

代码量：app 模块 242 个 .kt，ncmapi 模块 38 个 .kt。

| 耦合面 | 数量 | 分布/说明 |
|---|---|---|
| import 平台 `android.*` | 68/242 文件 | 主要是 Context、Toast、Uri、Intent、Bundle、Build；新增 data 存储类（HomeBgStore 等）也走 Context+DataStore，可垫片覆盖 |
| Media3 | 32 文件 import | playback 核心 + extensions + UI 文件（MediaItem/Player 类型外泄） |
| Hilt | 20 文件（dagger.hilt/javax.inject） | 全部 ViewModel 用 @HiltViewModel+@Inject；di/AppModule 只有一页、仅提供 Context → 手工装配很容易 |
| LX 音源插件（新增） | lx/ 包 6 文件 | 基于 `android.webkit.WebView`+JavascriptInterface 的 JS 插件引擎，桌面无对应物 → **排除并记缺口**（落雪/自定义音源在桌面不可用，默认网易云源不受影响）；ncmapi 侧 AudioSources/LxSourceBridge 为纯逻辑，保留 |
| ViewModel 构造 | 仅 (Context) / (SavedStateHandle) / (两者) / 无参 四种 | 手工工厂可全覆盖 |
| R 资源 | stringResource 157 处（R.string），painterResource(R.drawable) 在 UI 层 0 处 | 图标是 Kotlin ImageVector（ui/icons）+ Material Icons；字符串需生成桌面 Strings 表 |

关键结构事实：
- ncmapi 是自研网易云协议客户端（NeteaseCrypto 实现 weapi/eapi 加密），经 Ktor(OkHttp) **直连 music.163.com**，
  不依赖外部 API 服务器（API_BASE_URL 只剩解灰 unblock 旁路用）。只 import 了 2 处 android.util.Log。
- 会话 = Cookie（MUSIC_U），CookieProvider.init(cookieMap) 注入；FileProvider 是纯 java.io.File。
- 登录：安卓版只有 WebView 打开 music.163.com/m/login 一条路（LoginScreen 里 loadUrl），ncmapi 里没有任何
  QR/短信/密码登录接口。桌面版改走 Cookie 粘贴登录（见缺口清单）。
- 播放：UI/扩展层把 media3 `Player` 当接口用（Player.setPlaylist/addSong/playMediaAt 等扩展函数），
  MediaItem 的 uri 是假地址 `songId?fee=&pl=`，真实直链在播放时由 `PlayerApi.songPlayUrlV1(songId, level)`
  解析（PlaybackService 用 ResolvingDataSource 做这件事）。PlayerState 接口直接暴露 media3 类型，
  PlayerStateImpl 用 Player.Listener 把状态同步进 Compose 快照。
- DataStore：settings（Preferences）+ favorite_song_ids / search_history（protobuf 序列化，
  proto 文件在 app/src/main/proto，仅此用途）。
- proto 生成：favorite_song_ids.proto、search_history.proto，桌面构建用 protoc 重新生成 JVM lite 代码。

## 2. 桌面结构（最省改动方案）

新增独立 Gradle 构建 `desktop/`（自己的 settings，不纳入安卓模块——本机无 Android SDK 且避免 AGP 配置开销），
源码以 srcDir 方式直接纳入 app 与 ncmapi 的原目录，按排除清单剔除安卓专属文件，缺失类型用桌面垫片补齐：

- **垫片（desktop/src/main/kotlin/shims）**：
  - `android.util.Log`、`android.widget.Toast`（走回调 → 桌面 Snackbar/日志）、`android.os.Build`、
    `android.os.Bundle`（Map 实现）、`android.os.CountDownTimer`（java.util.Timer）、`android.os.SystemClock`、
    `android.net.Uri`（字符串包装）、`androidx.core.net.toUri`、`android.content.Context`/`Intent`
    （最小实现：applicationContext/cacheDir/filesDir/getString/startActivity→打开系统浏览器等）。
  - `androidx.media3.common` 子集：MediaItem、MediaMetadata、Player 接口（含 Listener/Commands/PositionInfo
    及 @State/@RepeatMode 等注解）、Timeline、Tracks、TrackSelectionParameters、PlaybackException、
    PlaybackParameters、AudioAttributes、DeviceInfo、VideoSize、text.CueGroup、C、util.UnstableApi。
    原 PlayerState.kt / extensions/PlayerExt.kt / MediaItemExt.kt 对着垫片**原样编译**。
  - Hilt 注解垫片：dagger.hilt.android.lifecycle.HiltViewModel、qualifiers.ApplicationContext、
    dagger.Module/Provides；`androidx.hilt.navigation.compose.hiltViewModel()` 改为手工工厂实现
    （按构造签名 (Context)/(SavedStateHandle)/(两者)/() 反射+注册表装配）。
- **替换实现（同包同名，desktop/src/main/kotlin/replacements）**：
  - playback/PlayerController → 桌面版（持有 DesktopPlayer，不再是 MediaController）。
  - DesktopPlayer（playback 引擎）：实现垫片 Player 接口；队列/进度/上下首/随机/循环齐全；
    URL 经 PlayerApi.songPlayUrlV1 解析；音频解码用 JLayer（纯 Java MP3）→ javax.sound 输出。
  - utils/DataStoreUtils、data 两个 Serializer 文件的 DataStore 委托 → 桌面版（落盘 ~/.jussichords/data/）。
  - utils/ApiServerErrorNotifier → 桌面版（Toast 回调）；utils/AppUpdateManager → 桌面版（检查 GitHub release，
    不做自更新下载，仅提示）；lyric/DesktopLyricManager → 桌面空实现（设置页开关保留，悬浮歌词窗未做）。
  - ui/screen/LoginScreen → 桌面版：Cookie 粘贴登录（浏览器登录后粘贴 MUSIC_U 等 cookie，
    经原 NcmCookieNormalizer 规范化 → CookieProvider.init + 持久化）。
- **排除文件**：MainActivity、JetMeloApp（其初始化逻辑搬进桌面 main）、PlaybackService、
  playback/audio/*、DesktopLyricService、UpdateDownloadService、AppVisibilityTracker、ImageSaveUtils、di/*、
  lx/ 包中依赖 WebView 的文件（LxPluginEngine 等，入口以空实现顶替）。注：AudioCache 已被上游 v2.4.5 删除，无需再排除。
- **字符串**：构建前用脚本把 res/values/strings.xml 生成 Strings.kt（com.jussicodes.music.Strings），
  源码里 `stringResource(R.string.x)` 机械替换为 `Strings.x`（副本内改，脚本 desktopify.py 留档）。

依赖版本对齐安卓版：Kotlin 2.0.21、Ktor 3.1.1、Coil 3.0.4、DataStore 1.1.4、kotlinx-datetime 0.6.2、
protobuf 4.29.1、zstd-jni 1.5.6-6；CMP 1.8.0（org.jetbrains.compose）+ JetBrains 的
lifecycle-viewmodel 2.9.0 / navigation-compose 2.9.0 / paging-compose 3.3.0 等多平台构件。

## 3. 环境

- 本机 2 核 / 8GB（账面可用内存时常只剩 ~500MB，邻居租户占用；加 swap 被容器拒绝）。
- JDK：Temurin 17.0.20.1（tools-dl/jdk17，本机下载）。
- **Gradle 在本沙箱不可用（2026-10-22 多次实测）**：daemon 正常启动、客户端连上发出 Build 后约 4 秒
  连接被断开（EOF），daemon 日志无异常、非 OOM。原因不明 → **放弃 Gradle，改走 curl 拉依赖 jar +
  kotlinc 手工管线**（编译 ncmapi → 编译垫片+app 源码 → fat jar）。desktop/build.gradle.kts 仅留作参考。
- **Java 出站网络必须直连、禁用代理（2026-10-22 实测）**：经沙箱 egress proxy 的 CONNECT 隧道时
  Java TLS 握手被网关 reset（curl/Python 同路径正常）；Java 直连 `https://music.163.com/` = 200。
  结论：本机跑应用/冒烟测试时不给 JVM 任何 proxy 参数（用户 Windows 机器上无此问题）。
  依赖 jar 用 curl 下载（curl 走代理正常）。
- Kotlin 编译器：另下 2.1.20（tools-dl/kotlinc-2.1.20）——CMP 1.8.0 构件用 Kotlin 2.1 元数据，
  2.0.21 编译器读不了；应用源码本身兼容 2.1 编译。
- 验证：Xvfb 可用，桌面应用可无头启动截图。

## 4. 打包方式（Windows 交付物）

用户确认最终交付物必须是 **.exe**（双击即开），不要 run.bat。路线（全程可在 Linux 上完成）：

1. fat jar：本沙箱 Gradle daemon 连接被断（见 §3），实际走手工管线——
   `desktop/tools/resolve.py` 拉齐依赖 jar 到 `desktop/libs/`，kotlinc 2.1.20 分步编译
   ncmapi → proto → app+垫片（`desktop/tools/build.sh`），再由 `desktop/tools/make_fatjar.py`
   合并为 `desktop/build/jussichords-all.jar`（含 Skiko Linux+Windows 双运行时与 app assets）。
2. **Launch4j**（可在 Linux 上运行）把 fat jar 包成 `Jussichords.exe`（GUI header、jar 内嵌进 exe），
   配置中 JRE 路径写相对路径 `jre`，指向同目录的 Temurin 17 Windows JRE。
3. 交付布局 `dist/Jussichords-Windows.zip`：
   ```
   Jussichords.exe      ← 双击这个
   jre/                 ← Temurin 17 Windows JRE（winjre17.zip 解出）
   ```
   解压后 exe 与 jre 同目录，双击 exe 即启动，不要求用户装 Java。
4. 备选（未采用，仅评估）：GitHub Actions windows runner + Compose Desktop 的 createMsi/createExe
   能出真安装包，但需要把仓库 fork 到用户账号并跑 CI，本次不执行；Launch4j 路线已满足「双击即开」。

## 5. 进展日志

- 2026-10-22 盘点完成（见上表）；确定垫片路线；JDK 17.0.20.1、Temurin Windows JRE 17（42M）已下载；
  打包方式改为 Launch4j .exe（用户确认）。
- 2026-10-22 基线更换：canary 60104f0（vc44）→ **v2.4.5 正式版 ebceb48（vc45）**，用户拍板；
  git fetch tag 成功，副本切到分支 `desktop-port`，HEAD 与 versionCode 45 已核对；
  桌面工作无损保留，垫片按新版 PlayerState 核对无冲突。
- 2026-10-22 编译打通：ncmapi.jar / proto.jar / app.jar（5.2M，含全部垫片与桌面替换实现）零错误。
  关键问题与解法：Compose 依赖集统一到 JetBrains 1.9.0 + AndroidX lifecycle 2.9.4/2.9.6 +
  savedstate 1.3.1/1.4.0（Google Maven 的 KMP 桌面构件；JetBrains 同名 -desktop jar 部分为空壳，
  须用 Google 版）；补 kotlinx-coroutines-swing 提供 Main 调度器；strings.xml 经脚本生成 R/Strings
  垫片，源码零改写；desktopify 副本做 3 类机械改写（sharedElement 参数名、Coil Builder 上下文、
  ArtworkThemeSeed 的 allowHardware/位图适配）。
- 2026-10-22 运行时打通（Xvfb 无头 + AWT Robot 脚本点击 + 截图存证，截图在 desktop/build/run1/）：
  主界面渲染、导航切换、搜索页排行榜真实数据、搜索「Jay Chou」真实结果、封面加载、歌单详情
  （飙升榜 65.2 亿播放/100 首）、设置页全项、登录页（Cookie 通道）均实测通过。
  期间修复两个真 bug：① Uri 垫片相对 URI 的 path 解析为空（`1315196858?fee=0&pl=0` 丢 id，
  导致播放解析恒失败）；② hiltViewModel 垫片传空 SavedStateHandle（导航参数丢失、带参页面
  恒 loading），改为取 NavBackStackEntry.savedStateHandle。
- 2026-10-22 播放引擎实测（引擎级探针 + 测试专用虚拟声卡 NullMixer，仅测试 classpath、不进交付包）：
  真实歌曲（id 1315196858）解析直链 → HTTPS 拉流 → JLayer 解码：duration=182650ms、
  position 实时推进（≈990ms/s）、seek 到 60s 落点 60026ms、pause 冻结、resume 续播、
  音量设置、解析失败自动重试并跳下一首，均符合预期。本机无声卡，PCM 实际出声需 Windows 真机
  （有标准音频设备时 javax.sound SourceDataLine 直通，本机已验证解码帧按实时速率被 line 消费）。
  引擎附带改进：音频流 URL 自动升级 HTTPS（网易 CDN 同对象支持；http 链在部分网络被过滤/劫持）。
- 2026-10-22 打包完成：fat jar 104MB（31644 条目）→ Launch4j 产出 Jussichords.exe（PE32+ GUI，
  内嵌 jar，JRE 相对路径 jre/）→ dist/Jussichords-Windows.zip（148MB，含 jre/ 与 README.txt）。
  最终 fat jar 本体在 Xvfb 下启动复验通过（截图与基准一致、零未捕获异常）。

## 6. 已知缺口 / 与原版差距

- **登录**：仅 Cookie 粘贴登录（原版内置的兜底通道，与扫码同一会话）。扫码/短信登录未实现：
  原版走安卓 WebView 承载官方登录页 + 设备参数，桌面无对应组件；如实标注，不假装。
  登录后的接口（每日推荐、收藏写操作、私信等）代码与原版同一套，未用真实 Cookie 实测。
- **游客态探索页空白**：原版行为——ExplorePreloader 对未登录（guest）不拉取推荐（每日推荐需登录），
  桌面版行为与原版一致，非缺陷；登录后即有数据（磁盘缓存机制亦原样保留）。
- **FLAC/无损格式**：桌面引擎（JLayer）只解 MP3；音质设到 lossless/hires 时若返回 FLAC 流会报
  「格式不支持」并按原版策略跳歌。默认音质 STANDARD 为 MP3，不受影响。列为缺口。
- **LX 自定义音源插件**：原版基于安卓 WebView 的 JS 引擎，桌面 stub 化（解析恒为空 → 自动回落
  官方/直连音源）。VIP 歌曲在 LX 模式下无法借 LX 解灰；内置直连/代理（API 服务器）音源不受影响。
- **桌面悬浮歌词窗**：未实现（DesktopLyricManager 恒为不可用终态，与安卓无悬浮权限时一致，
  设置页亦如实显示「需要悬浮窗权限」）。**窗内**播放页歌词同步滚动完整可用（已实测）。
- **8D/混响音效**：AudioEffectMode 设置项保留并落盘，桌面引擎不应用 DSP 处理（media3 音频
  处理器无桌面等价物）。普通播放不受影响。
- **应用内更新**：桌面版仅检查 GitHub 新版并提示（Toast 引导去项目页下载新 zip），不在应用内
  下载安装（原版是 APK 安装流程，桌面不适用）。
- **弱网重试语义差异**：原版靠 PlaybackService 内部恢复；桌面引擎在引擎层实现（解析失败重试
  1 次、网络类错误重试后仍失败则自动跳下一首），UI 层行为与原版基本一致，细节有别。
- **保存图片位置**：原版存系统相册（MediaStore）；桌面版存到 ~/Pictures/jussichords/ 并 Toast 告知。
- **动态取色**：桌面无壁纸取色，dynamic color 回落默认深/浅色板（与安卓关闭动态取色时一致）。
- **拼音排序**：android.icu Transliterator 桌面为恒等桩，个别按拼音排序/分组场景降级为原文序。
- **VIP 加密下载/离线缓存**：官方 DRM 能力，第三方 API 拿不到，不实现（同 §7 第 18 项）。
- **exe 未做代码签名**：Windows 上首次运行可能弹 SmartScreen 提示（Launch4j 已警告），需用户点
  「仍要运行」；如需消除，须购代码签名证书后对 exe 签名。

## 7. 官方客户端对标清单（2026-10-22 用户定验收标准）

验收口径：功能完整度与体验以网易云 / QQ 音乐官方桌面客户端为准；原版 jussichords 有的功能
不砍，必须砍的在此写明原因。曲库数据源仍为网易云（App 本身定位），不接 QQ 音乐接口。
状态：✅已实现并实测 / 🔶已实现待测 / 🛠临时实现 / ❌缺口（附原因）。
交付前须按「启动→登录→搜索→打开歌单→播放→拖进度→切歌→切播放模式→看歌词→关窗重开恢复」
主流程逐条自测并把结果回填本表；已知崩溃/阻断级问题不交付。

| # | 对标项（官方客户端核心体验） | 状态 | 备注/实测记录 |
|---|---|---|---|
| 1 | 登录（官方扫码/短信） | 🔶 | Cookie 粘贴登录页已实测渲染且流程完整（规范化→落盘→CookieProvider→跳库），与原版内置 Cookie 通道同一逻辑；扫码/短信为缺口见 §6；登录后接口未用真实 Cookie 实测 |
| 2 | 首页推荐（个性化歌单/每日推荐） | 🔶 | API 层已实测：匿名 personalized 返回 10 条真实歌单；探索页数据需登录（原版对 guest 按设计不拉取，见 §6），未登录无法验 UI 数据 |
| 3 | 搜索（歌曲/歌单/歌手/专辑） | ✅ | UI 实测（2026-10-22）：搜「Jay Chou」返回真实结果列表（屋顶/布拉格广场/骑士精神…，含 VIP 标记、封面）；搜索页排行榜（topList 65 榜）真实数据上屏 |
| 4 | 歌单详情（打开歌单、列表、收藏） | ✅ | UI 实测：飙升榜详情页（封面/65.2 亿播放/100 首列表/收藏·播放·评论按钮）完整渲染，数据经 v6/playlist/detail 真实接口 |
| 5 | 我的收藏/喜欢（红心、收藏歌单） | 🔶 | 代码同原版（proto 落盘已接）；需登录态，未实测 |
| 6 | 最近播放 | 🔶 | account/recent 匿名有真实响应；登录态列表未实测 |
| 7 | 播放/暂停 | ✅ | 引擎实测：真实歌曲解码播放、position 实时推进、pause 冻结/resume 续播（虚拟声卡验证解码与 line 消费；真实出声待 Windows 真机，见 §5） |
| 8 | 上一曲/下一曲 | ✅ | 引擎实测：队列索引切换、解析失败自动跳下一首均符合 media3 语义（含 >3s 回开头规则） |
| 9 | 进度拖动（seek） | ✅ | 引擎实测：seek 60s 落点 60026ms（Range 请求路径）；服务器回 200 时有字节估算回退（精度 ~1s） |
| 10 | 音量控制 | ✅ | 引擎实测：volume 设置经 SourceDataLine MASTER_GAIN 生效，探针回读一致 |
| 11 | 顺序/随机/单曲循环 | ✅ | 沿用原版 PlayerState 逻辑；TOGGLE_SHUFFLE 自定义命令在桌面引擎真实处理（实测 repeatMode 读写、shuffle 翻转路径）；UI 按钮走同一命令通道 |
| 12 | 播放队列管理（查看/增删/清空） | 🔶 | 引擎队列操作（增删/移动/清空）已实现并随播放探针间接验证；队列 UI 未单独截图自测 |
| 13 | 歌词同步滚动 | ✅ | UI 实测：播放页歌词加载（songLyric 接口）、当前行高亮随进度滚动正确；桌面悬浮歌词窗为缺口见 §6 |
| 14 | 播放状态恢复（关窗重开） | ✅ | 实测：跨进程重开后迷你播放器自动恢复上次队列曲目（《屋顶》）与暂停态，与原版 PlayerExt.init/SongListUtil 恢复链一致 |
| 15 | 弱网重试续播 | 🛠 | 桌面引擎实现：解析失败重试 1 次（间隔 1.2s）、网络错误重试后跳下一首，探针实测触发；与原版 Service 内恢复语义有别，见 §6 |
| 16 | 设置项（音质、主题、界面缩放、API 服务器等） | ✅ | 设置页 UI 实测全项渲染（账号 Cookie/外观/音质=标准/缓存计量 565.8KB）；DataStore 真实落盘 ~/.jussichords（settings.preferences_pb 已验）；API 服务器列表/测速/最快选择与原版同逻辑（measureApiServers 已移植）；逐项改值未逐一截图 |
| 17 | 加载/空/错误状态 | ✅ | 实测观察：歌单加载转圈、游客探索页空态（原版设计）、搜索空结果页均为原版 UI 状态，无崩溃；全程日志零未捕获异常 |
| 18 | VIP 加密内容下载/离线缓存 | ❌ | 官方 DRM 能力，第三方 API 拿不到，不实现（见 §6） |
| 19 | LX 自定义音源插件 | ❌ | 依赖安卓 WebView JS 引擎，桌面 stub 化（返回空→回落官方源），见 §6 |
| 20 | 检查更新 | 🛠 | 桌面版仅检查 GitHub release 并提示，不做 APK 式下载安装 |
| 21 | **API 集成（NeteaseCloudMusicApiEnhanced 规范）** | ✅ | 对接方式：**ncmapi 原生实现原样保留**（自研 weapi/eapi 加密直连 music.163.com；设置了自建服务器时走 Enhanced API 的 HTTP 接口 `API_BASE_URL`，与原版同一套 apiClient 路由逻辑，接口规范出处即上游 README 致谢的 NeteaseCloudMusicApiEnhanced）。接口地址/API 服务器设置项桌面版原样保留（JetMeloApp 的 AudioSync 同步逻辑在 DesktopMain 等价复刻，设置页实测可用）。逐类实测（2026-10-22 匿名真实请求，ncmapi 零改动）：搜索 ✅（含 UI 层）、首页推荐歌单 ✅（10 条）、歌曲 URL ✅（真实 MP3 直链并完成解码播放，版权受限歌曲匿名返回 null 属网易云账号策略）、歌单详情 ✅（含 UI 层）、歌单信息 ✅、排行榜 ✅（65 榜，含 UI 层）、歌词 ✅（LRC 原文，含 UI 层同步高亮）、专辑详情 ✅、歌手热门 50 ✅、评论 ✅（30656 条分页）；account/recent 匿名亦有真实响应。写操作与登录后接口（收藏、私信、云盘、最近播放写回）需登录态，无测试 Cookie 未实测，如实标注；登录类接口 ncmapi 本无（原版靠 WebView），桌面版 Cookie 注入会话 |

---

## 7. 2.0「企鹅音乐」重做（2026-10-22 晚，用户打回 1.x 后按效果图重做）

### 7.1 范围与红线

- 触发：用户真机试用 1.x 后明确否定「把安卓界面直搬」路线，要求按 Windows 桌面规范重做。
- 不动：ncmapi 接口层（仅**纯新增**登录/电台路由，见 7.3）、DesktopPlayer 引擎、数据落盘
  （`~/.jussichords/`，与 1.x 同目录同文件，老 Cookie/设置/队列自动延续）、Kotlin 包名与
  BuildConfig 版本（2.4.5/45，防上游误报更新）；app 源码不直改，仍走 build.sh EXCL +
  desktop/src 同包同名替换。
- 改名：窗口标题/标题栏品牌/托盘/关于/README 全部「企鹅音乐」；exe=PenguinMusic.exe，
  zip=PenguinMusic-Windows.zip；旧 Jussichords-Windows.zip 保留不删。
- 效果图基准：mockup/main.png、mockup/login-lyrics.png（用户 2026-10-22 22:09 拍板）。
- 对标优先级（用户定版）：①网易云 Windows 官方版 ②QQ 音乐 / 酷狗 ③Spotify/YesPlayMusic/LX。

### 7.2 配色方案（用户授权放开后定版）

**深色打底 + 封面动态取色**：JetMeloTheme 的 ARTWORK 机制在 2.0 默认开启——全 UI 强调色
（侧栏高亮、播放键、进度、链接、设置选中态）跟随当前播放歌曲封面主色；桌面歌词「已唱色」
默认哨兵值=跟随主题色（即跟随封面）；全屏播放页=封面高斯模糊放大背景+取色渐变+暗罩。
浅色主题走同一取色管线（浅色系种子），深/浅都可用。理由：与网易云的沉浸取色一致、又比
固定红色更有辨识度且随歌变化；对比度底线：正文一律 onSurface/onSurfaceVariant 语义色，
取色只动强调色不动正文色。实测图：run2/06（全屏页红色封面取色）、run2/12（浅色）、run2/10e（深色）。

### 7.3 ncmapi 纯新增（AuthApi + 路由）

- 路由新增：`/login/qr/key`、`/login/qr/check`、`/captcha/sent`、`/captcha/verify`、
  `/login/cellphone`、`/login`（邮箱）、`/dj/recommend`、`/dj/hot`。
- ApiClient 新增 `requestNeteaseWithCookies()`、`sendWithCookies()`（捕获 Set-Cookie，供扫码
  803/密码登录把会话 Cookie 落盘）；原 `send()` 改为委托它，行为不变。
- 实测：qrKey 成功；qrCheck 状态机 801 待扫（新 key 实测）；captcha/sent 对无效号段回 200
  （接口可达，该号段不存在短信不会真发）；错误验证码登录回业务错误「验证码错误」；
  匿名 account() 的 userId=0 → 会话判据定为 userId>0。

### 7.4 新增/替换的桌面文件（desktop/src）

- 壳与窗口（DesktopMain.kt 重写）：无边框窗口+自绘标题栏（拖动/双击最大化/最小化/关闭）、
  四边缩放手柄（AWT setBounds，最小 900×600）、窗口几何恢复（坐标 clamp 进主屏）、
  Tray（打开/播放暂停/上下曲/桌面歌词显示+锁定/退出）、AWT 全局键鼠监听（媒体键
  0xB0–0xB3、鼠标侧键 BUTTON4/5 转发导航）、主题模式与 UI 缩放接线、首启迁移取色=ARTWORK。
- MainScreen.kt（替换）：四区布局、NavHistory 历史栈（条目以类型安全路由对象保存，
  支持 ‹ › / Alt+←→ / 侧键；上次页面按字段反射拼回具体路由恢复）、顶栏自绘搜索丸
  （BasicTextField，见自查表 #18）、侧栏（可拖宽 180–320 落盘）、快捷键根处理
  （Esc 优先关全屏/队列；空格看 inputFocused 防误触）、队列/全屏覆盖层。
- 内容页替换：PlaylistScreen（取色头图、播放全部/随机/收藏、歌单内筛选、排序菜单、
  定位当前行）、AlbumScreen、ArtistScreen、SearchScreen（防抖建议、回车执行、搜索历史、
  类型页签、空态=历史+排行榜）、LoginScreen（四页签，见 7.5）、SettingsScreen（见 7.6）、
  NavGraph（+电台路由）。
- 新组件：PlayBar（三段式；BoxWithConstraints 窄窗紧凑模式见自查表 #13）、FullPlayer
  （模糊背景+歌词点行 seek+译开关）、QueuePanel（删单曲/清空）、SongTable（桌面歌曲表：
  悬停/双击/右键菜单/当前行高亮）、DjPages（电台页）、DesktopLyricController/Window
  （歌词悬浮窗：JNA Win32 穿透锁定、拖动、逐字扫光、双行、位置/锁定落盘）、
  LyricRepository（YRC 逐字优先、LRC 兜底、翻译匹配）、LikeController、SessionState
  （会话恢复/过期识别）、DesktopPrefs（2.0 专属键）。
- 依赖新增：zxing-core 3.5.3（二维码，BitMatrix+Compose Canvas 渲染）、jna 5.14.0（Win32）。

### 7.5 登录（对标网易云 Windows 版三通道）

扫码（unikey→二维码内容 `music.163.com/login?codekey=`→1.5s 轮询；800 过期出刷新层、
801 待扫、802 已扫待确认显示对方昵称头像、803 成功取 Set-Cookie 落盘 ncmCookieKey)、
手机号+验证码（captcha/sent 发码、60s 倒计时防重复、captcha/verify 校验）、
手机号/邮箱+密码（按 @ 分发，密码内部 md5）、Cookie 粘贴兜底（NcmCookieNormalizer 归一化）。
成功统一：CookieProvider.init + 清用户歌单缓存 + SessionState.refresh + 跳我的音乐。
不登录可浏览搜索。过期识别：account() 返回 userId≤0 且本地有 Cookie → expired 态
（顶栏头像区红字「登录已过期」、设置账号区给出重新登录入口）。

### 7.6 设置（改动即时生效且落盘）

账号 / 播放（在线音质、下载音质两个 SongLevel 下拉：标准/较高/极高/无损/Hi-Res…按接口
真实档位；失败自动跳歌开关）/ 界面（主题 浅/深/跟随系统分段、封面取色开关、界面缩放
0.7–1.1+重置、关窗最小化到托盘）/ 桌面歌词（开关/锁定/逐字/单双行/字号/双色预设+跟随
封面/透明度）/ 快捷键清单 / 缓存（计量+清理）/ API 服务器 / 关于（企鹅音乐 2.0 · 基于
jussichords 2.4.5；检查更新提示去 GitHub 取桌面包）。

### 7.7 自查对照表（追加轮：五大类 × 对标）

| # | 检查项 | 对标 | 发现 | 处理 |
|---|--------|------|------|------|
| 1 | 接口失败提示与重试 | 网易云（错误态+重试钮） | 歌单/专辑/歌手/DJ 页均有 ErrorState+重试；专辑页重试走重新导航 | 已具备，实测 DJ/歌单错误路径 |
| 2 | 播放 URL 失效自动跳歌 | 网易云 | 引擎既有：解析重试 1 次后跳下一首并 Toast；设置有「播放失败自动跳下一首」开关 | 已具备 |
| 3 | 匿名/地域限制歌曲 url=null | 网易云（版权/地域策略） | 沙箱美国 IP 实测：热门歌多为 code 404/cannotListenReason=1，属网易云策略非缺陷；可播样本 fee=0 全球曲 | 如实记录，不误判为引擎故障 |
| 4 | 登录过期全链路 | 网易云 | SessionState.expired：顶栏红字提示+设置页重登入口 | 已实现 |
| 5 | 二维码过期 | 网易云 | 800 态显示过期遮罩，点击即刷新新码 | 已实现（run2/08 为正常态） |
| 6 | 发码防重复 | 网易云 | 60s 倒计时内按钮禁用；手机号格式不过按钮禁用 | 已实现（run2/09） |
| 7 | 空歌单/无结果/无歌词 | 网易云 | 空态文案与占位齐全；纯音乐显示「纯音乐，请欣赏」（接口原文） | 已具备（run2/05） |
| 8 | 无封面占位 | 网易云 | ArtworkImage 统一占位（音符图） | 已具备（run2/14 空态播放栏） |
| 9 | 队列为空播放区状态 | 网易云 | 无队列时走带整体禁用、文案「选择一首歌开始播放」 | 已具备（run2/01、14） |
| 10 | 超长歌名截断 | 网易云 | 列表/播放栏/队列均 maxLines+省略号；窄窗实测歌手名截断正常 | 已具备（run2/13b） |
| 11 | 大歌单滚动 | 网易云 | 全量 LazyColumn 懒渲染；歌单内筛选本地过滤 | 已具备 |
| 12 | 深色主题对比度 | 通用底线 | **事故**：内容区无 Surface 提供 contentColor，深色下正文 LocalContentColor 仍为默认黑，设置页正文几乎不可见 | **已修**：MainScreen 根改 Surface(color=background, contentColor=onSurface) 统一提供（run2/10d 事故图 → run2/10e 修复图） |
| 13 | 最小窗口播放栏溢出 | 网易云 | **事故**：900 宽时主列仅 ~697dp，左右段各固定 300dp 把中段走带挤没（播放键被裁） | **已修**：PlayBar BoxWithConstraints 紧凑模式（<780dp：左段 176、右段 196、音量只留图标）（run2/13 事故图 → run2/13b 修复图） |
| 14 | 顶栏搜索框文字被裁 | 网易云（丸形搜索框） | **事故**：M3 OutlinedTextField 最小高度 56，硬塞 38dp 致「搜索」二字只剩一半 | **已修**：改自绘丸形 BasicTextField（run2/12 可见完整占位字） |
| 15 | 音量滑条配色 | QQ 音乐（播放栏细轨） | 浅色下 onSurface 黑色粗轨与效果图不符 | **已修**：改 primary 主题色，与进度条一致（run2/12） |
| 16 | 品牌重复 | 网易云 | 标题栏与侧栏各一份「企鹅音乐」上下堆叠 | **已修**：删侧栏品牌行（run2/12/14） |
| 17 | 搜索防抖与回车 | 网易云 | 建议请求 VM 内 300ms 防抖；回车提交并记录历史 | 已具备（run2/04 历史 chips） |
| 18 | 空格误触 | 网易云 | 输入框聚焦时 inputFocused 置位，根快捷键跳过空格/方向键处理 | 已实现 |
| 19 | 拖进度时歌词跟随 | 网易云 | 全屏页歌词按 shownPosition（含拖动预览）计算当前行 | 已实现 |
| 20 | 音量拖动被刷新打断 | QQ 音乐 | 音量直读直写 player，不进 400ms 轮询 | 已实现（代码结构） |
| 21 | Esc 退全屏后焦点 | 网易云 | Esc 先关全屏再关队列；07c 实测 Esc 后主界面 hover/交互正常 | 已具备（run2/07c） |
| 22 | 桌面歌词屏外恢复 | 酷狗 | 位置恢复时 clamp 进主屏 bounds（窗口几何同理） | 已实现 |
| 23 | 桌面歌词逐字变色 | 酷狗（卡拉OK 逐字） | YRC 逐字数据时逐字扫光；无 YRC 时整行渐变扫光 | 已实测（run2/07c：已唱段变紫） |
| 24 | 歌单内排序/筛选 | 网易云 | 筛选框本地过滤 + 排序菜单（默认/歌名/歌手/时长） | 已实现（run2/04b） |
| 25 | 队列删单曲/清空 | 网易云 | 行尾 ✕ 删单曲、头部清空按钮 | 已实测 UI（run2/11） |
| 26 | 当前播放定位 | 网易云 | 歌单页定位钮 + locateCurrentSignal 滚动到当前行并高亮 | 已实现 |
| 27 | 复制歌曲信息 | 网易云 | 右键菜单「复制歌曲信息」（歌名 - 歌手）写剪贴板 | 已实测 UI（run2/12） |
| 28 | 歌曲行右键菜单 | 网易云 | 播放/下一首播放/加入队列/喜欢/查看专辑/复制信息 | 已实测 UI（run2/12）；歌单卡片右键未做（成本低但入口价值低，取舍记录） |
| 29 | 电台页 | 网易云 | /dj/hot 匿名回空列表（接口需登录），页面做空状态说明而非假数据 | 已实现，如实标注 |
| 30 | EQ/音效入口 | QQ 音乐 | 未做：现引擎为 JLayer 直解 + javax.sound，无 DSP 链，加 EQ 需重构音频管线，风险/成本高 | **未修（取舍清单）**：待用户拍板再议 |
| 31 | 托盘/媒体键实测 | 网易云 | 代码就绪（Tray 菜单+AWT 媒体键监听）；Linux Xvfb 无系统托盘、媒体键无法注入验证 | **未实测**：待 Windows 真机验 |
| 32 | 下载全链路 | 网易云 | 下载音质设置已桌面化；实际下载沿用上游实现，本轮未在桌面端验完整下载 | 如实标注 |

### 7.8 2.0 测试证据（Xvfb 无头 + Robot，截图在 desktop/build/run2/）

- 01-main 主壳四区布局 / 02-search 真实搜索结果 / 03、03b 连续跳歌（地域限制样本行为验证）
- 04b 歌单详情页（原创榜：取色头图/筛选/排序/定位） / 04d UI 播放（进度 0:14→推进、暂停图标）
- 05 全屏页（纯音乐空歌词态） / 06 全屏页歌词滚动 / 06b 点歌词行 seek 到 3:02 当前行高亮
- 07/07b 桌面歌词窗（前奏期显示歌名） / 07c 桌面歌词逐字扫光+双行+Esc 退全屏
- 08 登录扫码真实二维码+轮询（日志含 qrcode/unikey 与 client/login 请求） / 09 验证码表单
- 10 设置页 / 10b 音质下拉展开（标准/较高/极高✓/无损/Hi-Res） / 10d→10e 深色事故与修复
- 11 队列面板（3 首+删单曲/清空入口） / 12 右键菜单+搜索丸+音量紫轨 / 13→13b 最小窗口事故与修复
- 14 fat jar 本体启动冒烟（全新数据目录）
- 引擎探针 PlayerProbe3（真 DesktopPlayer+测试专用 NullMixer，不进交付包）：id 1315196858
  解码、position ≈990ms/s 推进、seek 60026ms、pause 冻结、resume 续播、volume 回读
- 恢复链跨多跑实证：队列（种子歌单）/上次页面（Search→Playlist→Settings 逐跑接续）/
  主题（深色跨跑保留）/队列面板开关/桌面歌词开关均落盘恢复；登录态无真实 Cookie 未测（同 1.x）

### 7.9 2.0 交付

- dist/PenguinMusic-Windows.zip（145MB）= PenguinMusic.exe（Launch4j 内嵌 fat jar 107MB，
  PE32 GUI，fileVersion 2.0.0.0）+ Temurin 17 JRE + README.txt；MD5 `9aba09378758feeafceaa1b47c185319`
- 下载：https://tsemby.lblb.eu/dl/PenguinMusic-Windows.zip（服务器 /opt/directplay/www-dl/，
  chmod 644，curl -I 验 200，MD5 两端一致；目录属主为 root，上传经家目录+sudo mv）
- 旧 Jussichords-Windows.zip 保留不删；exe 未签名（SmartScreen 提示见 README）
- 维持的 1.x 缺口：FLAC/无损解码（JLayer 仅 MP3；无损档位会回退/不可播时自动跳歌）、
  LX 插件 stub、VIP 加密下载不实现——均维持原样

### 7.10 2.0.1 修复（2026-10-23，用户真机反馈四项，证据在 desktop/build/run3/）

1. **标题栏按钮缺失**：原 PenguinTitleBar 把 WindowDraggableArea 内的 Row 写成
   `Modifier.weight(1f).fillMaxWidth()`，与同级按钮行的约束叠加后三个窗口按钮被
   挤出右界（run2 的 01-main.png 右上角实为空，2.0 自查漏检）。重构：拖动区只占
   weight，按钮行固定靠右、各自 40dp；关闭键补悬停态（红底 #E81123 + 白字）。
   验收：01-main.png 右上角三按钮齐全；Robot 真点最大化（02/02b）——placement
   切到 Maximized 且 DataStore 落盘 `desktop.windowMaximized=true`（裸 Xvfb 无
   窗口管理器，几何尺寸不跟随变化，属环境限制，Windows 真机由平台执行）；
   真点最小化（03）点击与悬停态均送达（同因无 WM 不图标化）。
2. **桌面歌词打不开**：PlayBar 的歌词开关被 `enabled = hasQueue` 门控，无队列时
   点击无反应无提示。去掉门控（PlayBar 与全屏页两处），切换时 Toast 反馈
   已打开/已关闭；歌词窗位置恢复加主屏钳制，设置页桌面歌词组新增「重置歌词窗
   位置」按钮（DesktopLyricController.resetWindowPosition）。验收：全新数据目录、
   无队列点歌词键，04-lyric-window.png 全屏截图里底部居中出现歌词悬浮窗
   （显示「企鹅音乐」占位），日志含 `[Toast] 桌面歌词已打开`。
3. **API 服务器无测试按钮 + 文案误导**：ApiServerSection 接上从未被调用的
   AppUpdateManager.measureApiServers：每行状态点（灰未测/绿可用/红不可用）+
   状态文案 + 单台「测试」，顶部「全部测试」；区改名「API 服务器（解灰 · 高级）」
   并注明主数据直连官方、此列表仅影响解灰音源。内置列表删已死的
   api.jussichords.kdns.fr（2026-10-23 实测 530）；measureApiServers 顺手修：
   HTTP ≥500 判不可用（旧逻辑把 530 也报可用）。老用户迁移：apiServerListCleaned
   已跑过的人追加一层过滤——存量列表剔除死地址（自定义保留）、apiBaseUrl 指向
   死地址时清空。验收：06-settings-api.png（新文案/按钮/死地址消失）、
   07-olddata-api.png（构造的 2.0 存量数据：死地址被剔、自定义 custom.example.com
   保留、当前服务器被清空）、08b-api-test.png（全部测试回填：两台绿点
   可用·1302ms/640ms、假地址红点不可用；08 首跑冷启动全员 3s 超时，属沙箱冷链
   路瞬时现象，复跑即正常，状态渲染三态由此双份存证）。
4. **扫码/验证码登录「环境异常」**：根因是登录族请求指纹裸奔。新增
   ncmapi `DeviceIdentity` 持久身份（落盘 app 数据目录 device-identity.properties，
   跨重启稳定）+ 启动/打开登录页预热（桌面 UA GET music.163.com/discover，
   服务端真 Cookie 入 jar）。登录族（qr/unikey、qr/client/login、captcha/sent、
   captcha/verify、login/cellphone、login）统一：桌面 Chrome UA、Cookie =
   预热 jar + 会话 Cookie（剔除 CookieProvider 的安卓指纹键）+ 持久 deviceId，
   响应 Set-Cookie 回收进 jar；其它数据面请求指纹不动。
   **消融实测（关键）**：第一版曾把自造的 _ntes_nuid/NMTID/WNMCID 一起外发，
   探针立刻被风控判 code=-462 /「请完成验证操作」；对照组（老指纹）与最终形态
   （桌面 UA + 只发 deviceId + 真 jar）均正常回业务结果——自造身份字段能被
   服务端验伪，绝不可外发，jar 里只许放服务端真下发的 Cookie。
   验收（沙箱可做部分）：authprobe-final1/2 两跑 deviceId 完全一致、
   warmup jar=[NMTID]、qrKey 成功、qrCheck=Waiting(801)、captchaSent 回 200、
   坏验证码登录回「验证码错误」（业务错误，非环境异常）；09-login-qr.png 登录页
   真二维码出码、日志有 unikey + client/login 轮询。
   **未实测**：真账号扫码确认（803）与真号验证码/密码登录只能用户真机验；
   风控策略在服务端，第一版消融翻车说明指纹组合对结果影响真实存在，
   本版形态以探针实测为准、不打包票。

- 回归：10-search.png 真实搜索结果；11b 播放链自动跳过地域受限曲目并落到可播
  曲目；引擎探针 PlayerProbe3（+NullMixer）全绿：position ≈990ms/s 推进、
  seek 落点 60444ms、暂停冻结、续播推进、音量回读 0.4（playerprobe3-nullmixer.log）。
  两次启动同一数据目录 deviceId 不变（01-main.log / 08b-apitest.log）。
- 交付：dist/PenguinMusic-Windows.zip（151,117,796 字节 ≈144MB），
  MD5 `1a72c3edaa6ffd89f8b7f92d215c1f0d`；exe FileVersion/ProductVersion 2.0.1；
  旧 2.0 包备份 dist/PenguinMusic-Windows-2.0.zip；下载链接同 2.0
  （https://tsemby.lblb.eu/dl/PenguinMusic-Windows.zip，curl -I 200、MD5 两端一致）。
  升级方式：解压覆盖 PenguinMusic.exe（及整个目录）即可，数据在 ~/.jussichords 不动。
- 仍未实测（同 2.0）：系统托盘、系统媒体键（Xvfb 无托盘/无法注入）。


### 7.11 2.0.2 修复（2026-10-23，用户真机反馈三组 bug + 音源 P0 对齐 + API 全量盘点，证据在 desktop/build/run4/）

#### Bug 1：桌面设置页漏「音源设置」组 —— 已修

- 根因：2.0 设置桌面化时只搬了账号/播放/界面/桌面歌词/快捷键/下载与缓存，
  并单独做了「API 服务器（解灰·高级）」分区，把安卓共享版的整组「音源设置」
  落下了；且旧文案「主数据直连官方、仅影响解灰」对 PROXY 音源是错的
  （PROXY 下 VIP/灰色歌曲的播放取链就走所选服务器）。
- 修：桌面 SettingsScreen 在「播放」组后新增完整「音源设置」组：
  AudioSourceSelector 三选一（LX 音源/直连音源/API 代理服务器）、
  「正在使用」摘要、LX 脚本管理入口（导航既有 LxPlugins 页）、直连音源条目
  （LocalSourceDialog，六源）、API 服务器条目（打开服务器管理对话框，
  内容为原 ApiServerSection，含单台/全部测速）、更换解灰源
  （UnblockSourceDialog）。切换逻辑与共享版一致（写 DataStore →
  AudioSourceSwitcher.apply）。独立「API 服务器（解灰·高级）」分区删除，
  对话框文案改正为「PROXY 时播放取链走所选服务器；测速与取链同口径
  （真实解灰端点 /song/url/match），测通即代表能解灰」。
- 验收（run4 Xvfb）：07-settings-audio.png 音源组完整呈现；
  08-settings-proxy.png 切 PROXY 后卡片高亮、摘要同步；
  09-api-dialog.png 服务器管理对话框（两台内置服务器 + 测速按钮 +
  自定义地址）；10-settings-selected.png 选中 indevs 后摘要回填
  「正在使用：API 代理服务器（https://api.jussichords.indevs.in）」、
  API 服务器行显示当前台；12-direct-dialog.png 切直连后六源单选框
  （自动选择/Byfuns/Ddyr/Gdmusic/Oi/Qijieya/Msls）。探针侧：
  SourceProbe RELOAD 段证明切源后 AudioSourceSettings.SELECTED 与
  API_BASE_URL 同步且当前曲重解析（见 P0 一节）。

#### Bug 2：标题栏拖不动 + 三按钮按不动（Windows 真机）—— 已修

- 根因（代码路径证据）：旧 PenguinTitleBar 把带 detectTapGestures 的
  手势 Row 包在 WindowDraggableArea 内部、按钮做 Row 兄弟项。手势子级
  参与命中判定后，Windows 上整条标题栏的按下命中不稳定：拖动区拿不到
  完整的按下-拖动序列，按钮点击也被间歇吞掉（Xvfb 合成事件简单，所以
  沙箱里点着没事，真机现形——与 2.0.1「按钮被布局挤出」是不同的病）。
  主窗口定义本身无问题（undecorated=true、非 transparent，核对过）。
- 修：分层重构。Box(40dp) 底层为整幅拖动层，上层 Row 只放图标/标题/
  三按钮（最小化/最大化还原/关闭，关闭悬停红底白叉），按钮不被任何
  手势父级包裹。拖动实现最终未用框架 WindowDraggableArea：其实测在
  Xvfb 下完全不触发（run4/04-drag、04b-drag 两次截图窗口纹丝不动；
  字节码核对 foundation-desktop-1.9.0 的 DragHandler 是
  awaitFirstDown + AWT MouseMotionListener 路径，对事件消费顺序敏感），
  改为与桌面歌词窗同款的手动 detectDragGestures + AWT setLocation
  （应用驱动、跨平台行为一致），双击最大化挂同层第二个 pointerInput。
- 验收：01-main.png 三按钮齐全；04c-drag-screen.png 拖动后窗口由
  (48,48) 移到约 (141,122)（Xvfb 机器人快速拖动有 AWT 事件合并，
  位移小于手势全程，人手慢拖逐帧跟随）；最大化点后落盘
  windowMaximized=true（ReadPref 探针实读）；最小化点击命中+悬停态
  可见（Xvfb 无窗口管理器不执行图标化/最大化视觉缩放，真机由 WM
  执行 setState/placement 标准路径，与 run3 同口径，如实标注）；
  05-close-hover.png 关闭悬停变红；06b-close 实点关闭后组合被拆除
  （LeftCompositionCancellationException）且进程 11 秒内干净退出
  （closeToTray 默认关 → exitApplication）。

#### Bug 3：桌面歌词点了不出窗（Windows 真机）—— 已修

- 根因（代码证据，三条按确定性排序）：
  1. 位置校验过弱：旧 visibleLyricPosition 只按主屏 coerceIn，不查窗口
     宽度与多屏可见性。落盘宽度为坏值（如 0/极小）时窗口零宽不可见；
     坐标被钉在主屏边缘时只露一条缝——表象就是「点了根本不出窗」。
  2. applyLyricWindowLock 在组合阶段就对窗口调 JNA 取指针/改样式，
     窗口未 showing 时该调用静默失效（本身有 runCatching 不崩窗，但
     锁定样式不生效且无任何留痕，无法诊断）。
  3. 位置保存循环无守卫：window.locationOnScreen 在窗口未 showing 时
     抛 IllegalComponentStateException，旧代码可把保存 effect 打死。
- 修：①新增 visibleLyricPosition()：落盘位置必须与任一显示器有
  ≥120×60 的可见交集才采用（多屏/负坐标合法），哨兵 (-1,-1) 与坏值
  回落主屏底部居中；宽度先消毒到 [300,2560]（safeLyricWidth）。
  ②锁样式推迟到 window.isShowing 后应用（轮询上限 5 秒），失败
  runCatching 记日志只降级。③保存循环加 runCatching + isShowing
  守卫。④新增 DesktopLog：歌词窗关键事件（setEnabled/位置决策/
  首帧 state/锁应用）写 ~/.jussichords/logs/lyric-window.log 并同步
  stdout [LYRIC]，真机再出问题可直接查日志。
- 验收：18b-lyric-screen.png 全新数据目录点开即出窗（底部居中占位
  「企鹅音乐」），日志链完整：setEnabled → savedPos=(-1,-1) →
  BottomCenter(fallback) → lock apply waited=100ms showing=true →
  window state loc=(340,843) size=760×117。19b-badpos：用探针把
  落盘位置写成 (99999,88888)、宽度 50 后重启，日志
  「saved=(99999,88888) w=50 -> BottomCenter(fallback)」，窗口落
  (570,843)、宽消毒为 300，随后回存好值 (570,843) w=300。
  注：Xvfb 下 transparent 窗口在截图里呈黑底（合成器无 alpha 混合），
  Windows 真机为真透明，与 run2/run3 表现一致。

#### P0：音源接口逐条对齐（本轮最高优先级）

参数级对照 NeteaseCloudMusicApiEnhanced 的 module 源码（快照存
run4/enh/，解析表 run4/enhanced_parsed.json），结论：

| 接口 | 对齐结论 |
|---|---|
| /song/url/v1 | 与 song_url_v1.js 逐字一致：eapi /api/song/enhance/player/url/v1，ids 字符串 '[id]'、level、encodeType（flac 时 'flac' 否则 'aac')。既有实现无误，不动 |
| /song/url（旧版） | 本次新增：weapi /api/song/enhance/player/url，ids 为 JSON 字符串数组，br 默认 999000 |
| /song/download/url、/song/download/url/v1 | 本次新增（weapi player/download 与 eapi enhance/download/url，immerseType c51 仅空间音频时传）；注意官方 download 端点 data 是单对象非数组，模型单列 SongDownloadResponse |
| /check/music | 本次新增：weapi /api/song/enhance/player/check，成功判定 data[0].code==200（与 check_music.js 后处理一致） |
| /song/url/match（解灰） | 客户端实现不变：GET <所选 API 服务器>/song/url/match?id&source&Cookie；本次修正 parseUnblockData 优先采用响应 proxyUrl（与 song_url_match.js 语义一致，原实现只取 url 会丢代理改写链） |
| /song/detail | 本次新增路由（ids 拼 c 数组）；此前只有播放器内部批量路径 |
| /lyric、/lyric/new | /lyric/new 参数与 lyric_new.js 一致（tv/ylv/rv/kv 全量开关）；/lyric 旧版本次新增 |
| level 枚举 | 现有 SongLevel 已含 standard/higher/exhigh/lossless/hires/jyeffect/sky/jymaster/dolby，覆盖文档全量，不动 |
| timestamp 防缓存 | 文档侧 timestamp 用于破 CDN/浏览器缓存；本客户端走 Ktor 且请求已带 Cache-Control: no-cache，等价覆盖，不另加参数 |
| cookie 传递 | 全部路由经统一 ApiClient 请求管线携带登录 Cookie 与设备指纹头，与增强版 query.cookie 注入等价 |

三模式端到端（SourceProbe 第二轮 run4/sourceprobe2.log + DlProbe）：

- 官方直连（免费歌 1315196858）：/song/url、/song/download/url、
  /song/download/url/v1 均回非空 music.126.net 直链；播放位置推进
  ≈990ms/s。
- DIRECT（fee==1 候选曲 509781655）：日志实证
  "Local source 'byfuns' hit" → 直连源命中出链。首轮同曲曾 miss，
  系当时该曲官方兜底也回 null 的单曲资源波动，已用 25 个 fee==1
  候选轮询法区分「源不通」与「单曲无资源」。
- PROXY（API_BASE_URL=https://api.jussichords.indevs.in）：
  /song/url/match 回 code 200 真 FLAC 链，PlayerApi 解灰成功。
  另一内置台 api.jussicodes.kdns.fr 经新测速亦可用（4043ms）。
  注：PROXY 出 FLAC 链时桌面引擎（MP3-only 管线）仍会报格式不支持，
  是既有引擎限制、非取链不通，见待续。
- LX：无脚本时 stub 回退官方直连（预期行为）；LX 引擎本体仍未接
  （维持既有缺口，报告明说）。
- 地域 null 的区分：VIP 候选曲《想你就写信 (Live)》509781655 在
  沙箱美国 IP 下 check/music=false 且三条官方链均 success 但
  url=null——三口径互证是官方地域/版权回 null，不是接口没通。

自查三项：
1. 切源缓存失效（P0 实锤并已修）：桌面端此前无人 collect
   PlaybackReloadBus，且 DesktopPlayer.resolvedUrls 只按 mediaId
   缓存——切音源在桌面端实际不重解析。已修：缓存键改为
   mediaId|音质|sourceTag（与安卓 cacheKey 同口径），DesktopPlayer
   init collect 总线 → 清缓存 + 以当前进度重解析当前曲。SourceProbe
   RELOAD 段实证：apply(PROXY) 后日志「音源切换重载：清空直链缓存
   并重解析」，位置从 3030ms 继续推进无卡死。
2. 回退顺序：与原版一致——免费歌官方直连；fee==1 才走所选音源链
   （LX/DIRECT/PROXY 三选一互斥，既定设计）；音源链失败回落官方
   兜底；再失败由播放器跳歌提示。不改串联（互斥是原版语义）。
3. 测速口径：已修。旧测速是 HEAD /（测的是站点存活，与取链无关）。
   新测速 AppUpdateManager.measureApiServers 改走真实取链同路径
   GET <server>/song/url/match?id=347230，返回增强版协议 JSON
   （code 200 或 500 均记可用）、404 时回退旧版 /match 路径再判。
   MeasureProbe202 实测：kdns 可用 4043ms、indevs 可用 1859ms、
   pickFastest 正确选 indevs。

#### 功能补齐：右键菜单 + EQ

- 歌曲右键菜单：SongTable 单点定义 menuActions（播放/下一首播放/
  加入播放队列/喜欢切换/下载/查看专辑/复制歌曲信息/[上下文移除]），
  右键 ContextMenuArea 与行尾「更多」按钮（悬停显示）共用。
  17-context-menu.png 实证（搜索上下文无移除项）。歌单详情仅当
  creator 为当前用户时显示「从歌单移除」（/playlist/tracks op=del，
  成功后刷新）；队列行右键含「从队列移除」。下载走新
  DownloadHelper：先 /song/download/url/v1 取链，失败回退播放链，
  落 ~/Music/PenguinMusic/，全程 Toast 反馈。
- EQ 均衡器（已落地）：DesktopPlayer 自解码 PCM 管线有插入点
  （decodeLoop 在 shorts→bytes 前）。新 Eq.kt：10 段 RBJ biquad
  （31/62/125/250/500/1k/2k/4k/8k/16k，首尾低/高架、中间 peaking
  Q=1.0，双声道独立状态）+ 9 预设（关闭/流行/摇滚/古典/爵士/人声/
  低音增强/高音增强/电子），未启用零开销直通；增益落 DataStore。
  入口在设置「播放」组。SourceProbe EQ 段实证：切换预设日志打出
  [EQ] configure 系数（sr=44100 ch=2），播放位置继续推进、音量
  回读 1.0 不爆音。11-eq-dialog.png 为对话框实拍（开关/9 预设/
  10 段滑杆/重置）。

#### 功能层新接页面

- 排行榜页（/toplist/detail）：13-toplist.png 真实榜单网格；
  侧栏「排行榜」原误指向搜索页，一并纠正。
- 歌单广场（/playlist/catlist 分类 + /top/playlist 热门/最新）：
  14-square.png 分类 chips 全量 + 真实歌单卡片。
- 音乐云盘（/user/cloud 既有 AccountApi 列表）：15-cloud.png
  未登录态为登录引导；登录后可播放、右键删除（/user/cloud/del）。
  云盘上传未接（NOS 分片四步 check→token→upload→info），页面
  文案已如实标注待续，不假装。
- 设置账号组加「每日签到」行（/daily_signin 双类型各签一次）。
- 歌单详情加「评论」入口（PlayerComments，commentType=2）。

#### API 全量盘点（对 NeteaseCloudMusicApiEnhanced 449 个 module）

统计：module 总数 449；盘点前已有 107；本次新增数据层 85；
客户端实现 1（song_url_match）；不适用 14（激活/VIP 商城/注册类）；
缺失待续 242（长尾：yunbei/vip 任务、音乐人/创作者投稿、voice
电台投稿、fanscenter、listen_data 报告、broadcast、数字专辑交易、
账号绑定/设备类、云盘上传族等非播放核心）。ncmapi 现有路由共
172 条。逐条差距表如下（P0 组已在表中标注）。

统计：module 总数 449；已有 107；本次新增数据层 85；客户端实现 1；不适用 14；缺失待续 242。

| 接口(module) | 官方端点 | ncmapi 路由 | 数据层状态 | UI 接入 | 备注 |
|---|---|---|---|---|---|
| activate_init_profile | /api/activate/initProfile | — | 不适用 |  | 服务端初始化资料，无客户端场景 |
| ad_get | /api/ad/get | — | 缺失待续 |  |  |
| ad_listening_rights | /api/ad/homepage/free/tab/extend/v2 | — | 缺失待续 |  |  |
| ad_listening_rights_gain | /api/ad/listening/rights/gain | — | 缺失待续 |  |  |
| aidj_content_rcmd | /api/aidj/content/rcmd/info | — | 缺失待续 |  |  |
| album | (动态/复合) | /album | 已有 | 专辑页 |  |
| album_detail | /api/vipmall/albumproduct/detail | /album | 已有 |  | 等价实现：/album |
| album_detail_dynamic | /api/album/detail/dynamic | /album/detail/dynamic | 已有 | 专辑页 |  |
| album_list | /api/vipmall/albumproduct/list | — | 不适用 |  | VIP 商城专辑商品列表，非音乐播放功能 |
| album_list_style | /api/vipmall/appalbum/album/style | — | 不适用 |  | VIP 商城专辑商品列表，非音乐播放功能 |
| album_new | /api/album/new | /album/new | 已有 | 发现页新碟 |  |
| album_newest | /api/discovery/newAlbum | /album/newest | 本次新增数据层 | 数据层 |  |
| album_privilege | /api/album/privilege | /album/privilege | 本次新增数据层 |  |  |
| album_songsaleboard | (动态/复合) | — | 缺失待续 |  |  |
| album_sub | (动态/复合) | /album/sub | 已有 | 专辑收藏 |  |
| album_sublist | /api/album/sublist | /album/sublist | 已有 | 收藏专辑页 |  |
| api | (动态/复合) | /api（通用透传） | 已有 |  | 等价实现：/api（通用透传） |
| artist_album | (动态/复合) | /artist/album | 已有 | 歌手页 |  |
| artist_desc | /api/artist/introduction | /artist/desc | 本次新增数据层 | 数据层（歌手页简介待接） |  |
| artist_detail | /api/artist/head/info/get | /artist/detail | 已有 | 歌手页 |  |
| artist_detail_dynamic | /api/artist/detail/dynamic | — | 缺失待续 |  |  |
| artist_fans | /api/artist/fans/get | /artist/fans | 本次新增数据层 |  |  |
| artist_follow_count | /api/artist/follow/count/get | /artist/follow/count | 已有 |  |  |
| artist_list | /api/v1/artist/list | /artist/list | 本次新增数据层 | 数据层 |  |
| artist_mv | /api/artist/mvs | /artist/mv | 本次新增数据层 | 数据层（视频） |  |
| artist_new_mv | /api/sub/artist/new/works/mv/list | /artist/new/mv | 本次新增数据层 |  |  |
| artist_new_song | /api/sub/artist/new/works/song/list | /artist/new/song | 本次新增数据层 |  |  |
| artist_new_song_mv_list_v2 | /api/sub/artist/new/works/song-mv/list/v2 | — | 缺失待续 |  |  |
| artist_new_song_playall | /api/sub/artist/new/works/song/playall | — | 缺失待续 |  |  |
| artist_songs | /api/v1/artist/songs | /artist/songs | 已有 | 歌手页 |  |
| artist_sub | (动态/复合) | /artist/sub | 已有 |  |  |
| artist_sublist | /api/artist/sublist | /artist/sublist | 已有 |  |  |
| artist_top_song | /api/artist/top/song | /artist/top/song | 已有 | 歌手页 |  |
| artist_video | /api/mlog/artist/video | /artist/video | 本次新增数据层 |  |  |
| artists | (动态/复合) | /artists | 本次新增数据层 |  |  |
| audio_match | (动态/复合) | — | 不适用 |  | 本地音频指纹匹配（需音频指纹 SDK），客户端无对应场景 |
| avatar_upload | /api/user/avatar/upload/v1 | /user/avatar/upload | 已有 |  |  |
| banner | /api/v2/banner/get | /banner | 本次新增数据层 |  |  |
| batch | /api/batch | — | 不适用 |  | 服务端批量请求工具，非客户端接口 |
| broadcast_category_region_get | /api/voice/broadcast/category/region/get | — | 缺失待续 |  |  |
| broadcast_channel_collect_list | /api/content/channel/collect/list | — | 缺失待续 |  |  |
| broadcast_channel_currentinfo | /api/voice/broadcast/channel/currentinfo | — | 缺失待续 |  |  |
| broadcast_channel_list | /api/voice/broadcast/channel/list | — | 缺失待续 |  |  |
| broadcast_sub | /api/content/interact/collect | — | 缺失待续 |  |  |
| calendar | /api/mcalendar/detail | — | 缺失待续 |  |  |
| captcha_safe_sent | /api/sms/captcha/safe/sent | — | 缺失待续 |  |  |
| captcha_sent | /api/sms/captcha/sent | /captcha/sent | 已有 | 登录页 |  |
| captcha_sent_v1 | /api/middle/captcha/sent/v1 | /captcha/sent | 已有 |  | 等价实现：/captcha/sent |
| captcha_verify | /api/sms/captcha/verify | /captcha/verify | 已有 | 登录页 |  |
| cellphone_existence_check | /api/cellphone/existence/check | /cellphone/existence/check | 本次新增数据层 |  |  |
| chart_detail | /api/chart/detail | — | 缺失待续 |  |  |
| chart_song_detail | /api/chart/song/detail | — | 缺失待续 |  |  |
| check_music **P0** | /api/song/enhance/player/url | /check/music | 本次新增数据层 | 数据层（PlayerApi.checkMusic） |  |
| cloud | /api/cloud/upload/check | — | 不适用 |  | 云盘上传主流程（NOS 分片四步），本版未接，见待续 |
| cloud_import | /api/cloud/upload/check/v2 | — | 缺失待续 |  |  |
| cloud_lyric_get | /api/cloud/lyric/get | — | 缺失待续 |  |  |
| cloud_match | /api/cloud/user/song/match | — | 缺失待续 |  |  |
| cloud_upload_complete | /api/upload/cloud/info/v2 | — | 缺失待续 |  |  |
| cloud_upload_token | /api/cloud/upload/check | — | 缺失待续 |  |  |
| cloudsearch | /api/cloudsearch/pc | /cloudsearch | 已有 | 搜索页 |  |
| comment | (动态/复合) | /comment/new（type=0） | 已有 | 评论区（歌曲） | 等价实现：/comment/new（type=0） |
| comment_add | /api/resource/comments/add | /comment/add | 已有 |  |  |
| comment_album | (动态/复合) | /comment/new（type=3） | 已有 |  | 等价实现：/comment/new（type=3） |
| comment_delete | /api/resource/comments/delete | /comment/delete | 已有 |  |  |
| comment_dj | (动态/复合) | /comment/new（type=4） | 已有 |  | 等价实现：/comment/new（type=4） |
| comment_event | (动态/复合) | /comment/new（type=6） | 已有 |  | 等价实现：/comment/new（type=6） |
| comment_floor | /api/resource/comment/floor/get | /comment/floor | 已有 |  |  |
| comment_hot | (动态/复合) | /comment/new（sortType 热度） | 已有 |  | 等价实现：/comment/new（sortType 热度） |
| comment_hug_list | /api/v2/resource/comments/hug/list | /comment/hug/list | 本次新增数据层 |  |  |
| comment_info_list | /api/resource/commentInfo/list | — | 缺失待续 |  |  |
| comment_like | (动态/复合) | /comment/like | 已有 | 评论区 |  |
| comment_music | (动态/复合) | /comment/new（type=0） | 已有 | 评论区（等价 comment_new type 0） | 等价实现：/comment/new（type=0） |
| comment_mv | (动态/复合) | /comment/new（type=1） | 已有 |  | 等价实现：/comment/new（type=1） |
| comment_new | /api/v2/resource/comments | /comment/new | 已有 | 评论区 |  |
| comment_playlist | (动态/复合) | /comment/new（type=2） | 已有 |  | 等价实现：/comment/new（type=2） |
| comment_reply | /api/v1/resource/comments/reply | /comment/reply | 已有 |  |  |
| comment_report | /api/report/reportcomment | — | 缺失待续 |  |  |
| comment_video | (动态/复合) | /comment/new（type=5） | 已有 |  | 等价实现：/comment/new（type=5） |
| countries_code_list | /api/lbs/countries/v1 | — | 不适用 |  | 注册辅助数据，登录页未用 |
| creator_authinfo_get | /api/user/creator/authinfo/get | — | 缺失待续 |  |  |
| daily_signin | /api/point/dailyTask | /daily_signin | 本次新增数据层 | 设置页签到（2.0.2 新） |  |
| decrypt | (动态/复合) | — | 不适用 |  | 服务端加解密调试工具 |
| device_kickoff | /api/middle/user/security/device/kickoff | — | 缺失待续 |  |  |
| device_list | /api/middle/user/device/list | — | 缺失待续 |  |  |
| deviceinfo_center_upload | /api/deviceinfo/center/upload | — | 缺失待续 |  |  |
| digitalAlbum_detail | /api/vipmall/albumproduct/detail | — | 缺失待续 |  |  |
| digitalAlbum_ordering | /api/ordering/web/digital | — | 缺失待续 |  |  |
| digitalAlbum_purchased | /api/digitalAlbum/purchased | — | 缺失待续 |  |  |
| digitalAlbum_sales | /api/vipmall/albumproduct/album/query/sales | — | 缺失待续 |  |  |
| djRadio_top | /api/expert/worksdata/works/top/get | /dj/hot | 已有 |  | 等价实现：/dj/hot |
| dj_banner | /api/djradio/banner/get | /dj/banner | 本次新增数据层 |  |  |
| dj_category_excludehot | /api/djradio/category/excludehot | — | 缺失待续 |  |  |
| dj_category_recommend | /api/djradio/home/category/recommend | /dj/category/recommend | 本次新增数据层 |  |  |
| dj_catelist | /api/djradio/category/get | /dj/catelist | 本次新增数据层 | 数据层 |  |
| dj_detail | /api/djradio/v2/get | /dj/detail | 已有 | 电台页 |  |
| dj_difm_all_style_channel | /api/dj/difm/all/style/channel/v2 | — | 缺失待续 |  |  |
| dj_difm_channel_subscribe | /api/dj/difm/channel/subscribe | — | 缺失待续 |  |  |
| dj_difm_channel_unsubscribe | /api/dj/difm/channel/unsubscribe | — | 缺失待续 |  |  |
| dj_difm_playing_tracks_list | /api/dj/difm/playing/tracks/list | — | 缺失待续 |  |  |
| dj_difm_subscribe_channels_get | /api/dj/difm/subscribe/channels/get/v2 | — | 缺失待续 |  |  |
| dj_hot | /api/djradio/hot/v1 | /dj/hot | 已有 | 电台页 |  |
| dj_paygift | /api/djradio/home/paygift/list | — | 缺失待续 |  |  |
| dj_personalize_recommend | /api/djradio/personalize/rcmd | /dj/personalize/recommend | 本次新增数据层 |  |  |
| dj_program | /api/dj/program/byradio | /dj/program | 已有 | 电台页 |  |
| dj_program_detail | /api/dj/program/detail | /dj/program/detail | 本次新增数据层 |  |  |
| dj_program_toplist | /api/program/toplist/v1 | /dj/program/toplist | 本次新增数据层 |  |  |
| dj_program_toplist_hours | /api/djprogram/toplist/hours | /dj/program/toplist/hours | 本次新增数据层 |  |  |
| dj_radio_hot | /api/djradio/hot | — | 缺失待续 |  |  |
| dj_recommend | /api/djradio/recommend/v1 | /dj/recommend | 已有 | 电台页 |  |
| dj_recommend_type | /api/djradio/recommend | /dj/recommend/type | 本次新增数据层 |  |  |
| dj_sub | (动态/复合) | /dj/sub | 本次新增数据层 |  |  |
| dj_sublist | /api/djradio/get/subed | /dj/sublist | 本次新增数据层 |  |  |
| dj_subscriber | /api/djradio/subscriber | /dj/subscriber | 本次新增数据层 |  |  |
| dj_today_perfered | /api/djradio/home/today/perfered | /dj/today/perfered | 本次新增数据层 |  |  |
| dj_toplist | /api/djradio/toplist | /dj/toplist | 本次新增数据层 |  |  |
| dj_toplist_hours | /api/dj/toplist/hours | /dj/toplist/hours | 本次新增数据层 |  |  |
| dj_toplist_newcomer | /api/dj/toplist/newcomer | /dj/toplist/newcomer | 本次新增数据层 |  |  |
| dj_toplist_pay | /api/djradio/toplist/pay | — | 缺失待续 |  |  |
| dj_toplist_popular | /api/dj/toplist/popular | /dj/toplist/popular | 本次新增数据层 |  |  |
| eapi_decrypt | (动态/复合) | — | 不适用 |  | 服务端加解密调试工具 |
| event | /api/v1/event/get | /event | 本次新增数据层 | 数据层 |  |
| event_del | /api/event/delete | /event/del | 本次新增数据层 |  |  |
| event_forward | /api/event/forward | /event/forward | 本次新增数据层 |  |  |
| event_privacy | /api/event/privacy/op | — | 缺失待续 |  |  |
| fans_group_detail | /api/social/fansgroup/bff/detail/get | — | 缺失待续 |  |  |
| fans_group_feed_recommend | /api/fans/group/feed/recommend/get | — | 缺失待续 |  |  |
| fans_group_user_group_detail | /api/social/fansgroup/bff/user/group/detail/get | — | 缺失待续 |  |  |
| fans_group_user_groups | /api/social/fansgroup/bff/user/groups/get | — | 缺失待续 |  |  |
| fanscenter_basicinfo_age_get | /api/fanscenter/basicinfo/age/get | — | 缺失待续 |  |  |
| fanscenter_basicinfo_gender_get | /api/fanscenter/basicinfo/gender/get | — | 缺失待续 |  |  |
| fanscenter_basicinfo_province_get | /api/fanscenter/basicinfo/province/get | — | 缺失待续 |  |  |
| fanscenter_overview_get | /api/fanscenter/overview/get | — | 缺失待续 |  |  |
| fanscenter_trend_list | /api/fanscenter/trend/list | — | 缺失待续 |  |  |
| fm_trash | /api/radio/trash/add | /fm/trash | 本次新增数据层 | 数据层 |  |
| follow | (动态/复合) | /follow | 已有 | 关注按钮 |  |
| get_userids | /api/user/getUserIds | — | 缺失待续 |  |  |
| history_recommend_songs | /api/discovery/recommend/songs/history/recent | /history/recommend/songs | 本次新增数据层 | 数据层 |  |
| history_recommend_songs_detail | /api/discovery/recommend/songs/history/detail | /history/recommend/songs/detail | 本次新增数据层 |  |  |
| homepage_block_page | /api/homepage/block/page | /homepage/block/page | 本次新增数据层 |  |  |
| homepage_dragon_ball | /api/homepage/dragon/ball/static | /homepage/dragon/ball | 本次新增数据层 |  |  |
| hot_topic | /api/act/hot | — | 缺失待续 |  |  |
| hug_comment | /api/v2/resource/comments/hug/listener | — | 缺失待续 |  |  |
| inner_version | (动态/复合) | — | 不适用 |  | 服务端版本信息 |
| lbs_city_code | /api/lbs/city/code | — | 缺失待续 |  |  |
| like | /api/radio/like | /radio/like | 本次新增数据层 |  |  |
| like_v1 | /api/v1/radio/like | — | 缺失待续 |  |  |
| likelist | /api/song/like/get | /likelist | 已有 | 喜欢状态 |  |
| listen_data_realtime_report | /api/content/activity/listen/data/realtime/report | — | 缺失待续 |  |  |
| listen_data_report | /api/content/activity/listen/data/report | — | 缺失待续 |  |  |
| listen_data_song_play_rank | /api/content/activity/listen/data/song/play/rank | — | 缺失待续 |  |  |
| listen_data_today_song | /api/content/activity/listen/data/today/song/play/rank | — | 缺失待续 |  |  |
| listen_data_total | /api/content/activity/listen/data/total | — | 缺失待续 |  |  |
| listen_data_year_report | /api/content/activity/listen/data/year/report | — | 缺失待续 |  |  |
| listentogether_accept | /api/listen/together/play/invitation/accept | /listen/together/invitation/accept | 已有 |  |  |
| listentogether_end | /api/listen/together/end/v2 | /listen/together/end | 已有 |  |  |
| listentogether_heatbeat | /api/listen/together/heartbeat | /listen/together/heartbeat | 已有 |  |  |
| listentogether_play_command | /api/listen/together/play/command/report | /listen/together/play/command | 已有 |  |  |
| listentogether_room_check | /api/listen/together/room/check | /listen/together/room/check | 已有 |  |  |
| listentogether_room_create | /api/listen/together/room/create | /listen/together/room/create | 已有 |  |  |
| listentogether_status | /api/listen/together/status/get | /listen/together/status | 已有 |  |  |
| listentogether_sync_list_command | /api/listen/together/sync/list/command/report | /listen/together/sync/list | 已有 |  |  |
| listentogether_sync_playlist_get | /api/listen/together/sync/playlist/get | /listen/together/sync/playlist | 已有 |  |  |
| login | /api/w/login | /login | 已有 | 登录页邮箱 |  |
| login_cellphone | /api/w/login/cellphone | /login/cellphone | 已有 | 登录页 |  |
| login_qr_check | /api/login/qrcode/client/login | /login/qr/check | 已有 | 登录页扫码 |  |
| login_qr_create | (动态/复合) | — | 缺失待续 |  |  |
| login_qr_key | /api/login/qrcode/unikey | /login/qr/key | 已有 | 登录页扫码 |  |
| login_refresh | /api/login/token/refresh | — | 缺失待续 |  |  |
| login_status | /api/w/nuser/account/get | /user/account | 已有 |  |  |
| logout | /api/logout | — | 缺失待续 |  |  |
| lyric **P0** | /api/song/lyric | /lyric | 本次新增数据层 | 数据层 |  |
| lyric_new **P0** | /api/song/lyric/v1 | /lyric/new | 已有 | 歌词页/桌面歌词 |  |
| middle_play_do_lottery | /api/middle/play/do/lottery | — | 缺失待续 |  |  |
| middle_play_lottery_remain_chance | /api/middle/play/lottery/remain/chance | — | 缺失待续 |  |  |
| mlog_music_rcmd | /api/mlog/rcmd/feed/list | — | 缺失待续 |  |  |
| mlog_to_video | /api/mlog/video/convert/id | — | 缺失待续 |  |  |
| mlog_url | /api/mlog/detail/v1 | — | 缺失待续 |  |  |
| msg_comments | (动态/复合) | /msg/comments | 已有 | 消息页 |  |
| msg_forwards | /api/forwards/get | /msg/forwards | 已有 | 消息页 |  |
| msg_notices | /api/msg/notices | /msg/notices | 已有 | 消息页 |  |
| msg_private | /api/msg/private/users | /msg/private | 已有 | 消息页 |  |
| msg_private_history | /api/msg/private/history | /msg/private/history | 已有 |  |  |
| msg_recentcontact | /api/msg/recentcontact/get | /msg/recentcontact | 已有 | 消息页 |  |
| music_first_listen_info | /api/content/activity/music/first/listen/info | — | 缺失待续 |  |  |
| musician_cloudbean | /api/cloudbean/get | — | 缺失待续 |  |  |
| musician_cloudbean_obtain | /api/nmusician/workbench/mission/reward/obtain/new | — | 缺失待续 |  |  |
| musician_data_overview | /api/creator/musician/statistic/data/overview/get | — | 缺失待续 |  |  |
| musician_play_trend | /api/creator/musician/play/count/statistic/data/trend/get | — | 缺失待续 |  |  |
| musician_sign | /api/creator/user/access | — | 缺失待续 |  |  |
| musician_tasks | /api/nmusician/workbench/mission/cycle/list | — | 缺失待续 |  |  |
| musician_tasks_new | (动态/复合) | — | 缺失待续 |  |  |
| musician_vip_tasks | /api/nmusician/workbench/special/right/vip/info | — | 缺失待续 |  |  |
| mv_all | /api/mv/all | — | 缺失待续 |  |  |
| mv_detail | /api/v1/mv/detail | /mv/detail | 本次新增数据层 | 数据层（引擎无视频管线） |  |
| mv_detail_info | /api/comment/commentthread/info | — | 缺失待续 |  |  |
| mv_exclusive_rcmd | /api/mv/exclusive/rcmd | — | 缺失待续 |  |  |
| mv_first | /api/mv/first | /mv/first | 本次新增数据层 |  |  |
| mv_sub | (动态/复合) | /mv/sub | 本次新增数据层 |  |  |
| mv_sublist | /api/cloudvideo/allvideo/sublist | /mv/sublist | 本次新增数据层 |  |  |
| mv_url | /api/song/enhance/play/mv/url | /mv/url | 本次新增数据层 | 数据层（引擎无视频管线） |  |
| nickname_check | /api/nickname/duplicated | — | 缺失待续 |  |  |
| personal_fm | /api/v1/radio/get | — | 缺失待续 | 私人FM(Roam) |  |
| personal_fm_mode | /api/v1/radio/get | /personal/fm/mode | 已有 |  |  |
| personalized | /api/personalized/playlist | /personalized | 已有 | 发现页推荐歌单 |  |
| personalized_djprogram | /api/personalized/djprogram | /personalized/djprogram | 本次新增数据层 |  |  |
| personalized_mv | /api/personalized/mv | /personalized/mv | 本次新增数据层 |  |  |
| personalized_newsong | /api/personalized/newsong | /personalized/newsong | 本次新增数据层 | 数据层 |  |
| personalized_privatecontent | /api/personalized/privatecontent | /personalized/privatecontent | 本次新增数据层 |  |  |
| personalized_privatecontent_list | /api/v2/privatecontent/list | — | 缺失待续 |  |  |
| pl_count | /api/pl/count | — | 缺失待续 |  |  |
| playlist_category_list | /api/playlist/category/list | /playlist/catlist | 已有 |  | 等价实现：/playlist/catlist |
| playlist_catlist | /api/playlist/catalogue | /playlist/catlist | 本次新增数据层 | 歌单广场分类（2.0.2 新） |  |
| playlist_cover_update | /api/playlist/cover/update | /playlist/cover/update | 已有 |  |  |
| playlist_create | /api/playlist/create | /playlist/create | 已有 | 歌单管理 |  |
| playlist_delete | /api/playlist/remove | /playlist/delete | 已有 | 歌单管理 |  |
| playlist_desc_update | /api/playlist/desc/update | /playlist/desc/update | 已有 |  |  |
| playlist_detail | /api/v6/playlist/detail | /playlist/detail | 已有 | 歌单详情页 |  |
| playlist_detail_dynamic | /api/playlist/detail/dynamic | /playlist/detail/dynamic | 已有 |  |  |
| playlist_detail_rcmd_get | /api/playlist/detail/rcmd/get | — | 缺失待续 |  |  |
| playlist_highquality_tags | /api/playlist/highquality/tags | /playlist/highquality/tags | 本次新增数据层 |  |  |
| playlist_hot | /api/playlist/hottags | /playlist/catlist | 已有 |  | 等价实现：/playlist/catlist |
| playlist_import_name_task_create | /api/playlist/import/name/task/create | — | 缺失待续 |  |  |
| playlist_import_task_status | /api/playlist/import/task/status/v2 | — | 缺失待续 |  |  |
| playlist_mylike | /api/mlog/playlist/mylike/bytime/get | /user/playlist | 已有 |  | 等价实现：/user/playlist |
| playlist_name_update | /api/playlist/update/name | /playlist/name/update | 已有 |  |  |
| playlist_order_update | /api/playlist/order/update | /playlist/order/update | 已有 |  |  |
| playlist_privacy | /api/playlist/update/privacy | /playlist/privacy | 本次新增数据层 |  |  |
| playlist_subscribe | (动态/复合) | /playlist/subscribe | 已有 | 歌单详情收藏 |  |
| playlist_subscribers | /api/playlist/subscribers | /playlist/subscribers | 本次新增数据层 |  |  |
| playlist_tags_update | /api/playlist/tags/update | /playlist/tags/update | 本次新增数据层 |  |  |
| playlist_track_add | /api/playlist/track/add | /playlist/tracks（op=add） | 已有 |  | 等价实现：/playlist/tracks（op=add） |
| playlist_track_all | /api/v6/playlist/detail | /playlist/detail | 已有 |  | 等价实现：/playlist/detail |
| playlist_track_delete | /api/playlist/track/delete | /playlist/tracks（op=del） | 已有 |  | 等价实现：/playlist/tracks（op=del） |
| playlist_tracks | /api/playlist/manipulate/tracks | /playlist/tracks | 已有 | 歌单详情（加歌/2.0.2 移除） |  |
| playlist_update | /api/playlist/desc/update | /playlist/name/update + /playlist/desc/update | 已有 |  | 等价实现：/playlist/name/update + /playlist/desc/update |
| playlist_update_playcount | /api/playlist/update/playcount | — | 不适用 |  | 播放量统计上报，引擎有独立 scrobble 链路 |
| playlist_video_recent | /api/playlist/video/recent | — | 缺失待续 |  |  |
| playmode_intelligence_list | /api/playmode/intelligence/list | /playmode/intelligence/list | 本次新增数据层 | 数据层 |  |
| playmode_song_vector | /api/playmode/song/vector/get | — | 缺失待续 |  |  |
| program_recommend | /api/program/recommend/v1 | — | 缺失待续 |  |  |
| radio_sport_get | /api/radio/sport/get | — | 缺失待续 |  |  |
| rebind | /api/user/replaceCellphone | — | 缺失待续 |  |  |
| recent_listen_list | /api/pc/recent/listen/list | — | 缺失待续 |  |  |
| recommend_category_configs | /api/homepage/daily/song/config/get | — | 缺失待续 |  |  |
| recommend_category_set | /api/homepage/daily/song/tag/save | — | 缺失待续 |  |  |
| recommend_category_songs | /api/homepage/category/daily/song/list | — | 缺失待续 |  |  |
| recommend_resource | /api/v1/discovery/recommend/resource | /recommend/resource | 本次新增数据层 | 数据层 |  |
| recommend_songs | /api/v3/discovery/recommend/songs | /recommend/songs | 已有 | 发现页每日推荐 |  |
| recommend_songs_dislike | /api/v2/discovery/recommend/dislike | — | 缺失待续 |  |  |
| recommend_songs_v1 | /api/v3/discovery/recommend/songs | — | 缺失待续 |  |  |
| record_recent_album | /api/play-record/album/list | /record/recent/album | 已有 | 最近播放 |  |
| record_recent_dj | /api/play-record/djradio/list | — | 缺失待续 |  |  |
| record_recent_playlist | /api/play-record/playlist/list | /record/recent/playlist | 已有 | 最近播放 |  |
| record_recent_song | /api/play-record/song/list | /record/recent/song | 已有 | 最近播放 |  |
| record_recent_video | /api/play-record/newvideo/list | — | 缺失待续 |  |  |
| record_recent_voice | /api/play-record/voice/list | — | 缺失待续 |  |  |
| register_anonimous | /api/register/anonimous | — | 缺失待续 |  |  |
| register_cellphone | /api/w/register/cellphone | — | 缺失待续 |  |  |
| register_checktoken_v2 | (动态/复合) | — | 缺失待续 |  |  |
| register_checktoken_v3 | (动态/复合) | — | 缺失待续 |  |  |
| register_neapikey | /api/gorilla/algorithm/record | — | 缺失待续 |  |  |
| register_xeapikey | /api/bsr/sk/get | — | 缺失待续 |  |  |
| related_allvideo | /api/cloudvideo/v1/allvideo/rcmd | — | 缺失待续 |  |  |
| related_playlist | (动态/复合) | — | 缺失待续 |  |  |
| relay_play_state_submit | /api/relay/play/state/submit | — | 缺失待续 |  |  |
| rep_ugc_activity_collect | /api/rep/ugc/activity/collect | — | 缺失待续 |  |  |
| rep_ugc_activity_get | /api/rep/ugc/activity/get | — | 缺失待续 |  |  |
| rep_ugc_exam_info_get | /api/rep/ugc/exam/info/get | — | 缺失待续 |  |  |
| rep_ugc_exam_question_single_get | /api/rep/ugc/exam/question/single/get | — | 缺失待续 |  |  |
| rep_ugc_exam_result_get | /api/rep/ugc/exam/result/get | — | 缺失待续 |  |  |
| rep_ugc_exam_start | /api/rep/ugc/exam/start | — | 缺失待续 |  |  |
| rep_ugc_exam_submit | /api/rep/ugc/exam/submit | — | 缺失待续 |  |  |
| rep_ugc_user_collect-vip | /api/rep/ugc/user/collect-vip | — | 缺失待续 |  |  |
| rep_ugc_user_get | /api/rep/ugc/user/get | — | 缺失待续 |  |  |
| rep_ugc_user_sign | /api/rep/ugc/user/sign | — | 缺失待续 |  |  |
| rep_ugc_user_vip | /api/rep/ugc/user/vip | — | 缺失待续 |  |  |
| resource_like | (动态/复合) | — | 缺失待续 |  |  |
| sati_resource_list | /api/voice/sati/resource/list | — | 缺失待续 |  |  |
| sati_resource_list_more | /api/voice/sati/resource/list/more/v1 | — | 缺失待续 |  |  |
| sati_resource_sub | /api/voice/sati/resource/sub | — | 缺失待续 |  |  |
| sati_resource_sub_list | /api/voice/sati/resource/sub/list | — | 缺失待续 |  |  |
| sati_tag_list | /api/voice/sati/tag/list | — | 缺失待续 |  |  |
| sati_timescene_resources_get | /api/voice/sati/timescene/resources/get | — | 缺失待续 |  |  |
| scrobble | /api/feedback/weblog | — | 缺失待续 |  |  |
| scrobble_v1 | (动态/复合) | — | 缺失待续 |  |  |
| search | /api/search/voice/get | /search | 本次新增数据层 | 数据层（带 type） |  |
| search_default | /api/search/defaultkeyword/get | /search/default | 本次新增数据层 |  |  |
| search_hot | /api/search/hot | — | 缺失待续 | 搜索页 |  |
| search_hot_detail | /api/hotsearchlist/get | /search/hot/detail | 本次新增数据层 |  |  |
| search_match | /api/search/match/new | — | 缺失待续 |  |  |
| search_multimatch | /api/search/suggest/multimatch | /search/multimatch | 本次新增数据层 |  |  |
| search_suggest | /api/search/suggest/ | /search/suggest | 已有 | 搜索页联想 |  |
| search_suggest_pc | /api/search/pc/suggest/keyword/get | — | 缺失待续 |  |  |
| send_album | /api/msg/private/send | /send/album | 已有 |  |  |
| send_playlist | /api/msg/private/send | /send/playlist | 已有 |  |  |
| send_song | /api/msg/private/send | /send/song | 已有 |  |  |
| send_text | /api/msg/private/send | /send/text | 已有 |  |  |
| setting | /api/user/setting | — | 缺失待续 |  |  |
| share_resource | /api/share/friends/resource | — | 缺失待续 |  |  |
| sheet_list | /api/music/sheet/list/v1 | — | 缺失待续 |  |  |
| sheet_preview | /api/music/sheet/preview/info | — | 缺失待续 |  |  |
| sign_happy_info | /api/sign/happy/info | — | 缺失待续 |  |  |
| signin_progress | /api/act/modules/signin/v2/progress | /signin/progress | 本次新增数据层 | 数据层 |  |
| simi_artist | /api/discovery/simiArtist | /simi/artist | 已有 |  |  |
| simi_mv | /api/discovery/simiMV | /simi/mv | 本次新增数据层 |  |  |
| simi_playlist | /api/discovery/simiPlaylist | /simi/playlist | 本次新增数据层 |  |  |
| simi_song | /api/v1/discovery/simiSong | /simi/song | 本次新增数据层 |  |  |
| simi_user | /api/discovery/simiUser | — | 缺失待续 |  |  |
| song_chorus | /api/song/chorus | — | 缺失待续 |  |  |
| song_cloud_download | /api/cloud/dowonload | — | 缺失待续 |  |  |
| song_copyright_rcmd | /api/song/copyright/rcmd | — | 缺失待续 |  |  |
| song_creators | /api/song/creators | — | 缺失待续 |  |  |
| song_detail **P0** | /api/v3/song/detail | /song/detail | 本次新增数据层 | 数据层 |  |
| song_downlist | /api/member/song/downlist | — | 缺失待续 |  |  |
| song_download_url **P0** | /api/song/enhance/download/url | /song/download/url | 本次新增数据层 | 数据层 |  |
| song_download_url_v1 **P0** | /api/song/enhance/download/url/v1 | /song/download/url/v1 | 本次新增数据层 | 右键菜单下载 |  |
| song_dynamic_cover | /api/songplay/dynamic-cover | — | 缺失待续 |  |  |
| song_like | /api/song/like | /song/like | 已有 | 喜欢按钮 |  |
| song_like_check | /api/song/like/check | /song/like/check | 已有 | 喜欢状态（等价） | 等价实现：/song/like/check |
| song_lyrics_mark | /api/song/play/lyrics/mark/song | — | 缺失待续 |  |  |
| song_lyrics_mark_add | /api/song/play/lyrics/mark/add | — | 缺失待续 |  |  |
| song_lyrics_mark_del | /api/song/play/lyrics/mark/del | — | 缺失待续 |  |  |
| song_lyrics_mark_user_page | /api/song/play/lyrics/mark/user/page | — | 缺失待续 |  |  |
| song_monthdownlist | /api/member/song/monthdownlist | — | 缺失待续 |  |  |
| song_music_detail | /api/song/music/detail/get | /song/detail | 已有 |  | 等价实现：/song/detail |
| song_order_update | /api/playlist/manipulate/tracks | /song/order/update | 已有 |  |  |
| song_purchased | /api/single/mybought/song/list | /song/purchased | 本次新增数据层 | 数据层 |  |
| song_red_count | /api/song/red/count | — | 缺失待续 |  |  |
| song_simi_get | /api/link/position/show/resource | — | 不适用 |  | 运营位资源接口（link/position），非音乐功能 |
| song_singledownlist | /api/member/song/singledownlist | — | 缺失待续 |  |  |
| song_url **P0** | /api/song/enhance/player/url | /song/url | 本次新增数据层 | 数据层（下载回退链） |  |
| song_url_match **P0** | (动态/复合) | — | 客户端实现 |  | PlayerApi.tryUnblockUrl 直调所选 API 服务器的 /song/url/match（GET id/source + Cookie） |
| song_url_ncmget | (动态/复合) | — | 不适用 |  | 依赖第三方 ncmget 服务，非官方接口 |
| song_url_v1 **P0** | /api/song/enhance/player/url/v1 | /song/url/v1 | 已有 | 播放取链（核心） |  |
| song_url_v1_302 | /api/song/enhance/download/url/v1 | — | 不适用 |  | 服务端 302 部署变体，客户端不适用 |
| song_wiki_info | /api/link/page/parent/relation/construct/info | — | 缺失待续 |  |  |
| song_wiki_summary | /api/song/play/about/block/page | — | 缺失待续 |  |  |
| starpick_comments_summary | /api/homepage/block/page | — | 缺失待续 |  |  |
| style_album | /api/style-tag/home/album | — | 缺失待续 |  |  |
| style_artist | /api/style-tag/home/artist | — | 缺失待续 |  |  |
| style_detail | /api/style-tag/home/head | — | 缺失待续 |  |  |
| style_list | /api/tag/list/get | — | 缺失待续 |  |  |
| style_playlist | /api/style-tag/home/playlist | — | 缺失待续 |  |  |
| style_preference | /api/tag/my/preference/get | — | 缺失待续 |  |  |
| style_song | /api/style-tag/home/song | — | 缺失待续 |  |  |
| summary_annual | (动态/复合) | — | 缺失待续 |  |  |
| thinktank_audit_resource_detail | /api/thinktank/audit/resource/detail | — | 缺失待续 |  |  |
| thinktank_audit_resource_update | /api/thinktank/audit/resource/update | — | 缺失待续 |  |  |
| threshold_detail_get | /api/influencer/web/apply/threshold/detail/get | — | 缺失待续 |  |  |
| top_album | /api/discovery/new/albums/area | — | 缺失待续 |  |  |
| top_artists | /api/artist/top | — | 缺失待续 |  |  |
| top_list | /api/playlist/v4/detail | — | 缺失待续 |  |  |
| top_mv | /api/mv/toplist | /top/mv | 本次新增数据层 | 数据层（引擎无视频管线） |  |
| top_playlist | /api/playlist/list | /top/playlist | 本次新增数据层 | 歌单广场（2.0.2 新） |  |
| top_playlist_highquality | /api/playlist/highquality/list | /top/playlist/highquality | 本次新增数据层 |  |  |
| top_song | /api/v1/discovery/new/songs | — | 缺失待续 |  |  |
| topic_detail | /api/act/detail | — | 缺失待续 |  |  |
| topic_detail_event_hot | /api/act/event/hot | — | 缺失待续 |  |  |
| topic_sublist | /api/topic/sublist | — | 缺失待续 |  |  |
| toplist | /api/toplist | /toplist | 已有 | 排行榜页（2.0.2 新） |  |
| toplist_artist | /api/toplist/artist | /toplist/artist | 本次新增数据层 |  |  |
| toplist_detail | /api/toplist/detail | /toplist/detail | 本次新增数据层 | 数据层 |  |
| toplist_detail_v2 | /api/toplist/detail/v2 | — | 缺失待续 |  |  |
| ugc_album_get | /api/rep/ugc/album/get | — | 缺失待续 |  |  |
| ugc_artist_get | /api/rep/ugc/artist/get | — | 缺失待续 |  |  |
| ugc_artist_search | /api/rep/ugc/artist/search | — | 缺失待续 |  |  |
| ugc_detail | /api/rep/ugc/detail | — | 缺失待续 |  |  |
| ugc_mv_get | /api/rep/ugc/mv/get | — | 缺失待续 |  |  |
| ugc_song_get | /api/rep/ugc/song/get | — | 缺失待续 |  |  |
| ugc_user_devote | /api/rep/ugc/user/devote | — | 缺失待续 |  |  |
| user_account | /api/nuser/account/get | /user/account | 已有 | 会话/我的 |  |
| user_audio | /api/djradio/get/byuser | — | 缺失待续 |  |  |
| user_binding | (动态/复合) | — | 缺失待续 |  |  |
| user_bindingcellphone | /api/user/bindingCellphone | — | 缺失待续 |  |  |
| user_cloud | /api/v1/cloud/get | /user/cloud | 已有 | 云盘页（2.0.2 新） |  |
| user_cloud_del | /api/cloud/del | /user/cloud/del | 本次新增数据层 | 云盘页删除 |  |
| user_cloud_detail | /api/v1/cloud/get/byids | /user/cloud/detail | 本次新增数据层 | 数据层 |  |
| user_comment_history | /api/comment/user/comment/history | /user/comment/history | 本次新增数据层 |  |  |
| user_detail | (动态/复合) | /user/detail | 已有 | 用户页 |  |
| user_detail_new | (动态/复合) | /user/detail | 已有 |  | 等价实现：/user/detail |
| user_dj | (动态/复合) | — | 缺失待续 |  |  |
| user_event | (动态/复合) | — | 缺失待续 | 数据层 |  |
| user_event_all | (动态/复合) | /event | 已有 |  | 等价实现：/event |
| user_follow_mixed | /api/user/follow/users/mixed/get/v2 | /user/follows | 已有 |  | 等价实现：/user/follows |
| user_followeds | (动态/复合) | — | 缺失待续 |  |  |
| user_follows | (动态/复合) | /user/follows | 已有 | 关注列表 |  |
| user_level | /api/user/level | /user/level | 本次新增数据层 | 数据层 |  |
| user_medal | /api/medal/user/page | — | 缺失待续 |  |  |
| user_mutualfollow_get | /api/user/mutualfollow/get | — | 缺失待续 |  |  |
| user_playlist | /api/user/playlist | /user/playlist | 已有 | 侧栏/音乐库 |  |
| user_playlist_collect | /api/user/playlist/collect | — | 缺失待续 |  |  |
| user_playlist_create | /api/user/playlist/create | — | 缺失待续 |  |  |
| user_record | /api/v1/play/record | /user/record | 已有 | 播放历史页 |  |
| user_replacephone | /api/user/replaceCellphone | — | 缺失待续 |  |  |
| user_social_status | /api/social/user/status | — | 缺失待续 |  |  |
| user_social_status_edit | /api/social/user/status/edit | — | 缺失待续 |  |  |
| user_social_status_rcmd | /api/social/user/status/rcmd | — | 缺失待续 |  |  |
| user_social_status_support | /api/social/user/status/support | — | 缺失待续 |  |  |
| user_subcount | /api/subcount | /user/subcount | 本次新增数据层 | 数据层 |  |
| user_update | /api/user/profile/update | /user/update | 已有 |  |  |
| verify_getQr | /api/frontrisk/verify/getqrcode | — | 缺失待续 |  |  |
| verify_qrcodestatus | /api/frontrisk/verify/qrcodestatus | — | 缺失待续 |  |  |
| video_category_list | /api/cloudvideo/category/list | /video/category/list | 本次新增数据层 |  |  |
| video_detail | /api/cloudvideo/v1/video/detail | /video/detail | 本次新增数据层 | 数据层（引擎无视频管线） |  |
| video_detail_info | /api/comment/commentthread/info | — | 缺失待续 |  |  |
| video_group | /api/videotimeline/videogroup/otherclient/get | — | 缺失待续 |  |  |
| video_group_list | /api/cloudvideo/group/list | /video/group/list | 本次新增数据层 |  |  |
| video_sub | (动态/复合) | — | 缺失待续 |  |  |
| video_timeline_all | /api/videotimeline/otherclient/get | — | 缺失待续 |  |  |
| video_timeline_recommend | /api/videotimeline/get | /video/timeline/recommend | 本次新增数据层 |  |  |
| video_url | /api/cloudvideo/playurl | /video/url | 本次新增数据层 | 数据层（引擎无视频管线） |  |
| vip_growthpoint | /api/vipnewcenter/app/level/growhpoint/basic | — | 缺失待续 |  |  |
| vip_growthpoint_details | /api/vipnewcenter/app/level/growth/details | — | 缺失待续 |  |  |
| vip_growthpoint_get | /api/vipnewcenter/app/level/task/reward/get | — | 缺失待续 |  |  |
| vip_growthpoint_getall | /api/vipnewcenter/app/level/task/reward/getall | — | 缺失待续 |  |  |
| vip_info | /api/music-vip-membership/front/vip/info | — | 缺失待续 |  |  |
| vip_info_v2 | /api/music-vip-membership/client/vip/info | — | 缺失待续 |  |  |
| vip_sign | /api/vip-center-bff/task/sign | — | 缺失待续 |  |  |
| vip_sign_detail | /api/vipnewcenter/app/level/user/checkin/history/detail | — | 缺失待续 |  |  |
| vip_sign_history | /api/vipnewcenter/app/minidesk/music/sign/pc | — | 缺失待续 |  |  |
| vip_sign_info | /api/vipnewcenter/app/user/sign/info | — | 缺失待续 |  |  |
| vip_tasks | /api/vipnewcenter/app/level/task/list | — | 缺失待续 |  |  |
| vip_tasks_v1 | /api/middle/vip/mission/user/progress/list | — | 缺失待续 |  |  |
| vip_timemachine | /api/vipmusic/newrecord/weekflow | — | 缺失待续 |  |  |
| voice_delete | /api/content/voice/delete | — | 缺失待续 |  |  |
| voice_detail | /api/voice/workbench/voice/detail | — | 缺失待续 |  |  |
| voice_lyric | /api/voice/lyric/get | — | 缺失待续 |  |  |
| voice_upload | /api/nos/token/alloc | — | 缺失待续 |  |  |
| voicelist_detail | /api/voice/workbench/voicelist/detail | — | 缺失待续 |  |  |
| voicelist_list | /api/voice/workbench/voices/by/voicelist | — | 缺失待续 |  |  |
| voicelist_list_search | /api/voice/workbench/voice/list | — | 缺失待续 |  |  |
| voicelist_my_created | /api/social/my/created/voicelist/v1 | — | 缺失待续 |  |  |
| voicelist_search | /api/search/voicelist/get | — | 缺失待续 |  |  |
| voicelist_trans | /api/voice/workbench/radio/program/trans | — | 缺失待续 |  |  |
| weblog | /api/feedback/weblog | — | 缺失待续 |  |  |
| yunbei | /api/point/signed/get | — | 缺失待续 |  |  |
| yunbei_expense | /api/point/expense | — | 缺失待续 |  |  |
| yunbei_info | /api/v1/user/info | — | 缺失待续 |  |  |
| yunbei_rcmd_song | /api/yunbei/rcmd/song/submit | — | 缺失待续 |  |  |
| yunbei_rcmd_song_history | /api/yunbei/rcmd/song/history/list | — | 缺失待续 |  |  |
| yunbei_receipt | /api/point/receipt | — | 缺失待续 |  |  |
| yunbei_sign | /api/pointmall/user/sign | — | 缺失待续 |  |  |
| yunbei_task_finish | /api/usertool/task/point/receive | — | 缺失待续 |  |  |
| yunbei_task_finish_v1 | /api/ad/power/yunbei/distribution/create | — | 缺失待续 |  |  |
| yunbei_task_list_v1 | /api/ad/power/yunbei/distribution/list | — | 缺失待续 |  |  |
| yunbei_task_recommend_song | /api/ad/power/yunbei/distribution/recommend/song | — | 缺失待续 |  |  |
| yunbei_tasks | /api/usertool/task/list/all | — | 缺失待续 |  |  |
| yunbei_tasks_todo | /api/usertool/task/todo/query | — | 缺失待续 |  |  |
| yunbei_today | /api/point/today/get | — | 缺失待续 |  |  |


#### 对标自查表（核心流程逐项，如实标注）

| 项目 | 状态 | 证据/说明 |
|---|---|---|
| 搜索 | 已做（回归通过） | 16b-search.png 真实结果 |
| 播放/seek | 已做（回归通过） | SourceProbe PLAY 段位置推进；引擎探针同 run3 口径 |
| 歌词页（全屏） | 沿用 2.0 已做 | 本轮未改 |
| 桌面歌词 | 已修（Bug 3） | 18b/19b 截图 + [LYRIC] 日志链 |
| 音源切换 | 已补 UI + 修缓存失效（Bug 1/P0） | 07/08/10/12 截图 + RELOAD 探针 |
| API 测速 | 已修口径（与取链同路径） | MeasureProbe202 双台可用 |
| 右键菜单 | 已做 | 17-context-menu.png |
| EQ | 已落地 | SourceProbe [EQ] 日志 + 11-eq-dialog.png |
| 托盘 | 未实测（同前） | Xvfb 无托盘，代码未动 |
| 全屏播放页 | 沿用 2.0 已做 | 本轮未改 |

#### 回归

- 登录探针 AuthProbe201 全绿，与 run3 基线一致：deviceId 稳定、
  warmup jar=[NMTID]、qrKey 成功、qrCheck=Waiting(801)、
  captchaSent 200、坏验证码回业务错误「验证码错误」——2.0.1 设备
  指纹链路未回退（指纹红线不变：只发 deviceId + 服务端真 Cookie）。
- 全量构建 0 error（compile-app.log / compile-desktop.log）。

#### 交付

- dist/PenguinMusic-Windows.zip（151,341,351 字节 ≈144MB），
  MD5 `84b1c42da295f2ca87ac727d7650d8e5`；exe FileVersion/
  ProductVersion 2.0.2（PE 固定版本信息实读 2.0.2.0）；关于页
  文案 2.0.2；launch4j 配置 desktop/tools/launch4j-penguin.xml。
- 旧 2.0.1 包备份 dist/PenguinMusic-Windows-2.0.1.zip
  （MD5 1a72c3edaa6ffd89f8b7f92d215c1f0d，与 2.0.1 交付一致）。
- 下载（两份 MD5 一致、与本地一致、curl -I 均 200）：
  https://tsemby.lblb.eu/dl/PenguinMusic-Windows.zip
  https://tsemby.lblb.eu/dl/PenguinMusic-Windows-2.0.2.zip
- 升级方式：解压覆盖即可，数据在 ~/.jussichords 不动。

#### 待续（不假装已做完）

- 云盘上传（NOS 分片通道四步接口，数据层亦在缺失清单）。
- MV/视频播放：数据层已补（/mv/*、/video/*），桌面引擎无视频
  管线，不支持播放。
- 歌手页简介 UI：/artist/desc 数据层已备，页面待接。
- API 缺失待续 242 条长尾（见差距表），多为任务/投稿/交易/
  账号管理类非播放核心。
- FLAC 本地解码：PROXY 解灰常出 FLAC 链，桌面引擎 MP3-only
  仍不支持（既有缺口）。
- 仍未实测（同前）：真账号扫码 803 与真号登录后写操作、系统
  托盘、系统媒体键——只能用户真机验。

### 7.12 2.0.3 修复（2026-10-23，用户真机反馈四 P0，证据在 desktop/build/run5/）

2.0.2 的四项在真机全部复现。共性根因：2.0.2 只在 Linux Xvfb 沙箱验证，
而四个 bug 的触发条件都在沙箱照不出的层面——存量 DataStore 配置、Windows
窗口系统、网易云对真实登录环境的风控。本版按「真机条件」设计验证：种子化
存量状态矩阵 + 轨迹采样 + 逐参数对照上游源码。

**Bug 1 播放没反应 / 音源用不了**

- 真机根因（复合）：① PROXY 存量配置指向已下线/不可达服务器时，解灰请求
  走 apiClient 默认超时，单个坏地址能挂几十秒~几分钟，播放器还重试 2 次，
  表现为「点了没反应」；② fee=1 取链「三选一互斥不串联」（2.0.2 语义）：
  所选音源失败不级联，LX stub / 死服务器 / 空地址全部静默落到官方试听或
  空地址；③ 解析失败自动跳歌被 UI 层按设计忽略，全队列失败时用户只看到
  歌一首首跳、无任何提示；④ 音质选无损时官方回 FLAC 直链，MP3-only 引擎
  抛格式错误死停；⑤ 音频设备打开失败被统一映射成「网络错误」自动跳歌。
- 修法：取链改级联（所选音源 → 官方直连 → 解灰匹配 → 返回官方结果由
  播放器跳歌），每步写 ResolveTrace；终局失败 Toast 带步骤链（如
  「LX音源 未命中 → 官方直连 无可播地址」）；解灰单请求硬超时 6s；
  启动配置消毒 sanitizeProxyConfig：PROXY 且地址是已删死台/空/非 http(s)/
  探活（GET /song/url/match，3.5s+3.5s，任何 HTTP 响应算活）不通 → 依次
  探用户服务器列表顶替，全灭清地址切回默认音源，动作 Toast 告知；
  FLAC 直链自动以 EXHIGH 重解析一次（成功 Toast 说明，仍为 FLAC 则明确
  报错）；音频设备异常单独成 AudioDeviceException（错误码
  ERROR_CODE_AUDIO_TRACK_INIT_FAILED、不跳歌、Toast 指引查输出设备）；
  Toast 经 PlaybackToast 4 秒同文去重。缓存键审计：2.0.2 的键已含
  sourceTag，2.0.1 旧键条目在新版键下自然 miss、按当前音源重解析，不会
  在错误音源下被误用（代码审计结论，无专门运行时测试）。
- 种子矩阵（run5，--dump-state 每秒落盘断言；免费歌《涧》id=1315196858
  fee=0 + VIP 歌 Beatles/Jay Chou 曲目验级联）：
  (a) 全新：搜索→点播，pos 0→11.5s 持续推进 ✓
  (b) PROXY+死台 api.jussichords.kdns.fr：消毒识别死台→探活 alternates
      （沙箱里两台免费机响应都超探活预算判死）→清地址切默认+Toast，
      随后播放推进 ✓
  (c) PROXY+垃圾地址 nonexistent.invalid.example：探活失败→自动切到
      第一个活的 alternates（api.jussicodes.kdns.fr）+Toast，播放推进 ✓
  (d) LX 无脚本：免费歌官方直连播放推进 ✓；VIP 歌级联 Toast 完整
      （LX 未命中→官方无可播→跳歌提示）✓
  (e) DIRECT：播放推进 ✓
  (f) audioQuality=lossless：免费歌推进 ✓（该曲无 lossless 版本回 MP3）；
      FLAC 分支另以播放器级探针验：PROXY 解灰回 .flac 时自动降音质
      重解析，仍为 FLAC 时 Toast「无损(FLAC)格式暂不支持，降音质取链
      也失败」+ playerError 可见，不死停 ✓（flacprobe2.log）
  另：无声卡环境（沙箱缺省混音器）实测触发设备错误 Toast 文案正确、
  pos 不造假推进（a3 首跑）；沙箱回归用 NullMixer（仅测试 classpath）。
  沙箱注记：美 IP+未登录下大量商业曲目官方回 url=null（地域限制），
  这类在级联里属「官方无可播地址」的环境性失败，与接口不通已分开留痕。

**Bug 2 登录「环境异常有安全风险」**

- 逐参数对齐清单（地面真值 = run4 vendored 的 api-enhanced 源码
  module/*.js + util/request.js + util/index.js）：
  | 项 | 2.0.2 | 2.0.3 |
  |---|---|---|
  | login_qr_key/check 的 type | 1 | 3（login_qr_key.js） |
  | captcha_sent | 无 secrete | secrete='music_middleuser_pclogin' |
  | login_cellphone | /api/login/cellphone + rememberLogin=true | /api/w/login/cellphone，字段 type='1'/https='true'/remember='true'/secureCaptcha |
  | login（邮箱） | /api/login | /api/w/login，type='0'/https='true'/rememberLogin='true' |
  | 匿名注册 | 无 | 新增 /api/register/anonimous（eapi + PC profile，见下） |
  | 登录 Cookie 集合 | jar + deviceId | processCookieObject 的 os=pc 全套：__remember_me/ntes_kaola_ad/_ntes_nuid/_ntes_nnid/WNMCID/WEVNSM/os=pc/appver=3.1.17.204416/osver=Win10-19045/channel=netease + jar 真 Cookie + deviceId；有 MUSIC_U 时不带 MUSIC_A |
  | deviceId 形态 | 32 位 UUID hex | 新身份 52 位大写 hex（generateDeviceId 同形）；存量不迁移 |
  | _ntes_nnid | 无 | 与 _ntes_nuid 同批生成落盘（"<nuid>,<ts>"） |
- §7.10 红线细化：2.0.1 消融翻车时自造的 NMTID 是 16 位普通字母数字串，
  真实格式为 '00O'+38 位 hex，畸形值才是验伪触发点。本版 NMTID 仍只发
  服务端下发进 jar 的真值；其余指纹字段为官方客户端同形良构值且落盘
  稳定。改后探针当裁判：全部登录路径无 -462 回归（见下）。
- 匿名注册：username 编码逐字对齐 register_anonimous.js
  （base64(deviceId + ' ' + base64(md5raw(deviceId XOR '3go8&$8*3*3h0k(2)2')))）。
  踩坑：用移动端 eapi header（os=android）请求回 {"code":400}，该接口是
  PC 客户端（cloudmusic DLL）形态，改走 PC eapiProfile（os=pc 全套 +
  interface 域）后回 200 并下发 MUSIC_A/__csrf。注册接口对同 IP 有频率
  限制（沙箱实测：短时间多次注册后新 deviceId 也回 400），故
  ensureAnonymousToken 进程内 10 分钟节流；正常用户首次注册成功后
  MUSIC_A 落盘持久复用。触发点：启动预热 + 每次登录请求前兜底。
  全仓 grep 确认无逐请求随机 deviceId：ApiClient 顶部历史遗留的
  anonymousDeviceId（每进程随机）经 grep 确认零使用点，本版已删除；
  实际发送的 deviceId 恒为 DeviceIdentity 的落盘值（未 init 时的
  fallback 仅供旧调用方兜底，桌面端启动即 init）。
- 风控处理：AuthApi 识别 code=-462 /「环境异常」「安全风险」「请完成
  验证」→ 脱敏（抹 MUSIC_U/MUSIC_A/__csrf/NMTID 值与 cookie 串）写
  ~/.jussichords/logs/login.log（AuthDiagnostics.sink → DesktopLog.login），
  UI 抛带明确指引的 RiskControlException（提示改用 Cookie 登录）。
- Cookie 登录兜底真校验：LoginScreen Cookie 页改走 AuthApi.loginByCookie
  ——候选 Cookie 装入 CookieProvider → /user/account 校验，profile.userId>0
  才算登录（模型 profile 有默认实例永不为 null，旧「含 MUSIC_U 即成功」
  与「profile 非空」判据都会被伪造 Cookie 骗过，探针实测）；失败恢复原
  Provider 状态并显示原因。文案标「备用方式」。
- 沙箱探针（AuthProbe203，最终版 jar 复跑）：qrKey 成功、qrCheck=801
  Waiting ×2、captchaSent=200、坏验证码回「验证码错误」、坏密码回
  「账号或密码错误」、坏邮箱回 code=501——全是业务错误、零风控；
  Cookie 负样本（伪造 MUSIC_U）被校验拒绝且 Provider 已恢复；
  deviceId 跨进程稳定（同 dir 两跑一致）。MUSIC_A：机制实证两次
  （AnonDebug 全新身份 code=200、jar 吸收 MUSIC_A/__csrf/NMTID 并落盘；
  app 内启动预热 musicA=true，见 a1-search.log），其后沙箱 IP 被该接口
  限流（回 400），最终版复跑未能再次取到——属端点策略，非代码问题。

**Bug 3 拖动窗口抖动**

- 根因坐实：2.0.2 手动拖动用「窗口当前位置 + 组件相对增量 drag」逐步
  累加，drag 是窗口内坐标增量，窗口每动一次坐标系就变，形成反馈振荡。
- 重写：onDragStart 一次性捕获窗口位置 + java.awt.MouseInfo 屏幕绝对
  指针；拖动中 setLocation = 起点窗口位置 + (当前屏幕指针 − 起点屏幕
  指针)，全程屏幕绝对坐标。双击最大化保留（独立 pointerInput，未受
  影响）。歌词 AWT 窗拖动同款。
- 机器人验证（新增 --drag-trace 钩子，20ms 采样窗口屏幕坐标）：
  右下拖 (640,20)→(1000,300)：93 个移动样本，x/y 回跳均为 0、最大回退
  0px，终点 Δ=(330,257)；左上拖 (640,20)→(300,60)：92 个样本回跳 0，
  Δ=(−312,37)。两向终点都比机器人目标少恰好一个步进（30px/28px）——
  机器人最后一次 mouseMove 在 X 下被事件合并未送达（轨迹呈 5 段大跳
  达终点），是测试通道伪影：窗口位移与已送达指针位移严格 1:1、无漂移
  累积。钩子踩坑：首版在窗口 showing 前调 locationOnScreen 抛
  IllegalComponentState 被 Compose 弹成模态错误框，已加 isShowing 守卫。

**Bug 4 桌面歌词没反应（换纯 AWT 实现）**

- 弃 Compose Window，新建 DesktopLyricAwtWindow（JWindow + Graphics2D
  自绘）：120ms Timer 读 DesktopLyricController 状态重绘；当前行逐字
  扫光（YRC 逐词/字、LRC 按行进度，与旧 buildSweptText 同逻辑，该函数
  拆到 LyricSweep.kt 供全屏页继续用）；下一行小字；半透明圆角底；
  hover 工具条形状绘制（上一首/播放暂停/下一首/A-/A+/锁/关闭，不依赖
  emoji 字体）；长行按宽度自动缩字号防溢出；always-on-top；
  setFocusableWindowState(false)；锁定穿透沿用 JNA 实现（showing 后
  应用、runCatching 兜底、日志留痕，非 Windows 只降级不穿透）；
  位置校验沿 2.0.2 口径（任一屏可见交集 ≥120×60、宽度消毒 [300,2560]、
  坏值回落主屏底部居中，日志打 dpiScale 与屏幕数）；拖动屏幕绝对坐标；
  位置约 1.2s 轮询落盘 + 拖动结束即落盘；controller.setEnabled 直驱
  AWT 窗（不等 Compose 桥接），DesktopMain 保留 LaunchedEffect 桥作
  启动恢复路径，两路幂等。
- 可见反馈铁律：无队列/未播放也出窗，显示占位「企鹅音乐 · 未播放」
  （有歌无词显示歌名，纯音乐显示其占位行）。日志沿用
  ~/.jussichords/logs/lyric-window.log（awt 前缀行）。
- 沙箱验收（run5，--screenshot-screen + 外部 Robot 抓屏）：
  L1 全新+启用：出窗底部居中、占位文案正确、loc=(580,1021) ✓
  L2 坏位置 (5000,5000)+坏宽度 80：宽度消毒 300、回落底部居中 ✓
  L3 锁定=true：showing 后样式应用成功日志、不崩 ✓（Linux 下 JNA 段
     按设计跳过，Windows 真穿透未实测）
  L4 播放《涧》：占位行「纯音乐，请欣赏」随播放渲染 ✓
  L5 播放《remember.》（43 行词）：pos 9.2s 与 25.1s 两帧歌词行不同、
     已唱段绿色扫光、下一行预告 ✓（滚动实证）

**全量回归**：启动（final-main.png 零未捕获异常）、搜索、播放、seek
（点击进度条 pos 6112→98376 精确落点后续播）、登录页四页签（真二维码
渲染、Cookie 页新文案与校验接线截图）、设置页（音源说明已同步级联
语义）、登录探针（上）。

**交付**：dist/PenguinMusic-Windows.zip（151,368,572 字节，MD5
9625c34708f342dfc8bcde071259f19f），exe 内嵌版本串实读 2.0.3（UTF-16
×2，2.0.2/2.0.1 均为 0）；双链接（PenguinMusic-Windows.zip 与
PenguinMusic-Windows-2.0.3.zip）服务器 MD5 两端一致、curl -I 均 200；
旧 2.0.2 包 dist 留备份。设置页版本号文案与 launch4j fileVersion/
productVersion 均为 2.0.3。

**仍未实测（如实）**：真账号扫码 803、真号登录后写操作、托盘、媒体键
（同前，只能真机）；**风控在用户 IP 下的最终结果不打包票**——本版已把
参数、Cookie 集合、匿名令牌与官方 API 客户端逐项对齐且沙箱探针零
风控，但网易云风控是服务端策略，真机若仍被拦，Cookie 登录是已做通
校验的保底路径（负样本验过、正样本须真 Cookie 才能验）；Windows 下
歌词锁定真穿透与 DPI 缩放（125%/150%）下的歌词窗定位未实测（沙箱
dpiScale=1.0，定位逻辑按用户空间坐标设计，待真机反馈）；真机音频
设备异常分支（沙箱以「无声卡」环境验过文案与状态，真机设备占用/
拔插场景未验）。

### 7.13 2.0.4 修复（2026-10-23，用户真机反馈两 P0，证据在 desktop/build/run6/）

2.0.3 真机仍坏两处：①设置「极高」却取到无损 FLAC 且播不了、降音质
也救不回；②API 登录在真机环境仍被风控。源码级三根因：

1. **音质设置只作用于官方步**：fee=1 歌曲先走音源级联，直连/代理解灰
   步不传 level、源回什么是什么，VIP 歌常回 FLAC；安卓同逻辑但
   ExoPlayer 原生解 FLAC 未暴露。
2. **「降级」是假兜底**：DesktopPlayer 捕获格式异常后以 EXHIGH 重走
   同一条级联，又命中同源同一 FLAC。
3. **登录方向错了三版**：安卓端根本不用 API 登录——WebView 打开
   官方登录页抓两域 Cookie；桌面三版自创 API 模拟登录硬刚风控。

另在本版排查中新发现并修掉一个存量 bug：**桌面 SettingsScreen 存
audioQualityKey 用 SongLevel.value（小写 "exhigh"），DesktopPlayer 的
enumPreference 按枚举名解析 → 桌面播放器音质一直静默回落 STANDARD**，
B 闸门与无损播放全错位。修法：桌面垫片 DataStoreUtils.toEnum 增加
uppercase 回退解析（名/值两种存量都可读）；SettingsScreen 读取先按
name 再按 value 匹配。此前 FLAC 播放探针第一轮失败即此 bug 所致。

**修复**：
- **FLAC 解码**：DesktopPlayer 新增 decodeFlacStream（jFLAC 1.5.2 整曲
  解码为 16-bit PCM 进内存，24-bit 取高 16 位；STREAMINFO 定时长；
  playPcmLoop 从起始帧偏移播放——帧级 seek、过 EQ、暂停语义与 MP3
  同口径）。取舍：seek 需整曲重新下载+解码（沙箱经代理约 9s，真机
  直连应明显更快），换取实现简单与落点精确。
- **格式闸门**（ncmapi PlayerApi.gateFormat）：级联每步（LX/直连六源/
  PROXY 解灰/官方/解灰匹配）返回 URL 过闸——level≠LOSSLESS 且 URL 以
  .flac 结尾判未命中、记 ResolveTrace 并继续级联；GateProbe204 实测
  trace：「代理解灰 返回 FLAC 与所选音质不符，跳过 → 官方直连…」，
  LOSSLESS 时同源 FLAC 正常放行。
- **真降级**：格式异常 → updateMediaItemUriOfficial 直达官方
  /song/url/v1 指定 EXHIGH，校验非空、≠原 URL、非 FLAC 才采用，不再
  走级联；失败明确 Toast，不假成功。DowngradeProbe 实证：坏 FLAC →
  「FLAC 解码失败：Could not find Stream Sync」→ 进降级分支调用官方
  直达 → 沙箱内官方持续回 null（地域，GateProbe 同曲曾回真链）→
  fail Toast + 错误正常浮出、不挂死；「官方回真链后续播 MP3」最后
  一跳未在沙箱复现（MP3 解码播放本身为既有已验路径）。
- **网页登录（默认）**：LoginScreen 改三页签——网页登录（默认）/
  其他方式（扫码/验证码/密码次级页签）/Cookie（保留）。JCEF
  （jcefmaven 132.3.1）内嵌 https://music.163.com/m/login，每 2.5s
  visitUrlCookies 轮询 music.163.com 与 y.music.163.com 两域，出现
  MUSIC_U → NcmCookieNormalizer.normalizeForStorage →
  AuthApi.loginByCookie 真校验 uid>0 → 走既有 onLoginSuccess 落盘。
  选型过程：JavaFX WebView 判死（SPA 在其 WebKit 下不启动，证据
  run6/weblogin-probe*.png）；JCEF 探针实证 Chromium 渲染、JS 回读、
  两域 Cookie 可读、Robot 真实点击三级交互（落地页→协议弹层→手机
  号+验证码完整表单，run6/jcef-probe3..6.png）。应用内回归截图
  （run6/r2g-login.png）：登录页默认停在网页登录页签，JCEF 渲染出
  真实官方登录页（手机号登录按钮/协议链接/第三方图标），产品代码
  无任何证书绕过参数（沙箱探针曾用的 --ignore-certificate-errors
  只在探针进程，用户明确授权，未进产品）。JCEF 运行时不进 fat jar
  （jcefmaven 按 classpath 条目定位 natives），随包外置 jcef/ 八个
  jar，launch4j classPath 引用；初始化失败页签显示提示不崩。
- **默认音质统一**：SettingsScreen 与 DesktopPlayer 对
  audioQualityKey 的回落默认统一 STANDARD（与手机一致）；存量已存
  值不受影响；downloadQuality 独立键未动。设置页回归截图实证新装
  默认「标准」。

**验证**（run6/）：JFlacProbe 三规格（16/16s/24-bit）解码采样数与
STREAMINFO 全等 badFrames=0；FlacDirectProbe 真 FLAC 端到端：进度
实时推进、dur=238698 正确、seekTo(60000) 落点 60085（±0.1s）、暂停
drift=0、恢复推进（seek 后约 9s BUFFERING 为整曲重解码经沙箱代理
的延迟）；GateProbe204 闸门两轮；DowngradeProbe 如上；应用级回归
（run-app.sh + PIL 实测坐标）：启动零异常、首页/排行榜/搜索结果
渲染、设置页（默认标准 + 关于区「版本 2.0.4」）、登录页如上。exe
内嵌 UTF-16 版本串 2.0.4 ×2、2.0.3 ×0。

**体积**：zip 151MB → 296,365,336 字节（JCEF Windows natives 约
132MB 为主因），MD5 63cf7fb1d867dd1b6d00435eaed42c9d，本地/服务器
两端一致，双链接 200；dist 留 2.0.3 备份。

**仍未实测（如实）**：真账号网页登录完成态（登录后 Cookie 真校验
正样本）；JCEF 内表单键入（探针被跨域 iframe + Xvfb 焦点所限未取
得决定性证据，渲染与真实点击已实证）；降级最后一跳（见上，沙箱
地域限制）；Windows 真机 JCEF 证书/渲染、DPI 歌词定位、托盘/媒体
键、登录后写操作（同前，真机项）；应用内 UI 点选播放（沙箱 Robot
点选行未触发播放，播放器层已由探针完整验证）。

#### 7.13.1 音源验收（2026-10-23 晚，老板最高优先级，证据 run6/source-matrix.log、source-matrix2.log、slowflac.log）

口径：不只验拿到 URL——解析后逐项喂 DesktopPlayer 真播放、断言进度
推进（maxPos/playing/err 三件套）。样本 6 首（fee=1×5：想你就写信
Live 509781655、过渡期 25780143、Masterpiece 3449273923、Just Give
Me a Reason 21373645、永不失联的爱 Live 1479507262；fee=0×1：涧
1315196858），搜索 API + 飙升榜动态取样、新老/中外混合。

- **官方步**：fee=0《涧》→MP3 真播（两轮 maxPos 9325/10475）；
  fee=1×5 全部 url=null——未登录 + 沙箱海外 IP 的上游限制（非解析
  故障），真机登录态/国内 IP 下官方步行为以真机为准。
- **PROXY 解灰（indevs，AUTO）**：LOSSLESS 三首 VIP 全解析出 FLAC；
  两首即时真播（maxPos 9813/10837），一首（过渡期）慢启动——矩阵
  21s 窗口内 pos=0，定向复核（slowflac.log）~10s 后正常起播，是整曲
  下载经沙箱代理的延迟、非死链。EXHIGH：解灰只回 FLAC → 被闸门
  正确跳过 → 官方步沙箱回 null → 本轮无 URL（同上游限制）；第一轮
  还观测到 indevs /song/url/match 偶发 6s 超时与 404（AUTO 轮换源
  抖动，重试即恢复）。
- **DIRECT 六源逐个钉选**（样本 509781655）：oi / qijieya / msls 在
  EXHIGH 直接回 MP3 并真播（maxPos 10056–10896）；byfuns / ddyr /
  gdmusic 在 EXHIGH 只回 FLAC、被闸门正确判未命中，改 LOSSLESS 后
  byfuns（9045）、gdmusic（5546）FLAC 真播；**ddyr 的 FLAC 流下载
  停滞**：pos 停在 92、约 34s 报 Read timed out（错误正常浮出、不
  挂死），点名为源侧 CDN 交付问题。AUTO 模式链路正确（跳过被闸门
  拦的源、落到可播 MP3，maxPos 9864）。
- **音质两档结论**：EXHIGH 最终链接非 FLAC 且可播——DIRECT 路径
  实证；LOSSLESS FLAC 可播——PROXY ×3、DIRECT ×2 实证。PROXY 模式
  +EXHIGH 在沙箱内因官方步地域限制无果，属环境限制非解析故障。

### 7.14 2.0.5 体检扩单全清（2026-10-23，证据在 desktop/build/run7/）

2.0.4 交付后只读体检的扩单（bug 12 + 待验证防护 3 + 功能 12）在本版
全部落地。版本号统一 2.0.5（BuildConfig.VERSION_NAME 为产品版本；
上游版本改名 UPSTREAM_VERSION_NAME=2.4.5 仅作来源标注）。交付物
MD5 80a102b48f5bd27e99303ec51b9cdbe6（296,841,416 字节），exe 内嵌
版本串校验 2.0.5×2、2.0.4×0。

**A. bug 12 条**
1. 自动跳歌开关半失效 → DesktopPlayer 真正读取 autoSkipNextOnErrorKey
   （init 收集器维护 @Volatile 字段）；关时失败停播报错不跳。探针实证
   （fuse-nofuse.log：死链队列 idx 恒 0、playing=false）。
2. 无单实例 → desktop/SingleInstance.kt（数据目录文件锁 + 回环套接字
   SHOW 协议，第二实例通知第一实例前置后退出）。实证 r8-second.log：
   第二实例 <1s 退出并打印通知行，第一实例存活、前置回调已接线。
3. 主窗口多屏恢复 → 钳制范围改全部 screenDevices bounds 并集。
4. 几何落盘无节流 → snapshotFlow 加 debounce(450ms)；跨运行尺寸恢复
   实证（run7 多轮截图窗口尺寸连续变化/恢复正常）。
5. 直链缓存永不过期 → resolvedUrls 带时间戳，TTL 20 分钟过期剔除。
6. 队列文件非原子写 → SongListUtil 桌面影子件（.tmp 写 + rename 原子
   覆盖、loadSongList runCatching 防腐）；DesktopMain 里 player.init
   包 runCatching。
7. 更新检查盯错产品 → AppUpdateManager 改查自有源
   https://tsemby.lblb.eu/dl/penguin-version.json，与
   BuildConfig.VERSION_NAME 比对；UpdateDownloadService 改真下载
   （流式下 zip 到 ~/Downloads、进度回写、成功打开所在文件夹）；
   DesktopMain 与设置页去掉「又下载又弹 GitHub Toast」的矛盾逻辑，
   发现新版直接下载。探针实证（updateprobe.log：线上 2.0.5 vs 本地
   2.0.5 → checkUpdate 返回 null）；version.json 已随包上线、curl 200。
8. LocalSourceFetcher 连接泄漏 → httpGet 改 execute().use{} 全分支
   关闭。回归日志零 leaked 警告（regression.log）。
9. 日志轮转 + 崩溃留痕 → DesktopLog 的 lyric/login 加 1MB 轮转
   （滚动 .1 备份）；全局未捕获异常处理器落 logs/crash.log。探针实证
   （logprobe.log：crash.log 含标记异常、login.log.1 备份生成）。
10. 测试后门参数 → --screenshot/--screenshot-screen/--clicks/
    --data-dir/--drag-trace/--dump-state/--auto-exit-ms 全部收口到
    devHooks 门控：仅环境变量 PENGUIN_DEV_HOOKS=1 或系统属性
    penguin.devHooks=true 时生效，**发布包默认禁用**（沙箱验收脚本
    显式带 PENGUIN_DEV_HOOKS=1）。实证 r9-gateoff.log：不带环境变量时
    --screenshot/--auto-exit-ms 全部失效（无截图文件、进程不自退）。
11. DownloadHelper 缺陷组 → 任务模型重写：同名自动加 (1)/(2) 绝不
    覆盖、.part 临时文件成功才 rename、失败/取消删半截、同歌并发
    互斥、256KB 节流进度回调、cancel/retry/dismiss API。
12. 测速串行 → measureApiServers 改 coroutineScope + async/awaitAll
    并发（结果顺序不变）。

**B. 待验证 3 条防护**
1. 连续跳歌熔断 → onSessionError 连败计数，达 5 次停播 + Toast，
   成功起播清零。探针实证（fuse-fuse2.log：死链队列在 REPEAT_ALL 下
   跳 4 次后第 5 败熔断，state=ENDED、idx 冻结不再轮转）。
2. MP3 暂停尾音 → 两条解码管线统一暂停语义：pause 后继续写至淡出
   增益 ≤0.002 才 stop() 泊车输出线，恢复时 start()+淡入。回归实证
   （regression.log：PAUSE drift=0、RESUME 续播推进）。
3. Ktor 日志隐私 → ApiClient Logging 改过滤型 Logger，只放行
   REQUEST:/RESPONSE:/METHOD:/FROM: 行，头与体（含 Set-Cookie）全
   过滤。实证：run7 全部应用/探针日志零 Set-Cookie（r5-mini.log 可见
   新日志格式）。

**C. 功能 12 项**
1. 定时关闭 → PlayBar 时钟按钮菜单（15/30/45/60 分钟 + 自定义
   1–600 分钟 + 取消、剩余分钟显示），后端 PlayerState.startTimer。
   截图 r6c-timer.png。
2. 播放历史上报 → DesktopPlaybackHistory 镜像安卓 PlaybackService
   接线（PlaybackHistorySession + Reporter 双通道 eapi weblog +
   NCBL），未登录静默跳过；fuse 探针日志有 attached 行。
3. 全局快捷键 → DesktopHotkeys（JNA 直调 user32 RegisterHotKey，
   Ctrl+Alt+空格/←/→/↑/↓/L），设置页开关（默认关）。截图
   r2b-settings.png。Linux 沙箱无法注册（非 Windows 跳过打日志），
   真机行为未实测。
4. 下载管理页 → DownloadsPage（任务列表进度/取消/重试/移除 + 下载
   目录文件列表/删除/打开文件夹），侧栏入口。截图 r4-downloads.png。
5. 开机自启 → AutoStart（启动文件夹 PenguinMusic.lnk，PowerShell
   WScript.Shell 创建），设置页开关以 .lnk 存在为准。截图
   r2c-settings.png。非 Windows no-op，真机未实测。
6. 歌词罗马音 → LyricRepository 接 romalrc（与译文同口径时间对齐），
   FullPlayer 与设置页同键开关（DesktopPrefs.lyricRoma）。截图
   r2d-settings.png（设置侧）。
7. 歌手简介 → ArtistApi.artistDesc（/artist/desc）+ 歌手页
   「歌手简介」按钮与分节滚动对话框。数据层探针实证
   （artistdesc.log：周杰伦 code=200、briefDesc 665 字、6 分节）。
8. 云盘上传 → CloudUploadHelper NOS 四步（check→token→NOS 裸传带
   进度→info→pub），云盘页上传按钮 + 进度文案/进度条，每步失败带
   步骤名报错。截图 r7-cloud.png（未登录时按钮禁用）。真账号全链
   未实测（import 路由参数未经真账号验证；info 步元数据暂用文件名，
   无标签解析器）。
9. 迷你模式 → PlayBar 入口；主窗口缩为 400×96 置顶小窗播放条
   （封面/歌名/上下曲/播放暂停/退出迷你/底部细进度/整条可拖）。
   截图 r5-mini.png（实测 PNG 尺寸恰 400×96）。注意：在迷你模式下
   退出应用，下次启动窗口按钳制下限 900×600 恢复（几何落盘存的是
   迷你尺寸），真机 DPI 表现未实测。
10. 淡入淡出 → PcmEffects 增益斜坡（起播淡入、暂停/切歌淡出
    260ms），两管线处理链统一为 EQ → 淡入淡出 → 8D；回归探针
    暂停/恢复行为正常（见 B2）。
11. 歌单导入/导出 → 侧栏「我的歌单」+ 号导入对话框（粘贴链接/ID →
    正则提取 → playlistDetail 验存 → 跳转）；歌单页导出菜单
    （m3u 带 #EXTINF + 已下载文件项、纯文本每行「歌名 - 歌手」，
    落 ~/Music/PenguinMusic）。截图 r1-home.png（导入入口）。
12. 8D 环绕 → EqSettings.surround8d + PcmEffects.processSurround
    （等功率声像 LFO 0.12Hz），EQ 对话框开关。截图 r3-eq.png。

**音源回归抽验（regression.log / gate.log，未动解析链路逻辑）**
- DIRECT AUTO + VIP(509781655) + EXHIGH：maxPos=6288、playing=true、
  err=null；trace 显示格式闸门正确跳过 ddyr/gdmusic 的 FLAC 报价。
- PROXY 解灰(indevs AUTO) + VIP + LOSSLESS：try1 源侧慢启动
  （18s 内未推进），try2 maxPos=5120、playing=true、err=null。
- EXHIGH 闸门：DIRECT 终链 fmt=MP3（nonFlac ✓）；PROXY 本轮 indevs
  只回 FLAC、被闸门正确拒绝 → 级联官方步（沙箱未登录 + 海外 IP
  回 null，与 2.0.4 已知上游限制一致）——「EXHIGH 绝不交付 FLAC」
  的闸门语义成立，但 PROXY+EXHIGH 本轮无可播 URL（环境所限）。

**构建管线注记**：jcefmaven 只作编译期类路径（desktop/libs-compile/，
build.sh 的 app 编译步单独追加），不进 fat jar（与 2.0.4 一致：
fat jar 内 me/friwi 条目为 0，运行时走 launch4j 附加 cp 的松散
jcef/ 目录）；jcef-api.jar 在 desktop/libs（类并入 fat jar，同 2.0.4）。

**不在本版范围（已向老板明示待拍板）**：任务栏缩略图控制、严格
无缝播放、HRIR 卷积。

**未实测清单**：全局快捷键真机注册与冲突、开机自启真机、云盘上传
真账号全链、定时关闭真机到点停播、迷你模式真机 DPI、JCEF 网页
登录完成态与表单键入、托盘/媒体键真机、多屏位置恢复真机（A3 仅
代码级修复 + 单屏沙箱回归）、直链 TTL 与队列原子写的故障注入
（代码级实现，未做注入演练）。

### 7.15 2.1.0 大件四项（2026-10-23，证据在 desktop/build/run8/）

2.0.5 时明示顺延的四项大件在本版落地：本地音乐、MV 播放、LX
引擎、一起听。版本号统一 2.1.0（BuildConfig.VERSION_NAME /
VERSION_CODE=46、launch4j fileVersion 2.1.0.0、fat jar manifest）。
交付物 MD5 67397c22d6e35a76c56cd631b3bee959（327,683,744 字节；
2.0.5 为 296,841,416，净增 30.8MB = GraalJS 五个 jar 压缩后体积，
见下 LX 项；dist 留 2.0.5 备份）。exe 内嵌版本串校验 2.1.0×2、
2.0.5/2.0.4×0。penguin-version.json 已更新 2.1.0 并上线验证可读，
双下载链接 curl -I 均 200、服务器端 MD5 与本地一致。

**1. 本地音乐（完成）**
- desktop/local/LocalTags.kt：手写零依赖标签解析——MP3 ID3v2.3/2.4
  （TIT2/TPE1/TALB 四种文本编码）+ID3v1 兜底、时长 Xing/码率估算；
  FLAC STREAMINFO+Vorbis Comment；WAV fmt/data+LIST INFO；M4A
  mvhd+ilst。读不到标签以文件名/未知歌手/未知专辑兜底。
- desktop/local/LocalLibrary.kt：文件夹增删+递归扫描（mp3/flac/
  wav/m4a）、曲库持久化 filesDir/local-library.json、StateFlow 供 UI；
  LocalTrack.toMediaItem 约定 mediaId="local:<绝对路径>"、uri=
  file://、extras 带时长与来源名，**不进在线解析缓存键**。
- DesktopPlayer 新增本地分支（localFileOf 命中 mediaId 前缀或
  file:// scheme）：MP3 复用 JLayer 核心（本地 seek 按码率估字节
  偏移，同在线策略约 1s 精度）；FLAC 抽出 playFlacPcm 与在线共用
  jFLAC 核心（帧级 seek）；WAV 直喂 PCM（RIFF 解析，PCM 8/16/24/32
  与 float 32/64 转 16-bit，流式分块不过内存，暂停泊车语义与
  playPcmLoop 同构）；M4A 明确报「暂不支持」不假播。本地歌不走
  音源级联、格式不支持不降级官方（无链可降），错误码映射沿用
  在线口径（IO 类可跳歌、解析/设备类不跳）。
- UI：侧栏「本地音乐」页（文件夹管理/JFileChooser 添加、搜索、
  全部/按专辑/按歌手分组、点行整表入队播放、播放全部）；全屏页
  本地歌词走同目录同名 .lrc（LocalLyrics 复用 LyricRepository.
  buildEntries 解析）。
- 实证（LocalProbe210 / local210b.log）：素材为 DIRECT 真链 MP3+
  手工 ID3v2、PROXY 真 FLAC、本地生成 WAV。标签三格式全对
  （MP3 三字段+238728ms、FLAC 238698ms、WAV 6000ms）；曲库入库
  3 首；三格式真播 maxPos MP3 6864 / FLAC 2560 / WAV 6000（整曲），
  FLAC seek 60s → 60341、MP3 暂停 drift=0；.lrc 解析 3 条。应用级
  截图 shot-local.png（曲库页渲染、标签/兜底/时长全对）。

**2. MV 播放（部分完成：数据层与播放窗完成，真 MV 播放未实证）**
- 数据层补 ncmapi/api/mv/MvApi.kt：/mv/detail + /mv/url 类型化封装
  （ApiClient 早有两条 weapi 路由）。坑：brs[].size 接口回浮点，
  模型须 Double，写 Long 会整段解码失败（mvraw2.log 实证修正）。
- 入口：歌曲右键菜单「播放 MV」（song.mv>0）、全屏页顶栏 MV 按钮；
  独立 Window（desktop/ui/MvPlayerWindow.kt）+ JCEF HTML5 video
  面板（desktop/mv/MvBrowserPanel.kt，复用 WebLoginBrowser 的进程级
  CefApp——CEF 进程内只能初始化一次，故加 sharedCefApp() 只增不改）；
  页面每 500ms 经 CefMessageRouter 回报 pos/duration/paused，宿主
  反向控制播放/暂停；产品代码不带 --ignore-certificate-errors。
- 自动播放策略坑（实证）：带声 video 的 autoplay 被 Chromium 策略
  拦截（readyState=4 已缓冲满却卡 0s、静音则可播）→ 共享 CefApp
  构建加 --autoplay-policy=no-user-gesture-required（桌面媒体应用
  惯例开关，只影响内嵌浏览器媒体策略，与证书无关）。加后 WebM
  带声可播满。
- **未实证（卡点如实）**：网易云 MV 均为 H.264 MP4，而本套 CEF
  构建（jcefmaven 132.3.1，沙箱以 Linux natives 实测）在沙箱内
  解不了 H.264——6 支真 MV（最高 1080p）与本地新编的完好
  baseline MP4 全部 error:4（SRC_NOT_SUPPORTED）；同管线播
  WebM/VP9 正常（真 MV 转码 WebM 后带声播满 15s、截帧见真实
  画面 mv-frame.png、进度日志 mvfile-final.log）。预案评估：
  JavaFX MediaView 在沙箱更差（其捆绑 av 插件依赖已被淘汰的
  系统 ffmpeg ABI，播放器都建不出来；Windows 走 Media
  Foundation 理论可行但沙箱无从验证）；VLCJ 需打包 VLC 原生
  库（体积/再分发成本）且同样无法在沙箱验证。结论：本版 MV
  窗口与数据层交付，解码失败时如实报错并提供「用系统浏览器
  打开」兜底（shot-mv-window.png：真应用内 MV 窗、标题经
  MvApi 解析、错误与兜底按钮在位）；**Windows 真机上该 CEF
  构建能否解 H.264 未验证**，待真机回执再定是否换播放器。

**3. LX 引擎（完成）**
- 选型 GraalJS 23.0.5（JDK17 兼容线），5 个 jar 进 desktop/libs
  并入 fat jar：graal-sdk/js/truffle-api/regex/icu4j（未压缩合计
  约 61MB；icu4j 试过剔除——JavaScriptLanguage 初始化直接
  NoClassDefFoundError(com/ibm/icu)，是硬依赖，保留）。
- desktop 版 LxPluginEngine.kt 由 stub 改真引擎（与安卓同 API）：
  一插件一 Graal Context、全求值收敛到每沙盒单线程执行器；沙盒
  隔离 allowIO(NONE)、禁类查找/原生/线程/进程、HostAccess
  EXPLICIT 只暴露 LxHostBridge 五方法；桥 httpRequest 只放行
  http(s)、裸 OkHttp 代发（脚本不能读本地文件）；setTimer 宿主
  定时器回火；LxShim/Manager/Store 全是 app 既有代码零改动，
  设置页导入 UI（LxPluginScreen）本来就有。新增 sourceTag 语义
  不变：PlayerApi 级联选中 LX 时走 LxSourceBridge，quality 映射
  沿用 Manager.mapQuality，结果过既有格式闸门。
- 错误浮出：引擎不穿过 Manager 抛异常（保安卓逐脚本试语义），
  失败记 LxEngineStatus + DesktopLog.lx（logs/lx.log，只记
  method+host+状态码/异常类名，不记全 URL）；DesktopMain 接线
  在 resolver 返回 null 且有新鲜错误时 throw，PlayerApi 写进
  ResolveTrace（实证 trace 出现「LX音源 异常 [脚本名] …」）。
- 踩坑三则（均实证后修）：① Bridge/Sandbox 不能 private——Graal
  按 public 可见性反射成员，private 类方法对 JS 全是 Unknown
  identifier（玉宁熙 init 调 onEvent 即死）；② importScript 只
  体检不落库，confirmImport 才持久化（UI 确认步，探针须照此
  路径）；③ 聚合脚本 allSettled 语义等最慢上游落定，桥读超时
  15s > 解析上限 12s 时永远差一口气——桥 connect/read/write
  改 8s（死源快速让路、活源都在 1-3s 返回；12s 上限与安卓
  一致不动）。另：内置脚本玉宁熙能加载，但其上游从本沙箱
  出口全灭（SocketTimeout），属环境限制非引擎问题。
- 实证（LxProbe210 / lx210g.log）：公开脚本「全豆要」经
  importScript+confirmImport 落库；坏脚本导入放行（与安卓同
  口径）但解析失败且错误浮出；VIP 509781655 直解出真链
  （8.7s），级联真播 maxPos=4704、playing=true、err=null、
  trace「LX音源 成功」。

**4. 一起听（完成至沙箱可验边界，双账号同步未实测）**
- 安卓全套（ListenTogetherSession 状态机 + Models/Api + Sheet）
  本就编译进桌面，缺的只是接线：DesktopMain 在 PlayerController
  初始化后 ListenTogetherSession.attach(桌面播放器, context)；
  桌面面板 desktop/ui/ListenTogetherPanel.kt（建房/粘贴邀请
  加入/房内房号成员数/邀请链接复制/离开，error/notice 直接
  展示会话层状态）；入口：PlayBar 与全屏页顶栏各一个按钮。
- 实证（LtProbe210 / lt210b.log + shot-lt.png）：attach 后
  refresh 真对账；create() 真打官方 API，未登录错误
  「NetEase request failed (code=301)」如实浮出到面板，不假
  成功；面板在真应用内渲染完整。**未实测**：双账号真同步
  （建房→第二账号加入→播放/暂停/切歌/进度同步）需两个真实
  登录账号，沙箱无账号做不到；单实例锁下双进程模拟加入同理
  受阻于登录态（--data-dir 隔离已备，但无账号无法入房）。

**音源回归（不缩水，SourceRegression210 / regression210.log）**
DIRECT AUTO + VIP + EXHIGH 真播 maxPos=2856 playing=true
err=null；暂停 drift=0、恢复续播；PROXY 解灰 + VIP + LOSSLESS
首试冷启动 maxPos=0、次试 maxPos=10069 真播（与 2.0.5 基线
同形态）；EXHIGH 闸门 DIRECT 终链 MP3 非 FLAC ✓、PROXY 终链
null（与 2.0.5 基线一致：解灰回 FLAC 被闸门挡、官方无登录
回 null）。已验通链路语义未动：本地/LX 均为新增源，级联步
行为零改动。

**不在本版范围（待老板拍板，同 2.0.5 口径）**：任务栏缩略图
控制、严格无缝播放、HRIR 卷积；audit2 第二轮体检 17 条待排版。

**未实测清单**：MV 的 H.264 在 Windows 真机解码（见上，沙箱
构建解不了、Windows 构建未验）；一起听双账号真同步；LX 脚本
在 Windows 真机的 GraalJS 性能（沙箱解释模式 eval 147KB 脚本
约 5s，首播建沙盒有一次性等待，warmUp 已预建）；本地 M4A
播放（明确未支持，标签可读）；本地音乐页真机 JFileChooser
选目录（沙箱以预置曲库文件验证渲染与播放链）；JCEF 网页登录
完成态（2.1.0 只给共享 CefApp 加了 autoplay 参数，登录逻辑
未动，但未在真机复验登录全程）。

### 7.16 2.1.1 MV 换播放器（2026-10-24，证据在 desktop/build/run9/）

2.1.0 交付后复核确认：发货包的 `jcef-natives-windows-amd64.jar`
是**标准版** CEF 132.3.1 构建（无 proprietary-codecs 变体），
H.264 只是 profile 名常量、无解码器；网易云 MV 全是 H.264，
故 Windows 真机同样播不了（置信度约 95%，非「待真机确认」）。
老板拍板「B 换播放器再战」，本版只做这一件事：MV 播放器换
VLCJ/libVLC，版本号 2.1.1（VERSION_CODE 47、launch4j 2.1.1.0、
fat jar manifest 同步）。

**选型（四候选，取舍定版）**

| 候选 | H.264 把握 | 集成成本 | 体积/分发 | 结论 |
|---|---|---|---|---|
| (a) proprietary CEF/JCEF | 需自编 CEF（GN `proprietary_codecs=true ffmpeg_branding=Chrome`），jcefmaven 无此变体；官方论坛明言许可证禁止分发含专有编解码的二进制 | 要替换 jcefmaven 的 natives 加载机制 | 自编产物 ~200MB 且分发有许可风险 | ✗ 拿不到现成合法产物 |
| (b) **VLCJ 4.8.2 + libVLC 3.0.21** | libVLC 自带 FFmpeg（avcodec 插件），H.264/AAC 为原生解码，不依赖宿主系统编解码器，把握最大 | EmbeddedMediaPlayerComponent 是 AWT 重量级组件，走现有 SwingPanel 路径，与旧 JCEF 面板同构；事件协议可完全复用 | vlcj 两 jar ~0.5MB + 裁剪后的 Windows 原生包 46MB（见下）；VLC 为 LGPL/GPL，随包附 COPYING.txt | ✅ **定版** |
| (c) JavaFX MediaView | 官方文档列 H.264 支持，但实现依赖平台媒体栈；run8 沙箱探针直接 `Could not create player!`（其捆绑 av 插件依赖已淘汰的系统 ffmpeg ABI） | JFXPanel 嵌 Swing 可行 | 小 | ✗ 沙箱连播放器都建不出来，Windows 表现无从验证，风险不可控 |
| (d) mpv/libmpv | 解码能力与 VLC 同级（FFmpeg） | 无 Maven 中央维护版 Java 绑定，官方 examples 只是 JNA 教学代码；`wid` 嵌窗要自己处理 HWND/事件/属性全套 | libmpv dll 同量级 | ✗ 绑定与嵌窗全自研，成本最高 |

**实现**

- 新增 `desktop/mv/MvVlcPanel.kt`（替代已删除的 `MvBrowserPanel.kt`）：
  EmbeddedMediaPlayerComponent 嵌 JPanel；事件协议与旧面板一致
  （`pos:<秒>:<时长>:<paused>` 500ms 轮询回报 + `ended`/`error:`），
  宿主窗口只换了组件来源。两个实证坑：①libVLC 嵌入式视频面要求
  组件 **displayable 后**才能起播（`IllegalStateException: video
  surface component must be displayable`），SwingPanel factory 时机
  不满足，故起播推迟到轮询确认 `isDisplayable` 后（上限 15s）；
  ②本地文件给 libVLC 传 Java `File.toURI` 的单斜杠 `file:/` 形式
  会被误判相对路径——产品只播 http 直链不受影响，探针侧改传裸路径。
- 原生库定位：发货包 exe 同级 `vlc\`（libvlc.dll+libvlccore.dll+
  plugins 子集）。运行时按 `penguin.vlc.dir` 系统属性 → 工作目录
  `vlc\` → jar 所在目录顺序找 libvlc，找到则 JNA `addSearchPath`
  注册 + `--plugin-path` 指定插件目录（不靠改进程 env）；找不到
  回落 VLCJ NativeDiscovery（系统装了 VLC 的机器/Linux 沙箱）。
- `MvPlayerWindow.kt` 入口与兜底保留：MvApi 解析、如实报错、
  「用系统浏览器打开」不变；进度条由只读 LinearProgressIndicator
  改为可拖动 Slider（VLC 组件无 HTML5 自带控制条，补回 seek 入口）。
- 依赖：desktop/libs 新增 vlcj-4.8.2.jar、vlcj-natives-4.8.1.jar、
  jna-platform-5.14.0.jar（与既有 jna-5.14.0 配对，vlcj 的全屏策略
  需要 com.sun.jna.platform.*）；JCEF 保留不动（网页登录仍用）。
- Windows 原生包裁剪脚本 `desktop/tools/stage_vlc_bundle.sh`
  （源 = VideoLAN 官方 vlc-3.0.21-win64.zip，暂存于
  desktop/vlc-bundle/）：官方全插件 134MB，只留 file/http(s)
  access、MP4 等少数 demux、avcodec 为核心的 codec（剔除
  x264/x265/vpx/aom 等编码器与冗余解码器——avcodec 本身已含
  H.264/AAC/VP9 解码）、packetizer、Windows 音频输出
  （wasapi/mmdevice/directsound）、视频输出（direct3d11/9、
  glwin32、wingdi 等）、chroma/filter/mixer 播放链、gnutls
  （https）与 xml misc。结果 216 文件 / 46MB；无 plugins.dat
  缓存（Windows 首开由 libVLC 自行扫描生成）。

**实证（run9/，全部在 Linux 沙箱 + 系统 libVLC 3.0.20 下）**

- VLCJ 裸探针（VlcProbe211）：干净 H.264 High+AAC 45s 文件
  （ffmpeg 生成）——时长 45000ms 精确、截帧非黑比 0.879、
  暂停 drift=0、续播推进、seek 30s 落点 31570ms（vlc-test-h264.log）；
  VP9 WebM 同案全绿（vlc-webm.log）；真 MV 内容重编码的 baseline
  件（7.76s）播满并正确 finished（vlc-baseline.log）。
- run8 的 `mv-sample.mp4`（真 MV 原文件）在 VLC 下 5.65s 停滞的
  疑案已定性：ffmpeg 全文件解码报 `Invalid NAL unit size` ——
  **文件本身损坏**（run8 下载不完整），非解码器缺陷；同一文件的
  完好头部重编码件可播满（见上 baseline 案）。
- 产品路径探针（MvVlcProbe211，走真 MvVlcPanel）：本地 H.264
  maxPos=10.15、dur=45.0、非黑 0.879、error=null
  （product-file.log）；**真 MV http 流**（mvId 4909，MvApi 现取
  直链）maxPos=9.4、dur=542.552、error=null，截帧
  product-frame-mv.png 眼见为实（网易娱乐水印与节目画面真实
  渲染，非黑屏假播）。
- 音源回归（SourceRegression211，与 run8 同法 +NullMixer）：
  DIRECT AUTO VIP EXHIGH 真播 maxPos=6456、暂停 drift=0、续播
  推进；PROXY LOSSLESS 首试 maxPos=3157 真播；EXHIGH 闸门
  DIRECT=MP3 / PROXY=null 与 2.1.0 基线逐项一致；
  SUMMARY direct=true proxy=true（regression211.log）。**未缩水**。
  （注：无 NullMixer 时沙箱无声卡会报 `No line matching
  SourceDataLine`，是环境问题非回归——首跑实测踩过并已用回
  run8 同口径复跑。）
- 交付物自验：fat jar 141MB / 49343 条目（含 vlcj 与
  jna-platform 类、MvVlcPanel，旧 MvBrowserPanel 零条目）；
  exe 内嵌 UTF-16 版本串 2.1.1×2、2.1.0/2.0.5×0；fat jar 本体
  Xvfb 冒烟 50s 无未捕获异常（smoke211.log，唯一 Exception 是
  Xvfb 下已知的 Skiko GL 回退警告，与历版基线一致）。
- 产物（只放本地 dist，未上传）：dist/PenguinMusic-Windows.zip
  与 dist/PenguinMusic-Windows-2.1.1.zip 同一文件，
  **350,988,759 字节，MD5 `e300d8d6502ac204d67c054578985dd9`**，
  unzip -t 无错误；较 2.1.0（327,683,744）净增约 23MB 压缩量
  （46MB VLC 原生包 + 约 2MB jar）。dist 留 2.1.0 备份 zip 不动。

**未实测清单（如实）**：Windows 真机 MV 播放全链——随包
libVLC 3.0.21 的 Direct3D 视频输出、裁剪插件集在 Windows 上
的完整性、JNA 从 `vlc\` 目录加载 dll、SmartScreen/杀软对新增
dll 的反应，沙箱一概验不了，必须老板真机点一支 MV 确认；
沙箱验证的是同一 libVLC 解码链在 Linux 构建（3.0.20）上的
H.264 真解码与产品代码路径，不拿它冒充 Windows 结果。
另：MV 窗内无字幕/音量控制（与 2.1.0 形态一致，音量走系统）、
一起听双账号等 2.1.0 累积未实测项不变。

### 7.17 2.1.2 B 档全修（2026-10-25，证据在 desktop/build/run10/）

第三轮体检（audit3）老板拍板 B 档：存量 bug 全修 + 新发现 bug 全修 +
两个小功能（A3-07 全屏页歌曲评论、A3-09 的 MV 全屏/音量）；发布后
老板追加「在线更新整链必达验收」，与 A3-06 合并处理。版本号统一
2.1.2（VERSION_CODE 48、launch4j 2.1.2.0、fat jar manifest 同步）。
交付物 MD5 `e67abb4b4345282f64e77acae404016e`（351,066,994 字节），
双文件名已部署至服务器 /opt/directplay/www-dl/（两端 MD5 一致、
双链接 curl -I 200、2.1.1 备份 zip 保留），penguin-version.json 已
更新 2.1.2 并新增 md5 字段，线上读回确认。

**存量 bug（audit2 编号）**
1. #1 FLAC 内存守卫（高）：新增 FlacPcmSink——PCM 先内存累积、超
   32MB 溢写临时文件、播放双载体流式读，消除 toByteArray 双份复制；
   playFlacPcm 与 runDecodeSession 捕获 OutOfMemoryError/全体 Error
   并包成 DecodeFailedException 走既有跳歌/熔断语义。本地 FLAC 同
   路径受益。探针 FlacProbe212（flac212.log）：300s FLAC（PCM
   52,920,000 字节）落盘文件恰为 52,920,000 字节、播放推进、
   pause drift=0、seek 240s 落点 242786、release 后临时文件清零。
2. #2 网页登录轮询复活：fired 捕获后即复位 + pollingActive 防重入
   + MUSIC_U 值去重，同一 Session 可再次 startPolling。
3. #3 退出清 JCEF Cookie：WebLoginBrowser.clearCookies()
   （deleteCookies("","") + flushStore），顶栏与设置页两处退出均调用。
4. #4 SessionState.refresh 三路分明：成功且 userId>0 已登录 / 成功
   但 userId<=0 才置 expired / 请求失败保持原状态只记日志。
5. #5 空格防误触补登记三处：歌单筛选框、设置页自定义服务器框、
   评论框（PlayerComments 走 build.sh desktopify 改写 #3，构建日志
   已确认注入、幂等与锚点警告实测过）。
6. #6 FLAC PCM 缓存：LRU（≤2 首、内存 ≤64MB、落盘 ≤512MB），键与
   resolvedUrls 同口径（mediaId|音质|sourceTag|URL；本地用路径+
   长度+mtime），seek/单曲循环命中短路不重解码/重下载。flac212.log
   实证：seek 与回绕后落盘文件始终 1 个、回绕续播成功。
7. #7 大歌单截断（先验证再决定→坐实必修）：实证歌单 8482341734
   共 10933 首，detail 只回 tracks=10、trackIds 全量 10933 但模型
   无此字段被丢弃（bigplaylist7b.log）。修：PlaylistDetail 新增
   trackIds 承接，cachedPlaylistDetail 按 trackIds 分批（1000/批、
   顺序）调 /song/detail 补齐并按原序拼装、补齐版进缓存；单批失败
   降级部分完整、全部失败回原始 detail。复跑实证（fill212.log）：
   filled=10877/10933（56 首源端不可用跳过），小歌单 200/200。
8. #8 内容页三态：排行榜/歌单广场/云盘改 Result 驱动三态（失败
   error 色文案+重试、真空独立文案）；搜索 VM 新增 searchError +
   retrySearch，失败不清旧结果、无结果时显示失败态可重试；扫码
   轮询连续失败 5 次停轮询并出 Failed 态（既有重试按钮复活）。
9. #11 LyricRepository：失败不写缓存（成功无歌词仍可缓存空结果），
   缓存改锁保护 accessOrder LinkedHashMap、上限 200。
10. #12 FullPlayer 切歌先清 entries 再赋新值。
11. #13 全屏歌词滚动守卫：isScrollInProgress + 停手 3 秒宽限，
    程序滚动与手动滚动以标志区分，不抢滚。
12. #14 JCEF cache_path=<dataDir>/jcef-cache；CefApp 建成时注册
    JVM shutdown hook 调 dispose，并提供公开 shutdown()。
13. #15 超时显式化：主 Ktor 客户端 HttpTimeout（15s/30s/30s）+
    engine OkHttp 同口径；上传 OkHttpClient 15s/60s/60s；NCBL
    上报客户端同类收口（10s/20s）。2.0.5 的 Logging 过滤未回退。
14. #16 脱敏收口：AuthDiagnostics.sanitize 键集合扩到 23 个
    （大小写不敏感、四种文本形态、Bearer/Basic 凭证）；ApiClient
    三处全量打印（postNosBytes/apiPostFile/apiPostFileOkHttp）改只
    记状态/路径/字节数，URL 只留 host+path；DesktopMain 启动
    deviceId 改脱敏打印（冒烟日志实证 `deviceId=755C…2DA8 (len=52)`，
    对照 2.1.1 为明文全串）。

**新发现（audit3 编号）**
- A3-01 MV 换片：DesktopMain 调用点加 key(req.mvId) 整窗重建 +
  MvPlayerWindow 内部 LaunchedEffect 开头重置全部状态双保险。
- A3-02 开 MV 暂停主播放：DesktopUiState 新增 pauseMainPlayerAction
  注册点（沿 backAction 模式），MainScreen 注册、openMv 时在播才
  pause；关 MV 不自动恢复（保持暂停，刻意取舍）。
- A3-03 MV 错误人话化：error 码映射中文（引擎初始化失败/播放
  失败+排查指引），原码只进日志，浏览器兜底按钮保留。
- A3-05 libVLC 失败可诊断：定位结果带逐候选摘要（缺哪个文件、
  找过哪些路径），初始化失败文案带异常类名+简要信息与杀软/
  解压完整性提示，经 Handle.lastFailureDetail 传宿主展示。
- A3-06 + 在线更新整链（老板追加必达）：downloadApk 下载后强制
  三关校验——read==total（total>0 时）、zip 全条目可读（CRC）、
  version.json 的 md5（本版起发布带 md5）；任一不过删文件抛错、
  绝不发 done=true。完成提示补强：UpdateDownloadService 成功
  Toast 改明确引导文案（解压覆盖+重启）、失败 Toast 带重试入口
  指引；设置页手动检查更新区分失败/无更新（不再把失败说成
  「已是最新版本」）。整链实证（update212.log、check212-*.log、
  realdownload212.log）：小包五案（完整/对 MD5 放行；错 MD5、
  截断内容、非 zip 全拦截删文件）；2.1.1 发货代码对线上 json
  检出 FOUND 2.1.2、新包代码对线上 json 回 null；新 downloadApk
  真下载线上 351MB 整包 87 秒、MD5 一致、done 在校验后发出。
  未能演练的一格：服务端谎报 Content-Length 的 read!=total 分支
  （沙箱 Java 环回连接被网络层 reset、生产机高端口被防火墙挡，
  无处架设说谎服务器）——该分支与已实证的 zip/MD5 分支共用同一
  抛错删文件路径，代码级确认。
- A3-07 全屏页歌曲评论：右侧加「歌词/评论」页签，评论复用
  PlayerComments（commentType=0 歌曲、mediaId 随当前歌）。注：
  PlayerComments 本体是 ModalBottomSheet，呈现为点页签打开弹层
  （与桌面歌单评论同款），真内嵌需另拆组件，未在本版做。
- A3-09 半：MV 控制条加全屏按钮（WindowPlacement.Fullscreen）与
  音量滑杆（vlcj 0–100 标称段、静音切换），音量存进程级单例，
  换片/重开窗不重置、与主播放器音量独立。清晰度切换未做（C 档）。

**回归（未缩水）**：SourceRegression212（regression212.log）与
2.1.1 基线逐项一致——DIRECT VIP EXHIGH 真播 maxPos=6960、pause
drift=0、resume 推进；PROXY LOSSLESS try2 真播 maxPos=9728
（走的正是新 FLAC 管线）；EXHIGH 闸门 DIRECT=MP3/PROXY=null。
MV 产品探针（product-mv212.log + product-frame-mv.png 眼见为实）：
真 MV（4909）maxPos=9.406、dur=542.552、error=null。fat jar 本体
Xvfb 冒烟（smoke212.log）：零未捕获异常（唯一 Skiko GL 回退警告
与历版基线一致）、API 200 在流。

**不在本版（留待 C 档）**：#9 系统代理、#10 列表分页、#17 恢复
默认设置、A3-04 VLC bundle 补 adaptive/mkv 插件、A3-08 倍速、
A3-09 清晰度切换、老待拍板 3 项（任务栏缩略图、严格无缝、HRIR）。

**待真机验（如实）**：MV 换第二支的窗口闪烁程度与全屏下 VLC
Canvas 铺满/恢复尺寸、换片后音量延续；JCEF 退出清 Cookie 后重开
网页登录应为未登录态、退出 shutdown hook 时序与进程残留；A3-02
开 MV 暂停的真机手感；应用内更新在真机点「下载更新→按引导解压
覆盖重启」的完整点击链（沙箱已验到下载+校验+检出三段，Windows
的 Toast/打开文件夹/覆盖重启一段验不了）；#2 网页登录二次登录
全程（卡登录态）。

### 7.18 2.1.3 B 档全修 + 打赏 + JCEF 硬化（2026-10-25，证据在 desktop/build/run11/）

老板拍板 audit4 的 B 档（A4-01/02/03/04/05/06/07/08/10/11/12，
不含 A4-09 断点续传与 F3 预读），施工中追加两件：设置-关于新增
打赏入口（老板拍板）、JCEF 强制软件渲染（真机事故硬化，见下）。
版本号 2.1.2→2.1.3、VERSION_CODE 48→49（三处：BuildConfig、
make_fatjar 清单、launch4j xml）。

**逐条销账**：
- A4-01（必修）错误停留 play() 死锁：`DesktopPlayer.play()` 在
  ENDED 或（error!=null 且会话未运行）时强制重启当前曲会话；
  `onSessionError` 停留分支同步做位置归一（重试从出错点续播）、
  原因落盘、常驻错误，并把 playWhenReady 置 false 使状态自洽。
  PlayLockProbe213 Part B 实证：取链失败停留 pwr=false+常驻错误在，
  play() 后 6 秒内会话被重启（再走一轮取链）。反转 audit4 的
  PlayLockProbe212 结论。
- A4-02（必修）ENDED 后按播放从头重播：与 A4-01 同改动（play()
  对 ENDED 先归零再 startCurrentLocked）。探针 Part A 实证：
  20 秒本地 MP3 播完 ENDED 后 play() 恢复播放且位置从头。
  泊车回归 Part C：本地曲暂停位置冻结 drift=0、恢复原位续播。
- A4-03 错误常驻提示 + 暂停原因：新增 `PlaybackStatus`
  （StateFlow 常驻错误）与 `PauseDiagnostics`（原因枚举：用户/
  定时/媒体键/开 MV/一起听/设备/网络/取链/解码/EOF 假结束/熔断，
  追加写 `<dataDir>/logs/pauses.log`）；PlayBar 上方错误条含
  「重试」「跳下一首」与关闭，Xvfb 实拍（stickybar-213.png：
  取链失败停留在真实 UI 触发）。睡眠定时/开 MV/一起听/媒体键
  STOP 的暂停调用点已逐一登记原因，缺省记 USER。
- A4-04 音效入口：`EqDialog` 改 internal，FullPlayer 顶栏与
  PlayBar 各加 GraphicEq 按钮打开同一对话框；设置行与对话框
  标题统一改「音效（均衡器）」、设置行摘要带「· 混响开」后缀。
  三处入口均 Xvfb 实拍（eq-dialog-213.png / playbar-eq-213.png /
  fullplayer-eq-213.png）。
- A4-05 混响：安卓 `ReverbAudioProcessor`（Schroeder 8 梳状+4
  全通）忠实移植为桌面 `playback/audio/Reverb.kt`，插入点与 EQ
  相同（三条 PCM 管线、pcmEffects 之前），关时逐样本直通零开销；
  EqSettings 新增 `eqReverbEnabled`/`eqReverbIntensity` 键与
  reverbRevision，EqDialog 加开关+强度滑杆。EqProbe213 量化：
  OFF 与输入逐样本全等；ON 强度 0.5/1.0 的 300ms 尾窗 RMS
  0.0089/0.0384、随时间单调衰减、随强度增大。隔离处置：桌面桩
  `AudioEffectMode` 与安卓遗留 audioEffect* 键明确标注「桌面引擎
  不读不写、勿接活」，桌面音效唯一权威源是 EqSettings。
- A4-06 EQ 拖动分段：新增 `EqSettings.previewApply()`（只更新
  内存、引擎系数热更新不清滤波状态——EqProcessor.configure 同
  采样率/声道只换系数，日志带 `(coeff-only)` 后缀，探针已旁证），
  滑杆 onValueChange 走预览、onValueChangeFinished 才 apply+落盘；
  混响强度滑杆同理。顺带修复：`setReverbIntensity` 与属性 setter
  JVM 签名冲突，改名 `updateReverbIntensity`。
- A4-07 FLAC 溢写孤儿文件：FlacPcmSink 临时文件统一前缀
  `penguin-flac-pcm-`，进程启动扫 tmpdir 清本前缀旧进程残留；
  删除失败（Windows 文件被占用）时 deleteOnExit+登记待删集，
  下次缓存操作前重试删除。Windows 占用语义沙箱验不了，代码级
  推断（Linux 靠 fd 续读、删名即回收）。
- A4-08 大歌单补拉缓存：`PlaylistApi` 缓存改存 TTL 三元组，
  fillMissingTracks 返回 complete 标记——完整版 TTL 5 分钟、
  部分版（有批次失败/被截断）短 TTL 30 秒，不再被当完整版长
  缓存；补拉进度经 `fillProgress` StateFlow 在桌面歌单页加载
  转圈下显示「正在补齐曲目 X/Y 首」。
- A4-10 空格拦截统一：根 Surface 改 onKeyEvent 气泡阶段处理
  空格（焦点在任何已登记可编辑控件时不触发播放/暂停），取代
  onPreviewKeyEvent 的抢先拦截；焦点登记机制保留作第二道防线。
- A4-11 长 FLAC 缓存键：键改 `resolveCacheKey|host+文件名指纹`
  （去签名查询串与时间戳路径段），seek/单曲循环跨 20 分钟 TTL
  换签不再整曲重下。
- A4-12 Toast 链路文案去重：`ResolveTrace.summary()` 折叠相邻
  重复步（UI 实拍 Toast 与探针日志均确认无重复段）。
- 打赏（追加）：设置-关于加「请作者喝杯咖啡 ☕」行（SettingNavRow
  同款），点击弹 DonateDialog——赞赏码整卡按原比例 Fit 显示
  （不裁切不变形）、文案+「关闭」按钮。图片为老板微信赞赏码原图，
  字节级原样打进包：`desktop/src/main/resources/donate.jpg`，
  make_fatjar.py 新增 resources 段打到 jar 根，运行时经
  classloader 加载、无外链。Xvfb 实拍两张（donate-entry-213.png /
  donate-dialog-213.png）。
- JCEF 强制软件渲染（追加硬化）：背景——2026-10-25 约 12:03
  老板真机跑 2.1.2 时整机假死、被迫断电重启；其机器装有向日葵
  虚拟显卡驱动（OrayIddDriver），PenguinMusic 的 chrome_debug.log
  有 JCEF/Chromium 的 GPU 适配器错误（gpu_blocklist "Unable to
  get gpu adapter"、on_device_model service disconnect）。定性：
  应用层锁不死系统，JCEF 的 GPU 硬件加速与虚拟显卡驱动相撞是
  最大嫌疑触发点。修复：`weblogin/WebLoginBrowser.kt`
  ensureCefApp() 在 CefAppBuilder 上硬编码追加
  `--disable-gpu`、`--disable-gpu-compositing`、
  `--use-angle=swiftshader`，JCEF 纯软件渲染（本应用 JCEF 只用于
  网页登录，不需要 GPU，性能无损）。该段为强制项，不受应用内任何
  「硬件加速」类开关影响（那类开关只管 Compose/Skiko，与 CEF 两
  条线）。发货 jar 类字节已验三开关在位。注：cefapp 是登录与 MV
  旧路径共享的同一实例（MV 现走 VLC 不经 CEF），此改动覆盖全部
  CEF 用途。

**回归（未缩水）**：EqProbe213——EQ off 比 1.00、极端增益
lo/hi=50.21、低音预设 1.87、8D 声像旋转窗口，与 audit4 基线逐项
一致。SourceRegression213 与 2.1.2 基线逐项一致（SUMMARY
direct=true proxy=true、pause drift=0、resume 推进、EXHIGH 闸门
DIRECT=MP3/PROXY=null）。MV 产品探针（product-mv213.log +
product-frame-mv.png）：真 MV（4909）maxPos=9.15、dur=542.552、
error=null。更新整链：2.1.2 发货代码检出 FOUND 2.1.3、新代码
回 null（check-probe213.log）；新代码用 downloadApk 真下载线上
整包 351,186,934 字节、MD5 校验通过（realdownload213.log，
13 秒）。小包五案（截断/坏 MD5/非 zip 拦截）本版未复跑：沙箱
运行时重启后 Java 环回连接被网络层 reset（curl/python 正常、
Java 双栈均 reset，NetTest 存 run11/nettest/），本地说谎服务器
架不起来；更新模块本版未动源码（与 app-212.jar 类对比：下载
主逻辑类字节相同，差异类仅 BuildConfig 版本常量内联所致），
五案沿用 run10 对同一逻辑的实证（FULL_NOMD5/GOODMD5 成功、
BADMD5/TRUNCATED/NOTZIP 拦截删文件）。

**交付数据**：dist 双 zip MD5 均为 e0377e724eb8dccb2a44273420324516、
351,186,934 字节；服务器 /opt/directplay/www-dl/ 同 MD5、双链接
200、Content-Length 一致；exe 内嵌版本串 2.1.3×2、旧串 0；
penguin-version.json 已更新 2.1.3 带 md5（旧版备份
penguin-version-212.json.bak，2.1.2 版本名 zip 保留在服务器）。

**不在本版**：A4-09 断点续传、F3 预读缓冲（C 档）；audit3 的
C 档大件（系统代理/分页/恢复默认/VLC 插件/倍速/MV 清晰度）。

**待真机验（如实）**：①JCEF 软件渲染后网页登录在向日葵虚拟
显卡环境下不再触发假死（本版硬化的主目标，只能真机长时验证）；
②从 2.1.2 点更新→下载→解压覆盖重启最后一段；③A4-07 Windows
下删已打开 PCM 文件的延迟删除实际效果（沙箱只能代码推断）；
④混响/EQ 在真实声卡上的听感与咔哒消除效果（探针是数值级）；
⑤MV 换片/全屏手感、退登清 Cookie 后未登录态、网页登录二次登录
全程（历版遗留待验面不变）。

### 7.19 2.1.4 MP3 尾部「Bitstream errorcode 102」误报修复（2026-10-25，证据在 desktop/build/run12/）

老板真机反馈（附截图）：《阴天》播到 3:56/4:02 时常驻错误条弹
「播放出错，网络或音源异常 / Bitstream errorcode 102」，且「老是」
出现。主会话查码定性后老板拍板三条修法，本节销账。版本号
2.1.3→2.1.4、VERSION_CODE 49→50（三处：BuildConfig、
make_fatjar 清单、launch4j xml）。

**根因（JLayer 1.0.1 源码核对，run12 备有源码摘录背景）**：
errorcode 是十六进制 0x102 = BitstreamErrors.STREAM_ERROR。查
javazoom 源码：STREAM_ERROR 只由底层流 IOException 触发
（readBytes/readFully 包裹 IOException）；纯文件内容损坏（尾部
标签、坏帧）JLayer 只会重同步或干净 EOF（JlProbe 实测：short20
原文件、尾部加 128B 伪 ID3v1+300B 随机字节、中段抹零 30% 三个
文件全部 CLEAN_EOF）。即真机现场是 CDN 在尾部断流/复位撞上
JLayer 读帧；又因 BitstreamException 未在错误映射里单列，落
else 被误报成网络异常。此定性修正了「文件尾巴论」的机制细节，
但不改三条修法的适用性（尾部/中途判据按位置，与触发源无关）。

**三条修复（全部落 DesktopPlayer.kt）**：
- ①尾部容错：decodeLoop 的 decodeFrame（约 2021 行）与
  readFrame（约 2086 行）两处捕获 BitstreamException，
  isMp3TailError（2137 行）判定为尾部则吞掉、跳出循环走既有
  自然结束路径（onTrackEnded，自动切歌/单曲循环语义不变），
  并把 durationMs 吸附到实际播完位置（2112 行），使队列尾诊断
  记 TRACK_END 而非 EOF_PREMATURE，不落 ERROR。判据：错误码
  ∈ {STREAM_ERROR 0x102, UNEXPECTED_EOF 0x103, STREAM_EOF
  0x104}，且（时长已知时）剩余 ≤10s（MP3_TAIL_TOLERANCE_MS）；
  时长未知时须异常发生在 readFrame 阶段且已解码 ≥100 帧
  （≈2.6s，MP3_TAIL_MIN_FRAMES）。位置按已解码帧计，底层预读
  只会让感知位置更靠前，判据偏保守。
- ②中途自动续播一次：runDecodeSession 在线分支（880–914 行）：
  decodeStream 逃逸出的 BitstreamException（尾部已被①吞掉，
  剩下的都是中途断流）先按出错点归一位置（seekBaseMs=
  currentPosition、framesPlayed=0，与 2.1.3 停留分支同语义）、
  绕直链缓存重取一次新链（断流常见因是旧签名链失效）、同 gen
  重启 decodeStream，并 Toast「播放中断，正在从出错点自动
  续播」。同一条目只许一次：标记 bitstreamRetryItemId（193 行）
  在切歌（seekInternal 跨条目，691 行）或成功解出首帧
  （decodeLoop 建线缆处，与 consecutiveFailures 同落点，
  2053 行）时清零。续播再失败才落既有错误处理。
- ③归类修正：runDecodeSession 两个 `when (failure)`（本地
  801 行、在线 969 行）均新增 `is BitstreamException ->
  ERROR_CODE_DECODER_INIT_FAILED`：标题走「本曲解码失败，播放
  已停止」、PauseDiagnostics 归 DECODE_ERROR，detail 的 msg
  保留原文（含 errorcode）；retryableAdvance 不含它（续播已试
  过），再失败直接停留报错。本地文件不走②（损坏是确定性的，
  重连无意义），只受益③的归类。

**探针（TailProbe214，run12/tail-probe214.log，四案全过）**：
沙箱 Java 连本地 HTTP 服务器仍被网络层 reset（本轮 Fetch 实测
复现，含 LAN IP），故用 URLStreamHandlerFactory 在进程内拦截
http、以「在指定绝对字节处起抛 IOException 的流 + Range/206
应答」喂在线解码路径——即 STREAM_ERROR 的等价复现；音频输出
走 ALSA null 软声卡（当前运行时无 /dev/snd，asound-null.conf
在 run12，节拍≈3–4×实时，只影响时序类断言，见回归节）。
- T（96% 处断流）：ENDED、pos=dur=18938（时长吸附生效）、
  err=null、无常驻条、请求数=1（未触发续播）、诊断 TRACK_END。
- M1（40% 处断流、仅首请求）：自动续播生效——第 2 次请求带
  Range bytes=122452-（出错点 7653ms 的归一位置）、续播 Toast
  出现、播满 20061/20064 自然结束、无错误。
- M2（40% 处两次都断）：恰好 2 次请求后停留报错，code=4001
  （DECODER_INIT_FAILED）、msg 保留「Bitstream errorcode
  102」、常驻条标题「本曲解码失败，播放已停止」、诊断
  DECODE_ERROR、playWhenReady=false。
- L（本地尾垃圾文件）：ENDED、无错误（2.1.3 本已如此，防回归）。

**回归**：EqProbe214 与 2.1.3 基线逐项一致（off 比 1.00、极端
lo/hi=50.21、低音预设 1.87、混响尾 300ms 0.00889/0.03840）。
PlayLockProbe213：B 案（取链失败停留+重试）原样通过；A/C 案的
实时时序断言在软声卡快节拍下赛跑误报（A：重播 0.85s 内整曲冲
完、采样抓不到小位置；C：长曲未及暂停已播完），产品行为由
PauseDiag 双 TRACK_END 时间戳与 Lock214Mini 旁证（A' 重播二次
自然结束、C' 长曲 pausePos=27324/drift=0/原位续播通过）。
SourceRegression214：SUMMARY direct=true proxy=true、pause
drift=0、闸门 DIRECT=MP3/PROXY=null 与基线一致；RESUME 一项
advanced=false——快节拍下 DIRECT 案 15s 观察窗内已播满全曲
（maxPos=238752），无可推进，属同一节拍伪影（续播语义由
Lock214Mini C' 覆盖）。更新链：CheckProbe214 本地 2.1.4/code
50、线上（仍 2.1.3）回 null 正确；RealDownload214 用新代码真
下载线上整包 351,186,934 字节 MD5 校验通过（12s）。未跑：
UpdateProbe 小包五案（依赖 Java 环回，仍被 reset；更新模块本
版未动源码，下载+校验已由 RealDownload 兜底）；MvVlcProbe
（当前沙箱缺 libXtst.so.6，AWT 初始化失败；MV 取链本身正常、
MV 代码本版未动）。

**交付数据（本地，未上传——上传由主会话复核后执行）**：
dist 双 zip MD5 均为 0b6afafd4663edd24f0dff61fb3432e1、
351,187,568 字节；exe 内嵌版本串 2.1.4×2、旧串 0；fat jar
清单 Implementation-Version 2.1.4、BuildConfig 类字节
VERSION_CODE=50/VERSION_NAME=2.1.4 已验；run12/
penguin-version.json 已备（2.1.4 带 md5），线上未动。

**待真机验（如实）**：①《阴天》等原问题歌曲复播，3:56 处不再
弹错、自动切下一首；②真实网络中途断流时自动续播的实际衔接
（有无可闻间断、位置误差约 1s 的可接受度）；③真实声卡节拍
下 PlayLock A/C 原案（沙箱节拍问题与真机无关）。

### 7.20 2.2.0 audit5 全修 16 条 + 历轮搁置大件 8 项 + 尾部策略升级（2026-10-25，证据在 desktop/build/run13/）

老板拍板「全部都修」：audit5 的 A5-01…16、历轮搁置的 L1–L8、
外加老板追加的 A5-17（2.1.4 尾部策略升级），共 25 项。
VERSION_CODE 51 / VERSION_NAME 2.2.0。逐项落点与探针：

**A 档（audit5）**

- A5-01 FLAC 解码期 IOException 与格式错误分开：
  DesktopPlayer.playFlacPcm（desktop/.../playback/DesktopPlayer.kt:1660）
  在通用 Exception 之前先捕 IOException → 扔掉半成品 PCM 后原样
  上抛（runDecodeSession 归网络类），解码器自身异常才走
  UnsupportedFormatException（原误降级音质路径）。FlacProbe220
  F1：流 40% 处注错 → errorCode=2001、常驻条「播放出错，网络或
  音源异常」、诊断 NETWORK_ERROR、无「降为高音质」Toast ✔。
- A5-02 列表 key 与播放起点：SongTable.kt:95 的 itemsIndexed
  key 改 `"${s.id}@$index"` 复合键（同曲重复不再崩）；桌面版
  PlaylistScreen.kt:149 playAt 直接用 indexInShown（coerceIn
  钳制），删 indexOfFirst 反查（同曲重复时起点错位）。MiscProbe
  K 段：复合键唯一性 + 源码落点核查 ✔。
- A5-03 MP3 中段坏帧容错：decodeLoop 的 decodeFrame 捕获
  DecoderException 与解码期 RuntimeException 统一跳帧（常量
  DesktopPlayer.kt:1130 MP3_BAD_FRAME_MAX_STREAK=5，连续达上限
  抛 DecodeFailedException 归 DECODER_INIT_FAILED）。注：run13
  JlCorrupt 实验先行证明 JLayer 对帧体损坏常抛的是
  ArrayIndexOutOfBounds 等 RuntimeException（不止
  DecoderException），故捕获面扩到 Exception（Error 不捕）。
  CorruptProbe220：单帧坏体跳过后播满 ENDED 无错；连续 6 帧坏体
  → 4001 + 常驻条「本曲解码失败，播放已停止」+ DECODE_ERROR ✔。
- A5-04 FLAC 解码可中断：playFlacPcm 的 PCMProcessor 每帧先查
  代次（DesktopPlayer.kt:1625），切歌即抛
  FlacDecodeAbortedException（:1176）穿透 jFLAC、弃半成品静默
  返回。FlacProbe220 F2：慢速 FLAC 解码中切歌，旧流读计数在
  切换后冻住（24576 字节不再增长）、新曲正常播 ✔。
- A5-05 PCM 磁盘缓存淘汰：CachedFlacPcm 加
  sessionOnly（diskBytes > 512MB，DesktopPlayer.kt:1761）；
  putPcmCache 入缓存后淘汰其它 sessionOnly 条目（:1934），切歌
  跨条目时 evictSessionOnlyPcmCache() 显式清。MiscProbe C 段
  反射验：600MB 伪造条目被后来者淘汰且文件删、显式 evict 同效 ✔。
- A5-06 流式 WAV：decodeWavFile 扫描时 data size ≤0 或越出
  文件 → dataSize=-1（DesktopPlayer.kt:1419），按文件剩余字节
  兜底算 totalFrames。WavProbe220：size=0 与 size=0xFFFFFFFF
  两文件均播满 2000ms ENDED ✔。
- A5-07 VBR seek 落点：LocalTags 新增 parseXing +
  xingByteOffset(file, targetMs)（LocalTags.kt:185，TOC 线性
  插值），decodeMp3Local 的 toSkip 优先用它、无 Xing/TOC 回落
  平均码率估算。XingProbe220：vbr.mp3（前噪后静）15s 落点字节
  占比 0.867、单调；播放器 seekTo(15000) 后 827ms 自然播完
  （旧估算落点约真 10s、需约 2.4s+）✔。
- A5-08 EqSettings.load 钳制：gains 逐值 sanitizeGain（非有限
  →0、钳 ±12，Eq.kt:140/150），reverbIntensity 非有限 →0.5、
  有限钳 0..1。MiscProbe E 段：脏数据
  "NaN,999,-999,Infinity,…" + NaN 混响 → 载入后精确等于
  [0,12,-12,0,1.5,-1.5,0,0,0,12] / 0.5 ✔。
- A5-09 DataStore 自愈：DataStoreUtils.kt:40
  createWithPath 加 ReplaceFileCorruptionHandler（损坏回空
  Preferences + println 留痕）。StoreProbe220（独立 JVM）：预置
  300 字节垃圾 → data.first() 不抛、回空 ✔。
- A5-10 pauses.log 轮转：DesktopLog.pauses()（DesktopLog.kt:65）
  走既有 1MB 轮转 append；PauseDiagnostics.record 改调它。
  TailProbe220 的 pauses.log 输出实证含 TAIL_RESUME_OK /
  TAIL_RESUME_FALLBACK 新行 ✔。
- A5-11 网页登录校验失败留重试：WebLoginBrowser.kt:182
  resetCaptureDedup()（清 Session.lastFiredMusicU）；
  LoginScreen.WebLoginTab 留存归一化 cookie、校验抽成
  validate()、失败调 resetCaptureDedup()（2.5s 轮询自动重报）
  + 错误行「重新校验」钮用留存 cookie 重跑。代码级核查（UI
  类，无探针）；待真机验见下。
- A5-12 数据面 -462 风控：ncmapi AuthDiagnostics.kt:88 顶层
  RiskControlException（数据面专用文案；AuthApi 内嵌的同名类
  是登录文案版，文件内无限定名优先、共存不冲突）；ApiClient.kt:215
  checkDataRiskControl 解析顶层 code==-462 时先脱敏留痕再抛，
  apiGet 在 decode 前调用（登录族走另一入口不双重处理）。
  MiscProbe R 段反射验：-462 抛 RiskControlException、200 /
  -461 / 坏 JSON 均不抛 ✔。
- A5-13 MV 错误重试与播完重播：MvPlayerWindow.kt:98-99
  loadAttempt / panelAttempt 两路——取链失败重跑取链 effect、
  VLC 错误 dispose + panelAttempt++ 保进度重建面板；"ended"
  事件置 endedFlag → 覆盖层「播放结束 / 重新播放」，播放键在
  ended 时走 replay()。代码级核查；待真机验见下。
- A5-14 桌面歌词译文行 + 开关落盘：DesktopLyricAwtWindow
  双行第二行优先画 current.translation（0.62 字号、alpha 190），
  无译文回落 next.text；FullPlayer 的 showTranslation 改
  DesktopPrefs.lyricTranslation 持久化（默认 true）。代码级
  核查；待真机验见下。
- A5-15 + L7 更新断点续传：AppUpdateManager.downloadApk
  （AppUpdateManager.kt:290）整体重写——.part 落盘；Range
  续传（206 追加 / 200 清零重下 / 416 删 .part 重来 / 其它码
  抛错带码）；≤3 次尝试，流干净关闭但字节不足在同调用内续；
  IOException 保留 .part 抛「已保存进度」；完整性三关（长度 /
  zip 可读 / MD5）对 .part 执行、全过才改名（renameTo 失败
  copyTo+delete 兜底），校验失败删 .part；checkUpdate 成功后
  清其它版本残留 .part。UpdateProbe220（进程内拦截，连环回都
  不走——小包五案的沙箱 reset 问题自此有更强替代证据）：断后
  留 .part（524288/720583 字节）→ Range: bytes=524288- 真
  206 续满 md5 一致 → 200 清零重下 → 416 删后重来，四案 ✔。
- A5-16 切歌淡出等待：DesktopPlayer.kt:785 stopDecodeLocked
  淡出等待上限 280ms → 120ms（注释写明取舍：FADE_STEP 节拍下
  120ms 仍够增益落完；UI 线程最长阻塞减半）。真机听感（切歌
  爆音）列待验。
- A5-17 尾部策略升级（老板追加，「完整播放完成才切」）：
  decodeLoop 尾部 BitstreamException 不再就地吞（2.1.4 的
  tailTolerated 退役），改抛 Mp3TailStreamException；
  runDecodeSession 在线分支在中途续播块之后加尾部续播块
  （DesktopPlayer.kt:957 起）：独立额度 tailRetryItemId（与
  bitstreamRetryItemId 分开、清零落点一致——切歌 / 首帧成功），
  绕缓存重取链从断点续播把尾巴补齐；成功记
  PauseDiagnostics.TAIL_RESUME_OK（:997）；失败 / 无链才回落
  2.1.4 行为（duration 吸附 + onTrackEnded，记
  TAIL_RESUME_FALLBACK，记录顺序在 onTrackEnded 之后、与成功
  路径口径一致）。本地文件尾垃圾无新链可取、直接回落。
  TailProbe220 五案：T（96% 断 → 第 2 次请求 Range:
  bytes=303019-、播满 20061/20064、ENDED 无错、
  TAIL_RESUME_OK）✔；T2（两次都断 → 请求数 2、ENDED 无错、
  TAIL_RESUME_FALLBACK）✔；M1 / M2 / L 三案与 2.1.4 行为
  一致 ✔。

**B 档（历轮大件）**

- L1 系统代理：新文件 utils/NetProxy.kt——模式 DIRECT（默认、
  openHttp 强制 Proxy.NO_PROXY）/ SYSTEM 存
  DesktopPrefs.proxyMode；SYSTEM 时 Windows 读注册表
  HKCU Internet Settings 的 ProxyEnable/ProxyServer
  （parseProxyServerValue:127 两形态：裸 host:port 与
  "http=h:1;https=h:2" 按 scheme 取），非 Windows 回落 env
  http_proxy/https_proxy，30s 解析缓存；apply() 同步 JVM
  http(s).proxyHost/Port 系统属性（覆盖 OkHttp/Ktor 数据面）。
  接线：DesktopPlayer.openConnection、AppUpdateManager 全部
  五处、DownloadHelper.kt:162、CloudUploadHelper.kt:140、
  ImageSaveUtils.kt:19 改走 NetProxy.openHttp；DesktopMain
  启动早期读 pref 后 apply；设置页 ProxyRow（直连/跟随系统，
  副文案注明 JCEF 网页登录与 libVLC 不走此设置）。**不支持
  PAC/WPAD**（只读静态 ProxyServer）。MiscProbe P 段：两形态
  解析 + 空/非法 → null + apply(DIRECT) 清属性 ✔；注册表真机
  读取列待验。
- L2 列表分页：SearchViewModel.kt:138 loadMoreSearchResults()
  （_searchHasMore 源自 SearchData.more、offset 累加、代次守卫、
  空页收尾、失败保留已加载结果）；桌面 SearchScreen.kt:453
  结果底部「加载更多 / 加载中…」（五种结果类型共用）。歌单
  本就全量未动。代码级核查。
- L3 恢复默认：SettingsScreen.kt:602 起「恢复默认设置」行 +
  AlertDialog 二次确认 → resetPreferencesToDefaults()：移除
  DesktopPrefs 全部偏好键 + constants 偏好键（音质、自动跳歌、
  主题、桌面歌词/译文/逐字、UI 缩放、音效五键、首页背景八键
  等）+ EqSettings.resetToDefaults（Eq.kt:158）+
  NetProxy.apply(DIRECT)。**保留清单**：登录态（cookie/userId）、
  曲库与 pinned 数据缓存键、本地曲库（独立存储）、队列
  （SongListUtil 文件）、音源/服务器配置（audioSource /
  directSource / unblock / localSource / apiBaseUrl /
  apiServerList）、ignoredUpdateVersionKey。完成 Toast 注明
  部分设置重启生效。代码级核查。
- L4 VLC 补插件：desktop/tools/stage_vlc_bundle220.sh 以官方
  vlc-3.0.21-win64 全量 plugins 为源重裁：access 全量、demux
  全量（补 libmkv/libadaptive）、codec 除纯编码器
  （x264/x26410b/x265/twolame/qsv）与死模块（fluidsynth/
  schroedinger/zvbi/sdl_image/crystalhd/dmo）外全量（补回
  dav1d/aom/vpx/libass），其余类别沿 2.1.1。bundle
  46,804,279 → 85,601,366 字节（277 文件，+38,797,087 ≈
  +37MB 未压缩）；zip 增量见交付数据。旧 bundle 备份在
  run13/vlc-bundle-214-backup。
- L5 倍速：新文件 playback/audio/SpeedResampler.kt:21（线性
  插值重采样、跨块结转、speed==1 同引用直通）；三条 PCM 路径
  统一顺序 **EQ → 混响 → 变速 → PcmEffects（淡入淡出/8D）→
  写线缆**，framesPlayed 仍按输入帧计（位置语义不变）；
  档位 PLAYBACK_RATES=[0.5,0.75,1,1.25,1.5,2]（DesktopPrefs
  .kt:73）落盘 DesktopPrefs.playbackSpeed，PlayBar 倍速钮 +
  MainScreen 启动恢复；MV 侧 MvVlcPanel/Session.setRate 走
  libVLC 原生 rate（窗口内点击循环切档、与音乐侧同一落盘键
  同步）。**口径差异注**：音乐侧是简单重采样、会变调（0.5×
  低沉、2× 花栗鼠），VLC 侧是时间拉伸不变调。SpeedProbe220：
  单测帧数精确（2× → 22050/44100、0.5× → 88198/88200、
  直通同引用）；播放器级 1× vs 2× 到 ENDED 墙钟比 0.59 ✔。
- L6 MV 清晰度：MvPlayerWindow.kt:114 switchClarity——取链
  effect 按 (mvId, clarity, loadAttempt) 拆分，brs 降序去重；
  clarity 落盘 DesktopPrefs.mvClarity（0=自动：brs 中 ≤1080
  最大、无则最大、无 brs 取 1080）；切换时记 resumeAtSec +
  dispose + panelAttempt++ 保进度重建；控制条 brs>1 时显示
  「自动 / {br}P」菜单。已知近似：brs 在首链之后才到达，首链
  沿旧版 r=1080 请求（服务端封顶），brs 到达不重取链——清晰度
  菜单出现后切换才走新档。代码级核查；待真机验见下。
- L8 预读：DesktopPlayer.kt:2383 maybePrefetchNext(gen)——三条
  解码循环每帧后调用；守卫：进度 ≥80%、非单曲循环、
  computeNextIndex(forAuto=true) 有效且 ≠ 当前、缓存未命中/
  已过期；scope.launch 只调 updateMediaItemUri 把直链写进
  resolvedUrls（同 20 分钟 TTL），**不下载音频本体**；切歌时
  主路径命中缓存省整段取链级联，过期间接由主路径既有过期
  删除逻辑处理；prefetchItemId 在切歌 transition 清零、每曲
  只预读一次。PrefetchProbe220：限速流下 pos=16378/20064
  （81.6%）时 pf-next 已落缓存、仍在播第一首、音频请求数
  仍为 1 ✔。

**回归**：TailProbe214 四案语义并入 TailProbe220（见
A5-17）；EqProbe213 数值与 run12 基线逐项一致（off 直通、
低音预设比 1.87、混响尾 300ms 0.00889/0.03840）；Source-
Regression213：direct=true proxy=true；PlayLockProbe213：
B 过、A/C 不过——与 run12 的 lock214.log **完全同结果**
（ALSA null 节拍伪影、2.1.4 已注明的沙箱限制，非本版回归；
run12 另有 mini 变体旁证）。MvVlcProbe213 本版未复跑：沙箱
缺 libXtst.so.6（2.1.4 同一限制），且 MvVlcPanel
.createComponent 本版签名加了 startAtSec 参数、旧探针的位置
参数调用需改命名参数——MV 的 VLC 管线本身未动，改动在
Compose 侧（重试/清晰度/倍速接线），列真机验。

**交付数据（本地，未上传——上传由主会话复核后执行）**：
dist 双 zip MD5 均为 08816fd736ba9f865ae622f62abb38be、
368,129,787 字节（2.1.4 为 351,187,568，增量 +16,942,219
字节 ≈ +16.2MB，主要是 L4 插件补全的压缩后增量）；exe 内嵌
版本串 2.2.0×2、旧串 0；fat jar 清单 Implementation-Version
2.2.0、BuildConfig VERSION_CODE=51 / VERSION_NAME=2.2.0
已验；run13/penguin-version.json 已备（2.2.0 带 md5），线上
未动。

**待真机验（如实）**：①A5-17 真实网络尾部续播（《阴天》类
歌曲尾声断流时补齐最后几秒的实际衔接与可闻间断）；②L1
注册表代理在真实 Windows + 代理软件环境下 API/直链/下载
是否走代理（JCEF 登录、libVLC 不在此列已注）；③L4 补全
插件的实际格式覆盖（mkv、dav1d/aom 编码的 MV 等）；④L5
变调听感与 MV 倍速；⑤L6 MV 清晰度切换的保进度重建与菜单
档位；⑥A5-11 网页登录校验失败后的自动重报/手动重验；
⑦A5-13 MV 错误重试与播完重播引导；⑧A5-14 桌面歌词译文行
实际渲染；⑨A5-16 120ms 淡出在真实声卡切歌时有无爆音；
⑩PlayLock A/C 原案（承接 2.1.4，沙箱节拍问题与真机无关）；
⑪应用内更新走一遍新下载器（断点续传的真实网络表现）。
