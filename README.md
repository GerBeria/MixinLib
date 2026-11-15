# MixinLib

取消了Starsector对反射的禁用，提供Mixin框架

### 安装

1. 解压，将`MixinLib`文件夹放入`Starsector/mods/`
2. 运行`install.bat`
3. 正常启动 Starsector

### 卸载

1. 运行 `uninstall.bat`


## 架构设计

| 架构 | 说明 |
| --- | --- |
| 双 JVM | 启动器 JVM 只负责启动参数交接与日志轮转；所有 Mixin 配置（包括 `IMixinConfigPlugin` ）只在游戏 JVM 生效；不要假设它们在启动器阶段已被加载。 |
| 统一命名空间 | 游戏核心 jar 与全部已启用 mod jar 以及 janino 动态编译字节码 由同一个类加载器 `TargetClassLoader` 定义，类身份相同，彼此可见。 |
| 同名配置 | 两个 mod 声明同名 `*.mixin.json` 时直接报错 `Duplicate Mixin config name`，不按去重或按优先级合并。 |


## 开发

本文件中的「plugin」一词，除系统属性名 `mixinlib.plugin.strict` 外，均指 `IMixinConfigPlugin` 实现类。

| # | 说明 |
| --- | --- |
| 1 | config 放 mod jar 根，文件名 `*.mixin.json`；支持在 manifest 写 `MixinConfigs`。但请优先使用 `*.mixin.json` 。对于多个 mixin 包可以拆成多个 mixin 配置文件；也可以共用一个父包。|
| 2 | 需要 plugin 时在 config 写 `"plugin": "< FQN 全限定名>"`，类必须有无参构造并实现 `IMixinConfigPlugin`，7 个回调按 sponge 标准语义实现。该类不参与 Mixin 织入。多个 mixin 配置，可以分别声明各自的 plugin，也可以共用同一个 plugin 类。 |
| 3 | `IMixinConfigPlugin` 实现类只能引用 JDK、Mixin 框架、ASM 与自身 jar 内的类型，不可引用其它 mod 的类。 `IMixinConfigPlugin` 执行时游戏类与其它 mod 的类尚未加载。 |
| 4 | refmap 放 jar 内，`getRefMapperConfig()` 返回资源名（从 jar 根目录开始的相对路径）。 |
| 5 | **使用 MixinExtras 的 mod 无需自行初始化**：MixinLib 已在 `createTransformer()` 之后调用 `MixinExtrasBootstrap.init()`。但如果 mod 仍在自己的 `onLoad` 里调用一次也是安全的，`init()` 幂等执行。 |
| 6 | **不要自带 MixinExtras jar**。编译期用 `compileOnly + annotationProcessor` 依赖，运行时由 MixinLib 统一提供；已由 ParentFirst 规则保证只有一份。 |
| 7 | mixin 类中不支持 JAVA_13 之后的语言特性；请不要使用 mixin 进行嵌套匿名/内部类、复杂泛型的织入、直接修改字节码等复杂操作，支持度不高 |
| 8 | mixin 类、plugin 类、refmap 都由同一个 mod jar 提供。 |

your.mixin.json：

```json
{

    "required": true,
    "minVersion": "0.8",
    "package": "your.mixin.package"/"your.mixin.parentPackage",
    "compatibilityLevel": "JAVA_8",
    "plugin": "< FQN 全限定类名>",
    "mixins": [
        "YourMixinClass1"/"childPackage1.YourMixinClass",
        "YourMixinClass2"/"childPackage2.YourMixinClass"
    ]

}
```
## 类加载与前置依赖

游戏核心 jar 与全部已启用 mod jar 由同一个 `TargetClassLoader` 定义，保持可织入；**游戏前置依赖统一父类优先，默认不允许被 Mixin 改写**。

### 加载顺序

四级顺序是显式契约，启动时由 `[Order]` 行打印并在 `configureOrigins` 阶段校验：

| 顺序 | 来源 | 谁来定义 |
| --- | --- | --- |
| 1 | 前置依赖（`ParentFirst` 前缀命中） | 系统加载器；缺类时回落 target |
| 2 | 游戏核心 jar | `TargetClassLoader`（读字节 → 清洗 → 织入 → `defineClass`） |
| 3 | mod jar | 同上；URL 顺序保证核心先于 mod，同名类取核心 |
| 4 | Janino 动态编译 | 仅当前三步都没有 `.class` 资源时；产物仍由 `TargetClassLoader` 定义 |

URL 顺序被破坏时记 `core-before-mod=VIOLATED` 并带 ERROR（不阻断启动，但说明来源会漂移）。

### ParentFirst 规则

