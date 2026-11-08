package com.yucareux.tellus.worldgen.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.decision.BiomeDecision;
import com.yucareux.tellus.worldgen.decision.ColumnDecision;
import com.yucareux.tellus.worldgen.decision.DecisionStatus;
import com.yucareux.tellus.worldgen.decision.SemanticMaterial;
import com.yucareux.tellus.worldgen.decision.StrataDecision;
import com.yucareux.tellus.worldgen.decision.SurfaceDecision;
import com.yucareux.tellus.worldgen.decision.TellusGenerationContext;
import com.yucareux.tellus.worldgen.decision.WaterDecision;
import com.yucareux.tellus.worldgen.global.CubeNetApronOwnerView;
import com.yucareux.tellus.worldgen.global.CubeNetApronResolver;
import com.yucareux.tellus.worldgen.global.CubeNetLayout;
import com.yucareux.tellus.worldgen.global.GeneratorOwner;
import com.yucareux.tellus.worldgen.global.ImmutableTellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.SphereWorldModel;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContext;
import com.yucareux.tellus.worldgen.global.TellusCoordinateContextFactory;
import com.yucareux.tellus.worldgen.sample.GeometryIdentity;
import com.yucareux.tellus.worldgen.sample.SampleStatus;
import com.yucareux.tellus.worldgen.sample.SamplingPurpose;
import com.yucareux.tellus.worldgen.sample.SamplingResolution;
import com.yucareux.tellus.worldgen.sample.TellusCoverSample;
import com.yucareux.tellus.worldgen.sample.TellusElevationSample;
import com.yucareux.tellus.worldgen.sample.TellusSample;
import com.yucareux.tellus.worldgen.sample.TellusSampleProvenance;
import com.yucareux.tellus.worldgen.sample.TellusSampleRequest;
import com.yucareux.tellus.worldgen.sample.TellusWaterSample;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Pure semantic write-boundary contracts. */
class TellusColumnWriteContractTest {
    private static final double SCALE = 1.0;
    private static final SphereWorldModel MODEL = SphereWorldModel.forWorldScale(SCALE);
    private static final GeometryIdentity GEOMETRY = new GeometryIdentity(
        "tellus:write", "write-v1", "write-projection-v1", "write-noise-v1", SCALE);
    private static final TellusGenerationContext GENERATION = new TellusGenerationContext(
        11L, SCALE, 64, -64, 320, GEOMETRY);
    private static final TellusCoordinateContext CANONICAL = TellusCoordinateContextFactory.fromCubeNetCanonical(
        MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
        new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 32.5, 48.5));

    @Test
    void unknownAndQueuedDecisionsProduceNoWrites() {
        TellusColumnMaterializer materializer = new TellusColumnMaterializer();
        ColumnDecision unknown = unavailable(CANONICAL, DecisionStatus.UNKNOWN, "unknown");
        ColumnDecision queued = unavailable(CANONICAL, DecisionStatus.QUEUED, "queued");

        WriteResult<SemanticMaterial> unknownResult = materializer.materialize(
            WriteContext.canonical(CANONICAL, GENERATION, "fixture-writer"), unknown);
        WriteResult<SemanticMaterial> queuedResult = materializer.materialize(
            WriteContext.canonical(CANONICAL, GENERATION, "fixture-writer"), queued);

        assertEquals(WriteStatus.NO_WRITE_UNKNOWN, unknownResult.status());
        assertEquals(WriteStatus.NO_WRITE_QUEUED, queuedResult.status());
        assertTrue(unknownResult.mappedWrites().isEmpty());
        assertTrue(queuedResult.mappedWrites().isEmpty());
        assertTrue(unknownResult.status().noWrite());
        assertTrue(queuedResult.status().noWrite());
    }

    @Test
    void invalidDecisionAndInvalidContextAreRejectedWithoutWrites() {
        TellusColumnMaterializer materializer = new TellusColumnMaterializer();
        TellusCoordinateContext other = TellusCoordinateContextFactory.fromCubeNetCanonical(
            MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
            new CubeNetLayout.FacePoint(CubeNetLayout.Face.POS_X, 33.5, 48.5));
        ColumnDecision decision = available(other, 80);
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "fixture-writer");

        WriteResult<SemanticMaterial> invalid = materializer.materialize(context, decision);

        assertEquals(WriteStatus.NO_WRITE_INVALID, invalid.status());
        assertTrue(invalid.mappedWrites().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> WriteContext.canonical(
            CANONICAL, GENERATION, " "));
        assertThrows(IllegalArgumentException.class, () -> new WriteResult<>(
            WriteStatus.NO_WRITE_UNKNOWN, List.of(SemanticMaterial.STONE), List.of("bad")));
    }

    @Test
    void canonicalWritesAndApronMirrorUseCorrectOwnerSourceAndAreDeterministic() {
        TellusColumnMaterializer materializer = new TellusColumnMaterializer();
        ColumnDecision canonicalDecision = available(CANONICAL, 80);
        WriteContext canonicalContext = WriteContext.canonical(CANONICAL, GENERATION, "fixture-writer");
        WriteResult<SemanticMaterial> first = materializer.materialize(canonicalContext, canonicalDecision);
        WriteResult<SemanticMaterial> second = materializer.materialize(canonicalContext, canonicalDecision);
        assertEquals(WriteStatus.WRITTEN, first.status());
        assertEquals(first, second);
        assertEquals(145, first.mappedWrites().size());

        ImmutableTellusCoordinateContext apron = findApronContext().orElseThrow();
        WriteContext mirrorContext = WriteContext.apronMirror(
            apron, CANONICAL, GENERATION, "fixture-mirror");
        assertTrue(mirrorContext.apronMirror());
        assertEquals(GeneratorOwner.CUBE_NET_APRON, mirrorContext.targetOwner());
        assertTrue(mirrorContext.acceptsDecision(CANONICAL));
        assertFalse(mirrorContext.acceptsDecision(apron));
        WriteResult<SemanticMaterial> mirror = materializer.materialize(
            mirrorContext, canonicalDecision);
        assertEquals(WriteStatus.WRITTEN, mirror.status());
        assertEquals(first.mappedWrites(), mirror.mappedWrites());
        assertNotNull(mirror.diagnostics());
    }

    @Test
    void partialPolicyControlsKnownLayerWriteBoundary() {
        TellusColumnMaterializer materializer = new TellusColumnMaterializer();
        ColumnDecision partial = new ColumnDecision(CANONICAL, DecisionStatus.PARTIAL,
            OptionalInt.of(80), SurfaceDecision.defaultStone(), WaterDecision.unknown(),
            BiomeDecision.defaultBiome(), StrataDecision.stone(-64, 80), List.of("partial"));

        WriteResult<SemanticMaterial> knownLayers = materializer.materialize(
            WriteContext.canonical(CANONICAL, GENERATION, "partial-known", PartialWritePolicy.KNOWN_LAYERS_ONLY),
            partial);
        WriteResult<SemanticMaterial> noWrite = materializer.materialize(
            WriteContext.canonical(CANONICAL, GENERATION, "partial-none", PartialWritePolicy.NO_WRITE),
            partial);

        assertEquals(WriteStatus.PARTIAL, knownLayers.status());
        assertTrue(knownLayers.status().wrote());
        assertEquals(WriteStatus.NO_WRITE_PARTIAL, noWrite.status());
        assertTrue(noWrite.status().noWrite());
        assertTrue(noWrite.mappedWrites().isEmpty());
        assertTrue(noWrite.diagnostics().get(0).contains("complete decision"));
        assertTrue(knownLayers.persistenceIdentity().isPresent());
        assertTrue(noWrite.persistenceIdentity().isPresent());
    }

    @Test
    void canonicalPersistenceKeyIsSharedByCanonicalAndApronViews() {
        ImmutableTellusCoordinateContext apron = findApronContext().orElseThrow();
        var apronOwner = (CubeNetApronOwnerView)apron.owner();
        TellusCoordinateContext apronCanonicalContext = TellusCoordinateContextFactory.fromCubeNetCanonical(
            MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
            apronOwner.canonicalOwner().facePoint());
        WriteContext mirror = WriteContext.apronMirror(
            apron, apronCanonicalContext, GENERATION, "identity-test");
        CanonicalPersistenceKey canonical = CanonicalPersistenceKey.from(
            GEOMETRY, CANONICAL);
        CanonicalPersistenceKey fromMirror = mirror.canonicalPersistenceKey();
        CanonicalPersistenceKey apronCanonical = CanonicalPersistenceKey.from(
            GEOMETRY, apronCanonicalContext);
        TellusPersistenceIdentity canonicalIdentity = TellusPersistenceIdentity.from(
            GEOMETRY, CANONICAL);
        TellusPersistenceIdentity apronIdentity = TellusPersistenceIdentity.from(
            GEOMETRY, apron);

        assertEquals(canonical, CanonicalPersistenceKey.from(GEOMETRY, CANONICAL));
        assertEquals(apronCanonical, fromMirror);
        assertEquals(apronCanonical.stableKey(), fromMirror.stableKey());
        assertTrue(canonicalIdentity.canonical());
        assertTrue(apronIdentity.apronMirror());
        assertEquals(apronCanonical.stableKey(), apronIdentity.canonicalKey());
        assertTrue(apronIdentity.derivedView().isPresent());
        assertEquals(GeneratorOwner.CUBE_NET_APRON, apronIdentity.derivedView().orElseThrow().owner());
        assertThrows(IllegalArgumentException.class, () -> new CanonicalPersistenceKey(
            GEOMETRY.stableKey(), GeneratorOwner.CUBE_NET_APRON, 0, 0, 0, 0, 0));
    }

    @Test
    void seamProjectionIdentityIsFiniteStableAndAttachedOnlyToApronMirror() {
        ImmutableTellusCoordinateContext apron = findApronContext().orElseThrow();
        WriteContext mirror = WriteContext.apronMirror(apron, CANONICAL, GENERATION, "seam-test");
        SeamProjectionIdentity first = mirror.seamProjectionIdentity().orElseThrow();
        SeamProjectionIdentity second = mirror.seamProjectionIdentity().orElseThrow();
        WriteContext canonical = WriteContext.canonical(CANONICAL, GENERATION, "seam-test");

        assertEquals(first, second);
        assertEquals("cube-net-apron", first.projectionId());
        assertTrue(Double.isFinite(first.proxyX()));
        assertTrue(Double.isFinite(first.proxyZ()));
        assertTrue(first.targetFace() >= 0 && first.targetFace() < 6);
        assertTrue(canonical.seamProjectionIdentity().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SeamProjectionIdentity.of(
            "cube-net-apron", Double.NaN, 0.0, first.targetFace(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SeamProjectionIdentity.of(
            "cube-net-apron", 0.0, 0.0, -1, 0, 0));
    }

    @Test
    void repeatedMaterializationIsIdempotentAndNoWriteResultsRemainEmpty() {
        TellusColumnMaterializer materializer = new TellusColumnMaterializer();
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "idempotent");
        ColumnDecision available = available(CANONICAL, 80);
        WriteResult<SemanticMaterial> first = materializer.materialize(context, available);
        WriteResult<SemanticMaterial> second = materializer.materialize(context, available);
        WriteResult<SemanticMaterial> unknown = materializer.materialize(
            context, unavailable(CANONICAL, DecisionStatus.UNKNOWN, "unknown"));
        WriteResult<SemanticMaterial> queued = materializer.materialize(
            context, unavailable(CANONICAL, DecisionStatus.QUEUED, "queued"));

        assertEquals(first, second);
        assertEquals(first.persistenceIdentity(), second.persistenceIdentity());
        assertEquals(WriteStatus.WRITTEN, first.status());
        assertEquals(WriteStatus.NO_WRITE_UNKNOWN, unknown.status());
        assertEquals(WriteStatus.NO_WRITE_QUEUED, queued.status());
        assertTrue(unknown.mappedWrites().isEmpty());
        assertTrue(queued.mappedWrites().isEmpty());
        assertTrue(unknown.persistenceIdentity().isPresent());
        assertTrue(queued.persistenceIdentity().isPresent());
    }


    @Test
    void transactionBoundaryPreparesCanonicalIdentityAndStableIdempotency() {
        TellusColumnMaterializer materializer = new TellusColumnMaterializer();
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "transaction");
        WriteResult<SemanticMaterial> result = materializer.materialize(context, available(CANONICAL, 80));
        StorageTransactionBoundary boundary = new StorageTransactionBoundary();

        StorageTransaction<SemanticMaterial> first = boundary.prepare(
            context, result, StorageCommitPolicy.canonicalDefault(), "op-v1");
        StorageTransaction<SemanticMaterial> second = boundary.prepare(
            context, result, StorageCommitPolicy.canonicalDefault(), "op-v1");
        StorageTransaction<SemanticMaterial> differentOperation = boundary.prepare(
            context, result, StorageCommitPolicy.canonicalDefault(), "op-v2");

        assertEquals(StorageTransactionStatus.PREPARED, first.status());
        assertTrue(first.commitEligible());
        assertEquals(first.key(), second.key());
        assertEquals(first.idempotencyKey(), second.idempotencyKey());
        assertTrue(first.idempotentlyMatches(second));
        assertFalse(first.idempotentlyMatches(differentOperation));
        assertEquals(result.mappedWrites(), first.writes());
        assertTrue(first.seamProjection().isEmpty());
    }

    @Test
    void apronTransactionKeepsDerivedSeamIdentityButNeverCommits() {
        ImmutableTellusCoordinateContext apron = findApronContext().orElseThrow();
        var apronOwner = (CubeNetApronOwnerView)apron.owner();
        TellusCoordinateContext canonical = TellusCoordinateContextFactory.fromCubeNetCanonical(
            MODEL, CubeNetLayout.standard(MODEL.config().faceBlocks()),
            apronOwner.canonicalOwner().facePoint());
        WriteContext context = WriteContext.apronMirror(apron, canonical, GENERATION, "apron-transaction");
        WriteResult<SemanticMaterial> result = new TellusColumnMaterializer().materialize(
            context, available(canonical, 80));

        StorageTransaction<SemanticMaterial> transaction = new StorageTransactionBoundary().prepare(
            context, result, StorageCommitPolicy.canonicalDefault(), "op-v1");

        assertEquals(StorageTransactionStatus.NO_COMMIT_FALLBACK, transaction.status());
        assertFalse(transaction.commitEligible());
        assertTrue(transaction.writes().isEmpty());
        assertTrue(transaction.seamProjection().isPresent());
        assertEquals("cube-net-apron", transaction.seamProjection().orElseThrow().projectionId());
        assertEquals(transaction.key().stableKey(), context.canonicalPersistenceKey().stableKey());
    }

    @Test
    void transactionBoundaryMapsUnknownQueuedDisabledAndPartialNoWriteStatuses() {
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "status-transaction");
        TellusColumnMaterializer materializer = new TellusColumnMaterializer();
        StorageTransactionBoundary boundary = new StorageTransactionBoundary();
        StorageCommitPolicy enabled = StorageCommitPolicy.canonicalDefault();

        StorageTransaction<SemanticMaterial> unknown = boundary.prepare(context,
            materializer.materialize(context, unavailable(CANONICAL, DecisionStatus.UNKNOWN, "unknown")),
            enabled, "unknown-op");
        StorageTransaction<SemanticMaterial> queued = boundary.prepare(context,
            materializer.materialize(context, unavailable(CANONICAL, DecisionStatus.QUEUED, "queued")),
            enabled, "queued-op");
        StorageTransaction<SemanticMaterial> disabled = boundary.prepare(context,
            materializer.materialize(context, available(CANONICAL, 80)),
            StorageCommitPolicy.disabled(), "disabled-op");
        ColumnDecision partial = new ColumnDecision(CANONICAL, DecisionStatus.PARTIAL,
            OptionalInt.of(80), SurfaceDecision.defaultStone(), WaterDecision.unknown(),
            BiomeDecision.defaultBiome(), StrataDecision.stone(-64, 80), List.of("partial"));
        WriteContext partialContext = WriteContext.canonical(
            CANONICAL, GENERATION, "partial-no-write", PartialWritePolicy.NO_WRITE);
        StorageCommitPolicy partialReject = new StorageCommitPolicy(
            true, PartialWritePolicy.NO_WRITE, StorageFallbackPolicy.NO_COMMIT);
        StorageTransaction<SemanticMaterial> partialNoWrite = boundary.prepare(
            partialContext, materializer.materialize(partialContext, partial), partialReject, "partial-op");

        assertEquals(StorageTransactionStatus.NO_COMMIT_UNKNOWN, unknown.status());
        assertEquals(StorageTransactionStatus.NO_COMMIT_QUEUED, queued.status());
        assertEquals(StorageTransactionStatus.NO_COMMIT_DISABLED, disabled.status());
        assertEquals(StorageTransactionStatus.NO_COMMIT_PARTIAL, partialNoWrite.status());
        assertTrue(unknown.writes().isEmpty());
        assertTrue(queued.writes().isEmpty());
        assertTrue(disabled.writes().isEmpty());
        assertTrue(partialNoWrite.writes().isEmpty());
    }

    @Test
    void nonCommittableTransactionRejectsWritesAndRepeatPreparationIsStable() {
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "constructor-transaction");
        StorageTransactionBoundary boundary = new StorageTransactionBoundary();
        WriteResult<SemanticMaterial> result = new TellusColumnMaterializer().materialize(
            context, available(CANONICAL, 80));
        StorageTransaction<SemanticMaterial> first = boundary.prepare(
            context, result, StorageCommitPolicy.canonicalDefault(), "repeat");
        StorageTransaction<SemanticMaterial> second = boundary.prepare(
            context, result, StorageCommitPolicy.canonicalDefault(), "repeat");

        assertEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> new StorageTransaction<>(
            StorageTransactionStatus.NO_COMMIT_UNKNOWN, first.key(), first.idempotencyKey(),
            List.of(SemanticMaterial.STONE), List.of("invalid"), Optional.empty()));
    }


    @Test
    void semanticMapperSupportsOpaqueTargetTypeWithoutMinecraftApi() {
        TellusColumnWriter<String> writer = (context, decision, mapper) -> {
            WriteResult<SemanticMaterial> guard = new TellusColumnMaterializer().materialize(context, decision);
            if (guard.status().noWrite()) {
                return new WriteResult<>(guard.status(), List.of(), guard.diagnostics());
            }
            return new WriteResult<>(WriteStatus.WRITTEN,
                List.of(mapper.map(SemanticMaterial.STONE, decision.terrainY().orElseThrow(), context)),
                List.of("opaque mapped"));
        };
        SemanticMapper<String> mapper = (material, y, context) -> material.name() + "@" + y;
        WriteContext context = WriteContext.canonical(CANONICAL, GENERATION, "opaque-writer");
        WriteResult<String> result = writer.write(context, available(CANONICAL, 80), mapper);

        assertEquals(WriteStatus.WRITTEN, result.status());
        assertEquals(List.of("STONE@80"), result.mappedWrites());
        assertEquals("opaque mapped", result.diagnostics().get(0));
        assertFalse(result.mappedWrites().get(0).getClass().getName().startsWith("net.minecraft."));

        for (Class<?> type : new Class<?>[]{WriteContext.class, WriteStatus.class, WriteResult.class,
            SemanticMapper.class, TellusColumnWriter.class, TellusColumnMaterializer.class}) {
            assertFalse(type.getName().startsWith("net.minecraft."));
            for (Field field : type.getDeclaredFields()) {
                assertFalse(field.getType().getName().startsWith("net.minecraft."), field.getName());
            }
            for (Method method : type.getDeclaredMethods()) {
                assertFalse(method.getReturnType().getName().startsWith("net.minecraft."), method.getName());
            }
        }
    }

    private static ColumnDecision available(TellusCoordinateContext coordinate, int y) {
        return new ColumnDecision(coordinate, DecisionStatus.AVAILABLE, OptionalInt.of(y),
            SurfaceDecision.defaultStone(), WaterDecision.dry(), BiomeDecision.defaultBiome(),
            StrataDecision.stone(-64, y), List.of("fixture"));
    }

    private static ColumnDecision unavailable(
        TellusCoordinateContext coordinate, DecisionStatus status, String diagnostic
    ) {
        return ColumnDecision.unavailable(coordinate, status, diagnostic);
    }

    private static TellusSample sample(TellusCoordinateContext coordinate) {
        TellusSampleRequest request = TellusSampleRequest.fromContext(
            coordinate, SamplingPurpose.TERRAIN_COLUMN,
            SamplingResolution.exact(SCALE), GEOMETRY, 11L);
        return new TellusSample(request, SampleStatus.AVAILABLE,
            new TellusElevationSample(SampleStatus.AVAILABLE, 80.0),
            new TellusCoverSample(SampleStatus.AVAILABLE, 30, 30),
            TellusWaterSample.dry(),
            TellusSampleProvenance.snapshot("write-fixture", "r1", true, "available"));
    }

    private static Optional<ImmutableTellusCoordinateContext> findApronContext() {
        CubeNetLayout layout = CubeNetLayout.standard(MODEL.config().faceBlocks());
        CubeNetApronResolver resolver = new CubeNetApronResolver(layout, 64);
        double edge = layout.faceSize() * 0.5;
        for (CubeNetLayout.Face face : CubeNetLayout.Face.values()) {
            CubeNetLayout.FacePlacement placement = layout.placement(face);
            for (CubeNetLayout.Side side : CubeNetLayout.Side.values()) {
                if (layout.arePlanarNeighbors(face, side)) continue;
                CubeNetLayout.FacePoint interior = switch (side) {
                    case WEST -> new CubeNetLayout.FacePoint(face, 0.5, edge);
                    case EAST -> new CubeNetLayout.FacePoint(face, layout.faceSize() - 0.5, edge);
                    case NORTH -> new CubeNetLayout.FacePoint(face, edge, 0.5);
                    case SOUTH -> new CubeNetLayout.FacePoint(face, edge, layout.faceSize() - 0.5);
                };
                CubeNetLayout.PlanarPoint center = layout.toPlanar(interior);
                double outwardX = switch (side) {
                    case WEST -> -placement.uAxisX();
                    case EAST -> placement.uAxisX();
                    case NORTH -> -placement.vAxisX();
                    case SOUTH -> placement.vAxisX();
                };
                double outwardZ = switch (side) {
                    case WEST -> -placement.uAxisZ();
                    case EAST -> placement.uAxisZ();
                    case NORTH -> -placement.vAxisZ();
                    case SOUTH -> placement.vAxisZ();
                };
                CubeNetLayout.PlanarPoint proxy = new CubeNetLayout.PlanarPoint(
                    center.x() + outwardX * 24.0, center.z() + outwardZ * 24.0);
                Optional<ImmutableTellusCoordinateContext> candidate =
                    TellusCoordinateContextFactory.fromCubeNetPlanar(
                        MODEL, layout, resolver, proxy.x(), proxy.z());
                if (candidate.isPresent() && candidate.orElseThrow().owner() instanceof CubeNetApronOwnerView) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }
}
