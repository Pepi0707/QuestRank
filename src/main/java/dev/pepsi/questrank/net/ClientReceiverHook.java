package dev.pepsi.questrank.net;

import java.util.Objects;

/**
 * Punto de entrega común de los mensajes S2C hacia el lado cliente (contrato §11.6).
 *
 * <p>El código común (registro de payloads) no referencia clases de cliente: los handlers S2C
 * entregan aquí, y la implementación real la instala {@code QuestRankClient} en su arranque. Sin
 * receptor instalado (servidor dedicado o cliente con el mod aún sin inicializar) los mensajes se
 * descartan.
 */
public final class ClientReceiverHook {

    /** Receptor de mensajes S2C; se invoca en el hilo principal del cliente. */
    public interface Receiver {

        void onSnapshot(RankingSnapshotPayload payload);

        void onUnchanged(RankingUnchangedPayload payload);

        void onError(RankingErrorPayload payload);
    }

    private static volatile Receiver receiver;

    private ClientReceiverHook() {
    }

    /** Instala el receptor (una sola vez, desde el arranque del cliente). */
    public static void install(Receiver newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static void receiveSnapshot(RankingSnapshotPayload payload) {
        Receiver current = receiver;
        if (current != null) {
            current.onSnapshot(payload);
        }
    }

    public static void receiveUnchanged(RankingUnchangedPayload payload) {
        Receiver current = receiver;
        if (current != null) {
            current.onUnchanged(payload);
        }
    }

    public static void receiveError(RankingErrorPayload payload) {
        Receiver current = receiver;
        if (current != null) {
            current.onError(payload);
        }
    }
}
