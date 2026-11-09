package com.yucareux.tellus.worldgen.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/** Native NBT objects with synthetic contents: digest contracts, not world acceptance. */
class TellusCanonicalNbtTest {
    @Test void compoundOrderIsCanonicalWithoutMutatingTheTags() {
        var a = new CompoundTag(); a.putString("z", "last"); a.putInt("a", 1);
        var b = new CompoundTag(); b.putInt("a", 1); b.putString("z", "last");
        var before = a.copy();
        assertEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
        assertEquals(before, a);
        assertEquals(64, TellusCanonicalNbt.digest(a).length());
    }
    @Test void listOrderAndScalarTypesAreRetained() {
        var a = new CompoundTag(); var list = new ListTag(); list.add(StringTag.valueOf("a")); list.add(StringTag.valueOf("b")); a.put("list", list);
        var b = new CompoundTag(); var reversed = new ListTag(); reversed.add(StringTag.valueOf("b")); reversed.add(StringTag.valueOf("a")); b.put("list", reversed);
        assertNotEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
        a = new CompoundTag(); b = new CompoundTag(); a.putByte("number", (byte)1); b.putInt("number", 1);
        assertNotEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
        a.putFloat("fraction", 1.0f); b = a.copy(); b.putFloat("fraction", Math.nextUp(1.0f));
        assertNotEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
    }
    @Test void onlyExplicitRootVolatileFieldsAreExcluded() {
        var a = new CompoundTag(); a.putLong("LastUpdate", 10); var nested = new CompoundTag(); nested.putLong("LastUpdate", 20); a.put("nested", nested);
        var b = a.copy(); b.putLong("LastUpdate", 11);
        assertNotEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
        assertTrue(TellusCanonicalNbt.same(a, b, Set.of("LastUpdate")));
        b.getCompound("nested").putLong("LastUpdate", 21);
        assertFalse(TellusCanonicalNbt.same(a, b, Set.of("LastUpdate")));
    }
    @Test void allArrayTypesAndUnknownNativeMetadataRemainSignificant() {
        var a = new CompoundTag(); a.putByteArray("light", new byte[]{1,2}); a.putIntArray("ints", new int[]{3,4}); a.putLongArray("heightmap", new long[]{5,6});
        var b = a.copy(); b.putLongArray("heightmap", new long[]{5,7});
        assertNotEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
        b = a.copy(); b.putString("future_native_metadata", "retained");
        assertNotEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
    }
    @Test void nativeStringsAreNotReplacementEncoded() {
        var a = new CompoundTag(); a.putString("value", "\ud800");
        var b = new CompoundTag(); b.putString("value", "?");
        assertNotEquals(TellusCanonicalNbt.digest(a), TellusCanonicalNbt.digest(b));
    }
    @Test void deepOrCyclicTagsAndOversizedStringsAreBounded() {
        var root = new CompoundTag(); var cursor = root;
        for (int i = 0; i < TellusCanonicalNbt.MAX_DEPTH + 1; i++) { var next = new CompoundTag(); cursor.put("next", next); cursor = next; }
        assertThrows(IllegalArgumentException.class, () -> TellusCanonicalNbt.digest(root));
        var cycle = new CompoundTag(); cycle.put("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> TellusCanonicalNbt.digest(cycle));
        var oversized = new CompoundTag(); oversized.putString("long", "x".repeat(TellusCanonicalNbt.MAX_STRING_CHARS + 1));
        assertThrows(IllegalArgumentException.class, () -> TellusCanonicalNbt.digest(oversized));
    }
}
