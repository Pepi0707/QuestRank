package dev.pepsi.questrank.integration.ftb;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import dev.pepsi.questrank.core.model.MemberInfo;
import dev.pepsi.questrank.core.model.ParticipantData;
import dev.pepsi.questrank.core.model.ParticipantKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Adaptador de lectura de participantes y progreso (contrato §2, §3, §5, §8.11).
 *
 * <p>Solo lectura, sin caché, sin listeners y sin estado global; requiere el hilo del servidor
 * (§12.1). {@link Optional#empty()} = datos no disponibles (servidor o gestor de FTB Teams no
 * iniciados, o catálogo cargándose); presente = lista coherente (posiblemente vacía). La ausencia
 * puntual de {@code TeamData} para un participante concreto es una ausencia válida de datos
 * (§8.11: cero progreso), no un error; los equipos internos de servidor (§3.5) y los grupos sin
 * miembros (§3.6) se excluyen por regla del contrato, no arbitrariamente.
 */
public final class FtbParticipantsReader {

    private FtbParticipantsReader() {
    }

    /**
     * Lee los participantes vivos con sus miembros completos y su progreso.
     *
     * @return vacío si los datos no están disponibles; si lo están, la lista de participantes
     * @throws IllegalStateException si se invoca fuera del hilo del servidor o si FTB presenta
     *                               una anomalía estructural
     */
    public static Optional<List<ParticipantData>> read() {
        FTBTeamsAPI.API api = FTBTeamsAPI.api();
        if (!api.isManagerLoaded()) {
            return Optional.empty();
        }
        TeamManager manager = api.getManager();
        FtbThreading.requireServerThread(manager.getServer());

        Optional<ServerQuestFile> maybeFile = ServerQuestFile.getInstance();
        if (maybeFile.isEmpty()) {
            return Optional.empty();
        }
        ServerQuestFile file = maybeFile.get();
        if (file.isLoading()) {
            return Optional.empty();
        }
        List<Quest> quests = new ArrayList<>();
        file.forAllQuests(quests::add);

        Map<UUID, Team> effectiveTeams = new TreeMap<>();
        for (Map.Entry<UUID, ? extends Team> known : manager.getKnownPlayerTeams().entrySet()) {
            Team personal = Objects.requireNonNull(known.getValue(), "known player team");
            Team effective = manager.getTeamForPlayerID(known.getKey()).orElse(personal);
            if (effective.isServerTeam()) {
                continue; // contrato §3.5: equipos internos de servidor excluidos
            }
            effectiveTeams.putIfAbsent(effective.getId(), effective);
        }

        List<ParticipantData> participants = new ArrayList<>(effectiveTeams.size());
        for (Team team : effectiveTeams.values()) {
            Set<UUID> memberIds = new TreeSet<>(team.getMembers());
            if (memberIds.isEmpty() && team.isPlayerTeam()) {
                memberIds.add(team.getId()); // id de PlayerTeam = UUID del jugador (§2.1)
            }
            if (memberIds.isEmpty()) {
                continue; // contrato §3.6: grupos sin miembros excluidos
            }
            participants.add(toParticipant(team, memberIds, quests, file, manager));
        }
        return Optional.of(List.copyOf(participants));
    }

    private static ParticipantData toParticipant(Team team, Set<UUID> memberIds, List<Quest> quests,
                                                 ServerQuestFile file, TeamManager manager) {
        ParticipantKind kind = team.isPartyTeam() ? ParticipantKind.PARTY : ParticipantKind.PLAYER;
        Color4I color = team.getProperty(TeamProperties.COLOR);
        if (color == null) {
            throw new IllegalStateException("team " + team.getId() + " has no color property (structural anomaly)");
        }
        Set<UUID> onlineIds = new HashSet<>();
        for (ServerPlayer player : team.getOnlineMembers()) {
            onlineIds.add(player.getUUID());
        }
        List<MemberInfo> members = new ArrayList<>(memberIds.size());
        for (UUID memberId : memberIds) {
            String memberName = manager.getPlayerTeamForPlayerID(memberId)
                    .map(memberTeam -> memberTeam.getName().getString())
                    .orElse("");
            members.add(new MemberInfo(memberId, memberName, onlineIds.contains(memberId)));
        }
        return new ParticipantData(team.getId(), kind, team.getName().getString(), color.rgb(),
                completedQuestIds(file, team, quests), members);
    }

    private static Set<Long> completedQuestIds(ServerQuestFile file, Team team, List<Quest> quests) {
        TeamData teamData = file.getNullableTeamData(team.getId());
        if (teamData == null) {
            return Set.of(); // §8.11: TeamData ausente ⇒ cero progreso
        }
        Set<Long> completed = new HashSet<>();
        for (Quest quest : quests) {
            if (teamData.isCompleted(quest) || teamData.getCompletionCount(quest) > 0) {
                completed.add(quest.getId());
            }
        }
        return completed;
    }
}
