package com.yucareux.tellus.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TellusDfu1211CompatTest {
   private static Map<String, Object> generatorChoiceSchema() {
      Map<String, Object> types = new LinkedHashMap<>();
      types.put("minecraft:noise", new Object());
      types.put("minecraft:flat", new Object());
      types.put("minecraft:debug", new Object());
      return types;
   }

   @Test
   void onlyRegisteredTellusChoiceUsesTheNarrowSchemaGuard() {
      Map<String, Object> types = generatorChoiceSchema();
      assertTrue(TellusDfu1211Compat.shouldUseOpaqueFallback("type", types, "tellus:cube_net"));
      assertTrue(TellusDfu1211Compat.shouldUseOpaqueFallback("type", types, "tellus:earth"));
       assertTrue(TellusDfu1211Compat.shouldUseOpaqueFallback("type", types, "tellus:sphere_face"));
      assertFalse(TellusDfu1211Compat.shouldUseOpaqueFallback("other", types, "tellus:cube_net"));
      assertFalse(TellusDfu1211Compat.shouldUseOpaqueFallback("type", types, "minecraft:noise"));
   }

   @Test
   void schemaAndKnownKeyGuardsPreventBroadFallback() {
      Map<String, Object> missingFlat = Map.of("minecraft:noise", new Object());
      assertFalse(TellusDfu1211Compat.shouldUseOpaqueFallback("type", missingFlat, "tellus:cube_net"));
      Map<String, Object> alreadyRegistered = generatorChoiceSchema();
      alreadyRegistered.put("tellus:cube_net", new Object());
      assertFalse(TellusDfu1211Compat.shouldUseOpaqueFallback("type", alreadyRegistered, "tellus:cube_net"));
      assertFalse(TellusDfu1211Compat.shouldUseOpaqueFallback("type", generatorChoiceSchema(), "other:generator"));
   }

   @Test
   void opaqueMapCodecRetainsDynamicPayloadAndHasMapCodecType() {
      MapCodec<Dynamic<?>> codec = TellusDfu1211Compat.opaqueMapCodec();
      assertNotNull(codec);
      JsonObject object = new JsonObject();
      object.addProperty("type", "tellus:cube_net");
      object.addProperty("world_scale", 1000.0);
      Dynamic<JsonElement> payload = new Dynamic<>(JsonOps.INSTANCE, object);

      DataResult<JsonElement> encoded = codec.codec().encodeStart(JsonOps.INSTANCE, payload);
      JsonElement encodedValue = encoded.result().orElseThrow();
      assertEquals(object, encodedValue);
      Dynamic<?> decoded = codec.codec().parse(JsonOps.INSTANCE, encodedValue).result().orElseThrow();
      assertEquals(object, decoded.getValue());
   }
}
