package dev.pepsi.questrank.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import dev.pepsi.questrank.core.catalog.RankingPolicy;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.integration.ftb.FtbCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pruebas deterministas del servicio de servidor (§§8.5–8.11) con dobles de los puertos y tiempo
 * simulado por ticks: carga inicial, fallo y recuperación, revisiones, memos, debounce, TTLs,
 * suscriptores y reinicio.
 */
class RankingServiceTest {

    private static final long EPOCH = 4242L;

    private final FakeCatalogSource catalog = new FakeCatalogSource();
    private final FakeParticipantsSource participants = new FakeParticipantsSource();
    private final FakePolicySource policy = new FakePolicySource();
    private final List<ServiceSnapshot> published = new ArrayList<>();

    // ---------------------------------------------------------------- carga inicial y respuestas

    @Test
    void firstRequestPublishesInitialSnapshot() {
        baseSources();
        RankingService service = newService();

        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(null, 0L));
        ServiceSnapshot snapshot = result.snapshot();
        assertEquals(EPOCH, snapshot.epoch());
        assertEquals(1L, snapshot.rev());
        assertEquals(1L, snapshot.catalogRevision());
        assertEquals(3L, snapshot.ranking().total());
        assertEquals(2, snapshot.ranking().entries().size());
        assertEquals(1, published.size());
        assertEquals(snapshot, published.get(0));
    }

    @Test
    void matchingEpochAndRevYieldsUnchanged() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        assertEquals(RankingResult.Unchanged.INSTANCE, service.requestState(EPOCH, 1L));
        assertEquals(1, published.size());
    }

    @Test
    void mismatchedEpochOrRevisionYieldsSnapshot() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH + 1, 1L));
        assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 0L));
        assertInstanceOf(RankingResult.Snapshot.class, service.requestState(null, 1L));
    }

    // ---------------------------------------------------------------- catálogo y configuración

    @Test
    void catalogChangePublishesAfterMemoExpiry() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L, 4L));
        assertEquals(RankingResult.Unchanged.INSTANCE, service.requestState(EPOCH, 1L));

        ticks(service, RankingService.CATALOG_MEMO_TICKS);
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().catalogRevision());
        assertEquals(2L, result.snapshot().rev());
        assertEquals(4L, result.snapshot().ranking().total());
        assertEquals(2, published.size());
    }

    @Test
    void configRevisionChangeIsPartOfCatalogFingerprint() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        policy.configRevision = 42L;
        ticks(service, RankingService.CATALOG_MEMO_TICKS);
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().catalogRevision());
        assertEquals(2L, result.snapshot().rev());
    }

    @Test
    void reloadForcesValidationWithinMemo() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L, 4L));
        service.onCatalogReloaded();
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().catalogRevision());
        assertEquals(2L, result.snapshot().rev());
    }

    @Test
    void reloadFlagSurvivesCatalogFailure() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        service.onCatalogReloaded();
        catalog.value = Optional.empty();
        RankingResult.Error error = assertInstanceOf(RankingResult.Error.class, service.requestState(EPOCH, 1L));
        assertEquals(RankingResult.ErrorKind.CATALOG_UNAVAILABLE, error.kind());
        assertEquals(1L, service.currentCatalogRevision());

        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L, 4L));
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().catalogRevision());
    }

    @Test
    void catalogUnavailableYieldsErrorAndPreservesState() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        catalog.value = Optional.empty();
        ticks(service, RankingService.CATALOG_MEMO_TICKS);
        RankingResult.Error error = assertInstanceOf(RankingResult.Error.class, service.requestState(EPOCH, 1L));
        assertEquals(RankingResult.ErrorKind.CATALOG_UNAVAILABLE, error.kind());
        assertEquals(1L, service.currentRev());
        assertEquals(1, published.size());

        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L));
        assertEquals(RankingResult.Unchanged.INSTANCE, service.requestState(EPOCH, 1L));
    }

    // ---------------------------------------------------------------- fallo y recuperación

    @Test
    void firstRequestParticipantsFailureRecoversCleanly() {
        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.empty();
        RankingService service = newService();

        RankingResult.Error error = assertInstanceOf(RankingResult.Error.class, service.requestState(null, 0L));
        assertEquals(RankingResult.ErrorKind.PARTICIPANTS_UNAVAILABLE, error.kind());
        assertEquals(1L, service.currentCatalogRevision());
        assertEquals(0L, service.currentRev());
        assertNull(service.currentSnapshot());
        assertEquals(0, published.size());

        participants.value = Optional.of(defaultParticipants());
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(null, 0L));
        assertEquals(1L, result.snapshot().rev());
        assertEquals(1L, result.snapshot().catalogRevision());
        assertEquals(1, published.size());
    }

    @Test
    void catalogChangeWithParticipantsFailureKeepsCatalogRevisionWithoutPublishing() {
        RankingService service = serviceAfterCatalogChangeWithParticipantsFailure();

        RankingResult.Error error = assertInstanceOf(RankingResult.Error.class, service.requestState(EPOCH, 1L));
        assertEquals(RankingResult.ErrorKind.PARTICIPANTS_UNAVAILABLE, error.kind());
        assertEquals(2L, service.currentCatalogRevision());
        assertEquals(1L, service.currentRev());
        assertEquals(1L, service.currentSnapshot().catalogRevision());
        assertEquals(1, published.size());
    }

    @Test
    void repeatedRequestsDuringFailureReturnErrorNeverStale() {
        RankingService service = serviceAfterCatalogChangeWithParticipantsFailure();

        assertInstanceOf(RankingResult.Error.class, service.requestState(EPOCH, 1L));
        assertInstanceOf(RankingResult.Error.class, service.requestState(EPOCH, 2L));
        assertInstanceOf(RankingResult.Error.class, service.requestState(null, 0L));
        assertEquals(1L, service.currentRev());
        assertEquals(1L, service.currentSnapshot().catalogRevision());
        assertEquals(1, published.size());
    }

    @Test
    void recoveryPublishesCoherentSnapshot() {
        RankingService service = serviceAfterCatalogChangeWithParticipantsFailure();

        participants.value = Optional.of(defaultParticipants());
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().catalogRevision());
        assertEquals(2L, result.snapshot().rev());
        assertEquals(4L, result.snapshot().ranking().total());
        assertEquals(2, published.size());
        assertEquals(RankingResult.Unchanged.INSTANCE, service.requestState(EPOCH, 2L));
    }

    @Test
    void silentParticipantsFailureAndRecoveryKeepsRevAndPublishesOnce() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        participants.value = Optional.empty();
        ticks(service, RankingService.DISPLAY_MEMO_TICKS);
        assertInstanceOf(RankingResult.Error.class, service.requestState(EPOCH, 1L));
        assertEquals(1L, service.currentRev());
        assertEquals(1, published.size());

        participants.value = Optional.of(defaultParticipants());
        assertEquals(RankingResult.Unchanged.INSTANCE, service.requestState(EPOCH, 1L));
        assertEquals(1L, service.currentRev());
        assertEquals(1, published.size());
    }

    // ---------------------------------------------------------------- memos, eventos y TTLs

    @Test
    void eventWithinMemoForcesImmediateRebuild() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        participants.value = Optional.of(List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L),
                participant(3, "c", 1L)));
        service.onProgressChanged();
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().rev());
        assertEquals(1L, result.snapshot().catalogRevision());
        assertEquals(3, result.snapshot().ranking().entries().size());
        assertEquals(2, published.size());
    }

    @Test
    void silentChangeWithinMemoYieldsUnchangedThenRebuildsAfterMemo() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        participants.value = Optional.of(List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L),
                participant(3, "c", 1L)));
        assertEquals(RankingResult.Unchanged.INSTANCE, service.requestState(EPOCH, 1L));

        ticks(service, RankingService.DISPLAY_MEMO_TICKS);
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().rev());
        assertEquals(2, published.size());
    }

    @Test
    void silentCatalogChangeWaitsForCatalogMemo() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);

        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L, 4L));
        assertEquals(RankingResult.Unchanged.INSTANCE, service.requestState(EPOCH, 1L));
        assertEquals(1L, service.currentCatalogRevision());

        ticks(service, RankingService.CATALOG_MEMO_TICKS);
        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().catalogRevision());
        assertEquals(2L, result.snapshot().rev());
    }

    @Test
    void eventBurstDebouncesToSingleRebuild() {
        baseSources();
        RankingService service = newService();
        service.setSubscriberCount(1);
        service.requestState(null, 0L);
        assertEquals(1, participants.reads);

        participants.value = Optional.of(List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L),
                participant(3, "c", 1L)));
        for (int i = 0; i < 5; i++) {
            service.onProgressChanged();
            service.onTick();
        }
        ticks(service, 10);
        assertEquals(1L, service.currentRev());
        assertEquals(1, published.size());

        ticks(service, 10);
        assertEquals(2L, service.currentRev());
        assertEquals(2, published.size());
        assertEquals(2, participants.reads);
    }

    @Test
    void eventsWithoutSubscribersPerformNoWork() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);
        assertEquals(1, catalog.reads);
        assertEquals(1, participants.reads);

        participants.value = Optional.of(List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L),
                participant(3, "c", 1L)));
        service.onProgressChanged();
        ticks(service, 50);
        assertEquals(1L, service.currentRev());
        assertEquals(1, published.size());
        assertEquals(1, catalog.reads);
        assertEquals(1, participants.reads);

        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class, service.requestState(EPOCH, 1L));
        assertEquals(2L, result.snapshot().rev());
        assertEquals(2, published.size());
    }

    @Test
    void continuousEventsAreBoundedByCatalogTtl() {
        baseSources();
        RankingService service = newService();
        service.setSubscriberCount(1);
        service.requestState(null, 0L);

        participants.value = Optional.of(List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L),
                participant(3, "c", 1L)));
        for (int i = 1; i < RankingService.CATALOG_TTL_TICKS; i++) {
            service.onProgressChanged();
            service.onTick();
        }
        assertEquals(1L, service.currentRev());

        service.onProgressChanged();
        service.onTick();
        assertEquals(2L, service.currentRev());
        assertEquals(2, published.size());
        assertEquals(2, participants.reads);
    }

    @Test
    void silentChangeIsDetectedByDisplayTtlOnly() {
        baseSources();
        RankingService service = newService();
        service.setSubscriberCount(1);
        service.requestState(null, 0L);

        participants.value = Optional.of(List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L),
                participant(3, "c", 1L)));
        ticks(service, RankingService.DISPLAY_TTL_TICKS - 1);
        assertEquals(1L, service.currentRev());
        assertEquals(1, participants.reads);

        service.onTick();
        assertEquals(2L, service.currentRev());
        assertEquals(2, published.size());
        assertEquals(2, participants.reads);
    }

    // ---------------------------------------------------------------- reinicio

    @Test
    void restartIsolatesStateAndEpoch() {
        baseSources();
        RankingService first = newService();
        assertInstanceOf(RankingResult.Snapshot.class, first.requestState(null, 0L));

        FakeCatalogSource catalog2 = new FakeCatalogSource();
        FakeParticipantsSource participants2 = new FakeParticipantsSource();
        FakePolicySource policy2 = new FakePolicySource();
        catalog2.value = Optional.of(catalog(1L, 1L, 2L, 3L));
        participants2.value = Optional.of(defaultParticipants());
        List<ServiceSnapshot> published2 = new ArrayList<>();
        RankingService second = new RankingService(EPOCH + 1, catalog2, participants2, policy2);
        second.setSnapshotListener(published2::add);

        RankingResult.Snapshot result = assertInstanceOf(RankingResult.Snapshot.class,
                second.requestState(EPOCH, 1L));
        assertEquals(EPOCH + 1, result.snapshot().epoch());
        assertEquals(1L, result.snapshot().rev());
        assertEquals(1, published2.size());
        assertEquals(1, published.size());
    }

    // ---------------------------------------------------------------- utilidades

    private RankingService serviceAfterCatalogChangeWithParticipantsFailure() {
        baseSources();
        RankingService service = newService();
        service.requestState(null, 0L);
        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L, 4L));
        participants.value = Optional.empty();
        ticks(service, RankingService.CATALOG_MEMO_TICKS);
        return service;
    }

    private RankingService newService() {
        RankingService service = new RankingService(EPOCH, catalog, participants, policy);
        service.setSnapshotListener(published::add);
        return service;
    }

    private void baseSources() {
        catalog.value = Optional.of(catalog(1L, 1L, 2L, 3L));
        participants.value = Optional.of(defaultParticipants());
    }

    private static List<ParticipantData> defaultParticipants() {
        return List.of(participant(1, "a", 1L), participant(2, "b", 2L, 3L));
    }

    private static FtbCatalog catalog(long identity, long... questIds) {
        List<CatalogEntry> entries = new ArrayList<>();
        for (long questId : questIds) {
            entries.add(new CatalogEntry(questId, 1L, false, Set.of()));
        }
        return new FtbCatalog(identity, entries);
    }

    private static ParticipantData participant(long uuidLeast, String name, long... completed) {
        Set<Long> completedIds = new HashSet<>();
        for (long questId : completed) {
            completedIds.add(questId);
        }
        return new ParticipantData(new UUID(0, uuidLeast), ParticipantKind.PLAYER, name, 0xFFFFFF, completedIds,
                List.of());
    }

    private static void ticks(RankingService service, int count) {
        for (int i = 0; i < count; i++) {
            service.onTick();
        }
    }

    private static final class FakeCatalogSource implements RankingService.CatalogSource {

        private Optional<FtbCatalog> value = Optional.empty();
        private int reads;

        @Override
        public Optional<FtbCatalog> read() {
            reads++;
            return value;
        }
    }

    private static final class FakeParticipantsSource implements RankingService.ParticipantsSource {

        private Optional<List<ParticipantData>> value = Optional.empty();
        private int reads;

        @Override
        public Optional<List<ParticipantData>> read() {
            reads++;
            return value;
        }
    }

    private static final class FakePolicySource implements RankingService.PolicySource {

        private RankingPolicy policy = RankingPolicy.defaultPolicy();
        private long configRevision;

        @Override
        public RankingPolicy policy() {
            return policy;
        }

        @Override
        public long configRevision() {
            return configRevision;
        }
    }
}
