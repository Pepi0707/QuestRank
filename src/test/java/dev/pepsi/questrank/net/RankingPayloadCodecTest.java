package dev.pepsi.questrank.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.model.MemberInfo;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

/** Round-trip de los cinco mensajes (§9.1–§9.4) y límites estructurales del codec. */
class RankingPayloadCodecTest {

    private static final long EPOCH = 1234567890123L;

    private static <T> T roundTrip(StreamCodec<? super FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        return codec.decode(buf);
    }

    @Test
    void requestRoundTripWithAndWithoutEpoch() {
        RankingRequestPayload withEpoch = roundTrip(RankingRequestPayload.CODEC, RankingRequestPayload.of(7L, EPOCH, 3L));
        assertEquals(7L, withEpoch.requestId());
        assertTrue(withEpoch.hasKnownEpoch());
        assertEquals(EPOCH, withEpoch.knownEpoch());
        assertEquals(3L, withEpoch.knownRev());
        assertEquals(Long.valueOf(EPOCH), withEpoch.knownEpochOrNull());

        RankingRequestPayload withoutEpoch = roundTrip(RankingRequestPayload.CODEC, RankingRequestPayload.of(8L, null, 0L));
        assertEquals(8L, withoutEpoch.requestId());
        assertFalse(withoutEpoch.hasKnownEpoch());
        assertNull(withoutEpoch.knownEpochOrNull());
    }

    @Test
    void requestValidationRanges() {
        assertTrue(RankingRequestPayload.of(1L, null, 0L).isValid());
        assertTrue(RankingRequestPayload.of(Long.MAX_VALUE, EPOCH, Long.MAX_VALUE).isValid());
        assertFalse(RankingRequestPayload.of(0L, null, 0L).isValid());
        assertFalse(RankingRequestPayload.of(-5L, null, 0L).isValid());
        assertFalse(RankingRequestPayload.of(1L, null, -1L).isValid());
    }

    @Test
    void subscribeRoundTrip() {
        assertTrue(roundTrip(RankingSubscribePayload.CODEC, new RankingSubscribePayload(true)).subscribed());
        assertFalse(roundTrip(RankingSubscribePayload.CODEC, new RankingSubscribePayload(false)).subscribed());
    }

    @Test
    void unchangedRoundTripCarriesEpochAndRev() {
        RankingUnchangedPayload payload =
                roundTrip(RankingUnchangedPayload.CODEC, new RankingUnchangedPayload(42L, EPOCH, 9L));
        assertEquals(42L, payload.requestId());
        assertEquals(EPOCH, payload.epoch());
        assertEquals(9L, payload.rev());
    }

    @Test
    void errorRoundTripHasStableCodes() {
        RankingErrorPayload payload = roundTrip(RankingErrorPayload.CODEC,
                new RankingErrorPayload(11L, RankingErrorPayload.CODE_PARTICIPANTS_UNAVAILABLE));
        assertEquals(11L, payload.requestId());
        assertEquals(1, payload.code());
        assertEquals(0, RankingErrorPayload.CODE_CATALOG_UNAVAILABLE);
    }

    @Test
    void snapshotRoundTripIsLossless() {
        RankingSnapshotPayload payload = new RankingSnapshotPayload(5L, EPOCH, 2L, 4L, 300L, 2, false, List.of(
                new RankingSnapshotPayload.Entry(new UUID(1, 1), 0, "team", 0xABCDEF, 5L, 2, true, List.of(
                        new RankingSnapshotPayload.Member(new UUID(2, 2), "alice", true),
                        new RankingSnapshotPayload.Member(new UUID(3, 3), "bob", false))),
                new RankingSnapshotPayload.Entry(new UUID(4, 4), 1, "solo", 0x111111, 0L, 0, false, List.of())));
        assertEquals(payload, roundTrip(RankingSnapshotPayload.CODEC, payload));
    }

