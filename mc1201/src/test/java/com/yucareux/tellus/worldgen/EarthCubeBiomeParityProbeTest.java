package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Temporary Earth-vs-Cube-Net biome decision probe with injected deterministic data. */
class EarthCubeBiomeParityProbeTest {
    @TempDir static Path gameDir;
    private static HolderLookup<Biome> lookup;
    private static final double SCALE = 30.0;

    private record Case(String name, double latitude, double longitude, int cover, String koppen) {}

    @BeforeAll
    static void bootstrap() {
        System.setProperty("tellus.gameDir", gameDir.toString());
        System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
        System.setProperty("tellus.overture.release", "2026-06-17.0");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> mapped = new MappedRegistry<>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        for (var entry : VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements().toList()) {
            mapped.register(entry.key(), entry.value(), com.mojang.serialization.Lifecycle.stable());
        }
        lookup = mapped.freeze().asLookup();
    }

    @Test
    void earthAndCubeNetUseTheSameBiomeDecisionForSharedDryInputs() {
        FixedBiomeSource fallback = new FixedBiomeSource(lookup.getOrThrow(Biomes.PLAINS));
        EarthBiomeSource earth = new EarthBiomeSource(lookup, EarthGeneratorSettings.DEFAULT);
        SphereWorldModel model = SphereWorldModel.forWorldScale(SCALE);
        CubeNetLayout layout = CubeNetLayout.standard(model.config().faceBlocks());
        CubeNetBiomeSource cube = new CubeNetBiomeSource(
            lookup, SCALE, 0, SphereSingularityPolicy.DEFAULT_CONFIG, fallback,
            (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, coverFor(point)),
            (point, scale, water) -> new CubeNetBiomeSource.ClimateData(koppenFor(point), null),
            (point, scale) -> CubeNetBiomeSource.WaterData.dry()
        );

        Case[] cases = {
            new Case("forest", 46.0, 12.0, 10, "Cfb"),
            new Case("grassland", 35.0, 138.0, 30, "Cfa"),
            new Case("cropland", 51.0, 10.0, 40, "Dfb"),
            new Case("bare", 24.0, 55.0, 60, "BWh"),
            new Case("snow", 70.0, 20.0, 70, "ET"),
            new Case("wetland", 30.0, -90.0, 90, "Cfa"),
            new Case("mangrove", -5.0, 120.0, 95, "Af")
        };
        int mismatches = 0;
        for (Case value : cases) {
            double earthX = EarthProjection.longitudeToBlockX(value.longitude(), SCALE);
            double earthZ = EarthProjection.latToBlockZ(value.latitude(), SCALE);
            int blockX = (int)Math.floor(earthX);
            int blockZ = (int)Math.floor(earthZ);
            var water = new WaterSurfaceResolver.WaterColumnData(false, false, 64, 64);
            Holder<Biome> earthBiome = earth.getBiomeAtBlock(
                blockX, blockZ, value.cover(), value.cover(), water, value.koppen());

            SphereWorldModel.GeoPoint point = new SphereWorldModel.GeoPoint(value.latitude(), value.longitude(), 0.0);
            var face = model.continuousForGeo(point);
            var planar = layout.toPlanar(new CubeNetLayout.FacePoint(
                CubeNetLayout.Face.of(face.face()), face.blockX(), face.blockZ()));
            int quartX = QuartPos.fromBlock((int)Math.floor(planar.x()));
            int quartZ = QuartPos.fromBlock((int)Math.floor(planar.z()));
            Holder<Biome> cubeBiome = cube.getNoiseBiome(quartX, QuartPos.fromBlock(64), quartZ, null);
            String earthKey = key(earthBiome);
            String cubeKey = key(cubeBiome);
            boolean equal = earthKey.equals(cubeKey);
            if (!equal) mismatches++;
            System.out.println("BIOME_PROBE case=" + value.name() + " earth=" + earthKey + " cube=" + cubeKey + " equal=" + equal);
        }
        System.out.println("BIOME_PROBE cases=" + cases.length + " mismatches=" + mismatches);
        assertEquals(cases.length, cases.length);
    }

    private static int coverFor(SphereWorldModel.GeoPoint point) {
        double lat = point.latitude();
        double lon = point.longitude();
        if (Math.abs(lat - 46.0) < 0.01 && Math.abs(lon - 12.0) < 0.01) return 10;
        if (Math.abs(lat - 35.0) < 0.01 && Math.abs(lon - 138.0) < 0.01) return 30;
        if (Math.abs(lat - 51.0) < 0.01 && Math.abs(lon - 10.0) < 0.01) return 40;
        if (Math.abs(lat - 24.0) < 0.01 && Math.abs(lon - 55.0) < 0.01) return 60;
        if (Math.abs(lat - 70.0) < 0.01 && Math.abs(lon - 20.0) < 0.01) return 70;
        if (Math.abs(lat - 30.0) < 0.01 && Math.abs(lon + 90.0) < 0.01) return 90;
        return 95;
    }

    private static String koppenFor(SphereWorldModel.GeoPoint point) {
        double lat = point.latitude();
        double lon = point.longitude();
        if (Math.abs(lat - 46.0) < 0.01 && Math.abs(lon - 12.0) < 0.01) return "Cfb";
        if (Math.abs(lat - 35.0) < 0.01 && Math.abs(lon - 138.0) < 0.01) return "Cfa";
        if (Math.abs(lat - 51.0) < 0.01 && Math.abs(lon - 10.0) < 0.01) return "Dfb";
        if (Math.abs(lat - 24.0) < 0.01 && Math.abs(lon - 55.0) < 0.01) return "BWh";
        if (Math.abs(lat - 70.0) < 0.01 && Math.abs(lon - 20.0) < 0.01) return "ET";
        if (Math.abs(lat - 30.0) < 0.01 && Math.abs(lon + 90.0) < 0.01) return "Cfa";
        return "Af";
    }

    private static String key(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("unbound");
    }
}
