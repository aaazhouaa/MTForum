# Java → Kotlin 转换规范（本项目专用）

本文件是给转换执行者（人或 AI）的规范。**所有规则均为本项目实测得出，不是推测。**

## 背景

项目：`~/workspace/MTForum`，Android 应用，Java + Kotlin 混编。
目标：把 Java 文件转为 Kotlin，**外部行为与 JVM 签名必须完全不变**，
因为大量 Java 调用点不允许改动。

## 硬性要求

1. **只改语言，不改行为**。不重构、不"优化"、不改方法名、不改字段名。
2. **生成 .kt 后删除同名 .java**，路径不变（仍在 `src/main/java/...`）。
3. 转换后**必须跑通** `./gradlew :app:assembleDebug`。
   若失败，先修到通过再交给下一个文件；不能留下红着的状态。

## 互操作规则（关键，漏一条就编译失败）

### 1. Boolean 属性命名（最容易踩）

Kotlin **只对 `is` 开头的属性名**生成 `isXxx()`：

| Java 期望调用 | Kotlin 应写 | 说明 |
|---|---|---|
| `isSticky()` | `var isSticky: Boolean` | ✓ is 开头，自动正确 |
| `isRead()` | `var isRead: Boolean` | ✓ 同上，setter 为 `setRead()` |
| `isOP()` | `var isOP: Boolean` | ✓ 同上，setter 为 `setOP()` |
| `isOnline()` | `var isOnline: Boolean` | ✓ 同上，setter 为 `setOnline()` |
| `isHasImage()` | 需注解（见下） | ✗ `hasImage` 会生成 `getHasImage()` |
| `isFollowed()` | 需注解 | ✗ `followed` 会生成 `getFollowed()` |

对**不以 is 开头**但 Java 侧调用 `isXxx()` 的布尔属性，必须加注解：

```kotlin
@get:JvmName("isHasImage")
@set:JvmName("setHasImage")
var hasImage: Boolean = false
```

**转换前必做**：在该 Java 文件里 grep 出所有 `public boolean isXxx()` 与
`public boolean getXxx()`，逐个确认 Kotlin 侧生成的签名与之一致。

### 2. 静态成员

Java 的 `static` 方法，在 Kotlin 里需 `@JvmStatic` 才能保持 `类名.方法()` 调用：

```kotlin
object AccountManager {
    @JvmStatic
    fun list(c: Context): List<Account> { ... }
}
```

- 若类只有静态成员 → 用 `object` + 每个方法 `@JvmStatic`
- 若类是工具类且 Java 侧调用 `Xxx.method()` → 同上
- **不要**用顶层函数（会生成 `XxxKt.method()`，Java 调用点会崩）

### 3. 公开字段

Java 代码可能直接访问 `.field`（而非 getter）。Kotlin 属性默认生成私有字段 +
getter/setter，会导致 `error: field has private access`。

此时用 `@JvmField`：

```kotlin
class Account {
    @JvmField var uid: String? = null
}
```

**判断方法**：grep Java 调用点是否有 `xxx.field`（非 `getField()`）写法。

### 4. 嵌套类 / 静态内部类

Kotlin 的 `class` 默认就是静态嵌套类，直接写即可：

```kotlin
class ForumCategory {
    class Forum { ... }   // 对应 Java 的 static class Forum
}
```

### 5. 构造函数

Java 的多构造函数在 Kotlin 里用次级构造：

```kotlin
class ForumCategory {
    var name: String? = null
    var forums: MutableList<Forum>? = null

    constructor()

    constructor(name: String?, forums: MutableList<Forum>?) {
        this.name = name
        this.forums = forums
    }
}
```

### 6. 可变集合

Java 的 `List<T>` 参数/返回，Kotlin 用 `MutableList<T>`：

- Java 可对 List 调 `add()` → Kotlin 必须 `MutableList`
- **可空性照抄 Java**：若 Java 代码有 `x.getReplies() == null` 判断，
  Kotlin 侧必须是可空类型 `MutableList<T>?`，不能给默认空列表（会改变语义）

### 7. 数据类型

- Java `int` / `boolean` → Kotlin `Int` / `Boolean`，且**非空**（若 Java 里是基本类型）
- Java 对象引用 → 保持可空性判断与 Java 一致；无明确证据时用 `String?`（可空）
  更安全，因为解析器可能赋 null

