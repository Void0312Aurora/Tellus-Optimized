package com.yucareux.tellus.worldgen.decision;

import java.util.List;

/** Ordered production decoration decisions; native source eligibility keeps its original stage timing. */
public final class TellusDecorationDecisionKernel {
   public enum Stage { VANILLA, AXOLOTLS, TREES, BUILDINGS, REALTIME_SNOW, ROAD_LIGHTS,
      DEFERRED_DETAILS, LANDMARKS, CUBE_FEATURES, MIRROR_CANONICAL_VIEW }
   private static final List<Stage> CUBE = List.of(Stage.VANILLA, Stage.AXOLOTLS, Stage.TREES,
      Stage.REALTIME_SNOW, Stage.CUBE_FEATURES, Stage.MIRROR_CANONICAL_VIEW);
   private static final List<Stage> APRON = List.of(Stage.CUBE_FEATURES, Stage.MIRROR_CANONICAL_VIEW);
   private static final List<Stage> EARTH_DETAILS = List.of(Stage.TREES, Stage.BUILDINGS,
      Stage.REALTIME_SNOW, Stage.ROAD_LIGHTS, Stage.DEFERRED_DETAILS, Stage.LANDMARKS);
   private TellusDecorationDecisionKernel() { }

   public static List<Stage> cube(boolean canonical) { return canonical ? CUBE : APRON; }

   public static List<Stage> earthOrder() { return EARTH_DETAILS; }

   /** Native eligibility is observed at its original stage, not eagerly before trees. */
   public static boolean executeEarth(Stage stage, boolean delayed, boolean deferredOrReady) {
      if (delayed) return false;
      return switch(stage) {
         case TREES, BUILDINGS, ROAD_LIGHTS -> !deferredOrReady;
         case REALTIME_SNOW, LANDMARKS -> true;
         case DEFERRED_DETAILS -> deferredOrReady;
         default -> throw new IllegalArgumentException("not an Earth detail stage");
      };
   }
}
