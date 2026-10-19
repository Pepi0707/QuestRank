package dev.pepsi.questrank.client.ui;

import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.score.ScoreMath;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Conversión pura de {@link RankingSnapshotPayload} (protocolo §9.3) al modelo visual.
 *
 * <p>No recalcula puntuaciones: copia C, N y metadatos del servidor y aplica la fórmula exacta de
 * porcentaje del núcleo (§6.4). Los nombres ya llegan truncados por el protocolo (§9.4).
 */
public final class RankingViewModel {

    private final RankingSnapshotPayload snapshot;
    private final List<RankingRow> rows;

    private RankingViewModel(RankingSnapshotPayload snapshot, List<RankingRow> rows) {
        this.snapshot = snapshot;
        this.rows = rows;
    }

    public static RankingViewModel of(RankingSnapshotPayload snapshot) {
        List<RankingRow> rows = new ArrayList<>(snapshot.entries().size());
        for (int i = 0; i < snapshot.entries().size(); i++) {
            RankingSnapshotPayload.Entry entry = snapshot.entries().get(i);
            List<RankingRow.MemberFace> members = new ArrayList<>(entry.members().size());
            for (RankingSnapshotPayload.Member member : entry.members()) {
                members.add(new RankingRow.MemberFace(member.id(), member.name(), member.online()));
            }
            rows.add(new RankingRow(entry.name(), entry.kind() == 0, entry.color(), entry.completed(),
                    snapshot.total(), entry.memberCount(), List.copyOf(members), i));
        }
        return new RankingViewModel(snapshot, List.copyOf(rows));
    }

    public RankingSnapshotPayload snapshot() {
        return snapshot;
    }

    public List<RankingRow> rows() {
        return rows;
    }

    public long total() {
        return snapshot.total();
    }

    public int participantCount() {
        return snapshot.participantCount();
    }

    public boolean participantsTruncated() {
        return snapshot.participantsTruncated();
    }

    public long catalogRevision() {
        return snapshot.catalogRevision();
    }

    /** Texto «C / N» (§2.6). */
    public static String scoreText(long completed, long total) {
        return completed + " / " + total;
    }

    /** Validez del par C/N según el dominio del núcleo (§6.4, {@link RankingEntry}). */
    public static boolean isScoreValid(long completed, long total) {
        return completed >= 0 && total >= 0 && completed <= total
                && total <= RankingEntry.MAX_SAFE_TOTAL;
    }

    /** Porcentaje normativo (§6.4); vacío cuando N = 0 o el par C/N no es válido (§6.5). */
    public static OptionalInt percentage(long completed, long total) {
        return isScoreValid(completed, total) ? ScoreMath.displayed(completed, total) : OptionalInt.empty();
    }

    /**
     * Longitud entera de la barra para un ancho dado: 0 si el par C/N no es válido, si el ancho
     * no es positivo o si N = 0. Para el dominio válido el producto no desborda.
     */
    public static int barFill(long completed, long total, int width) {
        if (width <= 0 || total <= 0 || !isScoreValid(completed, total)) {
            return 0;
        }
        return (int) Math.min(width, completed * (long) width / total);
    }

    /** Texto de porcentaje con «%», o «—» cuando N = 0. */
    public static String percentageText(long completed, long total) {
        OptionalInt value = percentage(completed, total);
        return value.isPresent() ? value.getAsInt() + "%" : "\u2014";
    }

    /** Clase del participante para etiquetas discretas (§10.4). */
    public static String kindKey(boolean party) {
        return party ? "questrank.kind.party" : "questrank.kind.player";
    }

    /** Nombre en minúsculas para búsquedas/orden de presentación (Locale.ROOT, §6.2). */
    public static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
