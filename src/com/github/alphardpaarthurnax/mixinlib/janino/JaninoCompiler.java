package com.github.alphardpaarthurnax.mixinlib.janino;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自持的纯 Janino 编译器：构造参数与游戏 ScriptStore 一致
 * （parent + Objectsuper 源码查找器 + UTF-8），并打开 source/lines/vars 调试信息。
 *
 * <p>只编译、不定义类、不落盘：{@link #compile} 返回 Janino 生成的原始字节码，
 * 交给 {@link JaninoClassSource} 与 {@code TargetClassLoader} 统一清洗、织入、定义。
 *
 * <p><b>为什么全部走反射</b>：janino.jar 位于游戏 -classpath，因此
 * {@code org.codehaus.janino.*} 由 {@code TargetClassLoader} 定义；而本类位于
 * -javaagent 的 bridge jar，由系统加载器定义。若在这里静态引用 Janino 类型，
 * 拿到的是系统加载器版本的 Class，与 {@code ScriptStore} 静态字段（target 版本）
 * 是两个不同的类型，赋值会触发 loader constraint / ClassCastException。
 * 所以所有 Janino 与游戏类都通过传入的 {@code namespace} 解析，namespace 必须是
 * {@code TargetClassLoader}。
 *
 * <p>实例延迟到首次 {@link #create} 或 {@link #compile} 才创建，并用幂等闸保证
 * 同一命名空间只建一次；{@link #create} 供 P2 改写后填充 ScriptStore 静态字段。
 */
public final class JaninoCompiler {
    private static final String LOADER_CLASS = "org.codehaus.janino.JavaSourceClassLoader";
    private static final String FINDER_CLASS = "org.codehaus.janino.util.resource.ResourceFinder";
    private static final String GAME_FINDER_CLASS = "com.fs.starfarer.loading.Objectsuper";
    private static final String ENCODING = "UTF-8";

    private static final Object LOCK = new Object();
    private static volatile CompilerState state;
    private static final int DIAGNOSTIC_LIMIT = 10;
    private static final AtomicInteger DIAGNOSTICS = new AtomicInteger();
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private JaninoCompiler() {
    }

    /**
     * 返回命名空间内的纯编译器实例。P2 改写方法体时用它填充
     * ScriptStore 的 {@code JavaSourceClassLoader} 静态字段。
     */
    public static Object create(ClassLoader namespace) {
        return obtain(namespace).loader();
    }

    /**
     * 唯一编译入口：返回「类名 → 原始字节码」整张 Map（含内部类与匿名类）。
     * Janino 找不到源码时返回 {@code null}；编译错误以 ClassNotFoundException 抛出。
     */
    public static Map<String, byte[]> compile(ClassLoader namespace, String className)
            throws ClassNotFoundException {
        CompilerState current = obtain(namespace);
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            // 同一线程已在编译中。JavaSourceClassLoader 不可重入：嵌套 generateBytecodes
            // 会复用外层单元的编译器状态，把错误数从 24 叠到 48、72…并让外层整单元失败。
            // 这里按「无源码」放弃，让 Janino 把该符号记成解析失败，外层报错保持干净。
            return null;
        }
        depth[0] = 1;
        resetDiagnostics();
        Object generated;
        try {
            generated = current.generate().invoke(current.loader(), className);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ClassNotFoundException failure) {
                throw failure;
            }
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new ClassNotFoundException("Janino 编译失败: " + className, cause);
        } catch (ReflectiveOperationException exception) {
            throw new ClassNotFoundException("Janino generateBytecodes 不可用: " + className,
                    exception);
        } finally {
            DEPTH.remove();
        }

        if (generated == null) {
            return null;
        }
        Map<?, ?> raw = (Map<?, ?>) generated;
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key
                    && entry.getValue() instanceof byte[] bytes) {
                result.put(key, bytes);
            }
        }
        return result;
    }

    private static CompilerState obtain(ClassLoader namespace) {
        ClassLoader effective = namespace != null
                ? namespace : JaninoCompiler.class.getClassLoader();
        CompilerState current = state;
        if (current != null && current.namespace() == effective) {
            return current;
        }
        synchronized (LOCK) {
            current = state;
            if (current != null && current.namespace() == effective) {
                return current;
            }
            CompilerState created = createRuntime(effective, gameSourceFinder(effective));
            int roots = applySourceRoots(effective, created.loader());
            state = created;
            MixinLog.info("[Janino] compiler=JavaSourceClassLoader finder=PathResourceFinder"
                    + " source-roots=" + roots + " encoding=UTF-8 debug=source,lines,vars"
                    + " namespace=" + effective);
            return created;
        }
    }

    /**
     * 用「游戏核心目录 + 已启用 mod 目录」覆盖构造时传入的 {@code Objectsuper}。
     *
     * <p>{@code Objectsuper} 走的是游戏自己的资源快照（{@code com.fs.util.C}），
     * 实测 0.98a-YHCS 下对 {@code data/**}{@code .java} 大量返回 null：
     * 依赖类于是回落到 target → 重入 {@code generateBytecodes}，
     * 而 {@link org.codehaus.janino.JavaSourceClassLoader} 不可重入，
     * 会把外层单元的错误重复计数（24 → 48 → 72 …）并让整个单元编译失败。
     *
     * <p>{@code setSourcePath} 把查找器换成 {@code PathResourceFinder}，
     * 只按目录命中，顺序是 core 在前、mod 在后，与加载顺序一致。
     * jar 内只带源码不带 class 的情况本环境不存在（38 个 jar 内 .java 都有同名 .class）。
     *
     * @return 实际生效的源码根数量；0 表示退回 Objectsuper
     */
    private static int applySourceRoots(ClassLoader namespace, Object loader) {
        try {
            File[] roots = sourceRoots(namespace);
            if (roots.length == 0) {
                MixinLog.info("[Janino] 未定位到源码根，保留 Objectsuper");
                return 0;
            }
            Method setter = loader.getClass().getMethod("setSourcePath", File[].class);
            setter.invoke(loader, (Object) roots);
            return roots.length;
        } catch (Throwable throwable) {
            MixinLog.info("[Janino] setSourcePath 不可用，保留 Objectsuper: "
                    + throwable.getClass().getSimpleName());
            return 0;
        }
    }

    /** 源码根：core 目录 + target URL 里出现的已启用 mod 目录 + 游戏工作目录。 */
    private static File[] sourceRoots(ClassLoader namespace) {
        Map<String, File> roots = new LinkedHashMap<>();
        File modsRoot = modsRoot();
        if (namespace instanceof URLClassLoader loader) {
            for (URL url : loader.getURLs()) {
                File file = fileOf(url);
                if (file == null) {
                    continue;
                }
                if ("starfarer_obf.jar".equalsIgnoreCase(file.getName())) {
                    addRoot(roots, file.getParentFile());
                } else {
                    addRoot(roots, modDirectory(file, modsRoot));
                }
            }
        }
        addRoot(roots, new File(System.getProperty("user.dir", ".")));
        List<File> ordered = new ArrayList<>(roots.values());
        return ordered.toArray(new File[0]);
    }

    private static File modsRoot() {
        String configured = System.getProperty("com.fs.starfarer.settings.paths.mods");
        if (configured == null || configured.isBlank()) {
            return null;
        }
        File directory = new File(configured).getAbsoluteFile();
        return directory.isDirectory() ? directory : null;
    }

    /**
     * 从 jar 路径反推 mod 目录。优先按系统属性给的 mods 根做相对化，
     * 属性缺失（第三方启动器没设）时退回「路径里最后一个 /mods/ 之后的那一级」。
     */
    private static File modDirectory(File file, File modsRoot) {
        try {
            String path = file.getCanonicalPath();
            if (modsRoot != null) {
                String mods = modsRoot.getCanonicalPath();
                if (path.startsWith(mods + File.separator)) {
                    String relative = path.substring(mods.length() + 1);
                    int separator = relative.indexOf(File.separatorChar);
                    if (separator > 0) {
                        return new File(modsRoot, relative.substring(0, separator));
                    }
                }
            }
            String marker = File.separator + "mods" + File.separator;
            int index = path.lastIndexOf(marker);
            if (index < 0) {
                return null;
            }
            String rest = path.substring(index + marker.length());
            int separator = rest.indexOf(File.separatorChar);
            if (separator <= 0) {
                return null;
            }
            return new File(path.substring(0, index + marker.length()),
                    rest.substring(0, separator));
        } catch (IOException ignored) {
            return null;
        }
    }

    private static File fileOf(URL url) {
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }
        try {
            return new File(url.toURI());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void addRoot(Map<String, File> roots, File directory) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        try {
            roots.putIfAbsent(directory.getCanonicalPath(), directory.getCanonicalFile());
        } catch (IOException ignored) {
            roots.putIfAbsent(directory.getAbsolutePath(), directory.getAbsoluteFile());
        }
    }

    /** 供离线装置通过反射注入自定义源码查找器；产品路径始终走游戏 Objectsuper。 */
    private static CompilerState createRuntime(ClassLoader namespace, Object finder) {
        try {
            Class<?> loaderType = Class.forName(LOADER_CLASS, true, namespace);
            Class<?> finderType = Class.forName(FINDER_CLASS, true, namespace);

            Constructor<?> constructor = loaderType.getConstructor(
                    ClassLoader.class, finderType, String.class);
            Object loader = constructor.newInstance(namespace, finder, ENCODING);

            Method debuggingInfo = loaderType.getMethod(
                    "setDebuggingInfo", boolean.class, boolean.class, boolean.class);
            debuggingInfo.invoke(loader, true, true, true);

            installDiagnostics(namespace, loaderType, loader);

            Method generate = loaderType.getDeclaredMethod("generateBytecodes", String.class);
            generate.setAccessible(true);
            return new CompilerState(namespace, loader, generate);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("无法创建纯 Janino 编译器", cause);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("无法创建纯 Janino 编译器", exception);
        }
    }

    private static Object gameSourceFinder(ClassLoader namespace) {
        try {
            Class<?> type = Class.forName(GAME_FINDER_CLASS, true, namespace);
            return type.getDeclaredConstructor().newInstance();
        } catch (Throwable throwable) {
            MixinLog.error("[Janino] 游戏源码查找器不可用: " + GAME_FINDER_CLASS, throwable);
            throw new IllegalStateException("游戏源码查找器不可用: " + GAME_FINDER_CLASS,
                    throwable);
        }
    }

    /**
     * 把 Janino 的逐条诊断接到 MixinLib 日志上。
     *
     * <p>{@code generateBytecodes} 失败时只抛「N error(s) while compiling unit ...」，
     * 逐条原因（行号 + 符号名）默认被丢弃，实机上等于不可诊断。这里用动态代理实现
     * target 命名空间里的 {@code ErrorHandler} / {@code WarningHandler}
     * （两个接口只对 target 可见，本类在系统加载器里无法静态实现）。
     */
    private static void installDiagnostics(ClassLoader namespace, Class<?> loaderType,
                                           Object loader) {
        try {
            Class<?> errorHandler = Class.forName(
                    "org.codehaus.commons.compiler.ErrorHandler", true, namespace);
            Class<?> warningHandler = Class.forName(
                    "org.codehaus.commons.compiler.WarningHandler", true, namespace);
            InvocationHandler handler = (proxy, method, arguments) -> {
                // 只收 ERROR。Janino 的 WARN 全是风格告警（缺 @Override、局部变量带下划线、
                // 缺 Javadoc），在这个项目里没有诊断价值，还会把 ERROR 的配额挤掉。
                if ("handleError".equals(method.getName()) && arguments != null
                        && arguments.length >= 2) {
                    report("ERROR", String.valueOf(arguments[0]), arguments[1]);
                }
                return null;
            };
            Object errors = Proxy.newProxyInstance(
                    errorHandler.getClassLoader(), new Class<?>[]{errorHandler}, handler);
            Object warnings = Proxy.newProxyInstance(
                    warningHandler.getClassLoader(), new Class<?>[]{warningHandler},
                    (proxy, method, arguments) -> null);
            loaderType.getMethod("setCompileErrorHandler", errorHandler)
                    .invoke(loader, errors);
            loaderType.getMethod("setWarningHandler", warningHandler)
                    .invoke(loader, warnings);
        } catch (Throwable throwable) {
            MixinLog.info("[Janino] 编译诊断未接入: " + throwable.getClass().getSimpleName());
        }
    }

    private static void report(String level, String message, Object location) {
        int index = DIAGNOSTICS.incrementAndGet();
        if (index > DIAGNOSTIC_LIMIT) {
            if (index == DIAGNOSTIC_LIMIT + 1) {
                MixinLog.info("[Janino] diag ... 后续诊断已省略（上限 "
                        + DIAGNOSTIC_LIMIT + "）");
            }
            return;
        }
        MixinLog.info("[Janino] diag " + level + " " + message
                + (location == null ? "" : " @ " + location));
    }

    /**
     * 当前线程是否正处在一次编译里。编译器解析歧义名时会大量回问类加载器，
     * 这些请求必然在编译期内发生；调用方据此把「探测」与「真的没有源码」分开。
     */
    public static boolean isCompiling() {
        return DEPTH.get()[0] > 0;
    }

    /** 每次编译前重置诊断配额，避免一次坏编译刷满日志。 */
    static void resetDiagnostics() {
        DIAGNOSTICS.set(0);
    }

    private record CompilerState(ClassLoader namespace, Object loader, Method generate) {
    }
}
