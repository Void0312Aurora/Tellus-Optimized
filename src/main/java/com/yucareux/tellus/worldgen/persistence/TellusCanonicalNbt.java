package com.yucareux.tellus.worldgen.persistence;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Typed, bounded semantic NBT digest. It never changes the native persisted NBT. */
public final class TellusCanonicalNbt {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_NODES = 262_144;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_STRING_CHARS = 1_048_576;

    private TellusCanonicalNbt() { }

    public static String digest(CompoundTag tag) { return digest(tag, Set.of()); }

    /** Exclusions apply only to explicitly named root keys, never to nested keys. */
    public static String digest(CompoundTag tag, Set<String> excludedRootKeys) {
        Objects.requireNonNull(tag, "tag");
        Set<String> exclusions = Set.copyOf(Objects.requireNonNull(excludedRootKeys, "excludedRootKeys"));
        if (exclusions.size() > 64) throw new IllegalArgumentException("too many volatile root exclusions");
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            var budget = new Budget();
            var output = new DataOutputStream(new BoundedOutput(new DigestOutputStream(OutputStream.nullOutputStream(), hash)));
            output.writeInt(0x544e4254); // typed canonical digest format, not an on-disk Minecraft format
            output.writeByte(1);
            write(tag, output, budget, 0, exclusions);
            output.flush();
            return HexFormat.of().formatHex(hash.digest());
        } catch (IOException failure) {
            throw new IllegalArgumentException("NBT exceeds canonical digest bounds", failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static boolean same(CompoundTag left, CompoundTag right, Set<String> excludedRootKeys) {
        return digest(left, excludedRootKeys).equals(digest(right, excludedRootKeys));
    }

    private static void write(Tag tag, DataOutputStream out, Budget budget, int depth, Set<String> rootExclusions) throws IOException {
        if (tag == null || depth > MAX_DEPTH || ++budget.nodes > MAX_NODES)
            throw new IllegalArgumentException("NBT depth/node budget exceeded");
        byte id = tag.getId();
        out.writeByte(id);
        switch (id) {
            case 0 -> { }
            case 1 -> out.writeByte(((NumericTag)tag).getAsByte());
            case 2 -> out.writeShort(((NumericTag)tag).getAsShort());
            case 3 -> out.writeInt(((NumericTag)tag).getAsInt());
            case 4 -> out.writeLong(((NumericTag)tag).getAsLong());
            case 5 -> out.writeInt(Float.floatToIntBits(((NumericTag)tag).getAsFloat()));
            case 6 -> out.writeLong(Double.doubleToLongBits(((NumericTag)tag).getAsDouble()));
            case 7 -> {
                byte[] values = ((ByteArrayTag)tag).getAsByteArray();
                requireArray(values.length, 1); out.writeInt(values.length); out.write(values);
            }
            case 8 -> string(out, tag.getAsString());
            case 9 -> {
                var list = (ListTag)tag;
                if (list.size() > MAX_NODES - budget.nodes) throw new IllegalArgumentException("NBT list budget exceeded");
                out.writeByte(list.getElementType()); out.writeInt(list.size());
                for (Tag value : list) write(value, out, budget, depth + 1, Set.of());
            }
            case 10 -> {
                var compound = (CompoundTag)tag;
                if (compound.size() > MAX_NODES - budget.nodes) throw new IllegalArgumentException("NBT compound budget exceeded");
                List<String> keys = new ArrayList<>(compound.getAllKeys());
                if (depth == 0) keys.removeIf(rootExclusions::contains);
                keys.sort(String::compareTo);
                out.writeInt(keys.size());
                for (String key : keys) { string(out, key); write(compound.get(key), out, budget, depth + 1, Set.of()); }
            }
            case 11 -> {
                int[] values = ((IntArrayTag)tag).getAsIntArray();
                requireArray(values.length, Integer.BYTES); out.writeInt(values.length);
                for (int value : values) out.writeInt(value);
            }
            case 12 -> {
                long[] values = ((LongArrayTag)tag).getAsLongArray();
                requireArray(values.length, Long.BYTES); out.writeInt(values.length);
                for (long value : values) out.writeLong(value);
            }
            default -> throw new IllegalArgumentException("unsupported native NBT type: " + id);
        }
    }

    private static void requireArray(int length, int width) {
        if ((long)length * width > MAX_BYTES) throw new IllegalArgumentException("NBT array budget exceeded");
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        if (value.length() > MAX_STRING_CHARS) throw new IllegalArgumentException("NBT string budget exceeded");
        // Java UTF-16 code units preserve the entire native String value without replacement encoding.
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }

    private static final class Budget { int nodes; }
    private static final class BoundedOutput extends OutputStream {
        private final OutputStream target;
        private int bytes;
        BoundedOutput(OutputStream target) { this.target = target; }
        private void admit(int count) throws IOException {
            if (count < 0 || (long)bytes + count > MAX_BYTES) throw new IOException("canonical NBT byte budget exceeded");
            bytes += count;
        }
        @Override public void write(int value) throws IOException { admit(1); target.write(value); }
        @Override public void write(byte[] values, int offset, int count) throws IOException { admit(count); target.write(values, offset, count); }
        @Override public void flush() throws IOException { target.flush(); }
    }
}
