package com.yucareux.tellus.worldgen.runtime;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 1 contract tests for the common settings view and typed adapters. */
class TellusGeneratorSettingsViewAdapterTest {
    @TempDir
    static Path gameDir;

    private static String previousGameDir;
    private static String previousConfigDir;
    private static String previousOvertureRelease;

    @BeforeAll
    static void bootstrapMinecraft() {
        previousGameDir = System.getProperty("tellus.gameDir");
        previousConfigDir = System.getProperty("tellus.configDir");
        previousOvertureRelease = System.getProperty("tellus.overture.release");
        System.setProperty("tellus.gameDir", gameDir.toString());
        System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
        System.setProperty("tellus.overture.release", "2026-06-17.0");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterAll
    static void restoreProperties() {
        restoreProperty("tellus.gameDir", previousGameDir);
        restoreProperty("tellus.configDir", previousConfigDir);
        restoreProperty("tellus.overture.release", previousOvertureRelease);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void earthViewExposesStableIdentityRangeAndOptionalGroups() {
        EarthGeneratorSettings source = EarthGeneratorSettings.DEFAULT;
        TellusGeneratorSettingsView view = new EarthGeneratorSettingsViewAdapter(source);
        EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(source);

        assertEquals(TellusGeneratorSettingsView.Kind.EARTH, view.kind());
        assertEquals("tellus:earth", view.generatorId());
        assertEquals("earth:" + EarthProjection.projectionModeId(), view.geometryId());
        assertEquals(source.worldScale(), view.worldScale());
        assertEquals(limits.minY(), view.verticalRange().minY());
        assertEquals(limits.height(), view.verticalRange().height());
        assertEquals(limits.minY() + limits.height(), view.verticalRange().maxYExclusive());
        assertEquals(source.effectiveHeightOffset(), view.seaLevel());
        assertEquals(source.heightOffset(), view.heightOffset());

        assertTrue(view.spawn().isPresent());
        assertTrue(view.terrain().isPresent());
        assertTrue(view.structures().isPresent());
        assertTrue(view.randomBiomes().isPresent());
        assertTrue(view.realtime().isPresent());
        assertTrue(view.distantHorizons().isPresent());
        assertTrue(view.voxy().isPresent());
        assertTrue(view.managedTerrain().isPresent());
        assertTrue(view.experimentalHeight().isPresent());
        assertTrue(view.supports(TellusGeneratorSettingsView.Capability.GEODETIC_POSITION));
        assertTrue(view.supports(TellusGeneratorSettingsView.Capability.DISTANT_HORIZONS_LOD));
        assertEquals(source.addVillages(), view.structures().orElseThrow().enabled("minecraft:village"));
    }

    @Test
    void valueViewsKeepValidationAndLegacyOptionalSemantics() {
        TellusGeneratorSettingsView.VerticalRange range =
            new TellusGeneratorSettingsView.VerticalRange(-64, 384, 384);
        assertEquals(320, range.maxYExclusive());

        TellusGeneratorSettingsView.SpawnPoint spawn =
            new TellusGeneratorSettingsView.SpawnPoint(0.0, 180.0);
        assertEquals(180.0, spawn.longitude());

        TellusGeneratorSettingsView settings = new MinimalSettingsView();
        TellusGeneratorRuntimeView runtime = new TellusGeneratorRuntimeView() {
            @Override
            public TellusGeneratorSettingsView settings() {
                return settings;
            }
        };
        assertSame(settings, runtime.settings());
        assertEquals(TellusGeneratorSettingsView.Kind.SPHERE_FACE, runtime.kind());
        assertFalse(runtime.attached());
        assertTrue(runtime.capabilities().isEmpty());
        assertFalse(runtime.supports(TellusGeneratorSettingsView.Capability.FACE_ROUTING));
    }

    @Test
    void typedAdaptersExposeGeometryRangeAndLegacyFallbacksWhenTargetLayerIsPresent() throws Exception {
        boolean foundTypedLayer = false;
        Class<?> cubeGeneratorClass = optionalClass("com.yucareux.tellus.worldgen.CubeNetChunkGenerator");
        Class<?> cubeSettingsAdapterClass = optionalClass(
            "com.yucareux.tellus.worldgen.runtime.CubeNetGeneratorSettingsViewAdapter");
        if (cubeGeneratorClass != null && cubeSettingsAdapterClass != null) {
            foundTypedLayer = true;
            Object generator = newCubeGenerator(cubeGeneratorClass);
            Object sourceSettings = cubeSettingsDefault();
            TellusGeneratorSettingsView view = (TellusGeneratorSettingsView) cubeSettingsAdapterClass
                .getConstructor(cubeGeneratorClass, sourceSettings.getClass())
                .newInstance(generator, sourceSettings);

            assertEquals(TellusGeneratorSettingsView.Kind.CUBE_NET, view.kind());
            assertEquals("tellus:cube_net", view.generatorId());
            assertEquals(SphereWorldModel.MAPPING_ID, view.geometryId());
            assertEquals(1.0, view.worldScale());
            assertEquals(-64, view.verticalRange().minY());
            assertEquals(384, view.verticalRange().height());
            assertEquals(320, view.verticalRange().maxYExclusive());
            assertEquals(64, view.seaLevel());
            assertTrue(view.spawn().isPresent());
            assertTrue(view.terrain().isPresent());
            assertTrue(view.structures().isPresent());
            assertTrue(view.randomBiomes().isPresent());
            assertTrue(view.realtime().isEmpty());
            assertTrue(view.distantHorizons().isEmpty());
            assertTrue(view.voxy().isEmpty());
            assertTrue(view.managedTerrain().isEmpty());
            assertTrue(view.experimentalHeight().isEmpty());
            assertTrue(view.supports(TellusGeneratorSettingsView.Capability.GEODETIC_POSITION));
            assertTrue(view.supports(TellusGeneratorSettingsView.Capability.SEAM_NAVIGATION));
            assertFalse(view.supports(TellusGeneratorSettingsView.Capability.FACE_ROUTING));
            assertSame(sourceSettings, cubeSettingsAdapterClass.getMethod("sourceSettings").invoke(view));
        }

        Class<?> sphereGeneratorClass = optionalClass("com.yucareux.tellus.worldgen.SphereFaceChunkGenerator");
        Class<?> sphereSettingsAdapterClass = optionalClass(
            "com.yucareux.tellus.worldgen.runtime.SphereFaceGeneratorSettingsViewAdapter");
        if (sphereGeneratorClass != null && sphereSettingsAdapterClass != null) {
            foundTypedLayer = true;
            Object generator = newSphereGenerator(sphereGeneratorClass);
            TellusGeneratorSettingsView view = (TellusGeneratorSettingsView) sphereSettingsAdapterClass
                .getConstructor(sphereGeneratorClass)
                .newInstance(generator);

            assertEquals(TellusGeneratorSettingsView.Kind.SPHERE_FACE, view.kind());
            assertEquals("tellus:sphere_face", view.generatorId());
            assertEquals(SphereWorldModel.MAPPING_ID, view.geometryId());
            assertEquals(1.0, view.worldScale());
            assertEquals(-64, view.verticalRange().minY());
            assertEquals(384, view.verticalRange().height());
            assertEquals(320, view.verticalRange().maxYExclusive());
            assertEquals(64, view.seaLevel());
            assertEquals(64, view.heightOffset());
            assertTrue(view.spawn().isEmpty());
            assertTrue(view.terrain().isEmpty());
            assertTrue(view.structures().isEmpty());
            assertTrue(view.randomBiomes().isEmpty());
            assertTrue(view.realtime().isEmpty());
            assertTrue(view.distantHorizons().isEmpty());
            assertTrue(view.voxy().isEmpty());
            assertTrue(view.managedTerrain().isEmpty());
            assertTrue(view.experimentalHeight().isEmpty());
            assertTrue(view.capabilities().isEmpty());
        }

        // The root/common test source is shared by targets whose typed adapter
        // layer is not selected. Those targets retain the explicit legacy view.
        if (!foundTypedLayer) {
            assertNotNull(TellusGeneratorSettingsView.Kind.SPHERE_FACE);
        }
    }

    @Test
    void typedRuntimeAdaptersHaveIdempotentLifecycleWhenTargetLayerIsPresent() throws Exception {
        boolean foundTypedLayer = false;
        Class<?> cubeGeneratorClass = optionalClass("com.yucareux.tellus.worldgen.CubeNetChunkGenerator");
        Class<?> cubeRuntimeClass = optionalClass(
            "com.yucareux.tellus.worldgen.runtime.CubeNetGeneratorRuntimeAdapter");
        if (cubeGeneratorClass != null && cubeRuntimeClass != null) {
            foundTypedLayer = true;
            Object generator = newCubeGenerator(cubeGeneratorClass);
            Object generatorB = newCubeGenerator(cubeGeneratorClass);
            Object runtime = cubeRuntimeClass
                .getConstructor(cubeGeneratorClass)
                .newInstance(generator);
            TellusGeneratorRuntime typedRuntime = (TellusGeneratorRuntime) runtime;
            assertFalse(typedRuntime.owns((ChunkGenerator) generatorB));
            assertEquals(TellusGeneratorRuntime.AttachResult.NOT_OWNED,
                typedRuntime.attach((ChunkGenerator) generatorB));
            assertFalse(typedRuntime.attached());
            assertRuntimeLifecycle(typedRuntime, (ChunkGenerator) generator);

            TellusGeneratorSettingsView settings = typedRuntime.settings();
            assertSame(cubeSettingsDefault(), settings.getClass().getMethod("sourceSettings").invoke(settings));
        }

        Class<?> sphereGeneratorClass = optionalClass("com.yucareux.tellus.worldgen.SphereFaceChunkGenerator");
        Class<?> sphereRuntimeClass = optionalClass(
            "com.yucareux.tellus.worldgen.runtime.SphereFaceGeneratorRuntimeAdapter");
        if (sphereGeneratorClass != null && sphereRuntimeClass != null) {
            foundTypedLayer = true;
            Object generator = newSphereGenerator(sphereGeneratorClass);
            Object generatorB = newSphereGenerator(sphereGeneratorClass);
            Object runtime = sphereRuntimeClass
                .getConstructor(sphereGeneratorClass)
                .newInstance(generator);
            TellusGeneratorRuntime typedRuntime = (TellusGeneratorRuntime) runtime;
            assertFalse(typedRuntime.owns((ChunkGenerator) generatorB));
            assertEquals(TellusGeneratorRuntime.AttachResult.NOT_OWNED,
                typedRuntime.attach((ChunkGenerator) generatorB));
            assertFalse(typedRuntime.attached());
            assertRuntimeLifecycle(typedRuntime, (ChunkGenerator) generator);
        }

        if (!foundTypedLayer) {
            assertNotNull(TellusGeneratorSettingsView.Kind.CUBE_NET);
        }
    }

    @Test
    void cubeNetSettingsFactoryRejectsMismatchedSourceAndKeepsLegacyDefault() throws Exception {
        Class<?> generatorClass = optionalClass("com.yucareux.tellus.worldgen.CubeNetChunkGenerator");
        Class<?> adapterClass = optionalClass(
            "com.yucareux.tellus.worldgen.runtime.CubeNetGeneratorSettingsViewAdapter");
        if (generatorClass == null || adapterClass == null) {
            assertNotNull(TellusGeneratorSettingsView.Kind.CUBE_NET);
            return;
        }

        Object generator = newCubeGenerator(generatorClass);
        Object defaults = cubeSettingsDefault();
        Object adapter = adapterClass.getConstructor(generatorClass).newInstance(generator);
        Object actual = adapterClass.getMethod("sourceSettings").invoke(adapter);
        assertSame(defaults, actual);

        Object mismatch = alteredCubeSettings(defaults);
        InvocationTargetException error = assertThrows(InvocationTargetException.class, () ->
            adapterClass.getConstructor(generatorClass, defaults.getClass()).newInstance(generator, mismatch));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    private static void assertRuntimeLifecycle(TellusGeneratorRuntime runtime, ChunkGenerator generator) {
        assertFalse(runtime.attached());
        assertTrue(runtime.owns(generator));
        assertEquals(TellusGeneratorRuntime.AttachResult.ATTACHED, runtime.attach(generator));
        assertTrue(runtime.attached());
        assertEquals(TellusGeneratorRuntime.AttachResult.ALREADY_ATTACHED, runtime.attach(generator));
        runtime.detach();
        assertFalse(runtime.attached());
    }

    @Test
    void earthRuntimeAdapterHasIdempotentOwnershipLifecycle() {
        EarthChunkGenerator generator = new EarthChunkGenerator(
            new FixedBiomeSource(plains()), EarthGeneratorSettings.DEFAULT);
        EarthChunkGenerator generatorB = new EarthChunkGenerator(
            new FixedBiomeSource(plains()), EarthGeneratorSettings.DEFAULT);
        EarthGeneratorRuntimeAdapter runtime = new EarthGeneratorRuntimeAdapter(generator);

        assertFalse(runtime.attached());
        assertTrue(runtime.owns(generator));
        assertFalse(runtime.owns(generatorB));
        assertEquals(TellusGeneratorRuntime.AttachResult.NOT_OWNED, runtime.attach(generatorB));
        assertFalse(runtime.attached());
        assertEquals(TellusGeneratorRuntime.AttachResult.ATTACHED, runtime.attach(generator));
        assertTrue(runtime.attached());
        assertEquals(TellusGeneratorRuntime.AttachResult.ALREADY_ATTACHED, runtime.attach(generator));
        runtime.detach();
        assertFalse(runtime.attached());
    }

    private static Holder<Biome> plains() {
        return VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
    }

    private static Object newCubeGenerator(Class<?> generatorClass) throws Exception {
        Constructor<?> constructor = generatorClass.getConstructor(
            FixedBiomeSource.class, double.class, int.class, int.class, int.class);
        return constructor.newInstance(new FixedBiomeSource(plains()), 1.0, 64, -64, 384);
    }

    private static Object newSphereGenerator(Class<?> generatorClass) throws Exception {
        Constructor<?> constructor = generatorClass.getConstructor(
            FixedBiomeSource.class, int.class, double.class, int.class, int.class, int.class);
        return constructor.newInstance(new FixedBiomeSource(plains()), 0, 1.0, 64, -64, 384);
    }

    private static Object cubeSettingsDefault() throws Exception {
        Class<?> settingsClass = Class.forName(
            "com.yucareux.tellus.worldgen.CubeNetBiomeSource$Settings");
        Field field = settingsClass.getField("DEFAULT");
        return field.get(null);
    }

    private static Object alteredCubeSettings(Object defaults) throws Exception {
        Class<?> settingsClass = defaults.getClass();
        java.lang.reflect.RecordComponent[] components = settingsClass.getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType)
            .toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            values[index] = components[index].getAccessor().invoke(defaults);
            if (components[index].getName().equals("randomBiomes")) {
                values[index] = !((Boolean) values[index]);
            }
        }
        Constructor<?> constructor = settingsClass.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absentTargetLayer) {
            return null;
        }
    }

