package com.yucareux.tellus.client;

import com.yucareux.tellus.worldgen.global.SphereFaceTopology;
import com.yucareux.tellus.worldgen.global.SphereSingularityPolicy;
import com.yucareux.tellus.worldgen.global.SphereSurfaceMotion;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client-side preview of the same conformal surface step used by the server.
 * It never changes dimensions or overrides server authority; callers use the
 * result to feed prediction, collision, and rendering from one seam contract.
 */
public final class SphereClientMotion {
   private static final ConcurrentMap<Double, Context> CONTEXTS = new ConcurrentHashMap<>();

   private SphereClientMotion() {
   }

   public static Preview preview(SphereClientState.Snapshot frame,
      double velocityX, double velocityZ, double seconds) {
      return preview(frame, velocityX, velocityZ, seconds, (face, x, z) -> false);
   }

   public static Preview preview(SphereClientState.Snapshot frame,
      double velocityX, double velocityZ, double seconds, SphereSurfaceMotion.CollisionProbe probe) {
      Objects.requireNonNull(frame, "frame");
      Objects.requireNonNull(probe, "probe");
      if (!frame.spherical()) return Preview.inactive(frame);
      if (!SphereWorldModel.MAPPING_ID.equals(frame.mappingId())) return Preview.invalid(frame, Status.UNKNOWN_MAPPING);
      try {
         Context context = CONTEXTS.computeIfAbsent(frame.worldScale(), SphereClientMotion::context);
         SphereFaceTopology topology = context.motion().topology();
         if (!topology.containsEnginePoint(frame.x(), frame.z())) return Preview.invalid(frame, Status.INVALID_POSE);
         SphereSurfaceMotion.Result result = context.motion().step(frame.face(), frame.x(), frame.z(),
            velocityX, velocityZ, seconds, probe);
         if (result.blocked()) {
            SphereSurfaceMotion.Result quarantine = context.motion().step(frame.face(), frame.x(), frame.z(),
               velocityX, velocityZ, seconds, (face, x, z) -> false);
            return new Preview(quarantine.blocked() ? Status.BLOCKED : Status.COLLISION_BLOCKED, result);
         }
         return new Preview(result.crossedFace() ? Status.CROSS_FACE : Status.SAME_FACE, result);
      } catch (IllegalArgumentException error) {
         return Preview.invalid(frame, Status.INVALID_POSE);
      }
   }

   public static boolean shouldBlockLocalMove(SphereClientState.Snapshot frame,
      double velocityX, double velocityZ, double seconds) {
      return preview(frame, velocityX, velocityZ, seconds).status() == Status.BLOCKED;
   }

   public static boolean shouldBlockLocalMove(SphereClientState.Snapshot frame,
      double velocityX, double velocityZ, double seconds, SphereSurfaceMotion.CollisionProbe probe) {
      return preview(frame, velocityX, velocityZ, seconds, probe).status() == Status.BLOCKED;
   }

   private static Context context(double worldScale) {
      SphereWorldModel model = SphereWorldModel.forWorldScale(worldScale);
      return new Context(new SphereSurfaceMotion(model,
         new SphereSingularityPolicy(model, SphereSingularityPolicy.DEFAULT_CONFIG)));
   }

   public enum Status { INACTIVE, SAME_FACE, CROSS_FACE, BLOCKED, COLLISION_BLOCKED, UNKNOWN_MAPPING, INVALID_POSE }

   public record Preview(Status status, SphereSurfaceMotion.Result result) {
      public Preview {
         Objects.requireNonNull(status, "status");
         if (status == Status.SAME_FACE || status == Status.CROSS_FACE
            || status == Status.BLOCKED || status == Status.COLLISION_BLOCKED) {
            Objects.requireNonNull(result, "result");
         }
      }

      private static Preview inactive(SphereClientState.Snapshot frame) {
         return new Preview(Status.INACTIVE, null);
      }

      private static Preview invalid(SphereClientState.Snapshot frame, Status status) {
         return new Preview(status, null);
      }
   }

   private record Context(SphereSurfaceMotion motion) {
   }
}
