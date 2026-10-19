package dev.pepsi.questrank.client.ui;

/**
 * Estado visual de la pantalla, derivado exclusivamente de {@code ClientSessionView} (§10.5).
 *
 * <p>No hay lógica de protocolo aquí: el autómata de F5-A decide; este tipo solo clasifica para
 * renderizar.
 */
public enum RankingUiState {
    /** Sin datos aún y solicitud/ventana de recuperación activa. */
    LOADING,
    /** Error con reintento (B3/D5b). */
    ERROR,
    /** Catálogo vacío: N = 0 (§6.5). */
    EMPTY_CATALOG,
    /** Sin participantes en el ranking. */
    EMPTY_LIST,
    /** Datos disponibles (posiblemente marcados como obsoletos). */
    DATA
}
