package com.yucareux.tellus.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;

/** Controlled native objects in an actual transformed server; never places or saves blocks. */
public final class TellusNativeStructureMixinProbe {
    private static final long SEED = 773L;
    private static final int SHIFT_Y = 17;

    private TellusNativeStructureMixinProbe() { }

    public static JsonObject verify(ServerLevel level) {
        require(level != null, "actual server level required");
        require(Boolean.getBoolean("tellus.persistence.probe"), "explicit persistence probe required");
        require(!Boolean.getBoolean("tellus.sphere.syntheticSampler"), "synthetic sampler excluded");
        require(level.getServer().isSameThread(), "actual server main thread required");
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        require(generator instanceof TellusGeodeticPosition, "actual Tellus geodetic generator required");

        try {
            var root = new OceanMonumentPieces.MonumentBuilding(RandomSource.create(SEED), 0, 0, Direction.NORTH);
            List<StructurePiece> children = children(root);
            Box before = box(root);
            List<Box> childBefore = boxes(children);
            root.move(0, SHIFT_Y, 0);
            requireShift(before, box(root), SHIFT_Y, "root move");
            require(children.size() == children(root).size(), "move changed child count");
            JsonArray moves = new JsonArray();
            for (int i = 0; i < children.size(); i++) {
                require(children.get(i) == children(root).get(i), "move changed child identity/order");
                requireShift(childBefore.get(i), box(children.get(i)), SHIFT_Y, "child move " + i);
                moves.add(change(childBefore.get(i), box(children.get(i))));
            }

            // Native regeneration has its own seeded room construction. Compare shifted and
            // unshifted calls, not an assumed match to the constructor's original room graph.
            var baselineRoot = new OceanMonumentPieces.MonumentBuilding(RandomSource.create(SEED), 0, 0, Direction.NORTH);
            PiecesContainer baseline = OceanMonumentStructure.regeneratePiecesAfterLoad(
                new ChunkPos(0, 0), SEED, new PiecesContainer(List.of(baselineRoot)));
            PiecesContainer reloaded = OceanMonumentStructure.regeneratePiecesAfterLoad(
                new ChunkPos(0, 0), SEED, new PiecesContainer(List.of(root)));
            require(baseline.pieces().size() == 1 && reloaded.pieces().size() == 1,
                "expected one regenerated monument root");
            StructurePiece loadedRoot = reloaded.pieces().get(0);
            StructurePiece unshiftedRoot = baseline.pieces().get(0);
            require(loadedRoot != root, "native reload must reconstruct the root");
            require(box(loadedRoot).minY() == box(root).minY()
                && box(loadedRoot).maxY() == box(root).maxY(), "reload lost loaded root Y");
            requireShift(box(unshiftedRoot), box(loadedRoot), SHIFT_Y, "regenerated root");
            List<StructurePiece> reloadChildren = children(loadedRoot);
            List<StructurePiece> baselineChildren = children(unshiftedRoot);
            require(reloadChildren.size() == baselineChildren.size(), "regenerated room count differs");
            JsonArray reloadMoves = new JsonArray();
            for (int i = 0; i < reloadChildren.size(); i++) {
                require(reloadChildren.get(i).getClass() == baselineChildren.get(i).getClass(),
                    "regenerated room type/order differs");
                requireShift(box(baselineChildren.get(i)), box(reloadChildren.get(i)), SHIFT_Y,
                    "regenerated child " + i);
                reloadMoves.add(change(box(baselineChildren.get(i)), box(reloadChildren.get(i))));
            }

            Class<?>[] signature = {int.class, int.class, WorldGenLevel.class, StructureManager.class,
                ChunkGenerator.class, RandomSource.class, BoundingBox.class, ChunkPos.class, BlockPos.class};
            List<Method> redirects = Arrays.stream(root.getClass().getDeclaredMethods())
                .filter(method -> method.getName().contains("useTellusSeaLevel")
                    && method.getReturnType() == int.class && Arrays.equals(method.getParameterTypes(), signature))
                .toList();
            require(redirects.size() == 1, "unique actual transformed sea-level redirect required");
            Method redirect = redirects.get(0);
            require(redirect.trySetAccessible(), "sea-level redirect inaccessible");
            // The redirect only examines generator and the two ints. Other arguments are
            // inert context, with no postProcess invocation or writes to this real level.
            int sea = (Integer)redirect.invoke(root, 37, 63, level, null, generator,
                RandomSource.create(SEED), root.getBoundingBox(), new ChunkPos(0, 0), new BlockPos(0, 0, 0));
            require(sea == 37, "actual Tellus generator did not select shifted sea branch");

            JsonObject result = new JsonObject();
            result.addProperty("scope", "actual-native-mixin-controlled-objects-not-natural-starts");
            result.addProperty("passed", true);
            result.addProperty("worldWrites", false);
            result.addProperty("nativeSaveInvoked", false);
            result.addProperty("generatorClass", generator.getClass().getName());
            result.addProperty("seed", SEED);
            result.addProperty("shiftY", SHIFT_Y);
            result.addProperty("rootCount", 1);
            result.addProperty("childCount", children.size());
            result.add("rootMove", change(before, box(root)));
            result.add("childMoves", moves);
            JsonObject reload = new JsonObject();
            reload.addProperty("nativeRegeneratePiecesAfterLoad", true);
            reload.addProperty("rootReconstructed", true);
            reload.addProperty("loadedRootYPreserved", true);
            reload.addProperty("rootCount", reloaded.pieces().size());
            reload.addProperty("childCount", reloadChildren.size());
            reload.add("rootMove", change(box(unshiftedRoot), box(loadedRoot)));
            reload.add("childMoves", reloadMoves);
            result.add("reload", reload);
            JsonObject branch = new JsonObject();
            branch.addProperty("transformedMethod", redirect.getName());
            branch.addProperty("seaLevel", 37);
            branch.addProperty("minimumSeaLevel", 63);
            branch.addProperty("actualResult", sea);
            branch.addProperty("tellusBranch", true);
            result.add("seaBranch", branch);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("actual native monument mixin reflection failed", failure);
        }
    }

