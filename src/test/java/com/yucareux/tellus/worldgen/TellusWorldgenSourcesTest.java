package com.yucareux.tellus.worldgen;

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
