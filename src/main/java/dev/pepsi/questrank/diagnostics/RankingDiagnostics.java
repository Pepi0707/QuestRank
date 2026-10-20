package dev.pepsi.questrank.diagnostics;

import dev.pepsi.questrank.net.RankingSnapshotPayload;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnóstico de integración de QuestRank (G0).
 *
 * <p>Los eventos detallados se emiten solo si el logger está en {@code DEBUG}. El envoltorio del
 * codec delega exactamente una vez y mide los índices del buffer real; no copia, no vuelve a
 * serializar y no retiene buffers. {@code payload_bytes} es el cuerpo codificado del payload,
 * después del identificador de tipo y antes de compresión/transporte.
 *
 * <p><strong>Limitación de tamaño:</strong> el valor de 1 MiB de la plataforma no se ha verificado
 * como aplicado por NeoForge a un payload registrado conocido; en la ruta examinada ese límite se
 * usa para el fallback de payloads desconocidos. Por tanto, {@code payload_bytes} es una medición
 * diagnóstica y no demuestra que la plataforma rechace payloads superiores a 1 MiB. No se declara
 * cumplimiento de ese límite sin una decisión normativa explícita.
 */
public final class RankingDiagnostics {

    /** Nota que acompaña a los resúmenes para no convertir la medición en una garantía. */
    public static final String SIZE_LIMIT_NOTE =
            "payload_bytes_body_pre_compression;1MiB_enforcement_not_demonstrated_for_registered_payload";

    private static final Logger LOGGER = LoggerFactory.getLogger("QuestRankDiag");

    private RankingDiagnostics() {
    }

    /** {@code true} si los eventos detallados están habilitados. */
    public static boolean enabled() {
        return LOGGER.isDebugEnabled();
    }

    /** Codec de producción: mide solo cuando {@link #enabled()} es verdadero. */
    public static StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> measuredSnapshotCodec(
            StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> delegate) {
        return measuredSnapshotCodec(delegate, RankingDiagnostics::enabled, RankingDiagnostics::debug);
    }

