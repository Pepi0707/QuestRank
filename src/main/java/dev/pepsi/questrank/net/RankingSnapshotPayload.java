package dev.pepsi.questrank.net;

import dev.pepsi.questrank.core.model.MemberInfo;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import dev.pepsi.questrank.core.signature.DisplaySignature;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Snapshot S2C (contratos §9.3/§9.4): estado completo visible de la clasificación.
 *
 * <p>Los totales ({@code participantCount}, {@code memberCount}) son siempre reales; las banderas
 * de truncado son verdaderas si y solo si el total supera lo enviado; el orden del ranking
 * (§6.1–§6.2) se preserva. {@code requestId} es 0 en pushes y el eco de la solicitud en respuestas
 * directas (§9.2).
 */
public record RankingSnapshotPayload(
        long requestId,
        long epoch,
        long rev,
        long catalogRevision,
        long total,
        int participantCount,
        boolean participantsTruncated,
        List<Entry> entries) implements CustomPacketPayload {

    public static final Type<RankingSnapshotPayload> TYPE = new Type<>(QuestRankChannel.SNAPSHOT_ID);

    public static final StreamCodec<FriendlyByteBuf, RankingSnapshotPayload> CODEC =
            StreamCodec.of(RankingSnapshotPayload::encode, RankingSnapshotPayload::decode);

    public RankingSnapshotPayload {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** Entrada de participante enviada (§9.3). */
    public record Entry(
            UUID id,
            int kind,
            String name,
            int color,
            long completed,
            int memberCount,
            boolean membersTruncated,
            List<Member> members) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            members = List.copyOf(Objects.requireNonNull(members, "members"));
        }
    }

    /** Miembro enviado (§9.3). */
    public record Member(UUID id, String name, boolean online) {

        public Member {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Construye el payload truncado desde un ranking del núcleo (topes de §9.4 [PARAM]).
     * Conserva el orden del ranking y los totales reales; no trunca en F4.
     */
    public static RankingSnapshotPayload of(long requestId, long epoch, long rev, long catalogRevision, Ranking ranking) {
        Objects.requireNonNull(ranking, "ranking");
        List<RankingEntry> source = ranking.entries();
        int sentCount = Math.min(source.size(), QuestRankChannel.MAX_PARTICIPANTS);
        List<Entry> entries = new ArrayList<>(sentCount);
        for (int i = 0; i < sentCount; i++) {
            RankingEntry entry = source.get(i);
            List<MemberInfo> memberSource = entry.members();
            int sentMembers = Math.min(memberSource.size(), QuestRankChannel.MAX_MEMBERS);
            List<Member> members = new ArrayList<>(sentMembers);
            for (int m = 0; m < sentMembers; m++) {
                MemberInfo member = memberSource.get(m);
                members.add(new Member(member.id(),
                        QuestRankChannel.truncate(member.name(), QuestRankChannel.MAX_MEMBER_NAME),
                        member.online()));
            }
            entries.add(new Entry(
                    entry.id(),
                    DisplaySignature.kindByte(entry.kind()),
                    QuestRankChannel.truncate(entry.name(), QuestRankChannel.MAX_PARTICIPANT_NAME),
                    entry.color(),
                    entry.completed(),
                    memberSource.size(),
                    memberSource.size() > sentMembers,
                    List.copyOf(members)));
        }
        return new RankingSnapshotPayload(requestId, epoch, rev, catalogRevision, ranking.total(),
                source.size(), source.size() > sentCount, List.copyOf(entries));
    }

    private static void encode(FriendlyByteBuf buf, RankingSnapshotPayload payload) {
        buf.writeVarLong(payload.requestId());
        buf.writeVarLong(payload.epoch());
        buf.writeVarLong(payload.rev());
        buf.writeVarLong(payload.catalogRevision());
        buf.writeVarLong(payload.total());
        buf.writeVarInt(payload.participantCount());
        buf.writeBoolean(payload.participantsTruncated());
        buf.writeVarInt(payload.entries().size());
        for (Entry entry : payload.entries()) {
            UUIDUtil.STREAM_CODEC.encode(buf, entry.id());
            buf.writeByte(entry.kind());
            buf.writeUtf(entry.name(), QuestRankChannel.MAX_PARTICIPANT_NAME);
            buf.writeVarInt(entry.color());
            buf.writeVarLong(entry.completed());
            buf.writeVarInt(entry.memberCount());
            buf.writeBoolean(entry.membersTruncated());
            buf.writeVarInt(entry.members().size());
            for (Member member : entry.members()) {
                UUIDUtil.STREAM_CODEC.encode(buf, member.id());
                buf.writeUtf(member.name(), QuestRankChannel.MAX_MEMBER_NAME);
                buf.writeBoolean(member.online());
            }
        }
    }

    private static RankingSnapshotPayload decode(FriendlyByteBuf buf) {
        long requestId = buf.readVarLong();
        long epoch = buf.readVarLong();
        long rev = buf.readVarLong();
        long catalogRevision = buf.readVarLong();
        long total = buf.readVarLong();
        int participantCount = buf.readVarInt();
        boolean participantsTruncated = buf.readBoolean();
        int entryCount = buf.readVarInt();
        if (entryCount < 0 || entryCount > QuestRankChannel.MAX_PARTICIPANTS) {
            throw new DecoderException("invalid participant entries: " + entryCount);
        }
        if (participantCount < entryCount) {
            throw new DecoderException("participantCount " + participantCount + " < sent entries " + entryCount);
        }
        List<Entry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
            int kind = buf.readUnsignedByte();
            String name = buf.readUtf(QuestRankChannel.MAX_PARTICIPANT_NAME);
            int color = buf.readVarInt();
            long completed = buf.readVarLong();
            int memberCount = buf.readVarInt();
            boolean membersTruncated = buf.readBoolean();
            int memberListSize = buf.readVarInt();
            if (memberListSize < 0 || memberListSize > QuestRankChannel.MAX_MEMBERS) {
                throw new DecoderException("invalid sent members: " + memberListSize);
            }
            if (memberCount < memberListSize) {
                throw new DecoderException("memberCount " + memberCount + " < sent members " + memberListSize);
            }
            List<Member> members = new ArrayList<>(memberListSize);
            for (int m = 0; m < memberListSize; m++) {
                UUID memberId = UUIDUtil.STREAM_CODEC.decode(buf);
                String memberName = buf.readUtf(QuestRankChannel.MAX_MEMBER_NAME);
                boolean online = buf.readBoolean();
                members.add(new Member(memberId, memberName, online));
            }
            entries.add(new Entry(id, kind, name, color, completed, memberCount, membersTruncated, List.copyOf(members)));
        }
        return new RankingSnapshotPayload(requestId, epoch, rev, catalogRevision, total,
                participantCount, participantsTruncated, List.copyOf(entries));
    }

    @Override
    public Type<RankingSnapshotPayload> type() {
        return TYPE;
    }
}
