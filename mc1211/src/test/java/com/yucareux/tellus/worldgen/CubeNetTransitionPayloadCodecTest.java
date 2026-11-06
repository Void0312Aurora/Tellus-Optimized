package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.network.CubeNetBoundaryHintPayload;
import com.yucareux.tellus.network.CubeNetLayoutPayload;
import com.yucareux.tellus.network.CubeNetTransitionCancelPayload;
import com.yucareux.tellus.network.CubeNetTransitionChunksReceivedPayload;
import com.yucareux.tellus.network.CubeNetTransitionCommitPayload;
import com.yucareux.tellus.network.CubeNetTransitionMotionPayload;
import com.yucareux.tellus.network.CubeNetTransitionPreparePayload;
import com.yucareux.tellus.network.CubeNetTransitionReadyPayload;
import com.yucareux.tellus.network.CubeNetTransitionUnsupportedPayload;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubeNetTransitionPayloadCodecTest {
   private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      try {
         codec.encode(buffer, value);
         return codec.decode(buffer);
      } finally {
         buffer.release();
      }
   }

   @Test
   void everyTransitionPayloadRoundTripsThroughItsStreamCodec() {
      UUID id = UUID.randomUUID();
      assertEquals(new CubeNetTransitionPreparePayload(id, -42, 73, 5, false, 3),
         roundTrip(CubeNetTransitionPreparePayload.CODEC,
            new CubeNetTransitionPreparePayload(id, -42, 73, 5, false, 3)));
      assertEquals(new CubeNetTransitionReadyPayload(id),
         roundTrip(CubeNetTransitionReadyPayload.CODEC, new CubeNetTransitionReadyPayload(id)));
      assertEquals(new CubeNetTransitionChunksReceivedPayload(id),
         roundTrip(CubeNetTransitionChunksReceivedPayload.CODEC,
            new CubeNetTransitionChunksReceivedPayload(id)));
      assertEquals(new CubeNetTransitionMotionPayload(id, new Vec3(10.5, 72, -3.25), 90, -5,
         new Vec3(.12, -.08, .34), true),
         roundTrip(CubeNetTransitionMotionPayload.CODEC,
            new CubeNetTransitionMotionPayload(id, new Vec3(10.5, 72, -3.25), 90, -5,
               new Vec3(.12, -.08, .34), true)));
      assertEquals(new CubeNetTransitionCommitPayload(id, 10.5, 72, -3.25, 90, -5,
         new Vec3(.12, -.08, .34), 1.5f, 3),
         roundTrip(CubeNetTransitionCommitPayload.CODEC,
            new CubeNetTransitionCommitPayload(id, 10.5, 72, -3.25, 90, -5,
               new Vec3(.12, -.08, .34), 1.5f, 3)));
      assertEquals(new CubeNetTransitionCancelPayload(id),
         roundTrip(CubeNetTransitionCancelPayload.CODEC, new CubeNetTransitionCancelPayload(id)));
      assertEquals(new CubeNetTransitionUnsupportedPayload(id, "sodium"),
         roundTrip(CubeNetTransitionUnsupportedPayload.CODEC,
            new CubeNetTransitionUnsupportedPayload(id, "sodium")));
      assertEquals(new CubeNetLayoutPayload(10240, 112),
         roundTrip(CubeNetLayoutPayload.CODEC, new CubeNetLayoutPayload(10240, 112)));
      assertEquals(new CubeNetBoundaryHintPayload(1.5, -2.5, 0, 1, 120),
         roundTrip(CubeNetBoundaryHintPayload.CODEC,
            new CubeNetBoundaryHintPayload(1.5, -2.5, 0, 1, 120)));
   }

   @Test
   void payloadValidationRemainsBounded() {
      UUID id = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetTransitionPreparePayload(id, 0, 0, 36));
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetTransitionCommitPayload(id, 0, 0, 0, 0, 0, Vec3.ZERO, -1));
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetBoundaryHintPayload(0, 0, 0, 0, 1));
   }
}
