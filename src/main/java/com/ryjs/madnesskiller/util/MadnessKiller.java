package com.ryjs.madnesskiller.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MadnessKiller {

    private static final int MAX_HOST_DEPTH = 6;

    private static final Set<String> KILLED_TYPES = new HashSet<>();
    private static final Map<String, Integer> RESPAWN_COUNT = new HashMap<>();

    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("him", "herobrine"),
            Map.entry("he", "herobrine"),
            Map.entry("notch", "notch"),
            Map.entry("popbob", "popbob"),
            Map.entry("null", "null"),
            Map.entry("sun", "the sun"),
            Map.entry("moon", "the moon"),
            Map.entry("prototype", "prototype"),
            Map.entry("netheravatar", "nether avatar"),
            Map.entry("netheravator", "nether avatar"),
            Map.entry("ancientnetheravatar", "nether avatar"),
            Map.entry("ancientnetheravator", "nether avatar"),
            Map.entry("ancient", "nether avatar"),
            Map.entry("远古下界神明", "nether avatar"),
            Map.entry("下界神明", "nether avatar"),
            Map.entry("远古下界", "nether avatar"));

    private MadnessKiller() {
    }

    public enum Scope {
        MADNESS,
        ALL;

        public static Scope parse(String raw) {
            return "all".equalsIgnoreCase(raw) ? ALL : MADNESS;
        }
    }

    public static final class Report {
        public int scanned;
        public int selfKilled;
        public int forceKilled;
        public int survived;
        public int hosts;

        public void merge(Report other) {
            scanned += other.scanned;
            selfKilled += other.selfKilled;
            forceKilled += other.forceKilled;
            survived += other.survived;
            hosts += other.hosts;
        }
    }

    public static void resetTracking() {
        KILLED_TYPES.clear();
        RESPAWN_COUNT.clear();
    }

    public static Report run(MinecraftServer server, Scope scope) {
        Report report = new Report();
        Set<Integer> processed = new HashSet<>();
        MadnessReflect.resolve();
        for (ServerLevel level : server.getAllLevels()) {
            Player ref = level.players().isEmpty() ? null : level.players().get(0);
            for (Entity entity : MadnessReflect.entitiesOf(level)) {
                if (entity instanceof Player || !processed.add(entity.getId())) {
                    continue;
                }
                boolean madness = MadnessReflect.isMadnessEntity(entity);
                if (scope == Scope.MADNESS && !madness) {
                    continue;
                }
                report.scanned++;
                killOne(entity, level, ref, report, madness);
            }
        }

        killObservedLeftovers(report, processed, null, scope);
        sweepHostRegistry(report);
        clearLeftoverBossBars();
        restoreSky();
        return report;
    }

    public static Report kill(MinecraftServer server, String query) {
        Report report = new Report();
        Set<Integer> processed = new HashSet<>();
        List<String> keys = keysFor(query);
        MadnessReflect.resolve();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : MadnessReflect.entitiesOf(level)) {
                if (entity instanceof Player || !processed.add(entity.getId())) {
                    continue;
                }
                if (!matches(entity, keys)) {
                    continue;
                }
                report.scanned++;
                killOne(entity, level, level.players().isEmpty() ? null : level.players().get(0),
                        report, MadnessReflect.isMadnessEntity(entity));
            }
        }
        killObservedLeftovers(report, processed, keys, Scope.ALL);
        sweepHostRegistry(report);
        clearLeftoverBossBars();
        restoreSky();
        return report;
    }

    private static void killObservedLeftovers(Report report, Set<Integer> processed, List<String> keys, Scope scope) {
        for (Entity entity : MadnessReflect.observedMadnessEntities()) {
            if (entity.isRemoved() || entity instanceof Player || !processed.add(entity.getId())) {
                continue;
            }
            if (!(entity.level() instanceof ServerLevel level)) {
                continue;
            }
            if (keys != null && !matches(entity, keys)) {
                continue;
            }
            if (scope == Scope.MADNESS && !MadnessReflect.isMadnessEntity(entity)) {
                continue;
            }
            report.scanned++;
            killOne(entity, level, level.players().isEmpty() ? null : level.players().get(0),
                    report, MadnessReflect.isMadnessEntity(entity));
        }
    }

    private static void killOne(Entity entity, ServerLevel level, Player ref, Report report, boolean madness) {
        String typePath = typePath(entity);
        if (KILLED_TYPES.contains(typePath)) {
            RESPAWN_COUNT.merge(typePath, 1, Integer::sum);
        }
        Object host = MadnessReflect.hostOf(entity);

        MadnessReflect.setBooleanField(entity, "isDead", true);
        try {
            entity.addTag(MadnessReflect.TAG_DEATH_CRAZY);
        } catch (Throwable ignored) {

        }

        zeroHealth(entity, madness);

        if (host != null) {
            neutralizeChain(host, report, RESPAWN_COUNT.getOrDefault(typePath, 0) > 0);
        }

        if (triggerTheirOwnDeath(entity)) {
            report.selfKilled++;
            KILLED_TYPES.add(typePath);
            clearBossBars(entity, level);
            MadnessReflect.setField(entity, "target", null);
            return;
        }

        MadnessReflect.invokeInstance(entity, new String[]{"discard", "m_146870_"}, new Class<?>[0]);
        if (entity.isRemoved()) {
            report.selfKilled++;
            KILLED_TYPES.add(typePath);
            clearBossBars(entity, level);
            MadnessReflect.setField(entity, "target", null);
            return;
        }

        try {
            entity.setRemoved(Entity.RemovalReason.DISCARDED);
        } catch (Throwable ignored) {

        }

        boolean forced = false;
        forced |= MadnessReflect.tryCallAny(new String[]{"removeServerLevelEntity"}, MadnessReflect.P_ENTITY, entity);
        forced |= MadnessReflect.tryCallAny(new String[]{"setRemoved"}, MadnessReflect.P_ENTITY_REASON,
                entity, Entity.RemovalReason.KILLED);
        forced |= MadnessReflect.tryCallAny(new String[]{"deleteEntity"}, MadnessReflect.P_ENTITY_LEVEL,
                entity, entity.level());
        if (ref != null && !entity.isRemoved()) {
            forced |= MadnessReflect.tryCallAny(new String[]{"AsynchronousRemove"}, MadnessReflect.P_ENTITY_PLAYER,
                    entity, ref);
        }
        if (!entity.isRemoved()) {
            MadnessReflect.tryCallAny(new String[]{"setEntityPosReflection"}, MadnessReflect.P_ENTITY, entity);
            if (isClientSide()) {
                forced |= MadnessReflect.tryCallAny(new String[]{"removeDamnEntity"}, MadnessReflect.P_ENTITY, entity);
            }
        }

        if (!entity.isRemoved()) {
            forceRemovedFields(entity);
        }
        if (entity.isRemoved()) {
            report.forceKilled++;
            KILLED_TYPES.add(typePath);
            clearBossBars(entity, level);
        } else {
            report.survived++;
        }
        MadnessReflect.setField(entity, "target", null);
    }

    private static void zeroHealth(Entity entity, boolean madness) {
        MadnessReflect.tryCallAny(new String[]{"tagModify"}, MadnessReflect.P_ENTITY_FLOAT, entity, Float.valueOf(0.0F));
        MadnessReflect.tryCallAny(new String[]{"attributeModifier"}, MadnessReflect.P_ENTITY_FLOAT, entity, Float.valueOf(0.0F));
        MadnessReflect.tryCallAny(new String[]{"entityDataModify"}, MadnessReflect.P_ENTITY_FLOAT, entity, Float.valueOf(0.0F));

        if (madness) {

            MadnessReflect.invokeInstance(entity, new String[]{"setHelPo"}, new Class<?>[]{float.class}, Float.valueOf(0.0F));
            zeroAccessors(entity);
        }

        if (entity instanceof LivingEntity living) {
            try {

                living.setHealth(0.0F);
            } catch (Throwable ignored) {

            }
            try {
                var maxHealth = living.getAttribute(Attributes.MAX_HEALTH);
                if (maxHealth != null) {
                    maxHealth.setBaseValue(0.0D);
                }
            } catch (Throwable ignored) {

            }
        }
    }

    private static void zeroAccessors(Entity entity) {
        SynchedEntityData data;
        try {
            data = entity.getEntityData();
        } catch (Throwable t) {
            return;
        }
        for (EntityDataAccessor<?> accessor : MadnessReflect.accessors(entity.getClass())) {
            try {
                Object current = data.get(accessor);
                if (current instanceof Float) {
                    setUnchecked(data, accessor, Float.valueOf(0.0F));
                } else if (current instanceof Double) {
                    setUnchecked(data, accessor, Double.valueOf(0.0D));
                } else if (current instanceof String s && isNumericish(s)) {
                    setUnchecked(data, accessor, "0.0");
                }
            } catch (Throwable ignored) {

            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setUnchecked(SynchedEntityData data, EntityDataAccessor accessor, Object value) {
        data.set(accessor, value);
    }

    private static boolean triggerTheirOwnDeath(Entity entity) {
        try {
            entity.tick();
        } catch (Throwable ignored) {

        }
        if (entity.isRemoved()) {
            return true;
        }
        MadnessReflect.invokeInstance(entity, new String[]{"setOut"},
                new Class<?>[]{Entity.RemovalReason.class}, Entity.RemovalReason.DISCARDED);
        return entity.isRemoved();
    }

    private static void clearBossBars(Entity entity, ServerLevel level) {
        MadnessReflect.tryCallAny(new String[]{"clearBossEventsProper"},
                new Class<?>[]{Entity.class, ServerLevel.class}, entity, level);
        MadnessReflect.tryCallAny(new String[]{"forceCleanList"}, MadnessReflect.P_ENTITY, entity);
    }

    private static void forceRemovedFields(Entity entity) {
        MadnessReflect.setBooleanFieldAnyName(entity, new String[]{"removed", "f_19864_"}, true);
        MadnessReflect.setFieldAnyName(entity, new String[]{"removalReason", "f_146795_"}, Entity.RemovalReason.DISCARDED);
        MadnessReflect.setBooleanFieldAnyName(entity, new String[]{"valid", "f_19860_"}, false);
        MadnessReflect.setBooleanFieldAnyName(entity, new String[]{"canUpdate", "f_19849_"}, false);
    }

    private static void neutralizeChain(Object root, Report report, boolean aggressive) {
        neutralizeRecursively(root, Collections.newSetFromMap(new IdentityHashMap<>()), 0, report, aggressive);
    }

    private static void neutralizeRecursively(Object host, Set<Object> visited, int depth, Report report, boolean aggressive) {
        if (host == null || depth > MAX_HOST_DEPTH || !visited.add(host)) {
            return;
        }

        boolean deregistered = deregisterHost(host);
        List<Object> next = new ArrayList<>();
        int cleared = 0;
        for (Field f : MadnessReflect.allFields(host.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                Object value = MadnessReflect.safeValue(f, host);
                if (MadnessReflect.isHostLike(f.getType()) && value != null) {
                    next.add(value);
                }
                if (MadnessReflect.isBodyLike(f.getType())) {
                    if (Level.class.isAssignableFrom(f.getType()) && deregistered && !aggressive) {
                        continue;
                    }
                    if (value != null && MadnessReflect.setField(host, f, null)) {
                        cleared++;
                    }
                } else if (f.getType() == boolean.class && isAggroField(f.getName())
                        && value instanceof Boolean flag && flag) {
                    f.setAccessible(true);
                    f.setBoolean(host, false);
                    cleared++;
                }
            } catch (Throwable ignored) {

            }
        }
        if (cleared > 0) {
            report.hosts++;
        }
        for (Object child : next) {
            neutralizeRecursively(child, visited, depth + 1, report, aggressive);
        }
    }

    private static boolean deregisterHost(Object host) {
        boolean removed = false;
        Collection<?> backing = MadnessReflect.hostRegistryCollection();
        if (backing != null) {
            try {
                removed |= backing.remove(host);
            } catch (Throwable ignored) {

            }
        }
        for (Class<?> c : MadnessReflect.probeClasses()) {
            for (Field f : MadnessReflect.allStaticFields(c, Object.class)) {
                Collection<?> holder = MadnessReflect.asCollection(MadnessReflect.safeValue(f, null));
                if (holder == null) {
                    continue;
                }
                try {
                    removed |= holder.remove(host);
                } catch (Throwable ignored) {

                }
            }
        }
        return removed;
    }

    private static void sweepHostRegistry(Report report) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object host : MadnessReflect.hostRegistrySnapshot()) {
            if (visited.add(host)) {
                neutralizeRecursively(host, visited, 0, report, false);
            }
        }
        for (Class<?> c : MadnessReflect.probeClasses()) {
            for (Field f : MadnessReflect.allStaticFields(c, Object.class)) {
                Collection<?> holder = MadnessReflect.asCollection(MadnessReflect.safeValue(f, null));
                if (holder == null) {
                    continue;
                }
                List<Object> hosts = new ArrayList<>();
                for (Object o : holder) {
                    if (o != null && MadnessReflect.isHostLike(o.getClass()) && !visited.contains(o)) {
                        hosts.add(o);
                    }
                }
                if (hosts.isEmpty()) {
                    continue;
                }
                try {
                    holder.removeAll(hosts);
                } catch (Throwable ignored) {

                }
                for (Object host : hosts) {
                    neutralizeRecursively(host, visited, 0, report, false);
                }
            }
        }
    }

    private static void clearLeftoverBossBars() {
        Object bars = MadnessReflect.readStaticField("net.minecraft.madcrazy.Transformers", "bossBars");
        if (bars instanceof Collection<?> collection && !collection.isEmpty()) {
            try {
                collection.clear();
            } catch (Throwable ignored) {

            }
        }
    }

    private static void restoreSky() {
        Class<?> sky = MadnessReflect.loadInDynamicPackage("C849613");
        if (sky != null && MadnessReflect.findMethod(sky, "changeSky", boolean.class, String.class) != null) {
            MadnessReflect.tryCallOn(sky, "changeSky", new Class<?>[]{boolean.class, String.class}, Boolean.FALSE, "");
        }
        if (isClientSide()) {
            SkyRestoreClient.restore();
        }
        resetSkyCounters();
    }

    private static void resetSkyCounters() {
        Class<?> effects = MadnessReflect.loadInDynamicPackage("C849922");
        if (effects == null) {
            return;
        }
        for (Field f : MadnessReflect.allStaticFields(effects, int.class)) {
            try {
                f.setAccessible(true);
                if (f.getInt(null) != 0) {
                    f.setInt(null, 0);
                }
            } catch (Throwable ignored) {

            }
        }
    }

    private static boolean matches(Entity entity, List<String> keys) {
        for (String key : keys) {
            if (key.isEmpty()) {
                continue;
            }
            try {
                if (String.valueOf(entity.getId()).equals(key)) {
                    return true;
                }
                if (entity.getUUID().toString().replace("-", "").equals(key.replace("-", ""))) {
                    return true;
                }
            } catch (Throwable ignored) {

            }
            if (normalize(safeName(entity)).contains(key)) {
                return true;
            }
            if (normalize(entity.getClass().getSimpleName()).contains(key)) {
                return true;
            }
            String path = typePath(entity);
            if (path != null && normalize(path).contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> keysFor(String query) {
        Set<String> keys = new LinkedHashSet<>();
        String norm = normalize(query);
        keys.add(norm);
        String mapped = ALIAS.get(norm);
        if (mapped != null) {
            keys.add(normalize(mapped));
        }
        if (norm.contains("avator") || norm.contains("avatar")) {
            keys.add("netheravatar");
            keys.add("nether");
        }
        return new ArrayList<>(keys);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).replace("_", "").replace(":", "").replace("/", "").replace(" ", "").trim();

        return s.replace("avator", "avatar");
    }

    private static boolean isNumericish(String raw) {
        try {
            Float.parseFloat(raw);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isAggroField(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("hostile") || lower.contains("anger") || lower.contains("fastskill")
                || lower.contains("issecondphase") || lower.contains("startsecondphase");
    }

    private static String typePath(Entity entity) {
        try {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            return key == null ? "?" : key.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeName(Entity entity) {
        try {
            return entity.getName().getString();
        } catch (Throwable t) {
            return entity.getClass().getSimpleName();
        }
    }

    private static boolean isClientSide() {
        try {
            return FMLEnvironment.dist == Dist.CLIENT;
        } catch (Throwable t) {
            return false;
        }
    }
}
