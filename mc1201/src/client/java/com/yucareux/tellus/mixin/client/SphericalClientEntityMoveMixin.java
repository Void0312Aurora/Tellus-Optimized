package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.SphereClientMotion;
import com.yucareux.tellus.client.SphereClientState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents only a quarantined local prediction step. Cross-face candidates
 * remain visible to the server routing path and are not swallowed here.
 */
@Mixin(Entity.class)
public abstract class SphericalClientEntityMoveMixin {
   @Inject(method = "move", at = @At("HEAD"), cancellable = true)
   private void tellus$guardQuarantinedPrediction(MoverType type, Vec3 movement, CallbackInfo ci) {
      if ((Object)this instanceof LocalPlayer && type == MoverType.SELF
         && blockLocalMove((LocalPlayer)(Object)this, movement)) {
         ci.cancel();
      }
   }

   private static boolean blockLocalMove(LocalPlayer player, Vec3 movement) {
      SphereClientState.updateLocalPose(player.getX(), player.getY(), player.getZ(), player.getYRot());
      var frame = SphereClientState.current();
      if (!frame.spherical()) return false;
      return SphereClientMotion.shouldBlockLocalMove(frame, movement.x, movement.z, 1.0,
         (face, x, z) -> {
            if (face != frame.face()) return false;
            var box = player.getBoundingBox().move(x - player.getX(), 0.0, z - player.getZ());
            return !player.level().noCollision(player, box);
         });
   }
}