### 8. `data class` 禁用

**不要**把数据模型改成 `data class`：其 `equals`/`hashCode` 是值语义，
而本项目解析器是「new 出来逐字段 setXxx」的用法，改成 data class 会改变行为。
用普通 `class` + `var` 属性即可（同样消掉 getter/setter 样板）。

### 9. 位运算 `and` / `or` 的优先级（实测踩坑）

Kotlin 的 `and` / `or` / `shl` 是**中缀函数**，优先级**高于** `==`。
Java 的 `&` 优先级低于 `==`，两者相反。

所以 Java 里这样写是合法的：

```java
return (uiMode & MASK) == NIGHT_YES;   // 括号本就可有可无
```

直接逐行照抄去掉括号会变成：

```kotlin
// ❌ 被解析成 `uiMode and (MASK == NIGHT_YES)` → 报 Boolean/Int 类型不匹配
return uiMode and MASK == NIGHT_YES
```

**必须加括号**：

```kotlin
// ✓
return (uiMode and MASK) == NIGHT_YES
```

同理：`(a or b) == c`、`(x shl 2) == y` 都需括号。

### 10. 属性初始化与主构造器（实测踩坑）

Java 里「无参构造 + 在构造器里给 final 字段赋值」的写法，
直接改成「无主构造器 + 次级构造器赋 val」会报：

```
Supertype initialization is impossible without a primary constructor
Property must be initialized or be abstract
```

**正确做法**：用**主构造器** + `init` 块：

```kotlin
class FrostedGlassDrawable(
    private val fillColor: Int,
    private val radius: Float,
    private val density: Float
) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        bgPaint.style = Paint.Style.FILL
        borderPaint.strokeWidth = Math.max(0.5f, 0.75f * density)
    }
}
```

### 11. 超出 Int 范围的字面量

Java 的 `0xFF1E1E1E`（> `Int.MAX_VALUE`）在 Kotlin 里**不能直接写**，需加 `.toInt()`：

```kotlin
0xFF1E1E1E.toInt()   // 位模式与 Java 完全一致（负数），行为不变
0xFFFFFFFF.toInt()
```

### 12. 隐式数值加宽

Java 的 `int → float` 隐式加宽在 Kotlin 里需显式 `.toFloat()`：

```kotlin
rect.set(bounds.left.toFloat(), bounds.top.toFloat(), ...)
```

### 13. 其他

- `TextUtils.isEmpty(x)` 保持原调用，不要换成 `x.isNullOrEmpty()`
  （除非确认语义一致且同批验证过）—— 少改就少错
- 匿名内部类 → 保持为 `object : XxxInterface { ... }`
- lambda `->` 保持，`Consumer`/`Runnable` 等保持原接口
- 注解（`@Override` 除外）照抄
- `@Override` 在 Kotlin 里改为 `override`
- 字符串字面量、中文注释**原样保留**（注释也保留，便于对照）

## 转换步骤

1. 读 Java 文件全文
2. grep 该文件在**全项目**的调用点，确认：
   - 哪些方法是 `static`（需 @JvmStatic）
   - 哪些字段被直接访问（需 @JvmField）
   - 哪些布尔 getter 是 `isXxx()`（需 @JvmName）
3. 写 .kt（保持原有注释、结构、逻辑顺序）
4. `rm` 原 .java
5. `./gradlew :app:assembleDebug` 验证
6. 若报错，按报错修（多数是上面 1/2/3 条）
7. 通过后再下一个文件

## 反面清单（不要做的事）

- ❌ 不要用 `data class`
- ❌ 不要把 `TextUtils.isEmpty` 改成 `isNullOrEmpty`
- ❌ 不要改方法名/参数名/字段名
- ❌ 不要删注释
- ❌ 不要合并或拆分类
- ❌ 不要用顶层函数替代静态方法
- ❌ 不要顺手重构逻辑

## 追加规则（批次 2 实测，全部会导致编译失败）

### 14. `@JvmName` 只对 Java 调用方生效

给属性加 `@get:JvmName("isFollowed")` 后，**Kotlin 代码里看不到 `isFollowed` / `setFollowed`**，
只能用属性名：

```kotlin
// ❌ Unresolved reference 'setFollowed'
thread.setFollowed(true)
thread.isFollowed()

// ✓ Kotlin 侧用属性语法
thread.followed = true
thread.followed
```