| 分组 | 前缀 | 规则可停用性 |
| --- | --- | --- |
| 框架自保（9 项） | `java.` `javax.` `jdk.` `sun.` `com.sun.` `org.spongepowered.asm.` `org.objectweb.asm.` `com.github.alphardpaarthurnax.mixinlib.` `com.llamalad7.mixinextras.` | 不可停用该类规则 |
| 游戏前置依赖（9 项） | `org.codehaus.` `de.unkrig.` `com.jcraft.` `org.json.` `org.lwjgl.` `net.java.` `org.apache.` `com.thoughtworks.` `com.luciad.` | 可停用该类规则 |

规则集中在 `ParentFirstRules`，  
提供 `addPrefix` / `addPrefixes` / `disablePrefix` / `enablePrefix` / `reset` / `effectivePrefixes` / `describe`。  
不同游戏版本、整合包或后续扩展需要调整清单时，**必须在 `MixinRuntime.initialise()` 之前调用**：  
这些规则是静态全局的，越晚修改越不影响已定义的类，所以应尽可能早的调用修改。  
启动时 `[ParentFirst]` 行会打印当前计数。

**刻意保留在 target 的例外**：

- `org.spongepowered.asm.synthetic.` 是 Mixin 合成类，必须由 target 定义，优先于所有规则。

MixinExtras 0.5.5 随 bridge jar 提供、未 relocate；产物中的注解处理器 SPI 已排除，运行时不会注册额外的 `Processor`。  
`com.llamalad7.*` 由系统加载器解析，即使 mod 误自带 jar 也会被忽略。

### 动态编译与脚本类

动态编译的脚本类**改由 `TargetClassLoader` 定义**，`getClassLoader()` 恒为 target，不再来自 Janino 子加载器。  
字节来源分为 `CORE` / `MOD` / `JANINO` / `GENERATED` / `HOST`，诊断行带 `origin=`。

`ScriptStore` 采用能力探测式改写：无论 `enableScriptCaching` 等相关配置值、该版本存在几个构造分支，脚本类的加载路径都归 MixinLib 自持的纯编译器；加载入口形态不符时直接报错，不静默退回游戏自己的 loader。构造层定位不到时记 `[Compat] constructor-rewrite=SKIPPED` 并降级，加载入口仍必须改写成功。  
改写范围覆盖 `ScriptStore` 与全部 `ScriptStore$N` 内部类：0.98a 的后台加载线程 `ScriptStore$3` 里各有一处 `Class.forName` 与 `loadClass`，只改外部类会让脚本重新由游戏自己的 `JavaSourceClassLoader` 定义。

**默认没有脚本缓存**：每次启动全量编译，编译产物不写入磁盘。当前版本的脚本编译由 MixinLib 本体直接实现。

源码定位由 MixinLib 自建：`PathResourceFinder` 指向游戏核心目录与全部已启用 mod 目录（核心在前），不依赖游戏自带的 `Objectsuper` —— 后者走游戏的资源快照，在本版本对 `data/**` 下的 `.java` 大量返回 null，依赖类找不到源码就会回落到 target 并触发嵌套编译。  
`JavaSourceClassLoader` 不可重入：编译期再次请求编译一律按「无源码」放弃，避免外层单元的错误被重复累计（实测会把 24 条叠成 48、72…）。

另外，改写后不再经过 `B.loadClass` 的 `java.io` / `java.lang.reflect` 脚本黑名单；  
`JaninoBytecodeCacheHooks.finish(...)` 对纯编译器是空操作，原调用保留不动。

### 字节码自愈 *java28适配*

所有交给 target 定义的字节（jar 类与脚本类）统一经过 `BytePreprocessor.prepare`：

| 机制 | 说明 |
| --- | --- |
| 非法成员名 | 常量池 UTF8 等长替换 `A.B` → `A_B`，长度、索引、Code 偏移与 StackMapTable 不变 |
| 损坏栈帧 | 自发现 `Uninitialized(offset)` 是否指向 `new` 指令，命中时使用 `COMPUTE_FRAMES` 与 `COMPUTE_MAXS` 重算，修完复检 |

1. **常量池一致性**：X 声明的字段与 Y 中的引用分属两个独立的常量池。修改时必须保持同步，否则将引发 NoSuchFieldError 或 ClassFormatError。
2. **异常处理机制**：  
    **回退策略**：若规则无法覆盖非法名称，类将回退至原始字节码。  
    **Fail-Open 原则**：任何异常均原样放行，绝不输出部分修正的字节码。
3. **诊断与统计**：
    **日志标记**：请查看 [Sanitize]（清洗）与 [Repair]（修复）。
    **统计指标**：关注 `sanitized`、`frameRepaired`、`sanitizeRejected` 及 `failOpen`。

Mixin 框架生成的合成类不经此入口：它由已经清洗过的 ClassNode 派生，不含游戏原始非法名。

## 日志与诊断

