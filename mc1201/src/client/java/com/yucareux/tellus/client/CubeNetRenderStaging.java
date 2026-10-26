package com.yucareux.tellus.client;

import net.minecraft.client.renderer.ViewArea;

/** Ordinary 1.20.1 renderer adapter for installing an already compiled chart. */
public interface CubeNetRenderStaging {
   void tellus$installStagedView(ViewArea staged);
}
