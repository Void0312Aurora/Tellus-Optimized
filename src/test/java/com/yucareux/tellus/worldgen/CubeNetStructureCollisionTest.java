package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.osm.*;
import com.yucareux.tellus.world.data.geo.GeodeticBounds;
import com.yucareux.tellus.worldgen.global.*;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Shared tests call each target's real private generator adapter, not a copied adapter body. */
class CubeNetStructureCollisionTest {
   @TempDir static Path directory;
   static CubeNetChunkGenerator generator;
   static SphereWorldModel model;
   static CubeNetLayout layout;
   static Method nativeCollision;

   @BeforeAll static void bootstrap() throws Exception {
      SphereFaceNativeBiomeKernelBridgeTest.directory = directory;
      SphereFaceNativeBiomeKernelBridgeTest.bootstrap();
      try (var ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         generator = new CubeNetChunkGenerator(new FixedBiomeSource(
            SphereFaceNativeBiomeKernelBridgeTest.biomes.getOrThrow(Biomes.PLAINS)), 1000.0, 64, -64, 384,
            (point, resolution) -> new SphereChunkSampler.SurfaceData(10.0, 30), 0);
      }
      model = generator.model(); layout = generator.adapter().layout();
      nativeCollision = CubeNetChunkGenerator.class.getDeclaredMethod("cubeNetStructureCollides",
         BoundingBox.class, SphereChunkSampler.FeatureData.class, SphereWorldModel.SphereChunkAddress.class);
      nativeCollision.setAccessible(true);
   }
   @AfterAll static void restore() { SphereFaceNativeBiomeKernelBridgeTest.restore(); }

   static int u() { return ((layout.faceSize() * 37 / 100) / 16) * 16; }
   static int v() { return ((layout.faceSize() * 42 / 100) / 16) * 16; }
   static CubeNetStructureCollision.Rectangle engineBox(CubeNetLayout.Face face, int u0, int v0, int u1, int v1) {
      int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      for (int u : new int[]{u0,u1}) for (int v : new int[]{v0,v1}) {
         var p = layout.toPlanar(new CubeNetLayout.FacePoint(face, u + 0.5, v + 0.5));
         int x = (int)Math.floor(p.x()), z = (int)Math.floor(p.z());
         minX = Math.min(minX,x); minZ = Math.min(minZ,z); maxX = Math.max(maxX,x); maxZ = Math.max(maxZ,z);
      }
      return new CubeNetStructureCollision.Rectangle(minX,minZ,maxX,maxZ);
   }
   static double[][] ring(CubeNetLayout.Face face, boolean longitude) {
      int x = u()+4, z = v()+4;
      int[][] corners = {{x-2,z-2},{x+3,z-2},{x+3,z+3},{x-2,z+3},{x-2,z-2}};
      double[] values = new double[corners.length];
      for (int i=0;i<corners.length;i++) {
         var p = model.exactSurfacePoint(face.index(),corners[i][0],corners[i][1]);
         values[i] = longitude ? p.longitude() : p.latitude();
      }
      return new double[][]{values};
   }
   static SphereChunkSampler.FeatureData data(CubeNetLayout.Face face, int kind, boolean miss) {
      double[][] lon = ring(face,true), lat = ring(face,false);
      var building = new OsmBuildingFeature(OsmBuildingKind.FOOTPRINT, 77, "structure-overlap", false,
         new OsmBuildingMetadata("residential",null,null,null,1,null,null,null,null,null),6,0,lon,lat);
      var road = new RoadFeature(78,RoadClass.NORMAL,RoadMode.NORMAL,0,"residential",
         new double[]{lon[0][0],lon[0][2]},new double[]{lat[0][0],lat[0][2]});
      var area = new RoadAreaFeature(79,RoadClass.NORMAL,"residential","asphalt","",lon,lat);
      return new SphereChunkSampler.FeatureData(
         new TellusOsmBuildingSource.BuildingQueryResult(kind==0 ? List.of(building) : List.of(),miss),
         new TellusOsmRoadSource.RoadQueryResult(kind==1 ? List.of(road) : List.of(),miss),
         new TellusOsmWaterSource.WaterQueryResult(List.of(),TellusOsmWaterSource.CoverageStatus.COMPLETE,0),
         new TellusOsmRoadSource.RoadAreaQueryResult(kind==2 ? List.of(area) : List.of(),miss));
   }
   static boolean actual(CubeNetLayout.Face face, CubeNetStructureCollision.Rectangle box,
      SphereChunkSampler.FeatureData data) throws Exception {
      var address = new SphereWorldModel.SphereChunkAddress(face.index(),u()/16,v()/16);
      return (boolean)nativeCollision.invoke(generator,new BoundingBox(box.minX(),20,box.minZ(),box.maxX(),40,box.maxZ()),data,address);
   }

