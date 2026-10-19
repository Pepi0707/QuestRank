package dev.pepsi.questrank.client.ui;

import java.util.Objects;

/**
 * Layout de la lista virtualizada (contrato §10.6, §10.7): calcula la ventana visible y el scroll
 * con aritmética entera, sin dependencia de Minecraft, para poder probarlo de forma determinista.
 *
 * <p>Coste de render O(filas visibles), no O(participantes). El desplazamiento se acota para que
 * nunca quede hueco vacío al final.
 */
public final class RankingLayout {

    private final int viewportHeight;
    private int scrollOffsetPx;

    public RankingLayout(int viewportHeight) {
        if (viewportHeight < 0) {
            throw new IllegalArgumentException("viewportHeight must be >= 0: " + viewportHeight);
        }
        this.viewportHeight = viewportHeight;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    public int scrollOffsetPx() {
        return scrollOffsetPx;
    }

    /** Alto total de la lista para un número de filas dado. */
    public static int totalHeight(int rowCount) {
        int height = 0;
        for (int i = 0; i < rowCount; i++) {
            height += QuestRankTheme.rowHeight(i);
        }
        return height;
    }

    /** Desplazamiento máximo útil (0 si todo cabe). */
    public int maxScrollPx(int rowCount) {
        return Math.max(0, totalHeight(rowCount) - viewportHeight);
    }

    /** Fija el desplazamiento, acotándolo al rango válido. */
    public void setScrollOffsetPx(int offsetPx, int rowCount) {
        scrollOffsetPx = Math.max(0, Math.min(offsetPx, maxScrollPx(rowCount)));
    }

    /** Desplaza una cantidad de píxeles (positivo hacia abajo). */
    public void scrollByPx(int deltaPx, int rowCount) {
        setScrollOffsetPx(scrollOffsetPx + deltaPx, rowCount);
    }

    /** Desplaza una cantidad de filas (rueda/teclado). */
    public void scrollByRows(int deltaRows, int rowCount) {
        int pixels = 0;
        if (deltaRows > 0) {
            for (int i = 0; i < deltaRows; i++) {
                pixels += QuestRankTheme.ROW_HEIGHT;
            }
        } else {
            for (int i = 0; i < -deltaRows; i++) {
                pixels -= QuestRankTheme.ROW_HEIGHT;
            }
        }
        scrollByPx(pixels, rowCount);
    }

    /** Índice de la primera fila visible. */
    public int firstVisibleRow(int rowCount) {
        int offset = scrollOffsetPx;
        for (int i = 0; i < rowCount; i++) {
            int height = QuestRankTheme.rowHeight(i);
            if (offset < height) {
                return i;
            }
            offset -= height;
        }
        return Math.max(0, rowCount - 1);
    }

    /** Índice de la última fila visible (inclusive). */
    public int lastVisibleRow(int rowCount) {
        if (rowCount == 0) {
            return -1;
        }
        int remaining = viewportHeight;
        int offset = scrollOffsetPx;
        int index = 0;
        while (index < rowCount && (offset > 0 || remaining > 0)) {
            int height = QuestRankTheme.rowHeight(index);
            if (offset > 0) {
                int consumed = Math.min(offset, height);
                offset -= consumed;
                remaining -= height - consumed;
            } else {
                remaining -= height;
            }
            index++;
        }
        return Math.max(0, Math.min(rowCount - 1, index - 1));
    }

    /** Desplazamiento necesario para dejar una fila completamente visible. */
    public void ensureVisible(int rowIndex, int rowCount) {
        if (rowIndex < 0 || rowIndex >= rowCount) {
            return;
        }
        int top = 0;
        for (int i = 0; i < rowIndex; i++) {
            top += QuestRankTheme.rowHeight(i);
        }
        int bottom = top + QuestRankTheme.rowHeight(rowIndex);
        if (top < scrollOffsetPx) {
            setScrollOffsetPx(top, rowCount);
        } else if (bottom > scrollOffsetPx + viewportHeight) {
            setScrollOffsetPx(bottom - viewportHeight, rowCount);
        }
    }

    /** Posición Y de una fila relativa al inicio del viewport (puede ser negativa). */
    public int rowY(int rowIndex) {
        Objects.checkIndex(rowIndex, Integer.MAX_VALUE);
        int y = -scrollOffsetPx;
        for (int i = 0; i < rowIndex; i++) {
            y += QuestRankTheme.rowHeight(i);
        }
        return y;
    }
}
