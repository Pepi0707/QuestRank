package dev.pepsi.questrank.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Modelo de presentación de una fila del ranking, independiente de Minecraft.
 *
 * <p>Los campos provienen tal cual del snapshot del servidor (contrato §9.3/§9.4): el cliente no
 * recalcula puntuaciones (§1.2, §9.8); solo deriva el porcentaje con la regla normativa §6.4.
 * Se conservan todos los miembros recibidos (hasta 12) para el tooltip; las caras visibles se
 * limitan a cuatro en {@link #faces()}.
 */
public record RankingRow(
        String name,
        boolean party,
        int color,
        long completed,
        long total,
        int memberCount,
        List<MemberFace> members,
        int index) {

    public RankingRow {
        Objects.requireNonNull(name, "name");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }

    /** Miembro recibido en el snapshot (§10.4). */
    public record MemberFace(UUID id, String name, boolean online) {

        public MemberFace {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Caras visibles: como máximo cuatro (§10.4). */
    public List<MemberFace> faces() {
        return members.subList(0, Math.min(QuestRankTheme.MAX_VISIBLE_FACES, members.size()));
    }

    /** Miembros incluidos en el snapshot. */
    public int sentMembers() {
        return members.size();
    }

    /** Miembros del total que no se detallan en el snapshot (§10.4). */
    public int hiddenMembers() {
        return memberCount > members.size() ? memberCount - members.size() : 0;
    }

    /** Miembros adicionales respecto a las caras visibles, para el indicador «+N». */
    public int extraMembers() {
        int shown = Math.min(QuestRankTheme.MAX_VISIBLE_FACES, members.size());
        return memberCount > shown ? memberCount - shown : 0;
    }
}
