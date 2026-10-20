package dev.pepsi.questrank.client.ui;

/**
 * Estado mínimo para registrar una sola vez el primer pintado de cada revisión mostrada.
 *
 * <p>La identidad es el par {@code (epoch, rev)} del contrato: tras un reinicio de servidor
 * {@code rev} puede repetirse. No mide ni asigna nada por frame: solo compara una clave pendiente.
 * La pantalla llama a {@link #onModelBuilt(long, long)} cuando construye el modelo de un snapshot
 * nuevo y a {@link #claimFirstPaint()} una vez terminado el frame. El tracker no demuestra que el
 * usuario haya visto correctamente la tabla; solo que el frame terminó después de construir el
 * modelo.
 */
public final class FirstPaintTracker {

    /** Identidad de una revisión mostrada. */
    public record SnapshotKey(long epoch, long rev) {
    }

    private SnapshotKey pending;
    private SnapshotKey lastLogged;

    /** Marca una revisión como pendiente de pintado si no se registró ya. */
    public void onModelBuilt(long epoch, long rev) {
        SnapshotKey key = new SnapshotKey(epoch, rev);
        if (key.equals(lastLogged)) {
            pending = null;
        } else {
            pending = key;
        }
    }

    /** Descarta un pintado pendiente cuando los datos dejan de estar disponibles. */
    public void clearPending() {
        pending = null;
    }

    public boolean hasPending() {
        return pending != null;
    }

    /**
     * Consume el primer pintado pendiente. Devuelve {@code null} si no hay ninguno o si esa
     * revisión ya se registró. La segunda llamada para la misma {@code (epoch, rev)} no vuelve a
     * emitir.
     */
    public SnapshotKey claimFirstPaint() {
        if (pending == null) {
            return null;
        }
        SnapshotKey key = pending;
        pending = null;
        lastLogged = key;
        return key;
    }

    /**
     * Consume el pendiente solo si coincide con la revisión que se va a presentar. Si no coincide,
     * se descarta para no atribuir el evento a otra revisión.
     */
    public SnapshotKey claimIfCurrent(long epoch, long rev) {
        if (pending == null) {
            return null;
        }
        SnapshotKey key = pending;
        pending = null;
        if (!key.equals(new SnapshotKey(epoch, rev))) {
            return null;
        }
        lastLogged = key;
        return key;
    }
}
