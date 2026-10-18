package dev.pepsi.questrank;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

@Mod(QuestRank.MOD_ID)
public class QuestRank {
    public static final String MOD_ID = "questrank";
    public static final Logger LOGGER = LogUtils.getLogger();

    public QuestRank() {
        LOGGER.info("QuestRank initialized");
    }
}
