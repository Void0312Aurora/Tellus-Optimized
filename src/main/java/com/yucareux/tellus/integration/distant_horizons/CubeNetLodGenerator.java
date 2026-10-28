package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi.Delayed;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainAvailability;
import com.yucareux.tellus.worldgen.CubeNetLodSurface;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/** Surface preview, independent of vanilla chunk loading but bounded by the native apron. */
final class CubeNetLodGenerator implements IDhApiWorldGenerator {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final CubeNetLodSurface surface;
   private final ThreadLocal<WrapperCache> wrappers;
   private final Set<GenerationFuture> tasks = ConcurrentHashMap.newKeySet();
   private volatile boolean closed;

   CubeNetLodGenerator(IDhApiLevelWrapper levelWrapper, CubeNetLodSurface surface) {
      Objects.requireNonNull(levelWrapper, "levelWrapper");
      this.surface = Objects.requireNonNull(surface, "surface");
      this.wrappers = ThreadLocal.withInitial(() -> new WrapperCache(levelWrapper));
   }

   @Override public byte getSmallestDataDetailLevel() { return 0; }
   @Override public byte getLargestDataDetailLevel() { return 12; }
   @Override public EDhApiWorldGeneratorReturnType getReturnType() {
      return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
   }
   @Override public boolean runApiValidation() { return Boolean.getBoolean("tellus.dhApiValidation"); }

   // Optional Tellus DH fork extension: compile against the stock API too.
   public byte getGenerationAvailability(int chunkX, int chunkZ, int widthChunks, byte detail) {
      byte result;
      if (this.closed || !DistantHorizonsIntegration.isDistantGenerationReady()) {
         result = ManagedTerrainAvailability.WAIT;
      } else if (!this.surface.isUniformArea((long)chunkX * 16L,
         (long)chunkZ * 16L, (long)widthChunks * 16L)) {
         // Coarse requests can be split at a cut. A finest-detail request has
         // no smaller representation, so keep it unavailable rather than
         // allowing an outside-apron column to become cached air.
         result = detail > 0 ? ManagedTerrainAvailability.SPLIT : ManagedTerrainAvailability.WAIT;
      } else {
         result = ManagedTerrainAvailability.READY;
      }
      if (Boolean.getBoolean("tellus.dhLodTrace")) LOGGER.info(
         "Cube-Net DH availability chunk=[{}, {}] width={} detail={} result={} closed={} ready={}",
         chunkX, chunkZ, widthChunks, Byte.toUnsignedInt(detail), result, this.closed,
         DistantHorizonsIntegration.isDistantGenerationReady()
      );
      return result;
   }
   @Override public void preGeneratorTaskStart() { }
   @Override public void close() {
      this.closed = true;
      this.tasks.forEach(task -> task.cancel(true));
   }

   @Override
   public CompletableFuture<Void> generateLod(
      int chunkX, int chunkZ, int lodX, int lodZ, byte detail,
      IDhApiFullDataSource output, EDhApiDistantGeneratorMode mode,
      ExecutorService executor, Consumer<IDhApiFullDataSource> consumer
   ) {
      GenerationFuture future = new GenerationFuture();
      this.tasks.add(future);
      future.whenComplete((ignored, error) -> this.tasks.remove(future));
      if (Boolean.getBoolean("tellus.dhLodTrace")) LOGGER.info(
         "Cube-Net DH direct tile start chunk=[{}, {}] lod=[{}, {}] detail={} width={} mode={}",
         chunkX, chunkZ, lodX, lodZ, Byte.toUnsignedInt(detail), output.getWidthInDataColumns(), mode
      );
      if (this.closed) future.cancel(false);
      DistantHorizonsIntegration.distantGenerationReadyFuture().whenComplete((ignored, gateError) -> {
         if (future.isDone()) return;
         if (gateError != null) {
            future.completeExceptionally(gateError);
            return;
         }
         try {
            executor.execute(() -> {
               if (!future.begin()) return;
               Throwable error = null;
               try {
                  buildLod(chunkX, chunkZ, detail, output, future);
                  future.checkCancellation();
                  consumer.accept(output);
               } catch (Throwable failure) {
                  error = failure;
                  LOGGER.warn(
                     "Cube-Net DH direct tile failed chunk=[{}, {}] lod=[{}, {}] detail={} width={}",
                     chunkX, chunkZ, lodX, lodZ, Byte.toUnsignedInt(detail), output.getWidthInDataColumns(), failure
                  );
               } finally {
                  future.finish(error);
               }
            });
         } catch (RuntimeException rejected) {
            future.completeExceptionally(rejected);
         }
      });
      return future;
   }

