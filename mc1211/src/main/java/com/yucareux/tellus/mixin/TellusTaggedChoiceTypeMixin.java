package com.yucareux.tellus.mixin;

import com.mojang.datafixers.types.templates.TaggedChoice;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.yucareux.tellus.compat.TellusDfu1211Compat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps only DFU's dispatch call after Fabric dimensions' 1.20-era fail-soft
 * injection has run. That injection returns Codec.PASSTHROUGH, which DFU 8.0.16
 * later casts to MapCodec and rejects. The wrapper replaces the result only for
 * the two currently registered Tellus generator ids; every other schema/key
 * returns the original Fabric result unchanged.
 */
@Mixin(value = TaggedChoice.TaggedChoiceType.class, remap = false, priority = 1100)
public abstract class TellusTaggedChoiceTypeMixin<K> {
   @WrapOperation(
      method = "lambda$buildCodec$2",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/datafixers/types/templates/TaggedChoice$TaggedChoiceType;getMapCodec(Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"
      ),
      remap = false
   )
   private DataResult<?> tellus$adaptTellusMapCodec(
      TaggedChoice.TaggedChoiceType<?> owner, Object key, Operation<DataResult<?>> original) {
      DataResult<?> result = original.call(owner, key);
      if (TellusDfu1211Compat.shouldUseOpaqueFallback(owner.getName(), owner.types(), key)) {
         return DataResult.success(TellusDfu1211Compat.opaqueMapCodec());
      }
      return result;
   }
}
