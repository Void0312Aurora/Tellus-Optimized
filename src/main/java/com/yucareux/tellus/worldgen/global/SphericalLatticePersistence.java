package com.yucareux.tellus.worldgen.global;

import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.Atlas;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.BlockId;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.BoundaryLink;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.ChartKind;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.GridVersion;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.PatchDescriptor;
import com.yucareux.tellus.worldgen.global.SphericalLatticePrototype.PatchKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic Phase 2 persistence for the RFC prototype.
 *
 * <p>This is a versioned research format. It stores the canonical grid,
 * patches, reciprocal boundary links, sampled owner decisions, and edited
 * block identities. It deliberately does not pretend to be an Anvil region
 * format or a Minecraft chunk serializer.</p>
 */
public final class SphericalLatticePersistence {
   private static final int MAGIC = 0x544C5332; // TLS2
   private static final int FORMAT_VERSION = 1;
   private static final int MAX_RECORDS = 1_000_000;

   private SphericalLatticePersistence() { }

   public static Snapshot capture(Atlas atlas, List<SphereWorldModel.GeoPoint> ownerPoints,
      List<CellEdit> edits) {
      Objects.requireNonNull(atlas, "atlas");
      Objects.requireNonNull(ownerPoints, "ownerPoints");
      Objects.requireNonNull(edits, "edits");
      List<OwnerDecision> owners = ownerPoints.stream()
         .map(point -> new OwnerDecision(point, atlas.owner(point)))
         .sorted(OWNER_ORDER)
         .toList();
      return new Snapshot(atlas.grid(), atlas.patches(), atlas.boundaryLinks(), owners,
         edits.stream().sorted(EDIT_ORDER).toList());
   }

