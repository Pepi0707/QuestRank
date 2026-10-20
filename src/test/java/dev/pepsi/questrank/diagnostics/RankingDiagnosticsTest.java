package dev.pepsi.questrank.diagnostics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.net.RankingSnapshotPayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class RankingDiagnosticsTest {

    @Test
    void wrappedCodecKeepsBytesAndIndexesAndCorrelatesBothSides() {
        RankingSnapshotPayload payload = payload(42L);
        byte[] original = encode(RankingSnapshotPayload.CODEC, payload);

        List<String> lines = new ArrayList<>();
        AtomicBoolean enabled = new AtomicBoolean(true);
        StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> measured =
                RankingDiagnostics.measuredSnapshotCodec(RankingSnapshotPayload.CODEC, enabled::get, lines::add);

        FriendlyByteBuf outbound = new FriendlyByteBuf(Unpooled.buffer());
        measured.encode(outbound, payload);
        int encodedBytes = outbound.readableBytes();
        byte[] measuredBytes = bytes(outbound);
        assertArrayEquals(original, measuredBytes, "el envoltorio no altera la serialización");
        assertEquals(original.length, encodedBytes);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"event\":\"snapshot.encode\""));
        assertTrue(lines.get(0).contains("\"corr\":\"req:42\""));
        assertTrue(lines.get(0).contains("\"payload_bytes\":" + original.length));

        FriendlyByteBuf inbound = new FriendlyByteBuf(Unpooled.wrappedBuffer(original));
        RankingSnapshotPayload decoded = measured.decode(inbound);
        assertEquals(payload, decoded);
        assertEquals(original.length, inbound.readerIndex(), "el envoltorio no consume bytes adicionales");
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"event\":\"snapshot.decode\""));
        assertTrue(lines.get(1).contains("\"corr\":\"req:42\""));
        assertTrue(lines.get(1).contains("\"payload_bytes\":" + original.length));
    }

    @Test
    void disabledDiagnosticsDoNotEmitAndDoNotChangeCodecResult() {
        RankingSnapshotPayload payload = payload(7L);
        byte[] original = encode(RankingSnapshotPayload.CODEC, payload);

        List<String> lines = new ArrayList<>();
        AtomicBoolean enabled = new AtomicBoolean(false);
        StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> measured =
                RankingDiagnostics.measuredSnapshotCodec(RankingSnapshotPayload.CODEC, enabled::get, lines::add);

        FriendlyByteBuf outbound = new FriendlyByteBuf(Unpooled.buffer());
        measured.encode(outbound, payload);
        assertArrayEquals(original, bytes(outbound));
        assertTrue(lines.isEmpty(), "desactivado: no debe emitir eventos");

        FriendlyByteBuf inbound = new FriendlyByteBuf(Unpooled.wrappedBuffer(original));
        assertEquals(payload, measured.decode(inbound));
        assertTrue(lines.isEmpty(), "desactivado: no debe emitir eventos de decode");

        enabled.set(true);
        FriendlyByteBuf enabledOutbound = new FriendlyByteBuf(Unpooled.buffer());
        measured.encode(enabledOutbound, payload);
        assertEquals(1, lines.size(), "al activarse vuelve a medir");
    }

    @Test
    void pushUsesSnapshotIdentityRatherThanRequestCorrelation() {
        RankingSnapshotPayload payload = payload(0L, 7L, 3L);
        List<String> lines = new ArrayList<>();
        StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> measured =
                RankingDiagnostics.measuredSnapshotCodec(RankingSnapshotPayload.CODEC, () -> true, lines::add);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        measured.encode(buffer, payload);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"corr\":\"push:7:3\""));
        assertTrue(lines.get(0).contains("\"corr_kind\":\"push_identity\""));
        assertFalse(lines.get(0).contains("\"corr\":\"req:"));
    }

    @Test
    void summaryCarriesCountersAndExplicitSizeLimitLimitation() {
        String ranking = RankingDiagnostics.rankingSummaryLine(7L, 600L, 5L, 1L, 2L, 2L, 1L, 9L, 4L);
        String sync = RankingDiagnostics.syncSummaryLine(7L, 9L, 2L, 1L, 0L, 3L, 1);

        assertTrue(ranking.contains("\"ticks\":600"));
        assertTrue(ranking.contains("\"rebuild_attempts\":2"));
        assertTrue(ranking.contains("\"size_limit_note\":\"" + RankingDiagnostics.SIZE_LIMIT_NOTE + "\""));
        assertTrue(sync.contains("\"pushes_sent\":3"));
        assertTrue(sync.contains("\"size_limit_note\":\"" + RankingDiagnostics.SIZE_LIMIT_NOTE + "\""));
        assertTrue(RankingDiagnostics.SIZE_LIMIT_NOTE.contains("1MiB_enforcement_not_demonstrated"));
    }

    @Test
    void correlationDistinguishesRequestsAndPushes() {
        assertEquals("req:42", RankingDiagnostics.correlation(42L, 7L, 3L));
        assertEquals("push:7:3", RankingDiagnostics.correlation(0L, 7L, 3L));
    }

    private static RankingSnapshotPayload payload(long requestId) {
        return payload(requestId, 7L, 3L);
    }

    private static RankingSnapshotPayload payload(long requestId, long epoch, long rev) {
        return new RankingSnapshotPayload(
                requestId,
                epoch,
                rev,
                2L,
                10L,
                1,
                false,
                List.of(new RankingSnapshotPayload.Entry(
                        new UUID(1L, 2L),
                        0,
                        "alice",
                        0xFFFFFF,
                        4L,
                        1,
                        false,
                        List.of(new RankingSnapshotPayload.Member(new UUID(3L, 4L), "alice", true)))));
    }

    private static byte[] encode(StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> codec,
                                 RankingSnapshotPayload payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buffer, payload);
        return bytes(buffer);
    }

    private static byte[] bytes(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }
}