    @Test
    void snapshotBuilderTruncatesKeepingTotalsAndOrder() {
        RankingSnapshotPayload payload =
                RankingSnapshotPayload.of(1L, EPOCH, 1L, 1L, ranking(600, 15, "n".repeat(80), "m".repeat(30), 700L));
        assertEquals(600, payload.participantCount());
        assertEquals(512, payload.entries().size());
        assertTrue(payload.participantsTruncated());
        RankingSnapshotPayload.Entry first = payload.entries().get(0);
        assertEquals(15, first.memberCount());
        assertEquals(12, first.members().size());
        assertTrue(first.membersTruncated());
        assertEquals(64, first.name().length());
        assertEquals(16, first.members().get(0).name().length());
        RankingSnapshotPayload.Entry second = payload.entries().get(1);
        assertEquals(1, second.kind(), "el orden del ranking se preserva");
    }

    @Test
    void snapshotBuilderHandlesEmptyCatalog() {
        RankingSnapshotPayload payload = RankingSnapshotPayload.of(0L, EPOCH, 1L, 1L, new Ranking(0L, List.of()));
        assertEquals(0, payload.entries().size());
        assertEquals(0, payload.participantCount());
        assertFalse(payload.participantsTruncated());
    }

    @Test
    void truncateKeepsSurrogatePairsIntact() {
        String emoji = "\uD83D\uDE00";
        String value = "x".repeat(63) + emoji;
        assertEquals("x".repeat(63), QuestRankChannel.truncate(value, 64));
        assertEquals("", QuestRankChannel.truncate(value, 0));
        assertEquals(value, QuestRankChannel.truncate(value, 65));
        String pairs = emoji.repeat(10);
        assertEquals(18, QuestRankChannel.truncate(pairs, 19).length());
        assertEquals(pairs, QuestRankChannel.truncate(pairs, 20));
    }

    @Test
    void snapshotDecodeRejectsOutOfBoundsStructure() {
        List<RankingSnapshotPayload.Entry> tooMany = new ArrayList<>();
        for (int i = 0; i < QuestRankChannel.MAX_PARTICIPANTS + 1; i++) {
            tooMany.add(new RankingSnapshotPayload.Entry(new UUID(0, i + 1), 0, "x", 0, 0L, 0, false, List.of()));
        }
        RankingSnapshotPayload badEntries =
                new RankingSnapshotPayload(0L, 1L, 1L, 1L, 0L, tooMany.size(), false, tooMany);
        assertThrows(DecoderException.class, () -> roundTrip(RankingSnapshotPayload.CODEC, badEntries));

        RankingSnapshotPayload.Entry badMemberCount = new RankingSnapshotPayload.Entry(new UUID(0, 1), 0, "x", 0, 0L, 0,
                false, List.of(new RankingSnapshotPayload.Member(new UUID(0, 2), "m", true)));
        RankingSnapshotPayload badPayload =
                new RankingSnapshotPayload(0L, 1L, 1L, 1L, 1L, 1, false, List.of(badMemberCount));
        assertThrows(DecoderException.class, () -> roundTrip(RankingSnapshotPayload.CODEC, badPayload));

        RankingSnapshotPayload.Entry entry =
                new RankingSnapshotPayload.Entry(new UUID(0, 1), 0, "x", 0, 0L, 0, false, List.of());
        RankingSnapshotPayload badCount =
                new RankingSnapshotPayload(0L, 1L, 1L, 1L, 1L, 0, false, List.of(entry));
        assertThrows(DecoderException.class, () -> roundTrip(RankingSnapshotPayload.CODEC, badCount));
    }

    private static Ranking ranking(int participants, int membersPer, String participantName, String memberName, long total) {
        List<RankingEntry> entries = new ArrayList<>(participants);
        for (int i = 0; i < participants; i++) {
            List<MemberInfo> members = new ArrayList<>(membersPer);
            for (int m = 0; m < membersPer; m++) {
                members.add(new MemberInfo(new UUID(0, i * 100L + m + 1), memberName, (m & 1) == 0));
            }
            entries.add(new RankingEntry(new UUID(0, i + 1),
                    i % 2 == 0 ? ParticipantKind.PARTY : ParticipantKind.PLAYER,
                    participantName, 0xFFFFFF, 0L, total, members));
        }
        return new Ranking(total, entries);
    }
}
