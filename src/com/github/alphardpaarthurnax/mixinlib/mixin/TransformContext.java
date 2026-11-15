package com.github.alphardpaarthurnax.mixinlib.mixin;

import com.github.alphardpaarthurnax.mixinlib.loader.ClassOrigin;

/** 保存当前线程正在转换的类、加载器、字节与来源类别。 */
public final class TransformContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TransformContext() {
    }

    public static Scope enter(ClassLoader loader, String className, byte[] bytes,
                              ClassOrigin origin) {
        State previous = CURRENT.get();
        CURRENT.set(new State(loader, className, bytes, origin));
        return new Scope(previous);
    }

    public static ClassLoader loader() {
        State state = CURRENT.get();
        return state == null ? null : state.loader;
    }

    public static byte[] bytes(String className) {
        State state = CURRENT.get();
        return state != null && state.className.equals(className) ? state.bytes : null;
    }

    /** 当前类的字节来源；无转换上下文时按 HOST 处理（父加载器可见）。 */
    public static ClassOrigin origin() {
        State state = CURRENT.get();
        return state == null ? ClassOrigin.HOST : state.origin;
    }

    private record State(ClassLoader loader, String className, byte[] bytes, ClassOrigin origin) {
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;

        Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