日志写入 **`mixinlib.log`**，目录优先取系统属性 `com.fs.starfarer.settings.paths.logs`，未设置时为游戏工作目录（`user.dir`）。

### 每次启动轮转

每次启动时（Launcher JVM 的 agent 阶段）新建一份空的 `mixinlib.log`，  
上一次的日志归档为 `mixinlib.log.1`，更老的依次后移：**`.1` 是最近一次、`.5` 是最老一次**。  
默认保留近 5 次历史，即目录中最多有 `mixinlib.log` 加 `mixinlib.log.1` … `.5` 共 6 个文件。  
由系统属性 `mixinlib.logLimit` 调整保留次数；`<=0` 表示不保留历史（会清掉旧日志）。  

若第三方启动器直接拉起游戏 JVM、不经过 Launcher 阶段，则不会轮转，日志会追加到当前 `mixinlib.log` 文件。

### 日志内容

| 标签 | 含义 |
| --- | --- |
| `[<线程>/Mixin:<loggerId>/<LEVEL>]` | Mixin 框架与 MixinExtras 的日志 |
| `[Injector]` | 启动时输出已注册的注入器注解数量与清单、以及每个 config 声明的 plugin 类 |
| `[SelfCheck]` | 首次类织入之后核对声明了 plugin 的 config 是否拿到实例，`OK` / `MISSING` / `UNKNOWN` |
| `[Attribution]` | 每个 config 的来源 jar 与所属 mod id |
| `[Audit]` | 原有的类级审计；其中 `from mod <id>` 现在会标注来源 |
| `[Compat]` | `ScriptStore` 的改写结果；构造层定位不到时记 `constructor-rewrite=SKIPPED` |
| `[Origin]` | 来源清单注入与类别判定；未登记来源（如目录型资源）时记 `unresolved` |
| `[Janino]` | 由 target 定义的动态编译脚本类，固定带 `origin=JANINO`；有源码但编译不过时 `compile-failed` 带逐条诊断 `diag`；「没有源码」只做汇总计数，不逐条记录 |
| `[Sanity]` | 定义前 sanity 拒绝可疑字节（magic / 类名 / 可解析性） |
| `[Exclusion]` | 命中织入排除名单的类，记录原因后原样定义 |
| `[ParentFirst]` | 规则摘要与运行时的前缀增删 |
| `[Order]` | 四级加载顺序与 `core-before-mod` 校验结果 |
| `[Sanitize]` / `[Repair]` | 非法成员名清洗与损坏栈帧重算 |

`from mod <id>` 的 mod id 由 Launcher 从启用列表读取，并随启动参数 `--mod-id` 交接给游戏 JVM。  
旧格式或读取失败时降级为 `(unknown)`，同时在 `[Attribution]` 行给出原因，不影响启动。

启动日志 `[Injector] registered=` 分别在启动阶段与首次织入之后输出一次，并带 `stage=startup` / `stage=post-first-transform` 标记，可在 `mixinlib.log` 里核对。

### 开关

| 系统属性 | 默认 | 作用 |
| --- | --- | --- |
| `mixinlib.logLimit` | `5` | 保留近 n 次启动日志历史（槽位 `1..n`） |
| `mixinlib.mixin.logLevel` | `INFO` | Mixin 日志最低级别：`FATAL` / `ERROR` / `WARN` / `INFO` / `DEBUG` / `TRACE` |
| `mixinlib.mixin.logFilter` | 空 | loggerId 前缀白名单，逗号分隔 |
| `mixinlib.mixin.log` | `true` | 为 false 时关闭 Mixin 日志 |
| `mixinlib.plugin.strict` | `false` | 自检发现 plugin 缺失时是否硬失败；默认 false 只告警 |

### 已知项

- **一条既有的 ERROR**：`Initialising mixin subsystem after game pre-init phase! Some mixins may be skipped.`  
由 `getInitialPhase()` 返回 `Phase.DEFAULT` 引起。
- **负对照**：如果在 config 里写一个不存在的 plugin 类，日志中应同时出现：  
`Error loading companion plugin class ...` 与 `[SelfCheck] ... status=MISSING`；

## 行为变化与已知边界

| 变化 | 处理 |
| --- | --- |
| 绕过 `B.loadClass` 的脚本黑名单 | 与「取消反射禁用」目标一致，仅在此记录 |
| `JaninoBytecodeCacheHooks.finish(...)` 变成空操作 | 原调用保留不动 |
| 零缓存、每次启动全量编译 | 见「动态编译与脚本类」 |
| 脚本类 `getClassLoader()` 变为 `TargetClassLoader` | 改动目标，类身份与织入链路统一 |
| 脚本编译的归属 | 当前版本的编译行为由 MixinLib 本体直接实现，见「动态编译与脚本类」 |
