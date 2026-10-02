# MTForum-ThirdParty — MT 论坛第三方客户端（二改版）

[bbs.binmt.cc](https://bbs.binmt.cc/) 的第三方 Android 客户端。原生 Java + Material Design，
覆盖版块浏览、帖子阅读、回复/发帖、个人中心、多账号、AI 自动签到/自动回复。

> 本仓库基于 MTForum v2.2 (build83) 源码做二次修正，**非官方**，与论坛站点及原作者无关。

## 与原版的差异

### 修复

- **内容加载不出来（关键）**：站点 `forum.php` / `home.php` / `search.php` 挂的阿里云 ESA WAF
  会下发约 4 KB 的 JS 挑战页，要求携带 `acw_sc__v2` Cookie。原生 HTTP 客户端没有 JS 引擎，
  永远拿到挑战页 → 解析结果恒为空 → 表现为「界面正常但没有任何内容」。
  现新增 `network/WafChallenge.java` 本地复现该挑战算法，`HttpClient` 命中挑战页时自动求解、
  写入 Cookie 并重放一次（仅 GET；POST 不重放以免重复提交）。
- **进帖自动解锁重复回帖**：详情页有两条解锁链路会在同一次加载中各起一个线程，
  各自判「未解锁」后都会回帖 → 同一帖被回两条。现两条链路共用 `claimTid()` 原子认领，
  只有一条能发出回复。
- **「进帖自动解锁」开关失效**：`isUnlockOnView` 此前只在 `maybeAutoUnlock()` 里检查，
  而真正执行解锁的 `runUnlockInBackground()` 不看该开关，导致关掉开关仍会自动回帖。
- **解锁逻辑三套实现**：门控文案判定与解锁回复文案在 `AutoReplyEngine` / `ForumTools` /
  `AiSummarizeActivity` 里各写一份且行为不一致（其中 `ForumTools` 会调 AI 生成回复，
  模型可能拒答导致解锁失败）。现统一为 `AutoReplyEngine.containsGateWord()` 与
  `buildUnlockText()` 单一实现。

### 清理

- 移除零引用代码：`UpdateChecker`（整文件已注释）、`StickyThreadAdapter`、`FavoritePrefetcher`、
  `FrostedDialog`、`nav_graph.xml`、4 个无引用 `dimens.xml`，及 `getWithReferer` 等死方法。
- 移除零 import 依赖：`webkit`、`vectordrawable`、`lifecycle-livedata`、`lifecycle-viewmodel`、
  `navigation-fragment`、`navigation-ui`、`glide-compiler`。
  APK 体积因此缩小约 8%。
- 修正 `ThreadDetailActivity` 里用 `androidx.webkit.internal.AssetHelper.DEFAULT_MIME_TYPE`
  （`internal` 包非公开 API）取值为 `"text/plain"` 的写法，改为字面量。

### 配置

- `compileSdk` 36 / `targetSdk` 36（原来 targetSdk 34 < compileSdk 36），
  并为 14 个 activity 布局补 `fitsSystemWindows`，适配 Android 15+ 强制的 edge-to-edge。
- Java 21（与本机 JDK 一致）。
- `buildToolsVersion` 与 SDK 版本写入 `app/build.gradle`，本机只需在 `local.properties` 填 `sdk.dir`。
- release 用 debug 签名，仓库不包含发布用 keystore。
- `allowBackup=false`；移除 `usesCleartextTraffic`；日志不再镜像到对其它应用可读的
  `Android/media/<pkg>/`。

### 未处理

- `minifyEnabled false`（`proguard-rules.pro` 已接上但未启用混淆）。项目大量依赖 Jsoup 反射式
  解析与 `viewBinding`，且 `ThreadDetailActivity` 是反编译产物，开启混淆需先补 keep 规则并回归。

## 构建

需要：JDK 17+（本项目按 21 编译）、Android SDK（platform 36 + build-tools 36.0.0）。

本机只需在 `local.properties`（不入库）填 SDK 路径：

```properties
sdk.dir=/path/to/android-sdk
```

```bash
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包
```

> wrapper 固定 Gradle 8.14.2。首次执行会联网下载发行版（约 150 MB），之后复用本地缓存；
> 不便联网时可临时用系统 `gradle` 代替。
> 若本机没有 build-tools 36.0.0，改 `app/build.gradle` 里的 `buildToolsVersion`。
> aarch64 环境不要用 sdkmanager 去装缺失的 build-tools：AGP 只认 host=x86_64 的包，
> 装进来是不可执行的死包。

## 目录结构

```
.
├── build.gradle                 # 根配置
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml       # 依赖版本
│   └── wrapper/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/solosu/mtforum/
│       │   ├── network/         # HttpClient / ForumParser / WafChallenge
│       │   ├── ai/              # AiClient / ForumTools / AutoReplyEngine
│       │   ├── session/         # 登录态 / 多账号 / 黑名单 / 签到
│       │   ├── ui/              # 各页面与自定义控件
│       │   ├── adapter/ model/ util/
│       └── res/                 # 布局 / 资源
└── README.md
```

## 依赖

OkHttp（网络）、Jsoup（HTML 解析）、Glide（图片）、Material Components（UI）、
ViewPager2、DrawerLayout、SwipeRefreshLayout、RecyclerView。

## 注意

- 代码基线为 build83 (v2.2)。
- `HttpClient.USER_AGENT` 写死为三星 S918B / Chrome 120；站点风控严时可调整。
- `bbs.binmt.cc` 挂了阿里云 ESA，请求过频会被 IP 级拦截，勿短时间连发。
- `WafChallenge` 的置换表与 XOR 密钥取自站点当前挑战实现，站点改版后会失配。
  失配时不会静默：`HttpClient.DEBUG_WAF` 打开时会记录
  `命中挑战页但求解失败（算法可能已失配）`。
