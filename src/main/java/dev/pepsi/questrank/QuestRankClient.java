package dev.pepsi.questrank;

import dev.pepsi.questrank.client.integration.FtbSidebarIntegration;
import dev.pepsi.questrank.client.net.ClientSyncHooks;
import dev.pepsi.questrank.client.ui.QuestRankKeys;
import dev.pepsi.questrank.client.ui.RankingScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Punto de entrada de cliente (contrato §10, §11.6). Registra la tecla sin asignar por defecto,
 * el listener del botón lateral de FTB Library y el tick de la pantalla. Solo lo carga el juego
 * cliente ({@code dist = CLIENT}); el servidor dedicado no referencia esta clase.
 */
@Mod(value = QuestRank.MOD_ID, dist = Dist.CLIENT)
public class QuestRankClient {

    public QuestRankClient(ModContainer modContainer) {
        modContainer.getEventBus().addListener(QuestRankClient::registerKeys);
        ClientSyncHooks.register();
        FtbSidebarIntegration.register();
        NeoForge.EVENT_BUS.addListener(QuestRankClient::onClientTick);
    }

    /** Registro de teclas en el bus de mods, que solo publica eventos en el cliente. */
    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(QuestRankKeys.OPEN_RANKING);
        event.register(QuestRankKeys.REFRESH_RANKING);
    }

    /**
     * Abre la pantalla de clasificación desde la tecla o el botón lateral. No exige que no haya
     * otra pantalla: el botón lateral se pulsa precisamente dentro de una pantalla de FTB Library.
     */
    public static void openRankingScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.setScreen(new RankingScreen());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        boolean pressed = false;
        while (QuestRankKeys.OPEN_RANKING.consumeClick()) {
            pressed = true;
        }
        if (pressed) {
            openRankingScreen();
        }
    }
}