   public static byte[] encode(Snapshot snapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
      Atlas atlas = restoreAtlas(snapshot);
      validateOwners(atlas, snapshot.owners());
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeGrid(output, snapshot.grid());
            output.writeInt(snapshot.patches().size());
            for (PatchDescriptor patch : snapshot.patches()) writePatch(output, patch);
            output.writeInt(snapshot.links().size());
            for (BoundaryLink link : snapshot.links()) writeLink(output, link);
            output.writeInt(snapshot.owners().size());
            for (OwnerDecision owner : snapshot.owners()) {
               writePoint(output, owner.point());
               writeBlock(output, owner.owner());
            }
            output.writeInt(snapshot.edits().size());
            for (CellEdit edit : snapshot.edits()) {
               writeBlock(output, edit.id());
               output.writeInt(edit.state());
            }
         }
         return bytes.toByteArray();
      } catch (IOException exception) {
         throw new IllegalStateException("in-memory persistence failed", exception);
      }
   }

   public static Snapshot decode(byte[] encoded) throws IOException {
      Objects.requireNonNull(encoded, "encoded");
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
         if (input.readInt() != MAGIC) throw new IOException("unknown spherical lattice magic");
         if (input.readInt() != FORMAT_VERSION) throw new IOException("unsupported spherical lattice format");
         GridVersion grid = readGrid(input);
         int patchCount = count(input.readInt(), "patches");
         List<PatchDescriptor> patches = new ArrayList<>(patchCount);
         for (int i = 0; i < patchCount; i++) patches.add(readPatch(input, grid));
         int linkCount = count(input.readInt(), "links");
         List<BoundaryLink> links = new ArrayList<>(linkCount);
         for (int i = 0; i < linkCount; i++) links.add(readLink(input));
         int ownerCount = count(input.readInt(), "owners");
         List<OwnerDecision> owners = new ArrayList<>(ownerCount);
         for (int i = 0; i < ownerCount; i++) owners.add(new OwnerDecision(readPoint(input), readBlock(input, grid)));
         int editCount = count(input.readInt(), "edits");
         List<CellEdit> edits = new ArrayList<>(editCount);
         for (int i = 0; i < editCount; i++) edits.add(new CellEdit(readBlock(input, grid), input.readInt()));
         if (input.read() != -1) throw new IOException("trailing spherical lattice data");
         Snapshot snapshot = new Snapshot(grid, patches, links, owners, edits);
         validateOwners(restoreAtlas(snapshot), snapshot.owners());
         return snapshot;
      } catch (EOFException | IllegalArgumentException exception) {
         throw new IOException("invalid spherical lattice snapshot", exception);
      }
   }

   public static Atlas restoreAtlas(Snapshot snapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
      return new Atlas(snapshot.patches(), snapshot.links());
   }

   private static void validateOwners(Atlas atlas, List<OwnerDecision> owners) {
      for (OwnerDecision decision : owners) {
         if (!atlas.owner(decision.point()).equals(decision.owner())) {
            throw new IllegalArgumentException("owner decision does not match canonical atlas: " + decision);
         }
      }
   }

   private static int count(int value, String label) throws IOException {
      if (value < 0 || value > MAX_RECORDS) throw new IOException("invalid " + label + " count");
      return value;
   }

   private static void writeGrid(DataOutputStream output, GridVersion grid) throws IOException {
      writeString(output, grid.planetId());
      output.writeInt(grid.version());
   }

   private static GridVersion readGrid(DataInputStream input) throws IOException {
      return new GridVersion(readString(input), input.readInt());
   }

   private static void writePatch(DataOutputStream output, PatchDescriptor patch) throws IOException {
      PatchKey key = patch.key();
      output.writeInt(key.level());
      output.writeLong(key.x());
      output.writeLong(key.y());
      writePoint(output, patch.anchor());
      output.writeDouble(patch.sideMeters());
      output.writeInt(patch.cellsPerSide());
      output.writeByte(patch.chart().ordinal());
   }

   private static PatchDescriptor readPatch(DataInputStream input, GridVersion grid) throws IOException {
      PatchKey key = new PatchKey(input.readInt(), input.readLong(), input.readLong());
      SphereWorldModel.GeoPoint anchor = readPoint(input);
      double side = input.readDouble();
      int cells = input.readInt();
      int chart = input.readUnsignedByte();
      if (chart >= ChartKind.values().length) throw new IOException("invalid chart kind");
      return new PatchDescriptor(grid, key, anchor, side, cells, ChartKind.values()[chart]);
   }

   private static void writeLink(DataOutputStream output, BoundaryLink link) throws IOException {
      writePatchKey(output, link.source());
      output.writeByte(link.sourceEdge().ordinal());
      writePatchKey(output, link.target());
      output.writeByte(link.targetEdge().ordinal());
      output.writeBoolean(link.reversed());
   }

   private static BoundaryLink readLink(DataInputStream input) throws IOException {
      PatchKey source = readPatchKey(input);
      int sourceEdge = input.readUnsignedByte();
      PatchKey target = readPatchKey(input);
      int targetEdge = input.readUnsignedByte();
      if (sourceEdge >= SphericalLatticePrototype.Edge.values().length
         || targetEdge >= SphericalLatticePrototype.Edge.values().length) {
         throw new IOException("invalid boundary edge");
      }
      return new BoundaryLink(source, SphericalLatticePrototype.Edge.values()[sourceEdge], target,
         SphericalLatticePrototype.Edge.values()[targetEdge], input.readBoolean());
   }

   private static void writeBlock(DataOutputStream output, BlockId block) throws IOException {
      writePatchKey(output, block.patch());
      output.writeLong(block.i());
      output.writeLong(block.j());
      output.writeInt(block.k());
   }

   private static BlockId readBlock(DataInputStream input, GridVersion grid) throws IOException {
      return new BlockId(grid, readPatchKey(input), input.readLong(), input.readLong(), input.readInt());
   }

   private static void writePatchKey(DataOutputStream output, PatchKey key) throws IOException {
      output.writeInt(key.level());
      output.writeLong(key.x());
      output.writeLong(key.y());
   }

   private static PatchKey readPatchKey(DataInputStream input) throws IOException {
      return new PatchKey(input.readInt(), input.readLong(), input.readLong());
   }

   private static void writePoint(DataOutputStream output, SphereWorldModel.GeoPoint point) throws IOException {
      output.writeDouble(point.latitude());
      output.writeDouble(point.longitude());
      output.writeDouble(point.elevationMeters());
   }

   private static SphereWorldModel.GeoPoint readPoint(DataInputStream input) throws IOException {
      return new SphereWorldModel.GeoPoint(input.readDouble(), input.readDouble(), input.readDouble());
   }

   private static void writeString(DataOutputStream output, String value) throws IOException {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 1_000_000) throw new IOException("string too long");
      output.writeInt(bytes.length);
      output.write(bytes);
   }

   private static String readString(DataInputStream input) throws IOException {
      int length = input.readInt();
      if (length < 0 || length > 1_000_000) throw new IOException("invalid string length");
      byte[] bytes = input.readNBytes(length);
      if (bytes.length != length) throw new EOFException();
      return new String(bytes, StandardCharsets.UTF_8);
   }

   private static final Comparator<OwnerDecision> OWNER_ORDER = Comparator
      .comparing((OwnerDecision owner) -> owner.point().latitude())
      .thenComparing(owner -> owner.point().longitude())
      .thenComparing(owner -> owner.point().elevationMeters())
      .thenComparing(OwnerDecision::owner, Comparator.comparing(BlockId::patch)
         .thenComparingLong(BlockId::i).thenComparingLong(BlockId::j).thenComparingInt(BlockId::k));

   private static final Comparator<PatchDescriptor> PATCH_ORDER = Comparator.comparing(PatchDescriptor::key);

   private static final Comparator<BoundaryLink> LINK_ORDER = Comparator
      .comparing(BoundaryLink::source).thenComparing(BoundaryLink::sourceEdge)
      .thenComparing(BoundaryLink::target).thenComparing(BoundaryLink::targetEdge)
      .thenComparing(BoundaryLink::reversed);

   private static final Comparator<CellEdit> EDIT_ORDER = Comparator
      .comparing(CellEdit::id, Comparator.comparing(BlockId::patch)
         .thenComparingLong(BlockId::i).thenComparingLong(BlockId::j).thenComparingInt(BlockId::k))
      .thenComparingInt(CellEdit::state);

   public record OwnerDecision(SphereWorldModel.GeoPoint point, BlockId owner) {
      public OwnerDecision {
         Objects.requireNonNull(point, "point");
         Objects.requireNonNull(owner, "owner");
      }
   }

   public record CellEdit(BlockId id, int state) {
      public CellEdit {
         Objects.requireNonNull(id, "id");
      }
   }

   public record Snapshot(GridVersion grid, List<PatchDescriptor> patches, List<BoundaryLink> links,
      List<OwnerDecision> owners, List<CellEdit> edits) {
      public Snapshot {
         Objects.requireNonNull(grid, "grid");
         patches = Objects.requireNonNull(patches, "patches").stream().sorted(PATCH_ORDER).toList();
         links = Objects.requireNonNull(links, "links").stream().sorted(LINK_ORDER).toList();
         owners = Objects.requireNonNull(owners, "owners").stream().sorted(OWNER_ORDER).toList();
         edits = Objects.requireNonNull(edits, "edits").stream().sorted(EDIT_ORDER).toList();
         Set<PatchKey> patchKeys = new HashSet<>();
         for (PatchDescriptor patch : patches) {
            if (!grid.equals(patch.grid()) || !patchKeys.add(patch.key())) throw new IllegalArgumentException("invalid patch set");
         }
         Set<BlockId> editIds = new HashSet<>();
         for (CellEdit edit : edits) {
            if (!grid.equals(edit.id().grid()) || !patchKeys.contains(edit.id().patch()) || !editIds.add(edit.id())) {
               throw new IllegalArgumentException("invalid or duplicate edit");
            }
         }
         for (OwnerDecision owner : owners) {
            if (!grid.equals(owner.owner().grid()) || !patchKeys.contains(owner.owner().patch())) {
               throw new IllegalArgumentException("owner has wrong grid or patch");
            }
         }
      }
   }
}
