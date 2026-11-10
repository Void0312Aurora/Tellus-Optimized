package com.yucareux.tellus.mixin;

import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.types.templates.TaggedChoice;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.yucareux.tellus.compat.TellusDfu1211Compat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects DFU's private dispatch call so Fabric dimensions' 1.20-era
 * fail-soft injection is bypassed for this one path. That injection returns
 * Codec.PASSTHROUGH, which DFU 8.0.16 casts to MapCodec and rejects. This
 * redirect only handles the two currently registered Tellus generator ids in
 * the level.dat tagged-choice schema; every other choice follows the exact
 * vanilla map-codec/error behavior.
 */
@Mixin(value = TaggedChoice.TaggedChoiceType.class, remap = false, priority = 1100)
public abstract class TellusTaggedChoiceTypeMixin<K> {
   @Redirect(
      method = "lambda$buildCodec$2",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/datafixers/types/templates/TaggedChoice$TaggedChoiceType;getMapCodec(Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"
      ),
      remap = false
   )
   private DataResult<? extends MapCodec<?>> tellus$selectMapCodec(
      TaggedChoice.TaggedChoiceType<?> owner, Object key) {
      Type<?> type = owner.types().get(key);
      if (type != null) {
         return DataResult.success(MapCodec.assumeMapUnsafe(type.codec()));
      }
      if (TellusDfu1211Compat.shouldUseOpaqueFallback(owner.getName(), owner.types(), key)) {
         return DataResult.success(TellusDfu1211Compat.opaqueMapCodec());
      }
      return DataResult.error(() -> "Unknown type: " + key);
   }
}