**规则**：转换 `.kt` 文件时，若调用的是另一个已转 Kotlin 的类，
必须改用属性语法；调用纯 Java 类时才用 `getXxx()/setXxx()`。

### 15. `String.replaceAll` 不存在

Kotlin 的 `String` 没有 `replaceAll`：

```kotlin
// ❌ Unresolved reference 'replaceAll'
s.replaceAll("<[^>]+>", "")

// ✓
s.replace(Regex("<[^>]+>"), "")

// 带分组引用时用 lambda（Kotlin 的 "$1" 需转义，容易出错）
Regex("<!\\[CDATA\\[(.*?)\\]\\]>").replace(s) { m -> m.groupValues[1] }
```

### 16. getter 方法 → 属性

```kotlin
e.getMessage()   // ❌ Unresolved reference 'getMessage'
e.message        // ✓
s.toLowerCase()  // ⚠ deprecated 告警 → s.lowercase()
```

### 17. 字符串拼接：`+` 不能放在下一行行首

```kotlin
// ❌ Unresolved reference 'unaryPlus' for operator '+'
val url = HttpClient.BASE_URL
        + "plugin.php"

// ✓ 把 + 放上一行行尾
val url = HttpClient.BASE_URL +
        "plugin.php"
```

### 18. 可空参数：Java 可传 null 的 String 在 Kotlin 必须标 `String?`

原 Java 方法形参是 `String uid`（无注解，可传 null），
调用点若传可空值，Kotlin 侧不标 `?` 会报
`Argument type mismatch: actual type is 'String?', but 'String' was expected`。

**判断方法**：看方法体首行是否有 `TextUtils.isEmpty(x)` / `x == null` 判断 —— 有则参数应标 `?`。

### 19. `TextUtils.isEmpty` 无法让编译器推断非空

```kotlin
fun setState(uid: String?) {
    if (TextUtils.isEmpty(uid)) return
    set.add(uid)   // ❌ 编译器仍认为 uid 可空
}
```

因为 `TextUtils.isEmpty` 是 Java 方法，Kotlin 不做契约推断。需显式绑定：

```kotlin
fun setState(uid: String?) {
    if (TextUtils.isEmpty(uid)) return
    val safe = uid!!      // 或 if (uid.isNullOrEmpty()) return 后用 uid
    set.add(safe)
}
```

## 批次 24-26 新增规则（大文件迁移实测）

20. **`CharSequence.indexOf(Int)` 会被解析成私有扩展**：Java 的 `html.indexOf(60, i)`（char 码点重载）在 Kotlin 里必须写 `html.indexOf(60.toChar(), i)`；`lastIndexOf` 同理。否则报 `Cannot access 'fun CharSequence.indexOf(...)': it is private in file`。
21. **Java 的 `fun interface` 才能用 SAM 简写**：Kotlin 非 `fun interface` 的接口（如 `LikeUsersAdapter.OnUserClickListener`）必须用 `object : X { override fun ... }`，不能写 `LikeUsersAdapter { uid, name -> }`。
22. **`ScrollView.LayoutParams` 在 Kotlin 里不存在**：应改用 `android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)`。
23. **`SpannedString.valueOf(String)` 是 Java 静态方法**，Kotlin 里没有该形式；用 `android.text.SpannedString(s)` 构造。
24. **ReplacementSpan 的几何参数是 Float**：`dpToPx(n)` 返回 Int，传给自定义 span 构造的 float 形参要 `.toFloat()`。
25. **`Html.ImageGetter` 是 Java 单方法接口**，Kotlin 可 SAM 简写 `Html.ImageGetter { source -> ... }`，但返回类型不能是可空（`Drawable?` 要修成非空或改 `CustomTarget<Drawable>`）。
26. **`Regex.replace` 不支持 `replaceAll` 语义差异**：Java `str.replaceAll(regex, repl)` 全部替换，Kotlin `Regex(...).replace(str, repl)` 行为一致，但参数顺序是 `(input, replacement)`；注意别写成 `(replacement, input)`。
27. **`Lamda 尾随参数 + 可空函数类型`**：`addActionRow(container, icon, label, danger, { ... }, dialog)` 中 lambda 在中间位置必须带花括号显式传参，不能尾随。

## 完成状态

全部 76 个 `.kt`，**0 个 `.java`**。release / debug APK 均构建通过。
