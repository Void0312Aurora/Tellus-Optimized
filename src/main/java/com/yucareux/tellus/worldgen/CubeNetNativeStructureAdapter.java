package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.ArrayList;
import java.util.function.BiFunction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

/** Target-version-neutral native piece application; Earth's decision rules are shared, not simplified. */
final class CubeNetNativeStructureAdapter {
   private CubeNetNativeStructureAdapter() { }

   record Column(int terrainSurface, int waterSurface, boolean ocean) {
      int oceanDepth() { return ocean && waterSurface > terrainSurface ? waterSurface - terrainSurface : 0; }
   }

   /** Missing geography is not a zero-height dry column and must not reject or move a valid start. */
   private static final class UnavailableColumn extends RuntimeException { }
   private static Column requireColumn(BiFunction<Integer,Integer,Column> columns, int x, int z) {
      Column result = columns.apply(x,z);
      if (result == null) throw new UnavailableColumn();
      return result;
   }

   static void adapt(RegistryAccess registryAccess, ChunkAccess chunk, int minY, int maxY,
      int seaLevel, boolean enableWater, double worldScale, long worldSeed,
      BiFunction<Integer,Integer,Column> columns,
      BiFunction<Integer,Integer,SphereWorldModel.GeoPoint> geography) {
      var registry = MinecraftVersionCompat.registry(registryAccess, Registries.STRUCTURE);
      for (var entry : new ArrayList<>(chunk.getAllStarts().entrySet())) {
         StructureStart start = entry.getValue();
         if (start == null || !start.isValid()) continue;
         String path = MinecraftVersionCompat.registryKeyPath(registry, entry.getKey());
         if (path == null) continue;
         BoundingBox box = start.getBoundingBox();
         var bounds = new TellusStructureAdaptationRules.Bounds(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ());
         TellusStructureAdaptationRules.Decision decision;
         try {
         if (path.equals("monument") || path.equals("ocean_monument")) {
            decision = TellusStructureAdaptationRules.monument(true, enableWater, bounds, seaLevel, minY, maxY,
               (x,z) -> requireColumn(columns,x,z).oceanDepth());
         } else {
            var kind = switch (path) {
               case "stronghold" -> TellusStructureAdaptationRules.UndergroundKind.STRONGHOLD;
               case "mineshaft" -> TellusStructureAdaptationRules.UndergroundKind.NORMAL_MINESHAFT;
               case "mineshaft_mesa" -> TellusStructureAdaptationRules.UndergroundKind.MESA_MINESHAFT;
               case "ancient_city" -> TellusStructureAdaptationRules.UndergroundKind.ANCIENT_CITY;
               case "trial_chambers" -> TellusStructureAdaptationRules.UndergroundKind.TRIAL_CHAMBERS;
               default -> null;
            };
            if (kind == null || kind == TellusStructureAdaptationRules.UndergroundKind.MESA_MINESHAFT) continue;
            var point = geography.apply(box.getCenter().getX(),box.getCenter().getZ());
            if (point == null) continue; // no invented projection at an excluded/cut-gap center
            double earthX = EarthProjection.longitudeToBlockX(point.longitude(),worldScale);
            double earthZ = EarthProjection.latToBlockZ(point.latitude(),worldScale);
            if (!Double.isFinite(earthX) || !Double.isFinite(earthZ)
               || earthX < Integer.MIN_VALUE || earthX >= (double)Integer.MAX_VALUE + 1.0
               || earthZ < Integer.MIN_VALUE || earthZ >= (double)Integer.MAX_VALUE + 1.0) continue;
            decision = TellusStructureAdaptationRules.underground(kind,true,false,bounds,
               (int)Math.floor(earthX),(int)Math.floor(earthZ),worldSeed,minY,maxY,
               (x,z) -> {
                  var column = requireColumn(columns,x,z);
                  return new TellusStructureAdaptationRules.CenterColumn(column.terrainSurface(),column.oceanDepth()>0);
               }, (x,z) -> requireColumn(columns,x,z).terrainSurface());
         }
         } catch (UnavailableColumn unavailable) {
            continue; // preflight sampled nothing authoritative; all native pieces are still untouched
         }
         if (decision.outcome() == TellusStructureAdaptationRules.Outcome.INVALIDATE) {
            chunk.setStartForStructure(entry.getKey(),StructureStart.INVALID_START);
         } else if (decision.outcome() == TellusStructureAdaptationRules.Outcome.MOVE) {
            var pieces = new ArrayList<StructurePiece>(start.getPieces().size());
            for (var piece : start.getPieces()) {
               piece.move(0,decision.offsetY(),0);
               pieces.add(piece);
            }
            chunk.setStartForStructure(entry.getKey(),new StructureStart(entry.getKey(),start.getChunkPos(),
               start.getReferences(),new PiecesContainer(pieces)));
         }
      }
   }
}