    private static final class MinimalSettingsView implements TellusGeneratorSettingsView {
        @Override public String generatorId() { return "test:sphere"; }
        @Override public Kind kind() { return Kind.SPHERE_FACE; }
        @Override public String geometryId() { return "test:geometry"; }
        @Override public double worldScale() { return 1.0; }
        @Override public VerticalRange verticalRange() { return new VerticalRange(-64, 384, 384); }
        @Override public int seaLevel() { return 64; }
        @Override public int heightOffset() { return 64; }
        @Override public Optional<SpawnPoint> spawn() { return Optional.empty(); }
        @Override public Optional<TerrainOptions> terrain() { return Optional.empty(); }
        @Override public Optional<StructureOptions> structures() { return Optional.empty(); }
        @Override public Optional<RandomBiomeOptions> randomBiomes() { return Optional.empty(); }
        @Override public Optional<RealtimeOptions> realtime() { return Optional.empty(); }
        @Override public Optional<DistantHorizonsOptions> distantHorizons() { return Optional.empty(); }
        @Override public Optional<VoxyOptions> voxy() { return Optional.empty(); }
        @Override public Optional<ManagedTerrainOptions> managedTerrain() { return Optional.empty(); }
        @Override public Optional<ExperimentalHeightOptions> experimentalHeight() { return Optional.empty(); }
        @Override public java.util.Set<Capability> capabilities() { return java.util.Set.of(); }
    }
}
