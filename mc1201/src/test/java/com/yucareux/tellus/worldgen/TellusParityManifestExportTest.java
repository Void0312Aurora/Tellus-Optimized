package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereChunkSampler;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Explicit native-to-native manifest export of the recovered fixtures, not GIS/world-save evidence. */
class TellusParityManifestExportTest {
    private static final Path OUTPUT = Path.of(System.getProperty("java.io.tmpdir"), "tellus-parity-manifests", "mc1201");
    private static final Gson JSON = new Gson();
    private static HolderLookup<Biome> lookup;
    private static Holder<Biome> plains;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements().forEach(entry ->
            registry.register(entry.key(), entry.value(), com.mojang.serialization.Lifecycle.stable()));
        lookup = registry.freeze().asLookup();
        plains = lookup.getOrThrow(Biomes.PLAINS);
    }

    @Test void exportEveryOriginalNativeColumnWithFullBlockDigest() throws Exception {
        double scale = 1.0;
        int sea = 64, minY = -1024, height = 3056;
        SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
        SphereChunkSampler.PointSampler sampler = (point, resolution) -> {
            double lat = Math.toRadians(point.latitude()), lon = Math.toRadians(point.longitude());
            double elevation = 420.0 * Math.sin(lat) + 260.0 * Math.cos(lon) + 90.0 * Math.sin(lat + lon);
            boolean wet = elevation < 0.0;
            return new SphereChunkSampler.SurfaceData(elevation, wet ? 80 : 30, wet ? 0.0 : Double.NaN);
        };
        CubeNetChunkGenerator cube = new CubeNetChunkGenerator(new FixedBiomeSource(plains), scale, sea, minY, height, sampler, 0);
        CubeNetLayout layout = cube.adapter().layout();
        LevelHeightAccessor bounds = LevelHeightAccessor.create(minY, height);
        long half = model.chunksPerFace() / 2L;
        long[] samples = {half - 32L, half, half + 32L};
        List<String> left = new ArrayList<>(), right = new ArrayList<>();
        Map<Integer, String> stateLegend = new TreeMap<>();
        long compared = 0;
        for (int faceIndex = 0; faceIndex < 6; faceIndex++) {
            SphereFaceChunkGenerator face = new SphereFaceChunkGenerator(new FixedBiomeSource(plains), faceIndex, scale, sea, minY, height, sampler);
            for (long cx : samples) for (long cz : samples) {
                var address = new SphereWorldModel.SphereChunkAddress(faceIndex, cx, cz);
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    long canonicalX = address.blockX() + x, canonicalZ = address.blockZ() + z;
                    var planar = layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(faceIndex), canonicalX + 0.5, canonicalZ + 0.5));
                    int cubeX = (int)Math.floor(planar.x()), cubeZ = (int)Math.floor(planar.z());
                    int faceX = Math.toIntExact(canonicalX - half * 16L), faceZ = Math.toIntExact(canonicalZ - half * 16L);
                    NoiseColumn cubeColumn = cube.getBaseColumn(cubeX, cubeZ, bounds, null);
                    NoiseColumn faceColumn = face.getBaseColumn(faceX, faceZ, bounds, null);
                    byte[] cubeBytes = new byte[height * 4], faceBytes = new byte[height * 4];
                    for (int dy = 0; dy < height; dy++) {
                        var a = cubeColumn.getBlock(minY + dy); var b = faceColumn.getBlock(minY + dy);
                        int ai = Block.getId(a), bi = Block.getId(b);
                        stateLegend.putIfAbsent(ai, a.toString()); stateLegend.putIfAbsent(bi, b.toString());
                        putInt(cubeBytes, dy * 4, ai); putInt(faceBytes, dy * 4, bi); compared++;
                    }
                    String key = "face=" + faceIndex + ";chunk=" + cx + "," + cz + ";local=" + x + "," + z;
                    int aHeight = cube.getBaseHeight(cubeX, cubeZ, Heightmap.Types.WORLD_SURFACE_WG, bounds, null);
                    int bHeight = face.getBaseHeight(faceX, faceZ, Heightmap.Types.WORLD_SURFACE_WG, bounds, null);
                    left.add(row(key, cubeColumn, aHeight, minY, height, cubeBytes));
                    right.add(row(key, faceColumn, bHeight, minY, height, faceBytes));
                    assertArrayEquals(cubeBytes, faceBytes, key);
                    assertEquals(aHeight, bHeight, key);
                }
            }
        }
        assertEquals(13824, left.size());
        write("cube-net.jsonl", left); write("sphere-face.jsonl", right);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fixture", "recovered original consistency fixture; native-to-native; deterministic inputs; not live GIS");
        metadata.put("worldScale", scale); metadata.put("seaLevel", sea); metadata.put("minY", minY); metadata.put("height", height);
        metadata.put("chunksPerFace", model.chunksPerFace()); metadata.put("chunks", samples);
        metadata.put("blockDigestEncoding", "SHA-256 of ascending Y native Block.getId int32 big-endian; same JVM and state legend");
        metadata.put("stateLegend", stateLegend); metadata.put("comparedBlockStates", compared);
        write("columns-metadata.json", List.of(JSON.toJson(metadata)));
        System.out.println("PARITY_MANIFEST_EXPORT columns=" + left.size() + " comparedBlockStates=" + compared + " mismatchBlocks=0 output=" + OUTPUT);
    }

    private record Case(String name, double lat, double lon, int cover, String climate) { }
    private static final Case[] CASES = {
        new Case("forest",46,12,10,"Cfb"), new Case("grassland",35,138,30,"Cfa"),
        new Case("cropland",51,10,40,"Dfb"), new Case("bare",24,55,60,"BWh"),
        new Case("snow",70,20,70,"ET"), new Case("wetland",30,-90,90,"Cfa"), new Case("mangrove",-5,120,95,"Af")
    };
    @Test void exportSevenActualBiomeCasesWithoutNormalizingResults() throws Exception {
        double scale = 30.0;
        var fallback = new FixedBiomeSource(plains);
        var earth = new EarthBiomeSource(lookup, EarthGeneratorSettings.DEFAULT);
        SphereWorldModel model = SphereWorldModel.forWorldScale(scale);
        var layout = CubeNetLayout.standard(model.config().faceBlocks());
        var cube = new CubeNetBiomeSource(lookup, scale, 0, SphereSingularityPolicy.DEFAULT_CONFIG, fallback,
            (point,resolution) -> new SphereChunkSampler.SurfaceData(10.0, at(point).cover()),
            (point,resolution,water) -> new CubeNetBiomeSource.ClimateData(at(point).climate(),null),
            (point,resolution) -> CubeNetBiomeSource.WaterData.dry());
        List<String> rows = new ArrayList<>(); int mismatches = 0;
        for (Case c : CASES) {
            int x = (int)Math.floor(EarthProjection.longitudeToBlockX(c.lon(), scale));
            int z = (int)Math.floor(EarthProjection.latToBlockZ(c.lat(), scale));
            String a = key(earth.getBiomeAtBlock(x,z,c.cover(),c.cover(),new WaterSurfaceResolver.WaterColumnData(false,false,64,64),c.climate()));
            var face = model.continuousForGeo(new SphereWorldModel.GeoPoint(c.lat(),c.lon(),0));
            var planar = layout.toPlanar(new CubeNetLayout.FacePoint(CubeNetLayout.Face.of(face.face()),face.blockX(),face.blockZ()));
            String b = key(cube.getNoiseBiome(QuartPos.fromBlock((int)Math.floor(planar.x())),QuartPos.fromBlock(64),QuartPos.fromBlock((int)Math.floor(planar.z())),null));
             if (!a.equals(b)) mismatches++;
             assertEquals(a,b,c.name()+"/"+c.climate());
             if (c.name().equals("bare")) assertEquals("minecraft:desert",b);
            rows.add(JSON.toJson(Map.of("case",c.name()+"/"+c.climate(),"earth",a,"cube",b)));
        }
         assertEquals(0,mismatches); assertEquals(7,rows.size()); write("earth-cube-biome.jsonl",rows);
         System.out.println("PARITY_MANIFEST_EXPORT biomeCases=7 mismatches=0 baseline=travel-environment-v2 output="+OUTPUT);
    }
    private static Case at(SphereWorldModel.GeoPoint p) {
        for (int i=0;i<CASES.length-1;i++) { Case c=CASES[i]; if(Math.abs(c.lat()-p.latitude())<0.01 && Math.abs(c.lon()-p.longitude())<0.01)return c; }
        return CASES[CASES.length-1];
    }
    private static String key(Holder<Biome> b) { return b.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("unbound"); }
    private static void putInt(byte[] bytes,int offset,int value) { bytes[offset]=(byte)(value>>>24); bytes[offset+1]=(byte)(value>>>16); bytes[offset+2]=(byte)(value>>>8); bytes[offset+3]=(byte)value; }
    private static String row(String key,NoiseColumn column,int surface,int minY,int height,byte[] bytes) throws Exception {
        int top=Math.max(minY,Math.min(minY+height-1,surface-1));
        return JSON.toJson(Map.of("key",key,"material",column.getBlock(top).toString(),"height",Integer.toString(surface),"status","AVAILABLE",
            "blockSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
    }
    private static void write(String name,List<String> rows) throws Exception { Files.createDirectories(OUTPUT); Files.write(OUTPUT.resolve(name),rows,StandardCharsets.UTF_8); }
}
