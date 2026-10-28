package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.CubeNetSodiumSectionAccess;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Map;

/** Version-pinned queue/section access for the optional Sodium adapter. */
@Mixin(RenderSectionManager.class)
public abstract class CubeNetSodiumRenderSectionManagerMixin implements CubeNetSodiumSectionAccess {
   @Override
   @Accessor("sectionByPosition")
   public abstract Long2ReferenceMap<RenderSection> tellus$sections();

   @Override
   @Accessor("rebuildLists")
   public abstract Map<ChunkUpdateType, ArrayDeque<RenderSection>> tellus$rebuildLists();
}
