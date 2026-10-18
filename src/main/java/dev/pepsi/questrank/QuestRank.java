package dev.pepsi.questrank;

import dev.pepsi.questrank.config.QuestRankConfig;
import dev.pepsi.questrank.server.ServerRankingHooks;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

@Mod(QuestRank.MOD_ID)
public class QuestRank {
    public static final String MOD_ID = "questrank";
    public static final Logger LOGGER = LogUtils.getLogger();

    public QuestRank(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, QuestRankConfig.SPEC);
        ServerRankingHooks.register();
        LOGGER.info("QuestRank initialized");
    }
}
