package com.yucareux.tellus.worldgen.runtime;

import java.util.Objects;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Phase 1 lifecycle shell for a generator runtime adapter.
 *
 * <p>The first phase deliberately does not take over server lifecycle hooks or
 * manager ticks. It establishes ownership and an idempotent attachment marker
 * so later consumers can migrate without changing generator behavior.</p>
 */
public interface TellusGeneratorRuntime extends TellusGeneratorRuntimeView {
   enum AttachResult { ATTACHED, ALREADY_ATTACHED, NOT_OWNED }

   /** Pure ownership check; implementations must not load chunks or do I/O. */
   boolean owns(ChunkGenerator generator);

   /** Marks this adapter attached when the supplied generator is owned. */
   AttachResult attach(ChunkGenerator generator);

   /** Explicit bridge tick hook; adapters may override, but no caller is installed automatically. */
   default void tick() { }

   /** Idempotent phase-1 detach; later phases release manager state here. */
   void detach();

   default AttachResult attachOwned(ChunkGenerator generator) {
      return this.attach(Objects.requireNonNull(generator, "generator"));
   }
}
