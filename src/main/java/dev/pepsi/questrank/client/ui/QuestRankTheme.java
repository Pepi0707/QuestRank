package dev.pepsi.questrank.client.ui;

/**
 * Paleta y medidas del contrato visual (contrato §10.2, §10.3, §10.8 [PARAM]).
 *
 * <p>Valores por defecto ratificados en §10.8; cualquier ajuste requiere revisión del propietario.
 */
public final class QuestRankTheme {

    public static final int BACKGROUND = 0xFF14161C;
    public static final int PANEL = 0xFF1A1D24;
    public static final int HOVER = 0xFF20242D;
    public static final int BORDER = 0xFF262B36;
    public static final int FOCUS = 0xFF4CC2FF;
    public static final int TEXT_PRIMARY = 0xFFE8ECF1;
    public static final int TEXT_SECONDARY = 0xFF9AA4B2;
    public static final int TEXT_MUTED = 0xFF6B7480;
    public static final int PROGRESS = 0xFF57C98A;
    public static final int GOLD = 0xFFF2C14E;
    public static final int SILVER = 0xFFC9D2DD;
    public static final int BRONZE = 0xFFD08A5C;

    public static final int PANEL_MIN_WIDTH = 300;
    public static final int PANEL_MAX_WIDTH = 440;
    public static final int PANEL_HORIZONTAL_MARGIN = 16;
    public static final int ROW_HEIGHT = 34;
    public static final int LEADER_ROW_HEIGHT = 40;
    public static final int FACE_SIZE = 16;
    public static final int PROGRESS_BAR_HEIGHT = 6;
    public static final int MAX_VISIBLE_FACES = 4;

    private QuestRankTheme() {
    }

    /**
     * Ancho del panel: {@code clamp(pantalla - 32, 300, 440)} (§10.8), acotado además al ancho
     * físico de la pantalla para no salirse en resoluciones reducidas (§10.7).
     */
    public static int panelWidth(int screenWidth) {
        int candidate = screenWidth - PANEL_HORIZONTAL_MARGIN * 2;
        int width = Math.max(PANEL_MIN_WIDTH, Math.min(PANEL_MAX_WIDTH, candidate));
        return Math.min(width, Math.max(1, screenWidth - 8));
    }

    /** Alto de fila: el líder puede ser ligeramente más alta (§10.3). */
    public static int rowHeight(int index) {
        return index == 0 ? LEADER_ROW_HEIGHT : ROW_HEIGHT;
    }
}
