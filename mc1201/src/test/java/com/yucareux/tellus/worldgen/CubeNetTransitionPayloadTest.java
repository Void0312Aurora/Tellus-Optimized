package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.network.CubeNetTransitionPreparePayload;
import com.yucareux.tellus.network.CubeNetTransitionReadyPayload;
import com.yucareux.tellus.network.CubeNetTransitionCancelPayload;
import com.yucareux.tellus.network.CubeNetTransitionCommitPayload;
import com.yucareux.tellus.network.CubeNetTransitionMotionPayload;
import com.yucareux.tellus.network.CubeNetTransitionChunksReceivedPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubeNetTransitionPayloadTest {
   @BeforeAll
   static void bootstrapMinecraft() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @Test
   void preparePayloadRoundTripsItsStagingArea() {
      UUID id = UUID.randomUUID();
      var original = new CubeNetTransitionPreparePayload(id, -42, 73, 33);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      original.write(buffer);
      assertEquals(original, new CubeNetTransitionPreparePayload(buffer));
   }

   @Test
   void readyPayloadRoundTripsItsTransitionIdentity() {
      UUID id = UUID.randomUUID();
      var original = new CubeNetTransitionReadyPayload(id);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      original.write(buffer);
      assertEquals(original, new CubeNetTransitionReadyPayload(buffer));
   }

   @Test
   void chunksReceivedPayloadRoundTripsItsTransitionIdentity() {
      UUID id = UUID.randomUUID();
      var original = new CubeNetTransitionChunksReceivedPayload(id);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      original.write(buffer);
      assertEquals(original, new CubeNetTransitionChunksReceivedPayload(buffer));
      buffer.release();
   }

   @Test
   void preparePayloadRejectsAnUnboundedRadius() {
      UUID id = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetTransitionPreparePayload(id, 0, 0, -1));
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetTransitionPreparePayload(id, 0, 0, 36));
   }

   @Test
   void movingPreparationAndCancellationRoundTripTheirIdentity() {
      UUID id = UUID.randomUUID();
      var moving = new CubeNetTransitionPreparePayload(id, 638, 320, 5, false, 3);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      moving.write(buffer);
      assertEquals(moving, new CubeNetTransitionPreparePayload(buffer));
      var cancel = new CubeNetTransitionCancelPayload(id);
      cancel.write(buffer);
      assertEquals(cancel, new CubeNetTransitionCancelPayload(buffer));
      buffer.release();
   }

   @Test
   void commitPayloadRoundTripsPoseAndVelocity() {
      UUID id = UUID.randomUUID();
      var original = new CubeNetTransitionCommitPayload(id, 1024.25, 72.0, -33.5,
         90.0f, 12.0f, new Vec3(0.12, -0.08, 0.34), 1.5f, 3);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      original.write(buffer);
      assertEquals(original, new CubeNetTransitionCommitPayload(buffer));
      buffer.release();
   }

   @Test
   void commitPayloadRejectsNonFiniteState() {
      UUID id = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetTransitionCommitPayload(id, Double.NaN, 0, 0, 0, 0, Vec3.ZERO, 0));
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetTransitionCommitPayload(id, 0, 0, 0, 0, 0, Vec3.ZERO, -1));
   }

   @Test
   void firstMotionPayloadRoundTripsBoundedClientState() {
      UUID id = UUID.randomUUID();
      var original = new CubeNetTransitionMotionPayload(id,
         new Vec3(10215.88, 100.0, 5120.5), 90.0f, 0.0f,
         new Vec3(-0.01568, -0.005, 0.0), true);
      var buffer = new FriendlyByteBuf(Unpooled.buffer());
      original.write(buffer);
      assertEquals(original, new CubeNetTransitionMotionPayload(buffer));
      buffer.release();
   }

   @Test
   void firstMotionPayloadRejectsNonFiniteState() {
      UUID id = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class,
         () -> new CubeNetTransitionMotionPayload(id, Double.NaN, 0, 0, 0, 0, 0, 0, 0, true));
   }

   @Test
   void firstMotionMustStayWithinTheAuthoritativeVelocityAndGroundSnapshot() {
      UUID id = UUID.randomUUID();
      Vec3 expected = new Vec3(0.12, -0.08, 0.34);
      var valid = new CubeNetTransitionMotionPayload(id, Vec3.ZERO, 0.0f, 0.0f, expected, true);
      var forgedVelocity = new CubeNetTransitionMotionPayload(id, Vec3.ZERO, 0.0f, 0.0f,
         new Vec3(3.5, 0.0, 0.0), true);
      var forgedGround = new CubeNetTransitionMotionPayload(id, Vec3.ZERO, 0.0f, 0.0f, expected, false);
      assertTrue(CubeNetRuntime.isCustomMotionWithinSnapshot(expected, true, valid));
      assertFalse(CubeNetRuntime.isCustomMotionWithinSnapshot(expected, true, forgedVelocity));
      assertFalse(CubeNetRuntime.isCustomMotionWithinSnapshot(expected, true, forgedGround));
   }
}
