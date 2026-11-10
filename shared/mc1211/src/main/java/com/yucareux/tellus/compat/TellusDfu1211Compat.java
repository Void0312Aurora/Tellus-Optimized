package com.yucareux.tellus.compat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import java.util.Set;

/**
 * Narrow compatibility boundary for the 1.21.1 DFU/Fabric dimensions mismatch.
 *
 * <p>Fabric dimensions 4.0.0 still supplies a {@code Codec.PASSTHROUGH} from
 * its unknown tagged-choice hook. DFU 8.0.16's tagged choice method now
 * requires a {@link MapCodec}; this helper keeps the fallback opaque while
 * limiting it to the Tellus generator choices and the actual tagged-choice
 * schema used by level.dat.
 */
public final class TellusDfu1211Compat {
   private static final Set<String> REGISTERED_GENERATOR_IDS = Set.of(
       "tellus:earth",
       "tellus:sphere_face",
       "tellus:cube_net"
   );

   private TellusDfu1211Compat() { }

   /**
    * Returns whether the DFU tagged choice should use an opaque map codec.
    * Keys are compared by their serialized names so this works for both
    * ResourceLocation and string key implementations.
    */
   public static boolean shouldUseOpaqueFallback(String choiceName, Map<?, ?> types, Object key) {
      if (!"type".equals(choiceName) || types == null || key == null || types.containsKey(key)) {
         return false;
      }
      String requested = String.valueOf(key);
      if (!REGISTERED_GENERATOR_IDS.contains(requested)) {
         return false;
      }
      boolean hasNoise = false;
      boolean hasFlat = false;
      for (Object typeKey : types.keySet()) {
         String serialized = String.valueOf(typeKey);
         hasNoise |= "minecraft:noise".equals(serialized);
         hasFlat |= "minecraft:flat".equals(serialized);
      }
      return hasNoise && hasFlat;
   }

   /**
    * Adapts DFU's opaque pass-through codec to the MapCodec return type used by
    * DFU 8.0.16. The dynamic payload is retained by the codec; no Tellus
    * generator codec or saved-data schema is changed.
    */
   public static MapCodec<Dynamic<?>> opaqueMapCodec() {
      return MapCodec.assumeMapUnsafe(Codec.PASSTHROUGH);
   }
}