    /**
     * Variante inyectable para pruebas: permite comprobar activación/desactivación sin depender del
     * backend de logging. La producción usa {@link #measuredSnapshotCodec(StreamCodec)}.
     */
    static StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> measuredSnapshotCodec(
            StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> delegate,
            BooleanSupplier enabled,
            Consumer<String> sink) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(sink, "sink");
        return new StreamCodec<FriendlyByteBuf, RankingSnapshotPayload>() {
            @Override
            public RankingSnapshotPayload decode(FriendlyByteBuf buffer) {
                if (!enabled.getAsBoolean()) {
                    return delegate.decode(buffer);
                }
                int before = buffer.readerIndex();
                long start = System.nanoTime();
                RankingSnapshotPayload value = delegate.decode(buffer);
                long elapsed = System.nanoTime() - start;
                int bytes = buffer.readerIndex() - before;
                sink.accept(snapshotEvent("snapshot.decode", value, bytes, elapsed));
                return value;
            }

            @Override
            public void encode(FriendlyByteBuf buffer, RankingSnapshotPayload value) {
                if (!enabled.getAsBoolean()) {
                    delegate.encode(buffer, value);
                    return;
                }
                int before = buffer.writerIndex();
                long start = System.nanoTime();
                delegate.encode(buffer, value);
                long elapsed = System.nanoTime() - start;
                int bytes = buffer.writerIndex() - before;
                sink.accept(snapshotEvent("snapshot.encode", value, bytes, elapsed));
            }
        };
    }

    public static void requestPipeline(String result, long durationNs) {
        if (!enabled()) {
            return;
        }
        debug(json("request.pipeline", "\"result\":\"" + result + "\",\"duration_ns\":" + durationNs));
    }

    public static void catalogValidate(boolean available, boolean changed, long catalogRevision,
                                       long readNs, long fingerprintNs, long universeNs) {
        if (!enabled()) {
            return;
        }
        debug(json("catalog.validate",
                "\"available\":" + available
                        + ",\"changed\":" + changed
                        + ",\"catalog_revision\":" + catalogRevision
                        + ",\"read_ns\":" + readNs
                        + ",\"fingerprint_ns\":" + fingerprintNs
                        + ",\"universe_ns\":" + universeNs));
    }

    public static void rebuild(boolean success, int participants, long total, long rev,
                               long catalogRevision, boolean published, long participantsReadNs,
                               long scoreNs, long signatureNs, String cause) {
        if (!enabled()) {
            return;
        }
        debug(json("display.rebuild",
                "\"success\":" + success
                        + ",\"participants\":" + participants
                        + ",\"total\":" + total
                        + ",\"rev\":" + rev
                        + ",\"catalog_revision\":" + catalogRevision
                        + ",\"published\":" + published
                        + ",\"participants_read_ns\":" + participantsReadNs
                        + ",\"score_ns\":" + scoreNs
                        + ",\"signature_ns\":" + signatureNs
                        + ",\"cause\":\"" + cause + "\""));
    }

    public static void directResponse(long requestId, long epoch, long rev, String kind, long durationNs) {
        if (!enabled()) {
            return;
        }
        debug(json("response.direct",
                "\"corr\":\"req:" + requestId + "\""
                        + ",\"request_id\":" + requestId
                        + ",\"epoch\":" + epoch
                        + ",\"rev\":" + rev
                        + ",\"kind\":\"" + kind + "\""
                        + ",\"duration_ns\":" + durationNs));
    }

    public static void push(UUID playerId, long epoch, long rev, int participants, long sendNs) {
        if (!enabled()) {
            return;
        }
        debug(json("push.sent",
                "\"corr\":\"push:" + epoch + ":" + rev + "\""
                        + ",\"corr_kind\":\"push_identity\""
                        + ",\"player\":\"" + playerId + "\""
                        + ",\"epoch\":" + epoch
                        + ",\"rev\":" + rev
                        + ",\"participants\":" + participants
                        + ",\"send_ns\":" + sendNs));
    }

    public static void clientReceived(RankingSnapshotPayload payload, Long beforeEpoch, long beforeRev,
                                      Long afterEpoch, long afterRev, boolean sessionValid, String outcome,
                                      long durationNs) {
        if (!enabled()) {
            return;
        }
        debug(json("client.received",
                "\"corr\":\"" + correlation(payload.requestId(), payload.epoch(), payload.rev()) + "\""
                        + ",\"corr_kind\":\"" + correlationKind(payload.requestId()) + "\""
                        + ",\"request_id\":" + payload.requestId()
                        + ",\"payload_epoch\":" + payload.epoch()
                        + ",\"payload_rev\":" + payload.rev()
                        + ",\"known_epoch_before\":" + (beforeEpoch == null ? "null" : beforeEpoch)
                        + ",\"known_rev_before\":" + beforeRev
                        + ",\"known_epoch_after\":" + (afterEpoch == null ? "null" : afterEpoch)
                        + ",\"known_rev_after\":" + afterRev
                        + ",\"outcome\":\"" + outcome + "\""
                        + ",\"applied\":" + "APPLIED".equals(outcome)
                        + ",\"session_valid\":" + sessionValid
                        + ",\"duration_ns\":" + durationNs));
    }

    public static void uiModelBuilt(RankingSnapshotPayload payload, int rows, long durationNs) {
        if (!enabled()) {
            return;
        }
        debug(json("ui.model_build",
                "\"epoch\":" + payload.epoch()
                        + ",\"rev\":" + payload.rev()
                        + ",\"rows\":" + rows
                        + ",\"duration_ns\":" + durationNs));
    }

    public static void uiFirstPaint(long epoch, long rev, int rows, int visibleRows, long durationNs) {
        if (!enabled()) {
            return;
        }
        debug(json("ui.first_paint",
                "\"epoch\":" + epoch
                        + ",\"rev\":" + rev
                        + ",\"rows\":" + rows
                        + ",\"visible_rows\":" + visibleRows
                        + ",\"duration_ns\":" + durationNs));
    }

    public static String rankingSummaryLine(long epoch, long ticks, long catalogCycles,
                                            long displayCycles, long rebuildAttempts, long rebuildSuccess,
                                            long rebuildPublished, long rev, long catalogRevision) {
        return json("summary.ranking",
                "\"epoch\":" + epoch
                        + ",\"ticks\":" + ticks
                        + ",\"catalog_cycles\":" + catalogCycles
                        + ",\"display_cycles\":" + displayCycles
                        + ",\"rebuild_attempts\":" + rebuildAttempts
                        + ",\"rebuild_success\":" + rebuildSuccess
                        + ",\"rebuild_published\":" + rebuildPublished
                        + ",\"rev\":" + rev
                        + ",\"catalog_revision\":" + catalogRevision
                        + ",\"size_limit_note\":\"" + SIZE_LIMIT_NOTE + "\"");
    }

    public static String syncSummaryLine(long epoch, long rev, long directSnapshots, long unchanged,
                                         long errors, long pushesSent, int subscribers) {
        return json("summary.sync",
                "\"epoch\":" + epoch
                        + ",\"rev\":" + rev
                        + ",\"direct_snapshots\":" + directSnapshots
                        + ",\"unchanged\":" + unchanged
                        + ",\"errors\":" + errors
                        + ",\"pushes_sent\":" + pushesSent
                        + ",\"send_semantics\":\"attempted\""
                        + ",\"subscribers\":" + subscribers
                        + ",\"size_limit_note\":\"" + SIZE_LIMIT_NOTE + "\"");
    }

    public static void logSummary(String line) {
        LOGGER.info(line);
    }

    static String correlation(long requestId, long epoch, long rev) {
        return requestId != 0L ? "req:" + requestId : "push:" + epoch + ":" + rev;
    }

    private static String correlationKind(long requestId) {
        return requestId != 0L ? "request" : "push_identity";
    }

    private static String snapshotEvent(String event, RankingSnapshotPayload payload, int bytes, long durationNs) {
        long memberCountSum = 0L;
        int membersTruncated = 0;
        for (RankingSnapshotPayload.Entry entry : payload.entries()) {
            memberCountSum += entry.memberCount();
            if (entry.membersTruncated()) {
                membersTruncated++;
            }
        }
        return json(event,
                "\"side\":\"" + (event.endsWith(".encode") ? "server" : "client") + "\""
                        + ",\"corr\":\"" + correlation(payload.requestId(), payload.epoch(), payload.rev()) + "\""
                        + ",\"corr_kind\":\"" + correlationKind(payload.requestId()) + "\""
                        + ",\"request_id\":" + payload.requestId()
                        + ",\"epoch\":" + payload.epoch()
                        + ",\"rev\":" + payload.rev()
                        + ",\"catalog_revision\":" + payload.catalogRevision()
                        + ",\"total\":" + payload.total()
                        + ",\"participant_count\":" + payload.participantCount()
                        + ",\"entries\":" + payload.entries().size()
                        + ",\"participants_truncated\":" + payload.participantsTruncated()
                        + ",\"member_count_sum\":" + memberCountSum
                        + ",\"members_truncated_count\":" + membersTruncated
                        + ",\"payload_bytes\":" + bytes
                        + ",\"duration_ns\":" + durationNs);
    }

    private static String json(String event, String fields) {
        return "{\"schema\":1,\"event\":\"" + event + "\"," + fields
                + ",\"t_wall_ms\":" + System.currentTimeMillis() + "}";
    }

    private static void debug(String line) {
        LOGGER.debug(line);
    }
}
