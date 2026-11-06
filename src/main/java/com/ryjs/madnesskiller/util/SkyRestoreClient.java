package com.ryjs.madnesskiller.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

public final class SkyRestoreClient {

    private static final org.slf4j.Logger LOGGER = MadnessReflect.LOGGER;

    private SkyRestoreClient() {
    }

    public static boolean restore() {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc == null ? null : mc.level;
            if (level == null) {
                LOGGER.info("[madnesskiller] ClientLevel 为空（还没进世界？），跳过天空还原");
                return false;
            }
            Field effectsField = findEffectsField(level);
            if (effectsField == null) {
                LOGGER.warn("[madnesskiller] 没找到 ClientLevel 里的 DimensionSpecialEffects 字段，无法还原天空");
                return false;
            }
            DimensionSpecialEffects current = (DimensionSpecialEffects) read(effectsField, level);
            String currentName = current == null ? "null" : current.getClass().getName();
            if (current != null && !isTheirSky(current.getClass())) {
                LOGGER.info("[madnesskiller] 当前天空已是原版实现 {}，无需还原", currentName);
                return false;
            }
            DimensionSpecialEffects replacement = readOriginalEffects();
            String source = "它们缓存的 originalEffects";
            if (replacement == null) {
                replacement = constructVanillaEffects(level.dimension().location().getPath());
                source = "按维度构造原版实现";
            }
            if (replacement == null) {
                LOGGER.warn("[madnesskiller] 找不到可用的原版 DimensionSpecialEffects，天空无法还原（当前={}）", currentName);
                return false;
            }
            effectsField.set(level, replacement);
            LOGGER.info("[madnesskiller] 天空已还原 [OK]（{}）: {} -> {}", source, currentName,
                    replacement.getClass().getName());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[madnesskiller] 还原天空异常: {}", t.toString());
            return false;
        }
    }

    private static boolean isTheirSky(Class<?> type) {
        String name = type.getName();
        String pkg = MadnessReflect.dynamicPackage();
        return name.startsWith("net.minecraft.crazyentity.")
                || name.startsWith("net.madness.")
                || (pkg != null && name.startsWith(pkg + "."));
    }

    private static Field findEffectsField(ClientLevel level) {
        Field f = MadnessReflect.findInstanceFieldByType(level.getClass(), DimensionSpecialEffects.class);
        if (f != null) {
            LOGGER.info("[madnesskiller] 天空字段 = {}#{}", level.getClass().getSimpleName(), f.getName());
        }
        return f;
    }

    private static Object read(Field f, Object owner) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    private static DimensionSpecialEffects readOriginalEffects() {
        for (String simple : new String[]{"C849613", "C849922", "C841537", "C846371", "C844689"}) {
            Class<?> owner = MadnessReflect.loadInDynamicPackage(simple);
            if (owner == null) {
                continue;
            }
            for (Field f : MadnessReflect.allStaticFields(owner, DimensionSpecialEffects.class)) {
                Object v = read(f, null);
                if (v instanceof DimensionSpecialEffects effects && !isTheirSky(effects.getClass())) {
                    LOGGER.info("[madnesskiller] 从 {}.{} 取到原版天空 {}", owner.getSimpleName(), f.getName(),
                            effects.getClass().getName());
                    return effects;
                }
            }
        }
        return null;
    }

    private static DimensionSpecialEffects constructVanillaEffects(String dimensionPath) {
        String path = dimensionPath == null ? "" : dimensionPath.toLowerCase(Locale.ROOT);
        String want = path.contains("nether") ? "nether" : path.contains("end") ? "end" : "overworld";
        DimensionSpecialEffects fallback = null;
        for (Class<?> inner : DimensionSpecialEffects.class.getDeclaredClasses()) {
            if (Modifier.isAbstract(inner.getModifiers()) || !DimensionSpecialEffects.class.isAssignableFrom(inner)) {
                continue;
            }
            try {
                Constructor<?> ctor = inner.getDeclaredConstructor();
                ctor.setAccessible(true);
                DimensionSpecialEffects instance = (DimensionSpecialEffects) ctor.newInstance();
                if (inner.getSimpleName().toLowerCase(Locale.ROOT).contains(want)) {
                    LOGGER.info("[madnesskiller] 按维度 {} 构造原版天空 {}", path, inner.getSimpleName());
                    return instance;
                }
                if (fallback == null) {
                    fallback = instance;
                    LOGGER.info("[madnesskiller] 备选原版天空 {}", inner.getSimpleName());
                }
            } catch (Throwable ignored) {

            }
        }

        for (Field f : MadnessReflect.allStaticFields(DimensionSpecialEffects.class, DimensionSpecialEffects.class)) {
            Object v = read(f, null);
            if (v instanceof DimensionSpecialEffects effects && !isTheirSky(effects.getClass())) {
                LOGGER.info("[madnesskiller] 用 DimensionSpecialEffects.{} 作为原版天空", f.getName());
                return effects;
            }
        }
        return fallback;
    }
}
