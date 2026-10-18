package dev.pepsi.questrank.core.score;

/**
 * Predicado de finalización (contrato §5, [VERIF]): una misión computa como completada si y solo
 * si el estado almacenado está completado O el contador de repetibles es mayor que cero.
 *
 * <p>Es una función booleana pura: el booleano manda; el contador solo puede activar el resultado
 * (nunca negarlo). No valida contadores negativos porque FTB nunca los emite y el contrato no
 * exige esa validación.
 */
public final class CompletionRule {

    private CompletionRule() {
    }

    public static boolean isCompleted(boolean teamDataIsCompleted, int completionCount) {
        return teamDataIsCompleted || completionCount > 0;
    }
}
