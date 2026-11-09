package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.global.CubeNetAddressAdapter;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UndergroundStagePlannerTest {
   private static final TellusGenerationContext GENERATION =
      new TellusGenerationContext(42L, 1000.0, 64, -64, 320);
   private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(1000.0);
   private static final UndergroundStagePlan.Flags FLAGS =
      new UndergroundStagePlan.Flags(true, false, true, true);
   private static final UndergroundStagePlan.Flags NO_GEOLOGY =
      new UndergroundStagePlan.Flags(true, false, false, false);

   @Test
   void sameSnapshotProducesDeterministicImmutableDeltasInRecorderOrder() {
      for (var face : CubeNetLayout.Face.values()) {
         var request = request(face, SamplingResolution.exact(1));
         int x = request.chunkX() * 16, z = request.chunkZ() * 16;
         var recorded = new ArrayList<>(List.of(
            delta(x + 2, 63, z + 1, UndergroundStagePlan.UndergroundMaterial.CAVE_AIR),
            delta(x + 2, 62, z + 1, UndergroundStagePlan.UndergroundMaterial.WATER),
            delta(x, -63, z, UndergroundStagePlan.UndergroundMaterial.GRANITE)));
         AtomicInteger factories = new AtomicInteger(), resolves = new AtomicInteger();
         var planner = UndergroundStagePlanner.fromResolver(r -> {
            factories.incrementAndGet();
            assertSame(request, r);
            return ignored -> { resolves.incrementAndGet(); return recorded; };
         });
         var first = planner.plan(request);
         var second = planner.plan(request);
         assertEquals(DecisionStatus.AVAILABLE, first.status());
         assertEquals(first, second);
         assertEquals(recorded, first.deltas());
         assertEquals(2, factories.get());
         assertEquals(2, resolves.get());
         recorded.clear();
         assertEquals(3, first.deltas().size());
         assertThrows(UnsupportedOperationException.class, () -> first.deltas().clear());
         assertThrows(UnsupportedOperationException.class, () -> first.diagnostics().add("changed"));
      }
   }

   @Test
   void allFourArraysAreDefensiveAndHaloCenterMatchesSurface() {
      var identity = request(CubeNetLayout.Face.POS_X, SamplingResolution.exact(1));
      int[] surface = columns(64), flood = columns(40), floor = columns(-64), halo = halo(surface);
      var request = copy(identity, GENERATION, MODEL, surface, flood, floor, halo, FLAGS, "r1");
      surface[0] = 999; flood[0] = 999; floor[0] = 999; halo[0] = 999; halo[42] = 999;
      assertEquals(64, request.surfaceY()[0]);
      assertEquals(40, request.floodGuardY()[0]);
      assertEquals(-64, request.generationFloorY()[0]);
      assertEquals(64, request.surfaceHaloY()[0]);
      assertEquals(64, request.surfaceHaloY()[42]);
      request.surfaceY()[0] = -1000;
      request.floodGuardY()[0] = -1000;
      request.generationFloorY()[0] = -1000;
      request.surfaceHaloY()[42] = -1000;
      assertEquals(64, request.surfaceY()[0]);
      assertEquals(40, request.floodGuardY()[0]);
      assertEquals(-64, request.generationFloorY()[0]);
      assertEquals(64, request.surfaceHaloY()[42]);
      int[] varied = columns(64);
      varied[255] = 89;
      var variedRequest = copy(identity, GENERATION, MODEL, varied, null, null, halo(varied), FLAGS, "varied");
      assertEquals(89, variedRequest.surfaceHaloY()[17 * 20 + 17]);
   }

   @Test
   void nonBlockingIsColdBeforeFactoryOrResolver() {
      AtomicInteger factories = new AtomicInteger(), resolves = new AtomicInteger();
      var planner = UndergroundStagePlanner.fromResolver(request -> {
         factories.incrementAndGet();
         return ignored -> { resolves.incrementAndGet(); return List.of(); };
      });
      for (var face : CubeNetLayout.Face.values()) {
         var result = planner.plan(request(face, SamplingResolution.nonBlocking(1)));
         assertEquals(DecisionStatus.QUEUED, result.status());
         assertTrue(result.deltas().isEmpty());
      }
      assertEquals(0, factories.get());
      assertEquals(0, resolves.get());
      assertThrows(NullPointerException.class, () -> planner.plan(null));
      assertEquals(0, factories.get());
   }

   @Test
   void unknownQueuedAndPartialNeverPublishDeltaPrefixes() {
      var request = request(CubeNetLayout.Face.POS_X, SamplingResolution.exact(1));
      var absent = UndergroundStagePlanner.fromResolver(r -> null).plan(request);
      var incomplete = UndergroundStagePlanner.fromResolver(r -> ignored -> null).plan(request);
      for (var result : List.of(absent, incomplete)) {
         assertEquals(DecisionStatus.UNKNOWN, result.status());
         assertTrue(result.deltas().isEmpty());
      }
      int x = request.chunkX() * 16, z = request.chunkZ() * 16;
      var delta = delta(x, 64, z, UndergroundStagePlan.UndergroundMaterial.CAVE_AIR);
      for (var status : List.of(DecisionStatus.UNKNOWN, DecisionStatus.QUEUED)) {
         assertTrue(UndergroundStagePlan.blocked(request, status, "cold").deltas().isEmpty());
         assertThrows(IllegalArgumentException.class,
            () -> new UndergroundStagePlan(request, status, List.of(delta), List.of()));
      }
      assertThrows(IllegalArgumentException.class,
         () -> new UndergroundStagePlan(request, DecisionStatus.PARTIAL, List.of(), List.of()));
      assertThrows(IllegalArgumentException.class,
         () -> UndergroundStagePlan.blocked(request, DecisionStatus.AVAILABLE, "invalid"));
   }

   @Test
   void requestRejectsBadArraysFlagsHaloProfileAndRevision() {
      var identity = request(CubeNetLayout.Face.POS_X, SamplingResolution.exact(1));
      assertThrows(NullPointerException.class,
         () -> copy(identity, GENERATION, MODEL, null, null, null, null, NO_GEOLOGY, "r"));
      for (int badLength : new int[]{0, 255, 257}) {
         assertThrows(IllegalArgumentException.class,
            () -> copy(identity, GENERATION, MODEL, new int[badLength], null, null, null, NO_GEOLOGY, "r"));
         assertThrows(IllegalArgumentException.class,
            () -> copy(identity, GENERATION, MODEL, columns(64), new int[badLength], null, null, NO_GEOLOGY, "r"));
         assertThrows(IllegalArgumentException.class,
            () -> copy(identity, GENERATION, MODEL, columns(64), null, new int[badLength], null, NO_GEOLOGY, "r"));
      }
      assertThrows(IllegalArgumentException.class,
         () -> copy(identity, GENERATION, MODEL, columns(64), null, null, new int[399], FLAGS, "r"));
      assertThrows(IllegalArgumentException.class,
         () -> copy(identity, GENERATION, MODEL, columns(64), null, null, null, FLAGS, "r"));
      int[] wrongCenter = halo(columns(64)); wrongCenter[42] = 65;
      assertThrows(IllegalArgumentException.class,
         () -> copy(identity, GENERATION, MODEL, columns(64), null, null, wrongCenter, FLAGS, "r"));
      assertThrows(IllegalArgumentException.class, () -> copy(identity, GENERATION, MODEL, columns(64), null, null,
         null, new UndergroundStagePlan.Flags(false, true, false, false), "r"));
      assertThrows(IllegalArgumentException.class,
         () -> copy(identity, GENERATION, MODEL, columns(64), null, null, null, NO_GEOLOGY, " "));
      assertThrows(IllegalArgumentException.class,
         () -> copy(identity, GENERATION, SphereWorldModel.forWorldScale(1), columns(64), null, null, null, NO_GEOLOGY, "r"));
      for (var generation : List.of(new TellusGenerationContext(42,1000,64,-63,320),
         new TellusGenerationContext(42,1000,64,-64,319),
         new TellusGenerationContext(42,1000,64,-64,4048),
         new TellusGenerationContext(42,1000,64,Integer.MIN_VALUE,Integer.MAX_VALUE))) {
         assertThrows(IllegalArgumentException.class,
            () -> copy(identity, generation, MODEL, columns(64), null, null, null, NO_GEOLOGY, "r"));
      }
   }

   @Test
   void invalidDeltasMarkersAndOverBudgetOutputRejectBeforePublication() {
      var request = request(CubeNetLayout.Face.POS_X, SamplingResolution.exact(1));
      int x = request.chunkX() * 16, z = request.chunkZ() * 16;
      var delta = delta(x, 64, z, UndergroundStagePlan.UndergroundMaterial.WATER);
      assertThrows(IllegalArgumentException.class,
         () -> UndergroundStagePlan.available(request, List.of(delta, delta), List.of()));
      for (var outside : List.of(delta(x + 16,64,z,UndergroundStagePlan.UndergroundMaterial.LAVA),
         delta(x - 1,64,z,UndergroundStagePlan.UndergroundMaterial.LAVA),
         delta(x,64,z + 16,UndergroundStagePlan.UndergroundMaterial.LAVA),
         delta(x,64,z - 1,UndergroundStagePlan.UndergroundMaterial.LAVA),
         delta(x,320,z,UndergroundStagePlan.UndergroundMaterial.TUFF),
         delta(x,-65,z,UndergroundStagePlan.UndergroundMaterial.TUFF))) {
         assertThrows(IllegalArgumentException.class,
            () -> UndergroundStagePlan.available(request, List.of(outside), List.of()));
      }
      for (var material : UndergroundStagePlan.UndergroundMaterial.values()) {
         boolean fluid = material == UndergroundStagePlan.UndergroundMaterial.WATER
            || material == UndergroundStagePlan.UndergroundMaterial.LAVA;
         assertThrows(IllegalArgumentException.class,
            () -> new UndergroundStagePlan.Delta(x, 64, z, material, !fluid));
         assertDoesNotThrow(() -> new UndergroundStagePlan.Delta(x, 64, z, material, fluid));
      }
      assertThrows(NullPointerException.class, () -> new UndergroundStagePlan.Delta(x,64,z,null,false));
      assertThrows(IllegalArgumentException.class, () -> UndergroundStagePlan.available(request,
         Collections.nCopies(UndergroundStagePlan.MAX_DELTAS + 1, delta), List.of()));
   }

   @Test
   void exclusionsArePureImmutableAndRetainExpandedBounds() {
      var identity = request(CubeNetLayout.Face.POS_X, SamplingResolution.exact(1));
      var exclusion = new UndergroundStagePlan.Exclusion(-2, 4, -3, 2, 12, 3, 5, true);
      var input = new ArrayList<>(List.of(exclusion));
      var request = new UndergroundStagePlan.Request(GENERATION, MODEL, identity.address(),
         identity.chunkX(), identity.chunkZ(), columns(64), null, null, input,
         halo(columns(64)), FLAGS, "source-r1", SamplingResolution.exact(1));
      input.clear();
      assertEquals(List.of(exclusion), request.structureExclusions());
      assertThrows(UnsupportedOperationException.class, () -> request.structureExclusions().clear());
      assertTrue(exclusion.blocksCarving(0, 10, 0));
      assertTrue(exclusion.blocksFeaturePlacement(0, 10, 0));
      assertFalse(exclusion.blocksCarving(100, 10, 0));
      assertFalse(new UndergroundStagePlan.Exclusion(0,0,0,1,1,1,false).blocksFeaturePlacement(0,0,0));
      var edge = new UndergroundStagePlan.Exclusion(Integer.MAX_VALUE,0,0,Integer.MAX_VALUE,1,1,4,true);
      assertTrue(edge.blocksCarving(Integer.MAX_VALUE - 2, 0, 0));
      assertThrows(IllegalArgumentException.class, () -> new UndergroundStagePlan.Exclusion(1,0,0,0,1,1));
      assertThrows(IllegalArgumentException.class, () -> new UndergroundStagePlan.Exclusion(0,0,0,1,1,1,-1,true));
   }

   @Test
   void sixFacesUseCanonicalEngineCoordinatesIncludingNegativeTargets() {
      boolean negativeEngineSeen = false;
      var adapter = new CubeNetAddressAdapter(MODEL);
      for (var face : CubeNetLayout.Face.values()) {
         var request = request(face, SamplingResolution.exact(1));
         assertEquals(request.address(), adapter.chunkAddress(request.chunkX(), request.chunkZ()).orElseThrow());
         assertTrue(request.address().chunkX() >= 0 && request.address().chunkZ() >= 0);
         negativeEngineSeen |= request.chunkX() < 0 || request.chunkZ() < 0;
         int x = request.chunkX() * 16, z = request.chunkZ() * 16;
         var plan = UndergroundStagePlan.available(request,
            List.of(delta(x,-64,z,UndergroundStagePlan.UndergroundMaterial.GRANITE)), List.of());
         assertEquals(DecisionStatus.AVAILABLE, plan.status());
         var wrongFace = new SphereWorldModel.SphereChunkAddress((face.index() + 1) % 6,
            request.address().chunkX(), request.address().chunkZ());
         assertThrows(IllegalArgumentException.class, () -> new UndergroundStagePlan.Request(GENERATION,MODEL,
            wrongFace,request.chunkX(),request.chunkZ(),columns(64),null,null,List.of(),NO_GEOLOGY,"r",SamplingResolution.exact(1)));
         var outsideFace = new SphereWorldModel.SphereChunkAddress(face.index(), MODEL.chunksPerFace(), 0);
         assertThrows(IllegalArgumentException.class, () -> new UndergroundStagePlan.Request(GENERATION,MODEL,
            outsideFace,request.chunkX(),request.chunkZ(),columns(64),null,null,List.of(),NO_GEOLOGY,"r",SamplingResolution.exact(1)));
      }
      assertTrue(negativeEngineSeen);
      var valid = request(CubeNetLayout.Face.POS_X, SamplingResolution.exact(1));
      int outsideEngine = Math.toIntExact(MODEL.chunksPerFace() * 100L);
      assertThrows(IllegalArgumentException.class, () -> new UndergroundStagePlan.Request(GENERATION,MODEL,
         valid.address(),outsideEngine,outsideEngine,columns(64),null,null,List.of(),NO_GEOLOGY,"r",SamplingResolution.exact(1)));
      assertThrows(ArithmeticException.class, () -> new UndergroundStagePlan.Request(GENERATION,MODEL,
         valid.address(),Integer.MAX_VALUE,0,columns(64),null,null,List.of(),NO_GEOLOGY,"r",SamplingResolution.exact(1)));
   }

   @Test
   void factoryResolverAndErrorFailuresPropagateWithoutPartialPlans() {
      var request = request(CubeNetLayout.Face.POS_X, SamplingResolution.exact(1));
      var failure = new IllegalStateException("resolver failure");
      var factoryFailure = UndergroundStagePlanner.fromResolver(r -> { throw failure; });
      assertSame(failure, assertThrows(IllegalStateException.class, () -> factoryFailure.plan(request)));
      var resolverFailure = UndergroundStagePlanner.fromResolver(r -> ignored -> { throw failure; });
      assertSame(failure, assertThrows(IllegalStateException.class, () -> resolverFailure.plan(request)));
      var error = new AssertionError("resolver error");
      var errorPlanner = UndergroundStagePlanner.fromResolver(r -> ignored -> { throw error; });
      assertSame(error, assertThrows(AssertionError.class, () -> errorPlanner.plan(request)));
      assertThrows(NullPointerException.class, () -> UndergroundStagePlanner.fromResolver(null));
   }

   @Test
   void completeStageMayBeEmptyWithNullableOptionalArraysAndMaximumHeight() {
      var base = request(CubeNetLayout.Face.NEG_X, SamplingResolution.exact(1));
      var request = new UndergroundStagePlan.Request(GENERATION,MODEL,base.address(),base.chunkX(),base.chunkZ(),
         columns(64),null,null,List.of(),NO_GEOLOGY,"no-geology",SamplingResolution.exact(1));
      assertNull(request.floodGuardY());
      assertNull(request.generationFloorY());
      assertNull(request.surfaceHaloY());
      var plan = UndergroundStagePlanner.fromResolver(r -> ignored -> List.of()).plan(request);
      assertEquals(DecisionStatus.AVAILABLE, plan.status());
      assertTrue(plan.deltas().isEmpty());
      var maximum = copy(base,new TellusGenerationContext(42,1000,64,-2048,2048),MODEL,
         columns(64),null,null,null,NO_GEOLOGY,"max-height");
      int x = maximum.chunkX() * 16, z = maximum.chunkZ() * 16;
      assertDoesNotThrow(() -> UndergroundStagePlan.available(maximum,List.of(
         delta(x,-2048,z,UndergroundStagePlan.UndergroundMaterial.CAVE_AIR),
         delta(x + 15,2047,z + 15,UndergroundStagePlan.UndergroundMaterial.LAVA)),List.of()));
   }

   private static UndergroundStagePlan.Request request(CubeNetLayout.Face face, SamplingResolution resolution) {
      var address = new SphereWorldModel.SphereChunkAddress(face.index(), 1, 2);
      var planar = CubeNetLayout.standard(MODEL.config().faceBlocks()).toPlanar(
         new CubeNetLayout.FacePoint(face, address.blockX() + 8.0, address.blockZ() + 8.0));
      int chunkX = Math.toIntExact((long)Math.floor(planar.x() / 16.0));
      int chunkZ = Math.toIntExact((long)Math.floor(planar.z() / 16.0));
      int[] surface = columns(64);
      return new UndergroundStagePlan.Request(GENERATION,MODEL,address,chunkX,chunkZ,
         surface,null,null,List.of(),halo(surface),FLAGS,"source-r1",resolution);
   }

   private static UndergroundStagePlan.Request copy(UndergroundStagePlan.Request identity,
      TellusGenerationContext generation,SphereWorldModel model,int[] surface,int[] flood,int[] floor,
      int[] halo,UndergroundStagePlan.Flags flags,String revision) {
      return new UndergroundStagePlan.Request(generation,model,identity.address(),identity.chunkX(),identity.chunkZ(),
         surface,flood,floor,List.of(),halo,flags,revision,identity.resolution());
   }

   private static UndergroundStagePlan.Delta delta(int x,int y,int z,UndergroundStagePlan.UndergroundMaterial material) {
      boolean fluid = material == UndergroundStagePlan.UndergroundMaterial.WATER
         || material == UndergroundStagePlan.UndergroundMaterial.LAVA;
      return new UndergroundStagePlan.Delta(x,y,z,material,fluid);
   }

   private static int[] columns(int value) {
      int[] result = new int[256]; Arrays.fill(result,value); return result;
   }

   private static int[] halo(int[] surface) {
      int[] result = new int[400]; Arrays.fill(result,64);
      for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) result[(z + 2) * 20 + x + 2] = surface[z * 16 + x];
      return result;
   }
}
