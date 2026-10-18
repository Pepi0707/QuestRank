package dev.pepsi.questrank.core.signature;

import dev.pepsi.questrank.core.model.MemberInfo;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.util.Fnv1a64;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Firma de presentación {@code S_display} (contrato §8.3), exclusivamente para invalidación en
 * memoria. El resultado no depende del orden de entrada y considera <em>todos</em> los miembros
 * del contrato §8.3, aunque una futura interfaz limite los que muestra (§9.4).
 *
 * <p>Codificación canónica:
 * <pre>
 *   putByte(0x02)                 // prefijo de dominio (display)
 *   putLong(catalogRevision)
 *   putInt(count)                 // número de participantes
 *   por participante (orden total por uuid, kindByte, name, color, completed y miembros canónicos):
 *     putUuid(id); putByte(kindByte); putString(name); putInt(color); putLong(completed);
 *     putInt(memberCount); por miembro (orden por uuid, name, online):
 *       putUuid(id); putString(name); putBoolean(online)
 * </pre>
 *
 * <p>Mapeo explícito de {@link ParticipantKind}: {@code PARTY=0}, {@code PLAYER=1}. El total N de
 * las entradas no se firma aquí: los cambios de catálogo llegan vía {@code catalogRevision}
 * (§8.4). Los miembros duplicados se conservan y se ordenan por tupla completa.
 */
public final class DisplaySignature {

    private static final int DOMAIN_TAG = 0x02;

    public static final int KIND_PARTY = 0;
    public static final int KIND_PLAYER = 1;

    private DisplaySignature() {
    }

    /** Mapeo explícito y estable de la clase de participante a su byte de firma. */
    public static int kindByte(ParticipantKind kind) {
        return switch (kind) {
            case PARTY -> KIND_PARTY;
            case PLAYER -> KIND_PLAYER;
        };
    }

    public static long compute(long catalogRevision, Ranking ranking) {
        Objects.requireNonNull(ranking, "ranking");
        List<CanonicalEntry> canonical = new ArrayList<>(ranking.entries().size());
        for (RankingEntry entry : ranking.entries()) {
            canonical.add(new CanonicalEntry(entry, kindByte(entry.kind()), sortedMembers(entry.members())));
        }
        canonical.sort(DisplaySignature::compareEntries);
        Fnv1a64 hash = new Fnv1a64();
        hash.putByte(DOMAIN_TAG);
        hash.putLong(catalogRevision);
        hash.putInt(canonical.size());
        for (CanonicalEntry canonicalEntry : canonical) {
            RankingEntry entry = canonicalEntry.entry();
            hash.putUuid(entry.id());
            hash.putByte(canonicalEntry.kindByte());
            hash.putString(entry.name());
            hash.putInt(entry.color());
            hash.putLong(entry.completed());
            hash.putInt(canonicalEntry.members().size());
            for (MemberInfo member : canonicalEntry.members()) {
                hash.putUuid(member.id());
                hash.putString(member.name());
                hash.putBoolean(member.online());
            }
        }
        return hash.value();
    }

    private record CanonicalEntry(RankingEntry entry, int kindByte, List<MemberInfo> members) {
    }

    private static int compareEntries(CanonicalEntry a, CanonicalEntry b) {
        int result = a.entry().id().compareTo(b.entry().id());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.kindByte(), b.kindByte());
        if (result != 0) {
            return result;
        }
        result = a.entry().name().compareTo(b.entry().name());
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.entry().color(), b.entry().color());
        if (result != 0) {
            return result;
        }
        result = Long.compare(a.entry().completed(), b.entry().completed());
        if (result != 0) {
            return result;
        }
        return compareMemberLists(a.members(), b.members());
    }

    private static int compareMemberLists(List<MemberInfo> a, List<MemberInfo> b) {
        int shared = Math.min(a.size(), b.size());
        for (int i = 0; i < shared; i++) {
            int result = compareMembers(a.get(i), b.get(i));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareMembers(MemberInfo a, MemberInfo b) {
        int result = a.id().compareTo(b.id());
        if (result != 0) {
            return result;
        }
        result = a.name().compareTo(b.name());
        if (result != 0) {
            return result;
        }
        return Boolean.compare(a.online(), b.online());
    }

    private static List<MemberInfo> sortedMembers(List<MemberInfo> members) {
        List<MemberInfo> sorted = new ArrayList<>(members);
        sorted.sort(DisplaySignature::compareMembers);
        return sorted;
    }
}
