package com.github.alphardpaarthurnax.mixinlib.loader;

import com.github.alphardpaarthurnax.mixinlib.bytecode.BytePreprocessor;
import com.github.alphardpaarthurnax.mixinlib.janino.JaninoClassSource;
import com.github.alphardpaarthurnax.mixinlib.janino.StarsectorTransformer;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;
import com.github.alphardpaarthurnax.mixinlib.logger.TransformDiagnostics;
import com.github.alphardpaarthurnax.mixinlib.mixin.InjectorSnapshot;
import com.github.alphardpaarthurnax.mixinlib.mixin.TransformContext;
import com.github.alphardpaarthurnax.mixinlib.mixin.TransformerExclusions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * 在同一命名空间中定义 Starsector 核心类和已启用 JAR Mod 的类。
 * 宿主与 Mixin 包仍通过父加载器保持可见。
 */
public final class TargetClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> resourceOverrides = new LinkedHashMap<>();
    private final Set<String> invalidClasses = Collections.synchronizedSet(new LinkedHashSet<>());
    private final ThreadLocal<Set<String>> defining = ThreadLocal.withInitial(LinkedHashSet::new);
    private final Set<String> coreOrigins = ConcurrentHashMap.newKeySet();
    private final Set<String> modOrigins = ConcurrentHashMap.newKeySet();
    private final Set<String> unresolvedOrigins = ConcurrentHashMap.newKeySet();
    private final Set<String> janinoClasses = ConcurrentHashMap.newKeySet();
    private volatile IMixinTransformer transformer;

    public TargetClassLoader(URL[] urls, ClassLoader parent) {
        super("MixinLib-Target", urls, parent);
        // 只登记命名空间，不创建编译器、不参与加载路径（P4b）。
        JaninoClassSource.bind(this);
        // D6：帧重算按 target 命名空间的 class 字节解析继承层级。
        BytePreprocessor.bind(this);
        MixinLog.info("[ParentFirst] " + ParentFirstRules.describe());
    }

    public void setTransformer(IMixinTransformer value) {
        if (value == null) {
            throw new IllegalArgumentException("transformer");
        }
        transformer = value;
    }

    /**
     * 注入 core / mod 两组 jar 的 canonical path，用于给 findClass 命中的字节归类。
     * 只影响类别与诊断，不改变任何加载路径。
     *
     * <p>顺带核对一条硬约束：URL 顺序必须是「全部 core → 全部 mod」。
     * {@code URLClassLoader.findResource} 取首个命中，顺序正确时即等价于
     * 「游戏核心优先于 mod jar」；顺序被改动会导致同名类来源漂移。
     */
    public void configureOrigins(Set<String> corePaths, Set<String> modPaths) {
        coreOrigins.clear();
        modOrigins.clear();
        unresolvedOrigins.clear();
        addPaths(coreOrigins, corePaths);
        addPaths(modOrigins, modPaths);
        MixinLog.info("[Origin] configured core=" + coreOrigins.size()
                + " mod=" + modOrigins.size());
        announceLoadOrder();
    }

    /** 显式声明并校验四级加载顺序，避免顺序靠「拼 URL 时的巧合」维持。 */
    private void announceLoadOrder() {
        int lastCore = -1;
        int firstMod = Integer.MAX_VALUE;
        URL[] urls = getURLs();
        for (int index = 0; index < urls.length; index++) {
            String path = canonicalPathOfUrl(urls[index]);
            if (path == null) {
                continue;
            }
            if (coreOrigins.contains(path)) {
                lastCore = index;
            } else if (modOrigins.contains(path) && index < firstMod) {
                firstMod = index;
            }
        }
        boolean ordered = lastCore < 0 || firstMod == Integer.MAX_VALUE || lastCore < firstMod;
        String summary = "[Order] parent-first=" + ParentFirstRules.effectivePrefixes().size()
                + " prefixes -> core=" + coreOrigins.size()
                + " jars -> mod=" + modOrigins.size()
                + " jars -> janino -> generated"
                + " core-before-mod=" + (ordered ? "OK" : "VIOLATED");
        if (ordered) {
            MixinLog.info(summary);
        } else {
            MixinLog.error(summary, new IllegalStateException(
                    "core/mod URL order violated: lastCore=" + lastCore + " firstMod=" + firstMod));
        }
    }

    private static void addPaths(Set<String> target, Set<String> paths) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            String canonical = canonicalPath(path);
            if (canonical != null) {
                target.add(canonical);
            }
        }
    }

    /** 由 jar 的 codeSourceUrl 判定字节来源；供诊断与离线装置验证。 */
    public ClassOrigin originFor(URL codeSourceUrl) {
        String path = canonicalPathOfUrl(codeSourceUrl);
        if (path == null) {
            return ClassOrigin.HOST;
        }
        if (containsPath(modOrigins, path)) {
            return ClassOrigin.MOD;
        }
        if (containsPath(coreOrigins, path)) {
            return ClassOrigin.CORE;
        }
        if (unresolvedOrigins.add(path)) {
            MixinLog.info("[Origin] unresolved source=" + codeSourceUrl);
        }
        return ClassOrigin.CORE;
    }

    /**
     * P4c 之后 JANINO 类的唯一定义入口。P4a 只建立入口，尚不参与加载路径。
     * 专用注册表保证同名类不会被重复定义，日志固定带 origin=JANINO。
     */
    public Class<?> defineJaninoClass(String name, byte[] bytes) {
        if (!janinoClasses.add(name)) {
            throw new IllegalStateException("Duplicate Janino class definition: " + name);
        }
        definePackageIfNecessary(name, null, null);
        MixinLog.info("[Janino] origin=JANINO class=" + name + " bytes=" + bytes.length);
        return defineClass(name, bytes, 0, bytes.length,
                new ProtectionDomain(new CodeSource(null, (Certificate[]) null), null, this, null));
    }

    private static boolean containsPath(Set<String> paths, String candidate) {
        if (paths.contains(candidate)) {
            return true;
        }
        for (String path : paths) {
            if (path.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException ignored) {
            return new File(path).getAbsolutePath();
        }
    }

    private static String canonicalPathOfUrl(URL url) {
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }
        try {
            return new File(url.toURI()).getCanonicalPath();
        } catch (Exception primary) {
            try {
                return new File(url.getPath()).getCanonicalPath();
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    public void addResourceOverride(String name, byte[] bytes) {
        byte[] previous = resourceOverrides.putIfAbsent(name, bytes);
        if (previous != null) {
            throw new IllegalStateException("Duplicate target resource " + name);
        }
    }

    public void registerInvalidClass(String className) {
        invalidClasses.add(className);
    }

    public boolean isLoaded(String className) {
        synchronized (getClassLoadingLock(className)) {
            return findLoadedClass(className) != null;
        }
    }

    public byte[] readRawClassBytes(String className) throws IOException {
        byte[] inFlight = TransformContext.bytes(className);
        if (inFlight != null) {
            return inFlight;
        }
        URL resource = findResource(className.replace('.', '/') + ".class");
        return resource == null ? null : read(resource.openStream());
    }

    public InputStream openTargetResource(String name) {
        byte[] override = resourceOverrides.get(name);
        if (override != null) {
            return new ByteArrayInputStream(override);
        }
        URL resource = findResource(name);
        try {
            return resource == null ? null : resource.openStream();
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    try {
                        loaded = getParent().loadClass(name);
                    } catch (ClassNotFoundException parentMiss) {
                        // Java 17 已不再包含部分 javax API；Starsector 会在自己的核心 JAR
                        // 中提供这些 API，例如 JAXB。
                        loaded = findClass(name);
                    }
                } else {
                    boolean targetOwnsResource = findResource(name.replace('.', '/') + ".class") != null;
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException targetMiss) {
                        // 脚本类（走过 Janino 编译）必须保留原始失败原因，不能被父加载器的
                        // ClassNotFoundException 覆盖，否则实机只剩一行「找不到类」。
                        if (targetOwnsResource || JaninoClassSource.wasAttempted(name)) {
                            throw targetMiss;
                        }
                        loaded = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                try {
                    resolveClass(loaded);
                } catch (RuntimeException | LinkageError throwable) {
                    MixinLog.error("[Diag:RESOLVE_FAIL] class=" + name
                            + " thread=" + TransformDiagnostics.threadDescription()
                            + " loader=" + TransformDiagnostics.loaderDescription(this), throwable);
                    throw throwable;
                }
            }
            return loaded;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) {
            ClassNotFoundException failure =
                    new ClassNotFoundException("Mixin marked class invalid: " + name);
            MixinLog.error("[Diag:DEFINE_REJECTED] class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " reason=mixin-invalid", failure);
            throw failure;
        }
        Set<String> active = defining.get();
        if (!active.add(name)) {
            if (JaninoClassSource.isCompiling()) {
                // Janino 编译期会通过 parent 回查正在定义的脚本类，属正常探测：
                // 抛 CNFE 让编译器改用源码，不记 ERROR、不输出堆栈。
                throw new ClassNotFoundException("Janino compilation lookup: " + name);
            }
            ClassNotFoundException failure =
                    new ClassNotFoundException("Recursive class definition: " + name);
            MixinLog.error("[Diag:DEFINE_RECURSIVE] class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this)
                    + " definitionStack=" + TransformDiagnostics.definitionStack(active), failure);
            throw failure;
        }

        URL resource = null;
        try {
            String resourceName = name.replace('.', '/') + ".class";
            resource = findResource(resourceName);
            if (resource == null) {
                return defineGeneratedClass(name);
            }

            ClassMaterial material = readClassMaterial(resource);
            ClassOrigin origin = originFor(material.codeSourceUrl);
            byte[] transformed = transform(name, material.bytes, origin);
            definePackageIfNecessary(name, material.manifest, material.codeSourceUrl);
            CodeSource source = new CodeSource(material.codeSourceUrl, material.certificates);
            ProtectionDomain domain = new ProtectionDomain(source, null, this, null);
            Class<?> defined = defineClass(name, transformed, 0, transformed.length, domain);
            if (TransformDiagnostics.isWatched(name)) {
                MixinLog.info("[Diag:DEFINE_OK] class=" + name
                        + " thread=" + TransformDiagnostics.threadDescription()
                        + " loader=" + TransformDiagnostics.loaderDescription(this)
                        + " source=" + material.codeSourceUrl
                        + " origin=" + origin
                        + " rawBytes=" + material.bytes.length
                        + " definedBytes=" + transformed.length);
            }
            return defined;
        } catch (ClassNotFoundException throwable) {
            // 没有 .class 资源时的 ClassNotFoundException 是「这次查找确实不存在」
            // （JavaBeans Introspector 的 XxxBeanInfo、Janino 的包名探测都会走到这里），
            // 属于正常的否命中，不是定义失败；只有拿到字节却定义不出来才是 ERROR。
            if (resource != null) {
                MixinLog.error("[Diag:DEFINE_FAIL] class=" + name
                        + " stage=class-not-found"
                        + " thread=" + TransformDiagnostics.threadDescription()
                        + " loader=" + TransformDiagnostics.loaderDescription(this)
                        + " resource=" + resource
                        + " definitionStack=" + TransformDiagnostics.definitionStack(active), throwable);
            }
            throw throwable;
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:DEFINE_FAIL] class=" + name
                    + " stage=define-or-link"
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this)
                    + " resource=" + resource
                    + " definitionStack=" + TransformDiagnostics.definitionStack(active), throwable);
            throw new ClassNotFoundException("Unable to define target class " + name, throwable);
        } finally {
            active.remove(name);
            if (active.isEmpty()) {
                defining.remove();
            }
        }
    }

    private Class<?> defineGeneratedClass(String name) throws ClassNotFoundException {
        // 脚本类没有 jar 资源：先走唯一扩展缝；mixin 合成类与「无源码」回退生成器。
        if (!isMixinGeneratedName(name)) {
            Class<?> script = defineJaninoClassIfAvailable(name);
            if (script != null) {
                return script;
            }
        }
        return generateMixinClass(name);
    }

    private static boolean isMixinGeneratedName(String name) {
        return name.startsWith("org.spongepowered.asm.synthetic.");
    }

    /**
     * E 组主链路：JaninoClassSource（唯一扩展缝）→ EC-9 sanity → transform()
     * （内含 BytePreprocessor + mixin 织入）→ defineJaninoClass。
     * 任何环节返回 null 都回退到原有生成器，绝不把可疑字节交给 defineClass。
     */
    private Class<?> defineJaninoClassIfAvailable(String name) {
        Class<?> already = findLoadedClass(name);
        if (already != null) {
            return already;
        }
        byte[] original;
        try {
            original = JaninoClassSource.bytecodesFor(name);
        } catch (Throwable throwable) {
            MixinLog.error("[Janino] 取编译字节码失败 class=" + name, throwable);
            return null;
        }
        if (original == null) {
            return null;
        }
        String rejection = BytePreprocessor.rejection(name, original);
        if (rejection != null) {
            MixinLog.error("[Sanity] 拒绝定义可疑字节 class=" + name + " reason=" + rejection,
                    new IllegalStateException("EC-9 rejection: " + rejection));
            return null;
        }
        try {
            byte[] transformed = transform(name, original, ClassOrigin.JANINO);
            return defineJaninoClass(name, transformed);
        } catch (Throwable throwable) {
            MixinLog.error("[Janino] 定义脚本类失败 class=" + name, throwable);
            return null;
        }
    }

    private Class<?> generateMixinClass(String name) throws ClassNotFoundException {
        IMixinTransformer value = transformer;
        if (value == null) {
            throw new ClassNotFoundException(name);
        }
        try {
            synchronized (value) {
                try (TransformContext.Scope ignored = TransformContext.enter(
                        this, name, new byte[0], ClassOrigin.GENERATED);
                     TransformDiagnostics.Scope diagnostic =
                             TransformDiagnostics.enter("generate", this, name,
                                     ClassOrigin.GENERATED.name())) {
                    byte[] generated = value.generateClass(MixinEnvironment.getDefaultEnvironment(), name);
                    if (generated == null) {
                        throw new ClassNotFoundException(name);
                    }
                    definePackageIfNecessary(name, null, null);
                    MixinLog.info("[Generate] " + name + " origin=" + ClassOrigin.GENERATED
                            + " bytes=" + generated.length);
                    return defineClass(name, generated, 0, generated.length,
                            new ProtectionDomain(new CodeSource(null, (Certificate[]) null), null, this, null));
                }
            }
        } catch (ClassNotFoundException throwable) {
            throw throwable;
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:GENERATE_FAIL] class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this), throwable);
            throw new ClassNotFoundException("Unable to generate Mixin class " + name, throwable);
        }
    }

    private byte[] transform(String name, byte[] original, ClassOrigin origin) throws Throwable {
        // G11：config 声明的 plugin 类不参与 mixin 织入，命中即原样定义。
        String exclusion = TransformerExclusions.reasonFor(name);
        if (exclusion != null) {
            MixinLog.info("[Exclusion] class=" + name + " reason=" + exclusion);
            return original;
        }
        // D6 统一前置处理：jar 类与 JANINO 脚本类两条定义链路共用同一入口（P4c 直通）。
        byte[] prepared = BytePreprocessor.prepare(name, original, origin);
        prepared = StarsectorTransformer.transform(name, prepared);
        IMixinTransformer value = transformer;
        if (value == null) {
            return prepared;
        }
        try {
            synchronized (value) {
                try (TransformContext.Scope ignored = TransformContext.enter(
                        this, name, prepared, origin);
                     TransformDiagnostics.Scope diagnostic =
                             TransformDiagnostics.enter("target", this, name, origin.name())) {
                    MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
                    if (!value.couldTransformClass(environment, name)) {
                        return prepared;
                    }
                    byte[] result = value.transformClass(environment, name, prepared);
                    // ③ 自检必须晚于 onSelect()（S1-T1）；首次真正 mixin transform 之后触发一次。
                    InjectorSnapshot.runSelfCheckIfNeeded();
                    if (result != null && result != prepared) {
                        MixinLog.info("[Transform] target " + name + " origin=" + origin
                                + " " + prepared.length + " -> " + result.length);
                    }
                    return result == null ? prepared : result;
                }
            }
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:TRANSFORM_FAIL] kind=target class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this), throwable);
            throw throwable;
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] override = resourceOverrides.get(name);
        return override == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(override);
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        LinkedHashSet<URL> resources = new LinkedHashSet<>();
        Enumeration<URL> own = findResources(name);
        while (own.hasMoreElements()) {
            resources.add(own.nextElement());
        }
        Enumeration<URL> parent = getParent().getResources(name);
        while (parent.hasMoreElements()) {
            resources.add(parent.nextElement());
        }
        return Collections.enumeration(resources);
    }

    private static boolean isParentFirst(String name) {
        // 规则独立成类，允许不同环境 / 插件通过 ParentFirstRules 增删前缀。
        return ParentFirstRules.isParentFirst(name);
    }

    private void definePackageIfNecessary(String className, Manifest manifest, URL source) {
        int split = className.lastIndexOf('.');
        if (split < 0) {
            return;
        }
        String packageName = className.substring(0, split);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        try {
            if (manifest != null && source != null) {
                definePackage(packageName, manifest, source);
            } else {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException ignored) {
            // 另一个并行类定义已经率先定义了该包。
        }
    }

    private static ClassMaterial readClassMaterial(URL resource) throws IOException {
        URLConnection connection = resource.openConnection();
        Manifest manifest = null;
        URL source = resource;
        JarEntry entry = null;
        Certificate[] certificates = null;

        // LunaLib 的版本检查器会全局禁用 URLConnection 缓存。缓存关闭时，关闭
        // JarURLConnection 的条目流也会关闭对应 JarFile，因此所有依赖 JarFile
        // 的元数据都必须在流关闭前取得。证书只会在条目读取完成后填充，并会继续
        // 保存在 JarEntry 对象中。
        if (connection instanceof JarURLConnection jarConnection) {
            manifest = jarConnection.getManifest();
            source = jarConnection.getJarFileURL();
            entry = jarConnection.getJarEntry();
        }

        byte[] bytes;
        try (InputStream stream = connection.getInputStream()) {
            bytes = read(stream);
        }
        if (entry != null) {
            certificates = entry == null ? null : entry.getCertificates();
        }
        return new ClassMaterial(bytes, manifest, source, certificates);
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private record ClassMaterial(byte[] bytes, Manifest manifest, URL codeSourceUrl,
                                 Certificate[] certificates) {
    }
}
