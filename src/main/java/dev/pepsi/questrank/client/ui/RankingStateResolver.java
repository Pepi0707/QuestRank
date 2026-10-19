package dev.pepsi.questrank.client.ui;

import dev.pepsi.questrank.client.net.ClientSessionView;

/**
 * Clasificación pura de {@link ClientSessionView} a estado visual (§10.5).
 *
 * <p>D5a manda mostrar los datos utilizables aunque la sesión esté pendiente de validar (con la
 * marca discreta de posible obsolescencia). Por eso los datos tienen precedencia sobre el error:
 * el estado de error con reintento se reserva para cuando no hay datos utilizables (D5b).
 *
 * <p>Sin datos, la carga solo se muestra mientras {@code view.loading()} indique solicitud en
 * curso o ventana de recuperación activa; si no hay carga ni error, no existe progreso activo y
 * se muestra el estado de error con reintento.
 */
public final class RankingStateResolver {

    private RankingStateResolver() {
    }

    public static RankingUiState resolve(ClientSessionView view) {
        if (view.hasData()) {
            if (view.data().total() == 0L) {
                return RankingUiState.EMPTY_CATALOG;
            }
            if (view.data().entries().isEmpty()) {
                return RankingUiState.EMPTY_LIST;
            }
            return RankingUiState.DATA;
        }
        if (view.error()) {
            return RankingUiState.ERROR;
        }
        if (view.loading()) {
            return RankingUiState.LOADING;
        }
        // Sin datos, sin error y sin carga no hay progreso activo: se ofrece reintento.
        return RankingUiState.ERROR;
    }
}
