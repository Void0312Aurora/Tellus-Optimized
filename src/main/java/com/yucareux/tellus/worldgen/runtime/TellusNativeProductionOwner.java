package com.yucareux.tellus.worldgen.runtime;

/** The generator owns its production stages; runtime adapters never become a second generator. */
public interface TellusNativeProductionOwner {
    TellusNativeProductionPipeline nativeProductionPipeline();
    TellusGeneratorRuntime createNativeRuntimeAdapter();
}
