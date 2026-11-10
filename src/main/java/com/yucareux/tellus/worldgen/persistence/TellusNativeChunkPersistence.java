package com.yucareux.tellus.worldgen.persistence;

import com.yucareux.tellus.worldgen.CubeNetChunkGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Explicit native save-queue seam for an admitted, isolated world. It uses the
 * existing ChunkMap worker only. It neither requests/generates chunks nor claims
 * loaded-chunk mutation, tick/light lifecycle or crash-atomic region recovery.
 * Caller must quiesce competing engine saves and restore its loaded mutations
 * separately after a rolled-back disk operation.
 */
public final class TellusNativeChunkPersistence {
    public static final String PROBE_ROOT_PROPERTY = "tellus.persistence.probeRoot";
    public static final int MAX_HEIGHT = 4096;
    public enum Status { COMMITTED, ROLLED_BACK, REJECTED, QUARANTINED }
    public enum BatchStatus { COMMITTED, ROLLED_BACK, REJECTED, QUARANTINED }
    public enum Point { BEFORE_SUBMIT, AFTER_SUBMIT, AFTER_READBACK }
    @FunctionalInterface public interface FaultInjector {
        FaultInjector NONE = point -> { };
        void at(Point point);
    }

    @FunctionalInterface public interface BatchFaultInjector {
        BatchFaultInjector NONE = (ordinal, pos, point) -> { };
        void at(int ordinal, ChunkPos pos, Point point);
    }

    public record BatchEntry(ChunkPos position, CompoundTag proposed) {
        public BatchEntry {
            position = Objects.requireNonNull(position, "position");
            proposed = Objects.requireNonNull(proposed, "proposed").copy();
        }
        @Override public CompoundTag proposed() { return proposed.copy(); }
    }

    public record BatchCommitResult(BatchStatus status, List<CommitResult> entries, List<String> diagnostics) {
        public BatchCommitResult {
            Objects.requireNonNull(status, "status");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }
    }

    public record Binding(Path worldRoot, String dimension, long seed, String generatorType,
                          String generatorProfile, int minY, int height, int seaLevel) {
        public Binding {
            worldRoot = Objects.requireNonNull(worldRoot, "worldRoot").toAbsolutePath().normalize();
            dimension = text(dimension, "dimension"); generatorType = text(generatorType, "generatorType");
            generatorProfile = text(generatorProfile, "generatorProfile");
            if (height < 16 || height > MAX_HEIGHT || Math.floorMod(height, 16) != 0 || Math.floorMod(minY, 16) != 0)
                throw new IllegalArgumentException("native persistence height outside aligned budget");
            Math.addExact(minY, height);
        }
    }

    public record CommitResult(Status status, Optional<CompoundTag> previous,
                               Optional<CompoundTag> persisted, List<String> diagnostics) {
        public CommitResult {
            Objects.requireNonNull(status, "status"); previous = copy(previous); persisted = copy(persisted);
            diagnostics = List.copyOf(diagnostics);
        }
        @Override public Optional<CompoundTag> previous() { return copy(previous); }
        @Override public Optional<CompoundTag> persisted() { return copy(persisted); }
    }

    /** Narrow injection boundary used by contract tests, not evidence of a live world. */
    @FunctionalInterface interface SaveFence extends AutoCloseable {
        @Override void close();
    }
    interface Store {
        Binding currentBinding();
        boolean mainThread();
        boolean canonical(ChunkPos pos);
        Optional<CompoundTag> read(ChunkPos pos);
        void submit(ChunkPos pos, CompoundTag tag);
        void flush();
        default SaveFence quiesce(List<ChunkPos> positions, boolean requireUnloaded) { return () -> { }; }
        default void quarantine() { }
        default void recovered() { }
        default CompoundTag snapshot(ChunkAccess chunk) { throw new UnsupportedOperationException("no native target"); }
    }

    private record Key(Path root, String dimension, int x, int z) { }
    private static final ConcurrentHashMap<Key, Boolean> ACTIVE = new ConcurrentHashMap<>();
    // Runtime quarantine deliberately has no public reset. Restart/recovery is an external gate.
    private static final ConcurrentHashMap<Key, String> QUARANTINE = new ConcurrentHashMap<>();
    private final Binding binding;
    private final Store store;
    private List<String> startupRecoveryDiagnostics = List.of();

