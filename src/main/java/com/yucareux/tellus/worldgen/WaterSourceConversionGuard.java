package com.yucareux.tellus.worldgen;

/**
 * Generator-side hook used by the shared water source conversion mixin.
 *
 * <p>Both the planar Earth generator and the spherical Cube-Net generator
 * expose the same small contract. Keeping the hook version-neutral avoids
 * making the common mixin depend on a particular generator implementation.</p>
 */
public interface WaterSourceConversionGuard {
   boolean shouldSuppressWaterSourceConversion(int blockX, int blockY, int blockZ);
}
