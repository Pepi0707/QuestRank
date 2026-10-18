package dev.pepsi.questrank.integration.ftb;

import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ClearFileCacheEvent;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import java.util.Objects;

/**
 * Puente de eventos FTB (contrato §8.7): registra una sola vez los listeners de FTB Teams y
 * FTB Quests y los despacha a objetivos attachables. Sin objetivos (servidor no iniciado o ya
 * detenido) los eventos se descartan; el wiring del servidor sustituye los objetivos en el
 * arranque y los anula en la parada, sin fugas ni referencias obsoletas.
 *
 * <p>El hilo de emisión de los eventos FTB no está garantizado por esta clase: el wiring envuelve
 * cada objetivo con marshalling al hilo del servidor y los adaptadores F3 re-verifican el hilo.
 */
public final class FtbEventBridge {

    private static volatile Runnable teamsChanged;
    private static volatile Runnable progressChanged;
    private static volatile Runnable catalogReloaded;
    private static boolean registered;

    private FtbEventBridge() {
    }

    /** Registro único de los listeners (desde el constructor del mod). */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        TeamEvent.CREATED.register(event -> fire(teamsChanged));
        TeamEvent.DELETED.register(event -> fire(teamsChanged));
        TeamEvent.PLAYER_CHANGED.register(event -> fire(teamsChanged));
        TeamEvent.PLAYER_JOINED_PARTY.register(event -> fire(teamsChanged));
        TeamEvent.PLAYER_LEFT_PARTY.register(event -> fire(teamsChanged));
        TeamEvent.PLAYER_LOGGED_IN.register(event -> fire(teamsChanged));
        TeamEvent.PROPERTIES_CHANGED.register(event -> fire(teamsChanged));
        ObjectCompletedEvent.QUEST.register(event -> {
            fire(progressChanged);
            return EventResult.pass();
        });
        ClearFileCacheEvent.EVENT.register(file -> fire(catalogReloaded));
    }

    /** Activa los objetivos de despacho (arranque de servidor). */
    public static void attach(Runnable teams, Runnable progress, Runnable catalog) {
        teamsChanged = Objects.requireNonNull(teams, "teams");
        progressChanged = Objects.requireNonNull(progress, "progress");
        catalogReloaded = Objects.requireNonNull(catalog, "catalog");
    }

    /** Anula los objetivos (parada de servidor); los eventos posteriores se descartan. */
    public static void detach() {
        teamsChanged = null;
        progressChanged = null;
        catalogReloaded = null;
    }

    private static void fire(Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