    private TellusNativeChunkPersistence(Binding binding, Store store) {
        this.binding = Objects.requireNonNull(binding); this.store = Objects.requireNonNull(store);
        current();
    }

    public static TellusNativeChunkPersistence open(boolean enabled, ServerLevel level) {
        if (!enabled) throw new IllegalStateException("native persistence opt-in is disabled");
        String root = System.getProperty(PROBE_ROOT_PROPERTY);
        if (root == null || root.isBlank()) throw new IllegalStateException("exact isolated world root property is required");
        return open(true, level, Path.of(root));
    }

    /** The admitted root must equal the actual world root, not merely be its parent. */
    public static TellusNativeChunkPersistence open(boolean enabled, ServerLevel level, Path admittedWorldRoot) {
        return open(enabled, level, admittedWorldRoot, FaultInjector.NONE);
    }

    public static TellusNativeChunkPersistence open(boolean enabled, ServerLevel level, Path admittedWorldRoot,
                                                   FaultInjector recoveryFaults) {
        if (!enabled) throw new IllegalStateException("native persistence opt-in is disabled");
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) throw new IllegalStateException("native persistence requires server main thread");
        var nativeStore = new LevelStore(level, realPath(admittedWorldRoot));
        TellusNativeChunkPersistence persistence = new TellusNativeChunkPersistence(nativeStore.currentBinding(), nativeStore);
        persistence.startupRecoveryDiagnostics = persistence.recoverPendingBatches(recoveryFaults);
        return persistence;
    }

    static TellusNativeChunkPersistence forStore(Binding binding, Store store) {
        return new TellusNativeChunkPersistence(binding, store);
    }

    public Binding binding() { return binding; }
    public List<String> startupRecoveryDiagnostics() { return startupRecoveryDiagnostics; }
    public boolean quarantined(ChunkPos pos) { return QUARANTINE.containsKey(key(pos)); }
    public static String digest(CompoundTag tag) { return TellusCanonicalNbt.digest(tag); }
    public static String digest(CompoundTag tag, Set<String> excludedRootKeys) { return TellusCanonicalNbt.digest(tag, excludedRootKeys); }
    public static boolean sameNBT(CompoundTag left, CompoundTag right, Set<String> excludedRootKeys) {
        return TellusCanonicalNbt.same(left, right, excludedRootKeys);
    }

    /** Reads the existing native disk/worker queue before any demand getChunk; never generates. */
    public Optional<CompoundTag> readExistingNBT(ChunkPos pos) {
        admit(pos);
        if (ACTIVE.containsKey(key(pos))) throw new IllegalStateException("native persistence operation is already active");
        requireNoPendingJournal();
        store.flush();
        Optional<CompoundTag> value = Objects.requireNonNull(store.read(pos), "native read result");
        current();
        return value.map(tag -> checkedCopy(tag, pos));
    }

    /** Uses actual native ChunkSerializer.write on an exact loaded FULL chunk of the bound level. */
    public CompoundTag snapshot(ChunkAccess chunk) {
        Objects.requireNonNull(chunk, "chunk");
        admit(chunk.getPos());
        if (ACTIVE.containsKey(key(chunk.getPos()))) throw new IllegalStateException("native persistence operation is already active");
        requireNoPendingJournal();
        if (chunk.getMinBuildHeight() != binding.minY() || chunk.getHeight() != binding.height())
            throw new IllegalArgumentException("chunk build bounds differ from bound level");
        CompoundTag result = checkedCopy(store.snapshot(chunk), chunk.getPos());
        current();
        return result;
    }

    /**
     * Publishes one complete native tag and proves exact queue readback. A durable
     * existing FULL tag is mandatory. Rollback writes that complete original tag
     * through the same worker and verifies it without volatile-key exclusions.
     * BEFORE/AFTER faults are contract seams, not a process-crash transaction.
     */
    public CommitResult commit(ChunkPos pos, CompoundTag proposed, FaultInjector faults) {
        try { admit(pos); }
        catch (RuntimeException failure) { return rejected(pos, failure); }
        try (var ignored = TellusNativeWorldLease.acquire(binding);
             var saves = store.quiesce(List.of(pos), false)) {
            if (pendingJournal()) return result(Status.QUARANTINED, null, null, "native journal recovery is required before any new publication");
            return commitQueue(pos, proposed, faults, true);
        } catch (IOException failure) {
            return result(Status.REJECTED, null, null, "native world lease unavailable: " + failure.getClass().getName());
        }
    }

    private CommitResult commitQueue(ChunkPos pos, CompoundTag proposed, FaultInjector faults, boolean ownJournal) {
        Objects.requireNonNull(faults, "faults");
        try { admit(pos); }
        catch (RuntimeException failure) { return rejected(pos, failure); }
        Key key = key(pos);
        if (ACTIVE.putIfAbsent(key, Boolean.TRUE) != null)
            return result(Status.REJECTED, null, null, "native persistence operation is already active");
        try {
            final CompoundTag previous;
            final CompoundTag candidate;
            try {
                candidate = checkedCopy(proposed, pos);
                store.flush();
                Optional<CompoundTag> existing = Objects.requireNonNull(store.read(pos), "native read result");
                current();
                if (existing.isEmpty()) return result(Status.REJECTED, null, null, "existing native FULL disk baseline is required");
                previous = checkedCopy(existing.orElseThrow(), pos);
                if (previous.getInt("DataVersion") != candidate.getInt("DataVersion"))
                    return result(Status.REJECTED, previous, previous, "native DataVersion differs from durable baseline");
                if (!previous.getString("Status").equals(candidate.getString("Status")))
                    return result(Status.REJECTED, previous, previous, "exact native status differs from durable baseline");
            } catch (RuntimeException failure) {
                return result(Status.REJECTED, null, null, "native persistence preflight: " + failure.getClass().getName());
            }
            TellusNativeBatchJournal.Record journal = null;
            if (ownJournal) {
                try {
                    var entries = List.of(new TellusNativeBatchJournal.Entry(pos, previous, candidate));
                    journal = new TellusNativeBatchJournal.Record(TellusNativeBatchJournal.prepare(binding, entries),
                        TellusNativeBatchJournal.State.PREPARED, binding, entries);
                } catch (IOException failure) {
                    return result(Status.REJECTED, previous, previous, "native journal prepare failed: " + failure.getClass().getName());
                }
            }
            try {
                faults.at(Point.BEFORE_SUBMIT);
                current();
                store.submit(pos, candidate.copy());
                faults.at(Point.AFTER_SUBMIT);
                current();
                store.flush();
                CompoundTag readback = store.read(pos).orElseThrow(() -> new IllegalStateException("native submitted tag is absent"));
                current();
                checkedCopy(readback, pos);
                if (!sameNBT(candidate, readback, Set.of())) throw new IllegalStateException("native full NBT readback differs");
                faults.at(Point.AFTER_READBACK);
                current();
                return finishSingleJournal(journal, result(Status.COMMITTED, previous, readback, "native full NBT queue readback verified"));
            } catch (RuntimeException | Error failure) {
                Throwable recoveryFailure = null;
                try {
                    current();
                    // Even BEFORE_SUBMIT faults verify a durable complete baseline, not an assumed no-op.
                    store.submit(pos, previous.copy());
                    store.flush();
                    CompoundTag restored = store.read(pos).orElseThrow(() -> new IllegalStateException("native rollback tag is absent"));
                    current();
                    checkedCopy(restored, pos);
                    if (!sameNBT(previous, restored, Set.of())) throw new IllegalStateException("native rollback full NBT differs");
                } catch (RuntimeException | Error incomplete) { recoveryFailure = incomplete; }
                if (recoveryFailure != null) {
                    QUARANTINE.put(key, recoveryFailure.getClass().getName());
                    store.quarantine();
                    if (journal != null) try { TellusNativeBatchJournal.markQuarantined(journal); }
                    catch (IOException markFailure) { recoveryFailure.addSuppressed(markFailure); }
                    var incomplete = new IllegalStateException("native persistence rollback incomplete; chunk quarantined", failure);
                    if (recoveryFailure != failure) incomplete.addSuppressed(recoveryFailure);
                    throw incomplete;
                }
                var outcome = finishSingleJournal(journal, result(Status.ROLLED_BACK, previous, previous,
                    "native complete previous NBT restored: " + failure.getClass().getName()));
                if (failure instanceof Error fatal) throw fatal;
                return outcome;
            }
        } finally { ACTIVE.remove(key); }
    }

    /**
     * Publishes a deterministic, quiescent multi-chunk batch and restores all
     * earlier chunks when a later stage rejects or rolls back. This is an
     * in-process staged transaction contract backed by a durable journal and a
     * world-root file lease. The lease covers preflight, publication and
     * recovery so cooperating processes cannot interleave native batches.
     */
    public BatchCommitResult commitBatch(List<BatchEntry> requested, BatchFaultInjector faults) {
        current();
        Objects.requireNonNull(requested, "requested");
        try (TellusNativeWorldLease ignored = TellusNativeWorldLease.acquire(binding);
             var saves = store.quiesce(requested.stream().map(BatchEntry::position).toList(), true)) {
            if (pendingJournal()) return batch(BatchStatus.QUARANTINED, List.of(), "native journal recovery required before another batch");
            return commitBatchLocked(requested, faults);
        } catch (IOException failure) {
            return batch(BatchStatus.REJECTED, List.of(), "native world lease unavailable: " + failure.getClass().getName());
        }
    }

    private BatchCommitResult commitBatchLocked(List<BatchEntry> requested, BatchFaultInjector faults) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(faults, "faults");
        if (requested.isEmpty()) return batch(BatchStatus.REJECTED, List.of(), "batch must contain at least one chunk");
        List<BatchEntry> entries = new ArrayList<>(requested);
        entries.sort(Comparator.comparingInt((BatchEntry entry) -> entry.position().x)
            .thenComparingInt(entry -> entry.position().z));
        Set<ChunkPos> seen = new HashSet<>();
        List<TellusNativeBatchJournal.Entry> journalEntries = new ArrayList<>();
        for (BatchEntry entry : entries) {
            if (!seen.add(entry.position())) return batch(BatchStatus.REJECTED, List.of(), "batch contains duplicate chunk positions");
            try {
                Optional<CompoundTag> existing = readExistingNBT(entry.position());
                if (existing.isEmpty())
                    return batch(BatchStatus.REJECTED, List.of(), "existing native FULL baseline is required for every batch chunk");
                CompoundTag previous = existing.orElseThrow();
                CompoundTag proposed = checkedCopy(entry.proposed(), entry.position());
                if (previous.getInt("DataVersion") != proposed.getInt("DataVersion")
                    || !previous.getString("Status").equals(proposed.getString("Status")))
                    return batch(BatchStatus.REJECTED, List.of(), "batch candidate version/status differs from native baseline");
                journalEntries.add(new TellusNativeBatchJournal.Entry(entry.position(), previous, proposed));
            } catch (RuntimeException failure) {
                return batch(quarantined(entry.position()) ? BatchStatus.QUARANTINED : BatchStatus.REJECTED,
                    List.of(), "batch preflight failed: " + failure.getClass().getName());
            }
        }
        TellusNativeBatchJournal.Record journal;
        try {
            journal = new TellusNativeBatchJournal.Record(
                TellusNativeBatchJournal.prepare(binding, journalEntries), TellusNativeBatchJournal.State.PREPARED,
                binding, journalEntries);
        } catch (IOException failure) {
            return batch(BatchStatus.REJECTED, List.of(), "native batch journal prepare failed: " + failure.getClass().getName());
        }
        List<CommitResult> committed = new ArrayList<>();
        for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
            BatchEntry entry = entries.get(ordinal);
            int stage = ordinal;
            CommitResult result;
            try {
                result = commitQueue(entry.position(), entry.proposed(), point -> faults.at(stage, entry.position(), point), false);
            } catch (RuntimeException | Error failure) {
                var failureStatus = quarantined(entry.position()) ? BatchStatus.QUARANTINED : BatchStatus.ROLLED_BACK;
                var outcome = finishBatchRollback(journal, rollbackBatch(committed, failureStatus,
                    "batch stage threw: " + failure.getClass().getName()));
                if (failure instanceof Error fatal) throw fatal;
                return outcome;
            }
            committed.add(result);
            if (result.status() != Status.COMMITTED) {
                BatchStatus failureStatus = result.status() == Status.QUARANTINED
                    ? BatchStatus.QUARANTINED : BatchStatus.ROLLED_BACK;
                return finishBatchRollback(journal, rollbackBatch(committed.subList(0, Math.max(0, committed.size() - 1)), failureStatus,
                    "batch stage " + stage + " returned " + result.status()));
            }
        }
        try {
            TellusNativeBatchJournal.markCommitted(journal);
            TellusNativeBatchJournal.delete(journal);
        } catch (IOException failure) {
            store.quarantine();
            return new BatchCommitResult(BatchStatus.QUARANTINED, committed,
                List.of("all native stages committed but durable journal finalization failed: " + failure.getClass().getName()));
        }
        return new BatchCommitResult(BatchStatus.COMMITTED, committed, List.of("all native stages committed in canonical chunk order; journal finalized"));
    }

    /** Recovers prepared batches before normal probe/lifecycle work touches the world. */
    public List<String> recoverPendingBatches() {
        return recoverPendingBatches(FaultInjector.NONE);
    }

    public List<String> recoverPendingBatches(FaultInjector faults) {
        Objects.requireNonNull(faults, "faults");
        try (TellusNativeWorldLease ignored = TellusNativeWorldLease.acquire(binding)) {
            return recoverPendingBatchesLocked(faults);
        } catch (IOException failure) {
            throw new IllegalStateException("native world lease unavailable during journal recovery", failure);
        }
    }

    private List<String> recoverPendingBatchesLocked(FaultInjector faults) {
        current();
        if (!store.mainThread()) throw new IllegalStateException("native journal recovery requires server main thread");
        List<TellusNativeBatchJournal.Record> journals = List.of();
        try {
            journals = TellusNativeBatchJournal.pending(binding);
            var positions = new ArrayList<ChunkPos>();
            var seen = new HashSet<ChunkPos>();
            for (var journal : journals) for (var entry : journal.entries()) {
                // Validate every complete native record before touching the queue.
                admitRecovery(entry.position());
                checkedCopy(entry.previous(), entry.position());
                checkedCopy(entry.proposed(), entry.position());
                if (!seen.add(entry.position())) throw new IllegalStateException("overlapping pending native journals require explicit recovery");
                positions.add(entry.position());
            }
            try (var saves = store.quiesce(positions, true)) {
            List<String> diagnostics = new ArrayList<>();
            for (TellusNativeBatchJournal.Record journal : journals) {
                if (journal.state() == TellusNativeBatchJournal.State.COMMITTED) {
                    TellusNativeBatchJournal.delete(journal);
                    diagnostics.add("removed finalized native batch journal " + journal.path().getFileName());
                    continue;
                }
                faults.at(Point.BEFORE_SUBMIT);
                for (TellusNativeBatchJournal.Entry entry : journal.entries()) {
                    admitRecovery(entry.position());
                    store.submit(entry.position(), entry.previous());
                }
                faults.at(Point.AFTER_SUBMIT);
                store.flush();
                for (TellusNativeBatchJournal.Entry entry : journal.entries()) {
                    CompoundTag restored = store.read(entry.position()).orElseThrow(() ->
                        new IllegalStateException("native journal recovery record is absent"));
                    current();
                    if (!sameNBT(entry.previous(), restored, Set.of())) {
                        QUARANTINE.put(key(entry.position()), "journal recovery readback differs");
                        throw new IllegalStateException("native journal recovery readback differs");
                    }
                }
                faults.at(Point.AFTER_READBACK);
                TellusNativeBatchJournal.delete(journal);
                for (var entry : journal.entries()) QUARANTINE.remove(key(entry.position()));
                diagnostics.add("recovered native batch journal " + journal.path().getFileName());
            }
            store.recovered();
            return List.copyOf(diagnostics);
            }
        } catch (IOException | RuntimeException | Error failure) {
            store.quarantine();
            for (var journal : journals) if (journal.state() != TellusNativeBatchJournal.State.COMMITTED) {
                try { TellusNativeBatchJournal.markQuarantined(journal); }
                catch (IOException markFailure) { failure.addSuppressed(markFailure); }
            }
            if (failure instanceof Error fatal) throw fatal;
            throw new IllegalStateException("native batch journal recovery failed", failure);
        }
    }

    private BatchCommitResult finishBatchRollback(TellusNativeBatchJournal.Record journal, BatchCommitResult outcome) {
        if (outcome.status() != BatchStatus.ROLLED_BACK) {
            store.quarantine();
            try { TellusNativeBatchJournal.markQuarantined(journal); }
            catch (IOException ignored) { /* PREPARED remains a durable admission barrier. */ }
            return outcome;
        }
        try {
            TellusNativeBatchJournal.delete(journal);
            return outcome;
        } catch (IOException failure) {
            store.quarantine();
            return new BatchCommitResult(BatchStatus.QUARANTINED, outcome.entries(),
                List.of("batch rolled back but journal cleanup failed: " + failure.getClass().getName()));
        }
    }

    private BatchCommitResult rollbackBatch(List<CommitResult> committed, BatchStatus failureStatus, String diagnostic) {
        List<CommitResult> results = new ArrayList<>(committed);
        boolean complete = true;
        for (int i = committed.size() - 1; i >= 0; i--) {
            CommitResult prior = committed.get(i);
            if (prior.previous().isEmpty()) { complete = false; continue; }
            ChunkPos position = new ChunkPos(prior.previous().orElseThrow().getInt("xPos"), prior.previous().orElseThrow().getInt("zPos"));
            try {
                CommitResult restored = commitQueue(position, prior.previous().orElseThrow(), FaultInjector.NONE, false);
                results.add(restored);
                if (restored.status() != Status.COMMITTED) complete = false;
            } catch (RuntimeException | Error failure) {
                complete = false;
            }
        }
        if (!complete) failureStatus = BatchStatus.QUARANTINED;
        return new BatchCommitResult(failureStatus == BatchStatus.QUARANTINED ? BatchStatus.QUARANTINED : BatchStatus.ROLLED_BACK,
            results, List.of(diagnostic, complete ? "all previously committed chunks restored" : "batch recovery incomplete; inspect per-chunk quarantine"));
    }

    private static BatchCommitResult batch(BatchStatus status, List<CommitResult> entries, String diagnostic) {
        return new BatchCommitResult(status, entries, List.of(diagnostic));
    }

    private CommitResult rejected(ChunkPos pos, RuntimeException failure) {
        boolean quarantined = pos != null && quarantined(pos);
        return result(quarantined ? Status.QUARANTINED : Status.REJECTED, null, null,
            "native persistence admission: " + failure.getClass().getName());
    }
    private static CommitResult result(Status status, CompoundTag before, CompoundTag after, String diagnostic) {
        return new CommitResult(status, Optional.ofNullable(before), Optional.ofNullable(after), List.of(diagnostic));
    }
    private static Optional<CompoundTag> copy(Optional<CompoundTag> tag) {
        return Objects.requireNonNull(tag).map(CompoundTag::copy);
    }
    private void current() {
        if (!store.mainThread()) throw new IllegalStateException("native persistence requires server main thread");
        if (!binding.equals(store.currentBinding())) throw new IllegalStateException("world/dimension/seed/generator binding is stale");
    }
    private void admit(ChunkPos pos) {
        admitRecovery(pos);
        if (QUARANTINE.containsKey(key(pos))) throw new IllegalStateException("native chunk is quarantined after incomplete recovery");
    }
    private void admitRecovery(ChunkPos pos) {
        Objects.requireNonNull(pos, "pos"); current();
        Math.multiplyExact(pos.x, 16); Math.multiplyExact(pos.z, 16);
        Math.addExact(Math.multiplyExact(pos.x, 16), 15); Math.addExact(Math.multiplyExact(pos.z, 16), 15);
        if (!store.canonical(pos)) throw new IllegalArgumentException("derived apron/noncanonical chunk is not a persistence owner");
    }
    private Key key(ChunkPos pos) {
        Objects.requireNonNull(pos, "pos");
        return new Key(binding.worldRoot(), binding.dimension(), pos.x, pos.z);
    }

    private boolean pendingJournal() {
        try { return !TellusNativeBatchJournal.pending(binding).isEmpty(); }
        catch (IOException | RuntimeException failure) { store.quarantine(); return true; }
    }
    private void requireNoPendingJournal() {
        if (pendingJournal()) throw new IllegalStateException("native journal recovery required before observing normal chunk state");
    }
    private CommitResult finishSingleJournal(TellusNativeBatchJournal.Record journal, CommitResult outcome) {
        if (journal == null) return outcome;
        try {
            if (outcome.status() == Status.COMMITTED) TellusNativeBatchJournal.markCommitted(journal);
            TellusNativeBatchJournal.delete(journal);
            return outcome;
        } catch (IOException failure) {
            store.quarantine();
            return new CommitResult(Status.QUARANTINED, outcome.previous(), outcome.persisted(),
                List.of("native journal finalization requires recovery: " + failure.getClass().getName()));
        }
    }
    private CompoundTag checkedCopy(CompoundTag tag, ChunkPos pos) {
        Objects.requireNonNull(tag, "native tag");
        // Bound traversal before recursive native copy; also rejects cycles and malformed oversized inputs.
        digest(tag);
        if (!tag.contains("xPos", 3) || !tag.contains("zPos", 3) || tag.getInt("xPos") != pos.x || tag.getInt("zPos") != pos.z)
            throw new IllegalArgumentException("native NBT chunk coordinates differ");
        if (!tag.contains("Status", 8) || !isFull(tag.getString("Status"))) throw new IllegalArgumentException("native FULL status required");
        for (String compound : List.of("Heightmaps", "structures"))
            if (!tag.contains(compound, 10)) throw new IllegalArgumentException("missing full native compound: " + compound);
        for (String list : List.of("sections", "PostProcessing", "block_ticks", "fluid_ticks", "block_entities"))
            if (!tag.contains(list, 9)) throw new IllegalArgumentException("missing full native list: " + list);
        if (!tag.contains("DataVersion", 3) || !tag.contains("LastUpdate", 4) || !tag.contains("InhabitedTime", 4))
            throw new IllegalArgumentException("missing full native version/time metadata");
        if (!tag.contains("yPos", 3) || tag.getInt("yPos") != Math.floorDiv(binding.minY(), 16))
            throw new IllegalArgumentException("native minimum section coordinate differs from bound world");
        for (String list : List.of("sections", "block_ticks", "fluid_ticks", "block_entities"))
            requireList(tag, list, 10);
        requireList(tag, "PostProcessing", 9);
        for (net.minecraft.nbt.Tag offsets : (net.minecraft.nbt.ListTag)tag.get("PostProcessing"))
            requireElements((net.minecraft.nbt.ListTag)offsets, 2, "PostProcessing offsets");
        var sections = tag.getList("sections", 10);
        if (sections.size() > binding.height() / 16 + 2)
            throw new IllegalArgumentException("native section count exceeds bound world height");
        var seen = new java.util.HashSet<Integer>();
        int first = Math.floorDiv(binding.minY(), 16);
        int lastExclusive = Math.floorDiv(Math.addExact(binding.minY(), binding.height()), 16);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!section.contains("Y", 1) && !section.contains("Y", 3))
                throw new IllegalArgumentException("native section needs exact byte/int Y");
            int y = section.getInt("Y");
            if (y < first - 1 || y > lastExclusive || !seen.add(y))
                throw new IllegalArgumentException("native section Y is duplicate or outside body/light bounds");
            if ((y < first || y >= lastExclusive) && (section.contains("block_states") || section.contains("biomes")))
                throw new IllegalArgumentException("light boundary section contains body palettes");
            for (String light : List.of("BlockLight", "SkyLight"))
                if (section.contains(light) && (!section.contains(light, 7) || section.getByteArray(light).length != 2048))
                    throw new IllegalArgumentException("native light array must contain 2048 bytes: " + light);
            requirePalette(section, "block_states", 10);
            requirePalette(section, "biomes", 8);
        }
        return tag.copy();
    }
    private static void requireList(CompoundTag root, String key, int elementType) {
        requireElements((net.minecraft.nbt.ListTag)root.get(key), elementType, key);
    }
    private static void requireElements(net.minecraft.nbt.ListTag list, int elementType, String key) {
        int actual = list.getElementType();
        if (actual != elementType && !(actual == 0 && list.isEmpty()))
            throw new IllegalArgumentException("wrong native list element type: " + key);
    }
    private static void requirePalette(CompoundTag section, String key, int elementType) {
        if (!section.contains(key)) return;
        if (!section.contains(key, 10)) throw new IllegalArgumentException("native palette payload must be compound: " + key);
        var payload = section.getCompound(key);
        if (!payload.contains("palette", 9)) throw new IllegalArgumentException("native palette list is missing: " + key);
        var palette = (net.minecraft.nbt.ListTag)payload.get("palette");
        requireElements(palette, elementType, key + " palette");
        if (palette.isEmpty()) throw new IllegalArgumentException("native palette is empty: " + key);
        if (payload.contains("data") && !payload.contains("data", 12))
            throw new IllegalArgumentException("native packed palette data must be long array: " + key);
        for (net.minecraft.nbt.Tag value : palette) {
            if (elementType == 8) {
                if (value.getAsString().isBlank()) throw new IllegalArgumentException("native biome name is blank");
            } else {
                var state = (CompoundTag)value;
                if (!state.contains("Name", 8) || state.getString("Name").isBlank())
                    throw new IllegalArgumentException("native block state requires a string Name");
                if (state.contains("Properties") && !state.contains("Properties", 10))
                    throw new IllegalArgumentException("native block state Properties must be compound");
            }
        }
    }
    private static boolean isFull(String status) { return status.equals("minecraft:full") || status.equals("full"); }
    private static String text(String value, String name) {
        Objects.requireNonNull(value, name); if (value.isBlank()) throw new IllegalArgumentException(name); return value;
    }
    private static Path realPath(Path path) {
        try { return Objects.requireNonNull(path, "admittedWorldRoot").toRealPath(); }
        catch (IOException failure) { throw new IllegalArgumentException("world root must already exist", failure); }
    }

    private static final class LevelStore implements Store {
        private final ServerLevel level;
        private final ChunkGenerator generator;
        private final Path admittedRoot;
        LevelStore(ServerLevel level, Path admittedRoot) {
            this.level = level; this.generator = level.getChunkSource().getGenerator(); this.admittedRoot = admittedRoot;
        }
        @Override public Binding currentBinding() {
            if (!mainThread()) throw new IllegalStateException("native binding requires server main thread");
            if (level.getServer().getLevel(level.dimension()) != level) throw new IllegalStateException("exact active ServerLevel instance changed");
            Path actualRoot = realPath(level.getServer().getWorldPath(LevelResource.ROOT));
            if (!admittedRoot.equals(actualRoot)) throw new IllegalStateException("actual world root differs from admitted isolated root");
            if (generator != level.getChunkSource().getGenerator()) throw new IllegalStateException("exact native generator instance changed");
            var encoded = ChunkGenerator.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()), generator)
                .result().orElseThrow(() -> new IllegalStateException("native generator Codec profile unavailable"));
            if (!(encoded instanceof CompoundTag profile)) throw new IllegalStateException("native generator profile must be compound NBT");
            return new Binding(actualRoot, level.dimension().location().toString(), level.getSeed(), generator.getClass().getName(),
                digest(profile), level.getMinBuildHeight(), level.getHeight(), generator.getSeaLevel());
        }
        @Override public boolean mainThread() { return level.getServer().isSameThread(); }
        @Override public SaveFence quiesce(List<ChunkPos> positions, boolean requireUnloaded) {
            if (requireUnloaded) {
                if (!(level.getChunkSource().chunkMap instanceof TellusNativeChunkMapAccess access))
                    throw new IllegalStateException("native holder admission mixin is missing");
                for (var pos : positions) if (access.tellus$hasNativeHolder(pos.toLong()))
                    throw new IllegalStateException("native disk transaction/recovery requires unloaded target: " + pos);
            }
            return TellusNativeSaveFence.acquire(level);
        }
        @Override public void quarantine() { TellusNativeSaveFence.failed(level); }
        @Override public void recovered() { TellusNativeSaveFence.recovered(level); }
        @Override public boolean canonical(ChunkPos pos) {
            return !(generator instanceof CubeNetChunkGenerator cube)
                || cube.apronProjection(pos).isEmpty() && cube.address(pos).isPresent();
        }
        @Override public Optional<CompoundTag> read(ChunkPos pos) {
            return level.getChunkSource().chunkMap.read(pos).join();
        }
        @Override public void submit(ChunkPos pos, CompoundTag tag) {
            // Statement is compatible with void mc1201 and future-returning mc1211.
            // Native flush plus exact readback detects missing/failed queue publication.
            level.getChunkSource().chunkMap.write(pos, tag);
        }
        @Override public void flush() { level.getChunkSource().chunkMap.flushWorker(); }
        @Override public CompoundTag snapshot(ChunkAccess chunk) {
            if (!(chunk instanceof LevelChunk full) || full.getLevel() != level
                || level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) != full)
                throw new IllegalArgumentException("snapshot requires exact loaded FULL chunk from bound ServerLevel");
            return ChunkSerializer.write(level, chunk);
        }
    }
}
