package com.ryjs.madnesskiller.util;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class MadnessReflect {

    public static final boolean DEBUG = false;

    public static final Logger LOGGER = DEBUG ? LogUtils.getLogger() : NOPLogger.NOP_LOGGER;

    public static final String GOD_ENTITY_CLASS = "net.madness.entity.C849613";

    public static final String LOADER_CLASS = "net.minecraft.madcrazy.ModLoadingHelper3";

    public static final String REMOVER_CLASS = "C840004";

    public static final String TAG_DEATH_CRAZY = "DeathCrazy";

    public static final Class<?>[] P_ENTITY = {Entity.class};
    public static final Class<?>[] P_ENTITY_FLOAT = {Entity.class, float.class};
    public static final Class<?>[] P_ENTITY_REASON = {Entity.class, Entity.RemovalReason.class};
    public static final Class<?>[] P_ENTITY_LEVEL = {Entity.class, Level.class};
    public static final Class<?>[] P_ENTITY_PLAYER = {Entity.class, net.minecraft.world.entity.player.Player.class};

    private static final Map<String, MethodHandle> MH_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> OBSERVED = Collections.newSetFromMap(new WeakHashMap<>());

    private static final List<java.lang.ref.WeakReference<Entity>> OBSERVED_INSTANCES = new ArrayList<>();
    private static final Set<String> VANILLA_SUBPKG = Set.of(
            "world", "server", "client", "core", "nbt", "network", "resources", "commands", "tags",
            "data", "util", "sounds", "locale", "advancements", "recipes", "math", "structures",
            "item", "block", "entities", "effects", "gui", "chat", "player", "mobs", "model", "level");

    private static volatile boolean triedResolve;
    private static String dynamicPackage;
    private static ClassLoader modLoader;
    private static Class<?> removerClass;
    private static Class<?> godEntityClass;
    private static Class<?> hostClass;

    private static final List<Field> entityCollectionFields = new ArrayList<>();
    private static boolean entityFieldsProbed;
    private static Collection<?> HOST_REGISTRY;

    private MadnessReflect() {
    }

    public static void observe(Entity entity) {
        if (entity == null) {
            return;
        }
        OBSERVED.add(entity.getClass());
        String name = entity.getClass().getName();
        boolean interesting = name.startsWith("net.madness.")
                || name.startsWith("net.minecraft.crazyentity.")
                || (dynamicPackage != null && name.startsWith(dynamicPackage + "."));
        if (!interesting) {
            return;
        }
        synchronized (OBSERVED_INSTANCES) {
            OBSERVED_INSTANCES.removeIf(ref -> ref.get() == null);
            boolean seen = false;
            for (java.lang.ref.WeakReference<Entity> ref : OBSERVED_INSTANCES) {
                if (ref.get() == entity) {
                    seen = true;
                    break;
                }
            }
            if (!seen && OBSERVED_INSTANCES.size() < 48) {
                OBSERVED_INSTANCES.add(new java.lang.ref.WeakReference<>(entity));
            }
        }

        if (removerClass == null) {
            triedResolve = false;
        }
    }

    private static List<Entity> observedEntities() {
        List<Entity> out = new ArrayList<>();
        synchronized (OBSERVED_INSTANCES) {
            for (java.lang.ref.WeakReference<Entity> ref : OBSERVED_INSTANCES) {
                Entity e = ref.get();
                if (e != null && !e.isRemoved()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    public static synchronized boolean resolve() {
        if (triedResolve) {
            return removerClass != null;
        }
        triedResolve = true;

        discoverFromInstances();

        discoverFromLoaderClass();

        discoverFromStableClass();

        if (dynamicPackage == null || dynamicPackage.isBlank()) {
            dynamicPackage = "net.minecraft.crazyentity";
            LOGGER.warn("[madnesskiller] 未能确定随机包名，退回默认值 {}", dynamicPackage);
        }

        locateRemover();
        return removerClass != null;
    }

    private static void discoverFromInstances() {
        for (Entity entity : observedEntities()) {
            Class<?> c = entity.getClass();
            if (modLoader == null && c.getClassLoader() != null) {
                modLoader = c.getClassLoader();
                LOGGER.info("[madnesskiller] 由实体 {} 反查到专用 ClassLoader = {}", c.getName(), loaderName(modLoader));
            }
            Field target = findField(c, "target");
            if (target == null) {
                continue;
            }
            if (godEntityClass == null && c.getName().startsWith("net.madness.")) {
                godEntityClass = c;
                LOGGER.info("[madnesskiller] god 实体类（从实例）= {}", c.getName());
            }
            Object host = safeValue(target, entity);
            Class<?> declared = target.getType();
            if (hostClass == null) {
                hostClass = declared;
                LOGGER.info("[madnesskiller] 宿主基类（target 声明类型）= {}", declared.getName());
            }
            if (host != null) {
                if (modLoader == null && host.getClass().getClassLoader() != null) {
                    modLoader = host.getClass().getClassLoader();
                }
                String pkg = host.getClass().getPackageName();
                if (looksDynamic(pkg) && !pkg.equals(dynamicPackage)) {
                    dynamicPackage = pkg;
                    LOGGER.info("[madnesskiller] 由宿主实例 {} 反查随机包名 = {}", host.getClass().getName(), pkg);
                }
            }
        }
    }

    private static void discoverFromLoaderClass() {
        Class<?> mlh = tryLoad(LOADER_CLASS, true);
        if (mlh == null) {
            LOGGER.warn("[madnesskiller] 无法加载 {}（MadnessEntities 未安装或未启动？）", LOADER_CLASS);
            return;
        }
        if (dynamicPackage == null || dynamicPackage.isBlank()) {
            Object n = readStatic(mlh, "name");
            if (n != null) {
                dynamicPackage = "net.minecraft." + n;
                LOGGER.info("[madnesskiller] ModLoadingHelper3.name -> {}", dynamicPackage);
            }
        }
        if (modLoader == null) {

            for (Field f : allStaticFields(mlh, Object.class)) {
                Object v = safeValue(f, null);
                if (v instanceof ClassLoader loader) {
                    modLoader = loader;
                    LOGGER.info("[madnesskiller] ModLoadingHelper3 的 ClassLoader 字段 = {} ({})", f.getName(), loaderName(loader));
                    break;
                }
            }
            if (modLoader == null) {
                LOGGER.warn("[madnesskiller] {} 里没找到任何 ClassLoader 型静态字段", LOADER_CLASS);
            }
        }
    }

    private static void discoverFromStableClass() {
        godEntityClass = godEntityClass != null ? godEntityClass : tryLoad(GOD_ENTITY_CLASS, false);
        if (godEntityClass == null) {
            LOGGER.warn("[madnesskiller] {} 未能通过 Class.forName 加载（可能只能从实体实例反查）", GOD_ENTITY_CLASS);
            return;
        }
        Field target = findField(godEntityClass, "target");
        if (target == null) {
            LOGGER.warn("[madnesskiller] {} 没有 target 字段，版本可能不同", GOD_ENTITY_CLASS);
            return;
        }
        if (hostClass == null) {
            hostClass = target.getType();
        }
        if (modLoader == null && hostClass.getClassLoader() != null) {
            modLoader = hostClass.getClassLoader();
        }
        if (looksDynamic(hostClass.getName())) {
            dynamicPackage = hostClass.getPackageName();
        }
    }

    private static void locateRemover() {
        List<String> pkgs = candidatePackages();
        List<ClassLoader> loaders = candidateLoaders();
        for (String pkg : pkgs) {
            for (ClassLoader loader : loaders) {
                Class<?> c = tryLoad(pkg + "." + REMOVER_CLASS, false, loader);
                if (c != null) {
                    removerClass = c;
                    dynamicPackage = pkg;
                    modLoader = loader;
                    LOGGER.info("[madnesskiller] 内置移除器 = {} (loader={})", c.getName(), loaderName(loader));
                    return;
                }
            }
        }
        LOGGER.error("[madnesskiller] 找不到 {}；包候选={}, 加载器候选={}", REMOVER_CLASS, pkgs,
                loaders.stream().map(MadnessReflect::loaderName).toList());
    }

    public static String dynamicPackage() {
        resolve();
        return dynamicPackage;
    }

    public static Class<?> removerClass() {
        resolve();
        return removerClass;
    }

    public static Class<?> godEntityClass() {
        resolve();
        return godEntityClass;
    }

    public static Class<?> hostClass() {
        resolve();
        return hostClass;
    }

    private static List<String> candidatePackages() {
        Set<String> pkgs = new LinkedHashSet<>();
        if (dynamicPackage != null && !dynamicPackage.isBlank()) {
            pkgs.add(dynamicPackage);
        }
        Class<?> mlh = tryLoad(LOADER_CLASS, false);
        Object n = mlh == null ? null : readStatic(mlh, "name");
        if (n != null) {
            pkgs.add("net.minecraft." + n);
        }
        pkgs.add("net.minecraft.crazyentity");
        return new ArrayList<>(pkgs);
    }

    private static List<ClassLoader> candidateLoaders() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        if (modLoader != null) {
            loaders.add(modLoader);
        }
        ClassLoader own = MadnessReflect.class.getClassLoader();
        if (own != null) {
            loaders.add(own);
        }
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            loaders.add(tccl);
        }
        if (Entity.class.getClassLoader() != null) {
            loaders.add(Entity.class.getClassLoader());
        }
        return new ArrayList<>(loaders);
    }

    private static String loaderName(Object loader) {
        return loader == null ? "null" : loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static boolean looksDynamic(String name) {
        if (!name.startsWith("net.minecraft.")) {
            return false;
        }
        String rest = name.substring("net.minecraft.".length());
        int dot = rest.indexOf('.');

        String pkg = dot < 0 ? rest : rest.substring(0, dot);
        return !VANILLA_SUBPKG.contains(pkg) && pkg.matches("[a-z]{4,12}");
    }

    public static Object safeValue(Field f, Object owner) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> tryLoad(String name, boolean init) {
        for (ClassLoader cl : candidateLoaders()) {
            Class<?> c = tryLoad(name, init, cl);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static Class<?> tryLoad(String name, boolean init, ClassLoader cl) {
        try {
            return Class.forName(name, init, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static MethodHandle handleOf(Class<?> owner, Method method) {
        if (owner == null || method == null) {
            return null;
        }
        String key = owner.getName() + "#" + method.getName() + Arrays.toString(method.getParameterTypes());
        if (MH_CACHE.containsKey(key)) {
            return MH_CACHE.get(key);
        }
        MethodHandle mh = null;
        try {
            method.setAccessible(true);
            mh = lookupFor(owner).unreflect(method);
        } catch (Throwable t) {
            LOGGER.debug("[madnesskiller] {} unreflect 失败，退回 Method.invoke: {}", key, t.toString());
        }
        MH_CACHE.put(key, mh);
        return mh;
    }

    private static MethodHandles.Lookup lookupFor(Class<?> c) {
        try {
            return MethodHandles.privateLookupIn(c, MethodHandles.lookup());
        } catch (Throwable t) {
            return MethodHandles.lookup();
        }
    }

    public static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        return findMethod0(owner, true, name, params);
    }

    private static Method findMethod0(Class<?> owner, boolean onlyStatic, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        for (Class<?> k = owner; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {

            }
        }

        for (Class<?> k = owner; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || (onlyStatic && !Modifier.isStatic(m.getModifiers()))) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length != params.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!p[i].isAssignableFrom(params[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return m;
                }
            }
        }
        return null;
    }

    public static boolean tryCall(String name, Class<?>[] params, Object... args) {
        return tryCallOn(removerClass(), name, params, args);
    }

    public static boolean tryCallOn(Class<?> owner, String name, Class<?>[] params, Object... args) {
        if (owner == null) {
            LOGGER.warn("[madnesskiller] 目标类未定位，跳过 {}({})", name, Arrays.toString(params));
            return false;
        }
        Method m = findMethod(owner, name, params);
        if (m == null) {
            LOGGER.debug("[madnesskiller] {} 里没有方法 {}({})", owner.getName(), name, Arrays.toString(params));
            return false;
        }
        MethodHandle mh = handleOf(owner, m);
        try {
            if (mh != null) {
                mh.invokeWithArguments(args);
            } else {
                m.setAccessible(true);
                m.invoke(null, args);
            }
            LOGGER.info("[madnesskiller] 调用 {}.{} 成功", owner.getSimpleName(), name);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[madnesskiller] 调用 {}.{} 失败: {}", owner.getSimpleName(), name, t.toString());
            return false;
        }
    }

    public static Class<?> loadInDynamicPackage(String simpleName) {
        resolve();
        for (String pkg : candidatePackages()) {
            for (ClassLoader loader : candidateLoaders()) {
                Class<?> c = tryLoad(pkg + "." + simpleName, false, loader);
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }

    public static Field findField(Class<?> owner, String name) {
        if (owner == null || name == null) {
            return null;
        }
        for (Class<?> k = owner; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (Throwable ignored) {

            }
        }
        return null;
    }

    public static List<Field> allFields(Class<?> owner) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = owner; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                out.addAll(Arrays.asList(k.getDeclaredFields()));
            } catch (Throwable ignored) {
                break;
            }
        }
        return out;
    }

    public static boolean setBooleanField(Object owner, String name, boolean value) {
        if (owner == null) {
            return false;
        }
        Field f = findField(owner.getClass(), name);
        if (f == null || f.getType() != boolean.class) {
            return false;
        }
        try {
            f.setAccessible(true);
            f.setBoolean(owner, value);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[madnesskiller] 写 boolean 字段 {}#{} 失败: {}", owner.getClass().getSimpleName(), name, t.toString());
            return false;
        }
    }

    public static boolean setField(Object owner, Field f, Object value) {
        try {
            f.setAccessible(true);
            f.set(owner, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean setField(Object owner, String name, Object value) {
        if (owner == null) {
            return false;
        }
        Field f = findField(owner.getClass(), name);
        if (f == null) {
            LOGGER.debug("[madnesskiller] {} 没有字段 {}", owner.getClass().getSimpleName(), name);
            return false;
        }
        return setField(owner, f, value);
    }

    public static boolean setFieldAnyName(Object owner, String[] nameCandidates, Object value) {
        if (owner == null) {
            return false;
        }
        for (String name : nameCandidates) {
            Field f = findField(owner.getClass(), name);
            if (f != null && setField(owner, f, value)) {
                LOGGER.info("[madnesskiller] 字段 {}#{} 已写入 {}", owner.getClass().getSimpleName(), name,
                        value == null ? "null" : value);
                return true;
            }
        }
        LOGGER.debug("[madnesskiller] 未能写入字段 {}（候选 {}）", owner.getClass().getSimpleName(), Arrays.toString(nameCandidates));
        return false;
    }

    public static boolean setBooleanFieldAnyName(Object owner, String[] nameCandidates, boolean value) {
        if (owner == null) {
            return false;
        }
        for (String name : nameCandidates) {
            Field f = findField(owner.getClass(), name);
            if (f != null && f.getType() == boolean.class && setField(owner, f, Boolean.valueOf(value))) {
                LOGGER.info("[madnesskiller] boolean 字段 {}#{} = {}", owner.getClass().getSimpleName(), name, value);
                return true;
            }
        }
        LOGGER.debug("[madnesskiller] 未能写 boolean 字段（候选 {}）", Arrays.toString(nameCandidates));
        return false;
    }

    public static Object getField(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        Field f = findField(owner.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object readStatic(Class<?> owner, String name) {
        Field f = findField(owner, name);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            LOGGER.debug("[madnesskiller] 读静态字段 {}.{} 失败: {}", owner == null ? "?" : owner.getName(), name, t.toString());
            return null;
        }
    }

    public static Object readStaticField(String className, String fieldName) {
        Class<?> c = tryLoad(className, false);
        if (c == null) {
            LOGGER.debug("[madnesskiller] 找不到类 {}", className);
            return null;
        }
        return readStatic(c, fieldName);
    }

    public static List<Field> allStaticFields(Class<?> owner, Class<?> fieldType) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = owner; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && fieldType.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (Throwable ignored) {

                    }
                }
            }
        }
        return out;
    }

    public static List<net.minecraft.network.syncher.EntityDataAccessor<?>> accessors(Class<?> owner) {
        List<net.minecraft.network.syncher.EntityDataAccessor<?>> out = new ArrayList<>();
        for (Field f : allStaticFields(owner, net.minecraft.network.syncher.EntityDataAccessor.class)) {
            try {
                Object v = f.get(null);
                if (v instanceof net.minecraft.network.syncher.EntityDataAccessor<?> accessor) {
                    out.add(accessor);
                }
            } catch (Throwable ignored) {

            }
        }
        return out;
    }

    public static boolean hasIsDeadField(Class<?> owner) {
        Field f = findField(owner, "isDead");
        return f != null && f.getType() == boolean.class;
    }

    public static Method findInstanceMethod(Class<?> owner, String name, Class<?>... params) {
        return findMethod0(owner, false, name, params);
    }

    public static boolean invokeInstance(Object target, String[] names, Class<?>[] params, Object... args) {
        if (target == null) {
            return false;
        }
        for (String name : names) {
            Method m = findInstanceMethod(target.getClass(), name, params);
            if (m == null) {
                continue;
            }
            MethodHandle mh = handleOf(target.getClass(), m);
            Object[] withReceiver = new Object[args.length + 1];
            withReceiver[0] = target;
            System.arraycopy(args, 0, withReceiver, 1, args.length);
            try {
                if (mh != null) {
                    mh.invokeWithArguments(withReceiver);
                } else {
                    m.setAccessible(true);
                    m.invoke(target, args);
                }
                LOGGER.info("[madnesskiller] 实例方法 {}#{} 调用成功", target.getClass().getSimpleName(), name);
                return true;
            } catch (Throwable t) {
                LOGGER.warn("[madnesskiller] 实例方法 {}#{} 调用失败: {}", target.getClass().getSimpleName(), name, t.toString());
            }
        }
        return false;
    }

    public static boolean tryCallAny(String[] names, Class<?>[] params, Object... args) {
        Class<?> owner = removerClass();
        if (owner == null) {
            return false;
        }
        for (String name : names) {
            if (tryCallOn(owner, name, params, args)) {
                return true;
            }
        }
        LOGGER.debug("[madnesskiller] {} 里没找到 {}", owner.getSimpleName(), Arrays.toString(names));
        return false;
    }

    public static boolean isMadnessEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation key = null;
        try {
            key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        } catch (Throwable ignored) {

        }
        if (key != null && "madness".equals(key.getNamespace())) {
            return true;
        }
        String name = entity.getClass().getName();
        if (name.startsWith("net.madness.")) {
            return true;
        }
        String pkg = dynamicPackage;
        return pkg != null && name.startsWith(pkg + ".");
    }

    public static List<Entity> observedMadnessEntities() {
        List<Entity> out = new ArrayList<>();
        for (Entity e : observedEntities()) {
            if (isMadnessEntity(e)) {
                out.add(e);
            }
        }
        return out;
    }

    public static List<Object> hostRegistrySnapshot() {
        List<Object> snapshot = new ArrayList<>();
        Class<?> mgr = loadInDynamicPackage("C846359");
        if (mgr == null) {
            return snapshot;
        }
        Method m = findMethod(mgr, "m_691747_");
        if (m == null) {

            for (Method cand : mgr.getDeclaredMethods()) {
                if (Modifier.isStatic(cand.getModifiers()) && cand.getParameterCount() == 0
                        && Iterable.class.isAssignableFrom(cand.getReturnType())) {
                    m = cand;
                    LOGGER.info("[madnesskiller] 宿主注册表 getter 改用 {}", cand.getName());
                    break;
                }
            }
        }
        if (m == null) {
            return snapshot;
        }
        try {
            m.setAccessible(true);
            Object v = m.invoke(null);
            if (v instanceof Collection<?> c) {
                for (Object o : new ArrayList<>(c)) {
                    if (o != null) {
                        snapshot.add(o);
                    }
                }
                HOST_REGISTRY = c;
            } else if (v instanceof Iterable it) {
                for (Object o : it) {
                    if (o != null) {
                        snapshot.add(o);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[madnesskiller] m_691747_() 调用失败: {}", t.toString());
        }
        return snapshot;
    }

    public static Collection<?> hostRegistryCollection() {
        return HOST_REGISTRY;
    }

    public static boolean isMadnessOwnedClass(Class<?> type) {
        if (type == null) {
            return false;
        }
        ClassLoader loader = type.getClassLoader();
        if (modLoader != null && loader == modLoader) {
            return true;
        }
        String name = type.getName();
        if (name.startsWith("net.madness.") || name.startsWith("net.minecraft.madcrazy")
                || name.startsWith("net.minecraft.crazyentity.")) {
            return true;
        }
        String pkg = dynamicPackage;
        return pkg != null && name.startsWith(pkg + ".");
    }

    public static Object hostOf(Entity entity) {
        return getField(entity, "target");
    }

    public static List<Entity> entitiesOf(Level level) {
        if (!entityFieldsProbed) {
            probeEntityFields(level);
        }
        List<Entity> out = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        boolean readable = false;
        for (Field f : entityCollectionFields) {
            Collection<?> c = asCollection(safeValue(f, level));
            if (c == null) {
                continue;
            }
            readable = true;
            for (Object o : new ArrayList<>(c)) {
                if (o instanceof Entity entity && ids.add(entity.getId())) {
                    out.add(entity);
                }
            }
        }
        if (!readable || out.isEmpty()) {
            for (Entity entity : entitiesByArea(level)) {
                if (ids.add(entity.getId())) {
                    out.add(entity);
                }
            }
        }
        LOGGER.debug("[madnesskiller] {} 实体数={} (集合字段 {} 个)",
                level.dimension().location(), out.size(), entityCollectionFields.size());
        return out;
    }

    private static synchronized void probeEntityFields(Level level) {
        if (entityFieldsProbed) {
            return;
        }
        for (Class<?> k = level.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Collection<?> c = asCollection(safeValue(f, level));
                if (c == null || !containsAnyEntity(c)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                entityCollectionFields.add(f);
                LOGGER.info("[madnesskiller] 实体集合字段 +1 = {}#{} ({}, 元素数={})",
                        k.getSimpleName(), f.getName(), c.getClass().getSimpleName(), c.size());
            }
        }
        entityFieldsProbed = true;
        if (entityCollectionFields.isEmpty()) {
            LOGGER.warn("[madnesskiller] 未探测到任何实体集合字段，将只用范围查询");
        }
    }

    private static boolean containsAnyEntity(Collection<?> c) {
        int checked = 0;
        for (Object o : c) {
            if (o instanceof Entity) {
                return true;
            }
            if (++checked >= 16) {
                return false;
            }
        }
        return false;
    }

    private static Method findValuesMethod(Object v) {
        if (v == null || v instanceof Collection || v instanceof Map) {
            return null;
        }
        try {
            Method m = v.getClass().getMethod("values");
            return Collection.class.isAssignableFrom(m.getReturnType()) ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Collection<?> toCollection(Object v) {
        if (v instanceof Collection<?> c) {
            return c;
        }
        if (v instanceof Map<?, ?> m) {
            return m.values();
        }
        return null;
    }

    public static Collection<?> asCollection(Object v) {
        Collection<?> direct = toCollection(v);
        if (direct != null) {
            return direct;
        }
        Method values = findValuesMethod(v);
        if (values != null) {
            try {
                return toCollection(values.invoke(v));
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static List<Entity> entitiesByArea(Level level) {
        List<Entity> out = new ArrayList<>();
        try {
            Set<Integer> seen = new LinkedHashSet<>();
            List<AABB> boxes = new ArrayList<>();
            level.players().forEach(player -> boxes.add(player.getBoundingBox().inflate(300.0D, 200.0D, 300.0D)));
            if (boxes.isEmpty()) {
                boxes.add(new AABB(-256.0D, -64.0D, -256.0D, 256.0D, 320.0D, 256.0D));
            }
            for (AABB box : boxes) {
                for (Entity entity : level.getEntitiesOfClass(Entity.class, box)) {
                    if (seen.add(entity.getId())) {
                        out.add(entity);
                    }
                }
            }
            LOGGER.debug("[madnesskiller] {} 实体数(范围) {}", level.dimension().location(), out.size());
        } catch (Throwable t) {
            LOGGER.warn("[madnesskiller] 范围查询实体失败: {}", t.toString());
        }
        return out;
    }

    public static boolean isBodyLike(Class<?> type) {
        if (type == null || type.isPrimitive()) {
            return false;
        }
        if (Entity.class.isAssignableFrom(type)) {
            return true;
        }
        if (Level.class.isAssignableFrom(type)) {
            return true;
        }
        if (net.minecraft.server.MinecraftServer.class.isAssignableFrom(type)) {
            return true;
        }
        return isHostAssignable(type);
    }

    private static boolean isHostAssignable(Class<?> type) {
        Class<?> host = hostClass;
        return host != null && host.isAssignableFrom(type) && !Entity.class.isAssignableFrom(type);
    }

    public static boolean isHostLike(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum()) {
            return false;
        }
        if (isHostAssignable(type)) {
            return true;
        }

        if (hostClass != null) {
            return false;
        }
        if (Entity.class.isAssignableFrom(type)) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("net.minecraft.crazyentity.")) {
            return true;
        }
        String pkg = dynamicPackage;
        return pkg != null && name.startsWith(pkg + ".");
    }

    public static List<Class<?>> probeClasses() {
        resolve();
        List<Class<?>> out = new ArrayList<>();
        if (godEntityClass != null) {
            out.add(godEntityClass);
        }
        if (hostClass != null) {
            for (Class<?> k = hostClass; k != null && k != Object.class; k = k.getSuperclass()) {
                out.add(k);
            }
        }

        String[] known = {"C843109", "C846359", "C844689", "C841537", "C849613", "C846389", "C846829", "C845861"};
        for (String simple : known) {
            Class<?> c = loadInDynamicPackage(simple);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public static Field findInstanceFieldByType(Class<?> owner, Class<?> type) {
        for (Field f : allFields(owner)) {
            if (!Modifier.isStatic(f.getModifiers()) && type.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {

                }
            }
        }
        return null;
    }

    public static Field findStaticFieldByType(Class<?> owner, Class<?> type) {
        for (Field f : allStaticFields(owner, type)) {
            return f;
        }
        return null;
    }

    public static List<Class<?>> allClassesOf(Collection<?> c) {
        List<Class<?>> out = new ArrayList<>();
        if (c != null) {
            for (Object o : c) {
                if (o != null) {
                    out.add(o.getClass());
                }
            }
        }
        return out;
    }

    public static ClassLoader madnessClassLoader() {
        resolve();
        return modLoader;
    }
}
