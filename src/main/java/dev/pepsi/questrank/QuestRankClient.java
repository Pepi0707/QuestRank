package dev.pepsi.questrank;

import dev.pepsi.questrank.client.net.ClientSyncHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = QuestRank.MOD_ID, dist = Dist.CLIENT)
public class QuestRankClient {
    public QuestRankClient() {
        ClientSyncHooks.register();
    }
}
