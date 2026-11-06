package com.ryjs.madnesskiller.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public final class ThreadStopper {

    private static final Set<String> PROTECTED_NAMES = Set.of(
            "main", "Server thread", "Render thread", "Server Infinisleeper", "Snooper Starter Thread",
            "DestroyJavaVM", "DestroyJavaVM Thread", "Reference Handler", "Finalizer", "Signal Dispatcher",
            "Attach Listener", "Common-Cleaner", "Notification Thread", "AWT-Shutdown", "AWT-Windows");

    private ThreadStopper() {
    }

    private record Entry(Thread thread, String entryClass, boolean madnessOwned, boolean guarded) {
    }

    public static void stopOnce() {
        List<Entry> targets = new ArrayList<>();
        for (Entry entry : inspect()) {
            if (entry.madnessOwned() && !entry.guarded() && entry.thread() != Thread.currentThread()) {
                targets.add(entry);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        setTheirThreadSwitch();
        for (Entry entry : targets) {
            clearLoopFlags(readTarget(threadTargetField(), entry.thread()));
        }
        for (Entry entry : targets) {
            try {
                entry.thread().interrupt();
            } catch (Throwable ignored) {

            }
        }
        shutdownExecutors();

        waitQuietly(400L, targets);
    }

    private static List<Entry> inspect() {
        List<Entry> out = new ArrayList<>();
        Field targetField = threadTargetField();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String entryClass = entryClassOf(thread);
            Runnable target = readTarget(targetField, thread);
            out.add(new Entry(thread, entryClass, isOwned(entryClass, target, thread), isProtected(thread)));
        }
        return out;
    }

    private static String entryClassOf(Thread thread) {
        StackTraceElement[] stack = thread.getStackTrace();
        for (int i = stack.length - 1; i >= 0; i--) {
            if (stack[i].getClassName().equals("java.lang.Thread") && "run".equals(stack[i].getMethodName()) && i > 0) {
                return stack[i - 1].getClassName();
            }
        }
        return null;
    }

    private static boolean isOwned(String entryClass, Runnable target, Thread thread) {
        if (entryClass != null && isMadnessClassName(entryClass)) {
            return true;
        }
        if (target != null && MadnessReflect.isMadnessOwnedClass(target.getClass())) {
            return true;
        }
        for (StackTraceElement element : thread.getStackTrace()) {
            if (isMadnessClassName(element.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMadnessClassName(String className) {
        if (className == null) {
            return false;
        }
        if (className.startsWith("net.madness.") || className.startsWith("net.minecraft.madcrazy")
                || className.startsWith("net.minecraft.crazyentity.")) {
            return true;
        }
        String pkg = MadnessReflect.dynamicPackage();
        return pkg != null && className.startsWith(pkg + ".");
    }

    private static boolean isProtected(Thread thread) {
        String name = thread.getName() == null ? "" : thread.getName();
        if (PROTECTED_NAMES.contains(name)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("netty") || lower.startsWith("worker-") || lower.startsWith("gc ")
                || lower.startsWith("vm ") || lower.startsWith("compiler") || lower.startsWith("scheduled-")
                || lower.startsWith("pool-") || lower.startsWith("forkjoinpool") || lower.startsWith("filesystem")
                || lower.startsWith("java") || lower.startsWith("reference") || lower.startsWith("finalizer")
                || lower.contains("janitor") || lower.contains("destroyjavavm") || lower.contains("common-cleaner")
                || lower.contains("packet handler") || lower.contains("server thread") || lower.contains("render thread");
    }

    private static Field threadTargetField() {
        try {
            Field f = Thread.class.getDeclaredField("target");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Runnable readTarget(Field targetField, Thread thread) {
        if (targetField == null) {
            return null;
        }
        try {
            Object value = targetField.get(thread);
            return value instanceof Runnable runnable ? runnable : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setTheirThreadSwitch() {
        try {
            Class<?> transformers = Class.forName("net.minecraft.madcrazy.Transformers", false, currentLoader());
            Field f = MadnessReflect.findField(transformers, "threadSuspended");
            if (f != null) {
                f.setAccessible(true);
                f.setBoolean(null, true);
            }
        } catch (Throwable ignored) {

        }
    }

    private static void clearLoopFlags(Runnable target) {
        if (target == null) {
            return;
        }
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != boolean.class) {
                    continue;
                }
                if (!looksLikeLoopFlag(f.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.getBoolean(target)) {
                        f.setBoolean(target, false);
                    }
                } catch (Throwable ignored) {

                }
            }
        }
    }

    private static boolean looksLikeLoopFlag(String lower) {
        return lower.contains("running") || lower.contains("alive") || lower.contains("loop")
                || lower.contains("enable") || lower.contains("start") || lower.contains("ticking")
                || lower.contains("suspend") || lower.contains("active") || lower.equals("run");
    }

    private static void shutdownExecutors() {
        Set<Object> seen = new LinkedHashSet<>();
        List<Class<?>> classes = new ArrayList<>(MadnessReflect.probeClasses());
        for (String name : new String[]{"net.minecraft.madcrazy.Transformers",
                "net.minecraft.madcrazy.ModLoadingHelper3",
                "net.minecraft.madcrazy.MadnessEntities"}) {
            try {
                classes.add(Class.forName(name, false, currentLoader()));
            } catch (Throwable ignored) {

            }
        }
        for (Class<?> k : classes) {
            for (Field f : MadnessReflect.allStaticFields(k, Object.class)) {
                Object value = MadnessReflect.safeValue(f, null);
                if (!(value instanceof ExecutorService service) || !seen.add(service)) {
                    continue;
                }
                for (String methodName : new String[]{"shutdownNow", "shutdown"}) {
                    try {
                        Method m = service.getClass().getMethod(methodName);
                        m.setAccessible(true);
                        m.invoke(service);
                        break;
                    } catch (Throwable ignored) {

                    }
                }
            }
        }
    }

    private static void waitQuietly(long millis, List<Entry> targets) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            boolean anyAlive = false;
            for (Entry entry : targets) {
                if (entry.thread().isAlive()) {
                    anyAlive = true;
                    break;
                }
            }
            if (!anyAlive) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ClassLoader currentLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ThreadStopper.class.getClassLoader();
    }
}