   @Test void nativeAdapterDetectsBuildingsRoadsAndRoadAreasOnEveryRotatedTranslatedFace() throws Exception {
      int checked = 0;
      for (var face : CubeNetLayout.Face.values()) {
         var box = engineBox(face,u(),v(),u()+15,v()+15);
         // Frozen pre-fix clipping: address.blockX() was already a block origin.
         int oldX=u()*16, oldZ=v()*16;
         assertTrue(Math.max(oldX-96,box.minX())>Math.min(oldX+111,box.maxX())
            || Math.max(oldZ-96,box.minZ())>Math.min(oldZ+111,box.maxZ()),"original defect fixture "+face);
         for (int kind=0;kind<3;kind++) {
            assertTrue(actual(face,box,data(face,kind,false)),face+" kind="+kind);
            assertFalse(actual(face,engineBox(face,u()+128,v()+128,u()+143,v()+143),data(face,kind,false)),face+" disjoint");
            checked++;
         }
      }
      assertEquals(18,checked);
   }
   @Test void partialCacheResultsRemainUsableAndEmptyMissDoesNotInventCollision() throws Exception {
      for (var face : CubeNetLayout.Face.values()) {
         var box=engineBox(face,u(),v(),u()+15,v()+15);
         assertFalse(actual(face,box,data(face,-1,true)));
         assertTrue(actual(face,box,data(face,0,true)));
         assertTrue(actual(face,box,data(face,2,true)),"road areas independent of empty building/road caches");
      }
   }
   @Test void inclusiveCellBoundsAreExactForNegativeEngineCoordinatesAndAllFaceOrientations() {
      for (var face:CubeNetLayout.Face.values()) for (int size:new int[]{1,2,16,33}) {
         var box=engineBox(face,u(),v(),u()+size-1,v()+size-1);
         assertEquals(new CubeNetStructureCollision.Rectangle(u(),v(),u()+size-1,v()+size-1),
            CubeNetStructureCollision.faceBounds(layout,face,box).orElseThrow());
      }
   }
   @Test void faceEdgesAreClippedAndEmptyPlanarGapIsNotRasterizedAsCanonicalTerrain() {
      for (var face:CubeNetLayout.Face.values()) {
         var box=engineBox(face,0,0,15,15);
         var grown=new CubeNetStructureCollision.Rectangle(box.minX()-32,box.minZ()-32,box.maxX()+32,box.maxZ()+32);
         var clipped=CubeNetStructureCollision.faceBounds(layout,face,grown).orElseThrow();
         assertEquals(0,clipped.minX());assertEquals(0,clipped.minZ());
         assertTrue(clipped.maxX()<layout.faceSize());assertTrue(clipped.maxZ()<layout.faceSize());
      }
      var gap=new CubeNetStructureCollision.Rectangle(layout.faceSize()+16,layout.faceSize()+16,
         layout.faceSize()+31,layout.faceSize()+31);
      assertTrue(java.util.Arrays.stream(CubeNetLayout.Face.values()).allMatch(face ->
         CubeNetStructureCollision.faceBounds(layout,face,gap).isEmpty()));
   }
   @Test void landmarkQueriesUseGeodeticEarthGridRatherThanEnginePlanarCoordinates() {
      for (var face:CubeNetLayout.Face.values()) {
         int x=u()+4,z=v()+4;
         var point=model.exactSurfacePoint(face.index(),x+0.5,z+0.5);
         int earthX=(int)Math.floor(EarthProjection.longitudeToBlockX(point.longitude(),1000.0));
         int earthZ=(int)Math.floor(EarthProjection.latToBlockZ(point.latitude(),1000.0));
         var calls=new AtomicInteger();
         assertTrue(CubeNetStructureCollision.collides(model,layout,engineBox(face,x,z,x,z),data(face,-1,true),false,true,
            (ex,ez)->{calls.incrementAndGet();return ex==earthX&&ez==earthZ;}));
         assertEquals(1,calls.get());
      }
   }
   @Test void disabledFamiliesDoNotInspectGeometryAndQueryMarginUsesWorldMetresAndFullFootprint() {
      assertFalse(CubeNetStructureCollision.collides(null,null,null,null,false,false,null));
      var box=new CubeNetStructureCollision.Rectangle(-100,-200,300,400);
      assertEquals(1_568_000.0,CubeNetStructureCollision.queryMarginMeters(0,0,box,1000.0));
      assertEquals(96.0,CubeNetStructureCollision.queryMarginMeters(0,0,
          new CubeNetStructureCollision.Rectangle(0,0,15,15),1.0));
   }
   @Test void hugeCoarseScaleQueriesAreUnknownWithoutTileAllocationAndLandmarksStillApply() throws Exception {
      var buildings=new TellusOsmBuildingSource();var roads=new TellusOsmRoadSource();
      for(Object source:new Object[]{buildings,roads}) {
         for(String name:List.of("initialized","available")) {
            var f=source.getClass().getDeclaredField(name);f.setAccessible(true);f.setBoolean(source,true);
         }
         var zoom=source.getClass().getDeclaredField("queryZoom");zoom.setAccessible(true);zoom.setInt(source,20);
      }
      var full=new GeodeticBounds(-90,-180,90,180);
      var disabled=CubeNetStructureCollision.boundedFeatures(full,false,false,null,null);
      assertFalse(disabled.buildings().hadCacheMiss());assertFalse(disabled.roads().hadCacheMiss());
      var unknown=CubeNetStructureCollision.boundedFeatures(full,true,true,buildings,roads);
      assertTrue(unknown.buildings().hadCacheMiss());assertTrue(unknown.roads().hadCacheMiss());
      assertTrue(unknown.roadAreas().hadCacheMiss());assertTrue(unknown.buildings().features().isEmpty());
      for(Object source:new Object[]{buildings,roads}) {
         var pending=source.getClass().getDeclaredField("pendingAsyncLoads");pending.setAccessible(true);
         assertTrue(((java.util.Set<?>)pending.get(source)).isEmpty(),"over-budget must not enqueue source work");
         var cache=source.getClass().getDeclaredField("cache");cache.setAccessible(true);
         assertEquals(0,((com.google.common.cache.LoadingCache<?,?>)cache.get(source)).size());
      }
      var face=CubeNetLayout.Face.POS_Z;var box=engineBox(face,u(),v(),u(),v());
      assertFalse(CubeNetStructureCollision.collides(model,layout,box,unknown,true,true,null));
      assertTrue(CubeNetStructureCollision.collides(model,layout,box,unknown,true,true,(x,z)->true));
      var small=new GeodeticBounds(0,0,0,0);
      assertTrue(buildings.canQueryGeoAreaWithinBudget(small,1));assertTrue(roads.canQueryGeoAreaWithinBudget(small,1));
   }
}