   private void buildLod(int chunkX, int chunkZ, byte detail, IDhApiFullDataSource output, GenerationFuture future) {
      int level = Byte.toUnsignedInt(detail);
      if (level > getLargestDataDetailLevel()) throw new IllegalArgumentException("unsupported DH detail " + level);
      int cellSize = 1 << level;
      int width = output.getWidthInDataColumns();
      long baseX = (long)chunkX * 16L, baseZ = (long)chunkZ * 16L;
      int minY = this.surface.minY();
      WrapperCache cache = this.wrappers.get();
      List<DhApiTerrainDataPoint> column = new ArrayList<>(6);
      int terrainColumns = 0;
      long start = System.nanoTime();
      for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
         future.checkCancellation();
         column.clear();
         var layers = this.surface.sampleLodColumn(baseX + (long)x * cellSize,
            baseZ + (long)z * cellSize, cellSize).orElse(null);
         if (layers == null) {
            throw new IllegalStateException("Cube-Net LOD column outside canonical/apron coverage");
         } else {
            terrainColumns++;
            int expected = minY;
            for (var layer : layers) {
               if (layer.bottom() != expected || layer.top() > this.surface.maxY()) {
                  throw new IllegalArgumentException("non-contiguous Cube-Net LOD column");
               }
               column.add(DhApiTerrainDataPoint.create((byte)0, 0, 15,
                  layer.bottom() - minY, layer.top() - minY, cache.block(layer.state()), cache.biome));
               expected = layer.top();
            }
            if (expected != this.surface.maxY()) throw new IllegalArgumentException("incomplete Cube-Net LOD column");
         }
         future.checkCancellation();
         output.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.FEATURES, column);
      }
      if (Boolean.getBoolean("tellus.dhLodTiming")) LOGGER.info(
         "Cube-Net DH direct tile chunk=[{}, {}] detail={} width={} terrainColumns={} excludedColumns={} elapsedMs={}",
         chunkX, chunkZ, level, width, terrainColumns, width * width - terrainColumns,
         (System.nanoTime() - start) / 1_000_000L);
   }

   private static final class WrapperCache {
      private final IDhApiLevelWrapper level;
      private final IDhApiBiomeWrapper biome;
      private final Map<BlockState, IDhApiBlockStateWrapper> blocks = new IdentityHashMap<>();
      private WrapperCache(IDhApiLevelWrapper level) {
         this.level = level;
         try {
            this.biome = Objects.requireNonNull(Delayed.wrapperFactory.getBiomeWrapper(
               MinecraftVersionCompat.keyId(Biomes.PLAINS), level));
         } catch (IOException error) {
            throw new IllegalStateException("cannot create Cube-Net DH plains biome wrapper", error);
         }
      }
      IDhApiBlockStateWrapper block(BlockState state) {
         return this.blocks.computeIfAbsent(state,
            value -> Delayed.wrapperFactory.getBlockStateWrapper(new BlockState[]{value}, this.level));
      }
   }

   /** Do not release DH's pooled output while an interrupted sampler still owns it. */
   private static final class GenerationFuture extends CompletableFuture<Void> {
      private volatile boolean cancelled;
      private Thread runner;
      synchronized boolean begin() {
         if (isDone()) return false;
         this.runner = Thread.currentThread();
         return true;
      }
      void checkCancellation() {
         if (this.cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Cube-Net LOD cancelled");
      }
      synchronized void finish(Throwable error) {
         this.runner = null;
         if (this.cancelled || error instanceof CancellationException) {
            Thread.interrupted();
            super.cancel(false);
         } else if (error != null) completeExceptionally(error);
         else complete(null);
      }
      @Override public synchronized boolean cancel(boolean interrupt) {
         if (isDone()) return false;
         this.cancelled = true;
         if (this.runner == null) super.cancel(false);
         else if (interrupt) this.runner.interrupt();
         return true;
      }
   }
}
