package com.yucareux.tellus.worldgen.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lightweight metadata contract for the ordered 1.20.1 runtime descriptors. */
class TellusMinecraft1201RuntimeDescriptorOrderTest {
    @Test
    void descriptorsRemainEarthThenCubeNetThenSphereFaceWithoutRegistrySideEffects() {
        List<TellusGeneratorRuntimeDescriptor> descriptors =
            TellusMinecraft1201RuntimeFactories.descriptors();
        List<TellusGeneratorRuntimeFactory.AdapterFactory> factories =
            TellusMinecraft1201RuntimeFactories.orderedAdapterFactories();

        assertEquals(3, descriptors.size());
        assertEquals(3, factories.size());
        assertEquals(List.of("tellus:earth", "tellus:cube_net", "tellus:sphere_face"),
            descriptors.stream().map(TellusGeneratorRuntimeDescriptor::id).toList());
        assertEquals(List.of(
            TellusGeneratorSettingsView.Kind.EARTH,
            TellusGeneratorSettingsView.Kind.CUBE_NET,
            TellusGeneratorSettingsView.Kind.SPHERE_FACE),
            descriptors.stream().map(TellusGeneratorRuntimeDescriptor::kind).toList());
        assertEquals(List.of("root", "mc1201", "mc1201"),
            descriptors.stream().map(TellusGeneratorRuntimeDescriptor::sourceLayer).toList());

        for (TellusGeneratorRuntimeDescriptor descriptor : descriptors) {
            assertFalse(descriptor.id().isBlank());
            assertNotNull(descriptor.adapterFactory());
            assertNotNull(descriptor.settingsFactory());
        }
        for (TellusGeneratorRuntimeFactory.AdapterFactory factory : factories) {
            assertNotNull(factory);
        }

        TellusGeneratorRuntimeRegistry registry = TellusGeneratorRuntimeFactory.registry();
        assertEquals(0, registry.size());
        assertTrue(TellusMinecraft1201RuntimeFactories.descriptors().equals(descriptors));
        assertEquals(0, registry.size(), "descriptor lookup must not register lifecycle adapters");
    }
}