    private static List<StructurePiece> children(StructurePiece root) throws IllegalAccessException {
        // Field names may be remapped. MonumentBuilding has one List field whose
        // actual nonempty contents are StructurePieces; never depend on its name.
        List<Field> fields = Arrays.stream(root.getClass().getDeclaredFields())
            .filter(field -> List.class.isAssignableFrom(field.getType())).toList();
        require(fields.size() == 1, "unique native monument child list required");
        Field field = fields.get(0);
        require(field.trySetAccessible(), "monument child list inaccessible");
        Object value = field.get(root);
        require(value instanceof List<?>, "native monument child list missing");
        List<?> values = (List<?>)value;
        require(!values.isEmpty(), "native monument child list empty");
        List<StructurePiece> result = new ArrayList<>();
        for (Object child : values) {
            require(child instanceof StructurePiece, "native child is not a structure piece");
            result.add((StructurePiece)child);
        }
        return List.copyOf(result);
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }

    private static Box box(StructurePiece piece) {
        BoundingBox b = piece.getBoundingBox();
        return new Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    private static List<Box> boxes(List<StructurePiece> pieces) {
        return pieces.stream().map(TellusNativeStructureMixinProbe::box).toList();
    }

    private static void requireShift(Box before, Box after, int shift, String label) {
        require(before.minX() == after.minX() && before.maxX() == after.maxX()
            && before.minZ() == after.minZ() && before.maxZ() == after.maxZ()
            && after.minY() == before.minY() + shift && after.maxY() == before.maxY() + shift,
            label + " did not preserve X/Z and shift both Y bounds exactly");
    }

    private static JsonObject change(Box before, Box after) {
        JsonObject value = new JsonObject();
        value.addProperty("beforeY", before.minY());
        value.addProperty("beforeMaxY", before.maxY());
        value.addProperty("afterY", after.minY());
        value.addProperty("afterMaxY", after.maxY());
        value.addProperty("minX", before.minX());
        value.addProperty("maxX", before.maxX());
        value.addProperty("minZ", before.minZ());
        value.addProperty("maxZ", before.maxZ());
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
