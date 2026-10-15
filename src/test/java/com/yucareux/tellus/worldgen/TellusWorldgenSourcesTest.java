package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TellusWorldgenSourcesTest {
   @Test
   void syntheticSphericalSamplerIsExplicitAndDeterministic() {
      String previous = System.getProperty("tellus.sphere.syntheticSampler");
      try {
         System.setProperty("tellus.sphere.syntheticSampler", "true");
         var sampler = TellusWorldgenSources.sphericalSampler(
            com.yucareux.tellus.worldgen.global.SphereWorldModel.forWorldScale(1.0));
         var dry = sampler.samplePoint(new com.yucareux.tellus.worldgen.global.SphereWorldModel.GeoPoint(0.0, 0.0, 0.0));
         var wet = sampler.samplePoint(new com.yucareux.tellus.worldgen.global.SphereWorldModel.GeoPoint(-60.0, 0.0, 0.0));
         assertEquals(dry, sampler.samplePoint(new com.yucareux.tellus.worldgen.global.SphereWorldModel.GeoPoint(0.0, 0.0, 0.0)));
         assertEquals(30, dry.coverClass());
         assertFalse(dry.waterAvailable());
         assertEquals(80, wet.coverClass());
         assertTrue(wet.waterAvailable());
         assertEquals(0.0, wet.waterSurfaceMeters());
      } finally {
         if (previous == null) System.clearProperty("tellus.sphere.syntheticSampler");
         else System.setProperty("tellus.sphere.syntheticSampler", previous);
      }
   }

   @Test
   void chunkComputeSubmissionCompletesWhenParallelFillIsDisabled() {
      assumeTrue(!Boolean.parseBoolean(System.getProperty("tellus.chunkgen.parallelSolidSections", "false")));
      CompletableFuture<Void> result = TellusWorldgenSources.submitChunkComputeTask(() -> {
      });
      result.join();
      assertTrue(result.isDone());
   }

   @Test
   void failedChunkComputeTaskCompletesExceptionally() {
      IllegalStateException expected = new IllegalStateException("expected task failure");
      CompletableFuture<Void> result = TellusWorldgenSources.submitChunkComputeTask(() -> { throw expected; });
      CompletionException error = assertThrows(CompletionException.class, result::join);
      assertTrue(error.getCause() == expected);
   }

   @Test
   void enabledChunkComputePoolRunsIndependentTasksConcurrently() throws Exception {
      assumeTrue(Boolean.parseBoolean(System.getProperty("tellus.chunkgen.parallelSolidSections", "false")));
      Set<String> workerNames = ConcurrentHashMap.newKeySet();
      CountDownLatch twoTasksStarted = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      CompletableFuture<?>[] tasks = IntStream.range(0, 8)
         .mapToObj(ignored -> TellusWorldgenSources.submitChunkComputeTask(() -> {
            workerNames.add(Thread.currentThread().getName());
            twoTasksStarted.countDown();
            try {
               assertTrue(release.await(5L, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
               Thread.currentThread().interrupt();
               throw new AssertionError(error);
            }
         }))
         .toArray(CompletableFuture[]::new);

      try {
         assertTrue(twoTasksStarted.await(5L, TimeUnit.SECONDS));
      } finally {
         release.countDown();
      }

      CompletableFuture.allOf(tasks).join();
      assertTrue(workerNames.size() >= 2, "at least two workers must execute overlapping tasks");
      assertTrue(workerNames.stream().allMatch(name -> name.startsWith("tellus-chunkgen-")),
         "the enabled pool should own the chunk-compute tasks");
   }
}
