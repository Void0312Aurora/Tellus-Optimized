package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.CubeNetApronMutationRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forwards writes from a native apron into canonical face storage. */
@Mixin(Level.class)
public abstract class CubeNetApronMutationMixin {
   @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
      at = @At("HEAD"), cancellable = true)
   private void tellus$routeApronBlockSet(BlockPos pos, BlockState state, int flags,
      CallbackInfoReturnable<Boolean> cir) {
      if (CubeNetApronMutationRouter.route((Level)(Object)this, pos, state, flags)) {
         cir.setReturnValue(true);
      }
   }

   @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
      at = @At("HEAD"), cancellable = true)
   private void tellus$routeApronBlockSetWithRecursion(BlockPos pos, BlockState state, int flags,
      int recursionDepth, CallbackInfoReturnable<Boolean> cir) {
      if (CubeNetApronMutationRouter.route((Level)(Object)this, pos, state, flags)) {
         cir.setReturnValue(true);
      }
   }
}
