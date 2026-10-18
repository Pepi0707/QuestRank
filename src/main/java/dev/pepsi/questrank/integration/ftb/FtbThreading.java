package dev.pepsi.questrank.integration.ftb;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * Guardia de hilo (contrato §12.1): todo acceso a FTB ocurre en el hilo del servidor.
 *
 * <p>Las guardias son obligatorias también en producción: una invocación fuera del hilo del
 * servidor es un error de programación y falla de forma explícita, en vez de leer estado
 * inconsistente.
 */
public final class FtbThreading {

    private FtbThreading() {
    }

    /** Falla con {@link IllegalStateException} si no se ejecuta en el hilo del servidor. */
    public static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("QuestRank FTB adapters must be called from the server thread");
        }
    }
}
