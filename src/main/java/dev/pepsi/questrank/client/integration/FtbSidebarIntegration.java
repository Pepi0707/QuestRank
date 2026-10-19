package dev.pepsi.questrank.client.integration;

import dev.architectury.event.EventResult;
import dev.ftb.mods.ftblibrary.ui.CustomClickEvent;
import dev.pepsi.questrank.QuestRankClient;

/**
 * Adaptador aislado del botón lateral de FTB Library (contrato §10.1, §11.6).
 *
 * <p>El recurso {@code assets/questrank/sidebar_buttons/ranking.json} declara
 * {@code click: ["custom:questrank:open"]}. FTB Library parsea el prefijo {@code custom:} y despacha
 * {@link CustomClickEvent} con el {@code ResourceLocation} restante (comportamiento verificado por
 * bytecode en 2101.1.37). Aquí se abre la pantalla sin mixins, AT, reflexión ni comandos.
 */
public final class FtbSidebarIntegration {

    private static boolean registered;

    private FtbSidebarIntegration() {
    }

    /** Registro único del listener del clic lateral (cliente). */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        CustomClickEvent.EVENT.register(event -> {
            if (!SidebarClickTarget.matches(event.id())) {
                return EventResult.pass();
            }
            QuestRankClient.openRankingScreen();
            return EventResult.interruptFalse();
        });
    }
}
