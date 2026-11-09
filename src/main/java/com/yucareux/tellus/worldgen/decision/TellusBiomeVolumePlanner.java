package com.yucareux.tellus.worldgen.decision;

import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.write.CanonicalColumnPosition;
import com.yucareux.tellus.worldgen.write.WriteContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Pure planner for an exact, Minecraft-free biome quart volume. */
@FunctionalInterface
public interface TellusBiomeVolumePlanner {
   TellusBiomeVolumePlan plan(TellusBiomeVolumePlan.Request request);

   @FunctionalInterface
   interface Resolver {
      BiomeDecision resolve(WriteContext context, int quartX, int quartY, int quartZ);
   }

   static TellusBiomeVolumePlanner fromResolver(
      Function<TellusBiomeVolumePlan.Request, Resolver> factory
   ) {
      return fromResolver(factory, null);
   }

   /**
    * Creates a planner whose resolver is created only after the entire request
    * has passed structural/profile/anchor validation. A null model selects the
    * generation-scale default model; a non-null model must explicitly match it.
    */
   static TellusBiomeVolumePlanner fromResolver(
      Function<TellusBiomeVolumePlan.Request, Resolver> factory,
      SphereWorldModel model
   ) {
      Objects.requireNonNull(factory, "factory");
      return request -> {
         validateRequest(request, model);
         if (request.resolution().kind() == SamplingResolution.Kind.NON_BLOCKING) {
            return TellusBiomeVolumePlan.blocked(request, DecisionStatus.QUEUED,
               "biome volume request is NON_BLOCKING; resolver remains cold");
         }
         Resolver resolver = Objects.requireNonNull(factory.apply(request), "resolver");
         List<TellusBiomeVolumePlan.QuartCoordinate> order =
            TellusBiomeVolumePlan.nativeCellOrder(request);
         List<TellusBiomeVolumePlan.Cell> cells = new ArrayList<>(order.size());
         for (TellusBiomeVolumePlan.QuartCoordinate coordinate : order) {
            int localX = Math.floorMod(coordinate.quartX(), 4);
            int localZ = Math.floorMod(coordinate.quartZ(), 4);
            WriteContext context = request.columns().get(localX + 4 * localZ);
            BiomeDecision biome = Objects.requireNonNull(
               resolver.resolve(context, coordinate.quartX(), coordinate.quartY(), coordinate.quartZ()),
               "resolver biome decision");
            if (!biome.known()) {
               return TellusBiomeVolumePlan.blocked(request, DecisionStatus.UNKNOWN,
                  "resolver returned an unknown biome at quart "
                     + coordinate.quartX() + "," + coordinate.quartY() + "," + coordinate.quartZ());
            }
            cells.add(new TellusBiomeVolumePlan.Cell(
               coordinate.quartX(), coordinate.quartY(), coordinate.quartZ(), biome));
         }
         return TellusBiomeVolumePlan.available(request, cells,
            List.of("complete native biome quart volume"));
      };
   }

   /** Validates against the generation-scale default spherical model. */
   public static void validateRequest(TellusBiomeVolumePlan.Request request) {
      validateRequest(request, null);
   }

   /** Validates a request without invoking a source factory or resolver. */
   public static void validateRequest(TellusBiomeVolumePlan.Request request, SphereWorldModel suppliedModel) {
      Objects.requireNonNull(request, "request");
      TellusGenerationContext generation = Objects.requireNonNull(request.generation(), "generation");
      long height = (long)generation.maxYExclusive() - generation.minY();
      if (height <= 0L || height > 4096L || height % 16L != 0L) {
         throw new IllegalArgumentException("biome volume height must be a positive multiple of 16 and <=4096");
      }
      if (generation.minY() % 16 != 0 || generation.maxYExclusive() % 16 != 0) {
         throw new IllegalArgumentException("biome volume bounds must be divisible by 16");
      }
      long count = Math.multiplyExact(height / 4L, 16L);
      if (count > 16_384L) throw new IllegalArgumentException("biome volume exceeds cell budget");
      long blockX = Math.multiplyExact((long)request.chunkX(), 16L);
      long blockZ = Math.multiplyExact((long)request.chunkZ(), 16L);
      Math.toIntExact(blockX);
      Math.toIntExact(Math.addExact(blockX, 15L));
      Math.toIntExact(blockZ);
      Math.toIntExact(Math.addExact(blockZ, 15L));
      SphereWorldModel model = suppliedModel == null
         ? SphereWorldModel.forWorldScale(generation.worldScale()) : suppliedModel;
      if (Double.compare(model.config().worldScale(), generation.worldScale()) != 0) {
         throw new IllegalArgumentException("biome volume model world scale differs from generation");
      }
      List<WriteContext> columns = request.columns();
      if (columns.size() != 16) throw new IllegalArgumentException("biome volume requires 16 columns");
      GeneratorOwner owner = null;
      for (int localZ = 0; localZ < 4; localZ++) {
         for (int localX = 0; localX < 4; localX++) {
            int slot = localX + 4 * localZ;
            WriteContext context = Objects.requireNonNull(columns.get(slot), "column context");
            if (!generation.equals(context.generation())) {
               throw new IllegalArgumentException("column generation does not match volume generation");
            }
            if (context.apronMirror() || context.targetOwner() == GeneratorOwner.CUBE_NET_APRON) {
               throw new IllegalArgumentException("biome volume accepts canonical columns only");
            }
            GeneratorOwner current = context.targetOwner();
            if (owner == null) owner = current;
            else if (owner != current) throw new IllegalArgumentException("volume columns must share one owner kind");
            CanonicalColumnPosition position = CanonicalColumnPosition.resolve(context, model);
            long expectedX = Math.addExact(blockX, Math.multiplyExact((long)localX, 4L));
            long expectedZ = Math.addExact(blockZ, Math.multiplyExact((long)localZ, 4L));
            if (position.x() != expectedX || position.z() != expectedZ) {
               throw new IllegalArgumentException("column is not the canonical quart anchor for its slot");
            }
         }
      }
   }
}
