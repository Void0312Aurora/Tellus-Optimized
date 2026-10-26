package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetClientTransition;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the locally controlled boat at the server's staging barrier. */
@Mixin(Boat.class)
public abstract class CubeNetBoatStagingMixin {
   @Inject(method = "controlBoat", at = @At("HEAD"), cancellable = true)
   private void tellus$holdStagedBoat(CallbackInfo ci) {
      Boat boat = (Boat)(Object)this;
      if (CubeNetClientTransition.holdsVehicle(boat)) {
         boat.setDeltaMovement(Vec3.ZERO);
         ci.cancel();
      }
   }
}
