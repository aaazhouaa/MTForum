# MTForum — MT 论坛第三方客户端（二改版）

Kotlin + Material Design，
覆盖版块浏览、帖子阅读、回复/发帖、个人中心、多账号、人机验证兜底等功能。

> 本仓库基于 MTForum v2.2 (build83) 源码做二次修正，**非官方**，与论坛站点及原作者无关。
> 当前版本 **v2.3.3**。

### 未处理

- `minifyEnabled false`（`proguard-rules.pro` 已接上但未启用混淆）。项目大量依赖 Jsoup 反射式
  解析与 `viewBinding`，且 `ThreadDetailActivity` 是反编译产物，开启混淆需先补 keep 规则并回归。

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
│       │   ├── network/         # HttpClient / ForumParser / WafChallenge / RateLimiter
│       │   ├── session/         # 登录态 / 多账号 / 黑名单
│       │   ├── ui/              # 各页面与自定义控件
│       │   ├── adapter/ model/ util/   # util 含 AiLog（运行日志）
│       └── res/                 # 布局 / 资源
└── README.md
```

## 依赖

OkHttp（网络）、Jsoup（HTML 解析）、Glide（图片）、Material Components（UI）、
ViewPager2、DrawerLayout、SwipeRefreshLayout、RecyclerView。

## 语言

全量 Kotlin（103 个 `.kt`，0 个 `.java`）。迁移前的 Java 基线为 27,553 行 / 76 个文件。

迁移实测要点见 [KOTLIN_MIGRATION.md](KOTLIN_MIGRATION.md)，其中 27 条规则均由本项目
编译错误倒推。典型几条：

- Kotlin 只对 `is` 开头的属性名生成 `isXxx()`，其余生成 `getXxx()`。故
  `hasImage` / `followed` / `likedStateKnown` 等需 `@get:JvmName` 固定方法名。
- `object` 的公开成员需 `@JvmStatic` 才有静态形式；public 字段需 `@JvmField`。
- 不用 `data class`：其 `equals`/`hashCode` 值语义与「解析器逐字段填充」的用法不符。

> 代码基线本身是反编译产物（`ThreadDetailActivity` 含 117 个 `lambda$` 合成方法），
> 迁移时把这些合成方法内联回了原调用点，逻辑与参数顺序保持一致。

## 更新日志

### v2.3.3
- 移除整个 AI 模块与自动化：AI 助手/一键总结/AI 接口配置、自动回复与自动解锁隐藏内容、
  演练模式、自动签到，以及发帖页「优化」「AI发帖」入口与相关逻辑。
- `AiLog`（侧边栏「运行日志」）保留，并由 `ai/` 迁至 `util/`。
- 侧边栏重排为：主题设置 / 人机验证 / 底部栏自动隐藏 / 运行日志 / 设置；设置与主题设置入口
  由「我的」页迁移至侧边栏。
- 修复连续切换主题时的闪退（后台线程在 Fragment 解绑后调用 `requireActivity()`）。

## 注意

- 代码基线为 build83 (v2.2)，当前开发版本 v2.3.3。
- `HttpClient.USER_AGENT` 写死为三星 S918B / Chrome 120；站点风控严时可调整。
- `bbs.binmt.cc` 挂了阿里云 ESA，请求过频会被 IP 级拦截，勿短时间连发。
- `WafChallenge` 的置换表与 XOR 密钥取自站点当前挑战实现，站点改版后会失配。
  失配时不会静默：`HttpClient.DEBUG_WAF` 打开时会记录
  `命中挑战页但求解失败（算法可能已失配）`。
