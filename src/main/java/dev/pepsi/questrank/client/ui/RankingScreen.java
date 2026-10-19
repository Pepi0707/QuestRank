package dev.pepsi.questrank.client.ui;

import dev.pepsi.questrank.client.net.ClientSessionView;
import dev.pepsi.questrank.client.net.ClientSyncHooks;
import dev.pepsi.questrank.net.RankingSnapshotPayload;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Pantalla de clasificación (contrato §10). Consume exclusivamente {@link ClientSessionView} y las
 * operaciones de F5-A ({@code openRanking}/{@code closeRanking}/{@code refreshRanking}); no contiene
 * lógica de protocolo (§9).
 */
public final class RankingScreen extends Screen {

    private static final Component TITLE =
            Component.translatable("questrank.screen.title").withStyle(ChatFormatting.BOLD);
    private static final int HEADER_HEIGHT = 44;
    private static final int FOOTER_HEIGHT = 26;
    private static final int PADDING = 10;
    private static final int REFRESH_WIDTH = 66;
    private static final int REFRESH_HEIGHT = 16;
    private static final int MAX_SCROLL_STEP_PX = 1_000;

    private RankingLayout layout = new RankingLayout(0);
    private RankingSnapshotPayload cachedSnapshot;
    private RankingViewModel viewModel;
    private int selectedIndex = -1;
    private int[] retryBounds;
    private int[] refreshBounds;
    private RankingRow hoveredRow;
    private boolean openKeyHeld;

    public RankingScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        super.init();
        openKeyHeld = QuestRankKeys.OPEN_RANKING.isDown();
        ClientSyncHooks.openRanking();
    }

    @Override
    public void removed() {
        ClientSyncHooks.closeRanking();
        super.removed();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render pinta el fondo/desenfoque y los widgets; el contenido propio va después
        // para no volver a pintar el fondo encima de la interfaz (B1).
        super.render(graphics, mouseX, mouseY, partialTick);
        hoveredRow = null;
        ClientSessionView view = ClientSyncHooks.rankingView();
        updateViewModel(view);
        RankingUiState state = RankingStateResolver.resolve(view);

        int panelWidth = QuestRankTheme.panelWidth(width);
        int left = (width - panelWidth) / 2;
        int top = PADDING;
        int panelHeight = Math.max(0, height - PADDING * 2);
        int panelBottom = top + panelHeight;
        int panelRight = left + panelWidth;

        graphics.fill(left, top, panelRight, panelBottom, QuestRankTheme.BACKGROUND);
        graphics.fill(left, top, panelRight, top + 1, QuestRankTheme.BORDER);
        graphics.fill(left, panelBottom - 1, panelRight, panelBottom, QuestRankTheme.BORDER);
        graphics.fill(left, top, left + 1, panelBottom, QuestRankTheme.BORDER);
        graphics.fill(panelRight - 1, top, panelRight, panelBottom, QuestRankTheme.BORDER);

        // El texto del botón Refresh empieza en top + 12; el título comparte esa línea base y
        // usa negrita para tener jerarquía real de cabecera.
        int titleY = top + 12;
        graphics.drawString(font, title, left + PADDING, titleY, QuestRankTheme.TEXT_PRIMARY, false);
        retryBounds = null;
        drawRefreshButton(graphics, left, top, panelWidth, mouseX, mouseY);

        if (view.hasData() && view.possiblyStale()) {
            graphics.drawString(font, Component.translatable("questrank.state.stale"),
                    left + PADDING, titleY + 18, QuestRankTheme.TEXT_MUTED, false);
        }
        // Separador de cabecera: delimita el título del contenido de la lista.
        graphics.fill(left + 1, top + HEADER_HEIGHT - 1, panelRight - 1, top + HEADER_HEIGHT,
                QuestRankTheme.BORDER);

        int contentTop = top + HEADER_HEIGHT;
        int contentBottom = Math.max(contentTop, panelBottom - FOOTER_HEIGHT);
        switch (state) {
            case LOADING -> drawCentered(graphics, Component.translatable("questrank.state.loading"),
                    left, panelWidth, (contentTop + contentBottom) / 2);
            case ERROR -> drawError(graphics, left, panelWidth, contentTop, contentBottom, mouseX, mouseY);
            case EMPTY_CATALOG -> {
                int center = (contentTop + contentBottom) / 2;
                drawCentered(graphics, Component.translatable("questrank.state.empty_catalog"),
                        left, panelWidth, center - 7);
                drawCentered(graphics, Component.literal(RankingViewModel.scoreText(0L, 0L) + "  \u00b7  "
                        + RankingViewModel.percentageText(0L, 0L)), left, panelWidth, center + 7);
            }
            case EMPTY_LIST -> drawCentered(graphics, Component.translatable("questrank.state.empty"),
                    left, panelWidth, (contentTop + contentBottom) / 2);
            case DATA -> renderList(graphics, left, panelWidth, contentTop, contentBottom, mouseX, mouseY);
        }

        drawFooter(graphics, view, left, top, panelWidth, panelHeight);
        if (hoveredRow != null) {
            RankingRowRenderer.renderTooltip(graphics, font, hoveredRow, mouseX, mouseY);
        }
    }

    private void updateViewModel(ClientSessionView view) {
        if (!view.hasData()) {
            cachedSnapshot = null;
            viewModel = null;
            return;
        }
        if (view.data() != cachedSnapshot) {
            cachedSnapshot = view.data();
            viewModel = RankingViewModel.of(cachedSnapshot);
            int rowCount = viewModel.rows().size();
            if (rowCount == 0) {
                selectedIndex = -1;
            } else if (selectedIndex >= rowCount) {
                selectedIndex = rowCount - 1;
            }
            layout.setScrollOffsetPx(layout.scrollOffsetPx(), rowCount);
            if (selectedIndex >= 0) {
                layout.ensureVisible(selectedIndex, rowCount);
            }
        }
    }

    private void drawRefreshButton(GuiGraphics graphics, int left, int top, int panelWidth,
                                   int mouseX, int mouseY) {
        int buttonX = left + panelWidth - PADDING - REFRESH_WIDTH;
        int buttonY = top + 8;
        boolean hovered = mouseX >= buttonX && mouseX < buttonX + REFRESH_WIDTH
                && mouseY >= buttonY && mouseY < buttonY + REFRESH_HEIGHT;
        graphics.fill(buttonX, buttonY, buttonX + REFRESH_WIDTH, buttonY + REFRESH_HEIGHT,
                hovered ? QuestRankTheme.HOVER : QuestRankTheme.PANEL);
        graphics.fill(buttonX, buttonY, buttonX + REFRESH_WIDTH, buttonY + 1, QuestRankTheme.BORDER);
        graphics.fill(buttonX, buttonY + REFRESH_HEIGHT - 1, buttonX + REFRESH_WIDTH,
                buttonY + REFRESH_HEIGHT, QuestRankTheme.BORDER);
        Component label = Component.translatable("questrank.button.refresh");
        graphics.drawString(font, label, buttonX + (REFRESH_WIDTH - font.width(label)) / 2, buttonY + 4,
                QuestRankTheme.TEXT_SECONDARY, false);
        refreshBounds = new int[] {buttonX, buttonY, REFRESH_WIDTH, REFRESH_HEIGHT};
    }

    private void drawError(GuiGraphics graphics, int left, int panelWidth, int contentTop, int contentBottom,
                           int mouseX, int mouseY) {
        int center = (contentTop + contentBottom) / 2;
        drawCentered(graphics, Component.translatable("questrank.state.error"), left, panelWidth, center - 16);
        int buttonWidth = 100;
        int buttonX = left + (panelWidth - buttonWidth) / 2;
        int buttonY = Math.min(center + 4, Math.max(contentTop, contentBottom - 22));
        boolean hovered = mouseX >= buttonX && mouseX < buttonX + buttonWidth
                && mouseY >= buttonY && mouseY < buttonY + 20;
        graphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 20,
                hovered ? QuestRankTheme.HOVER : QuestRankTheme.PANEL);
        graphics.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + 1, QuestRankTheme.BORDER);
        graphics.fill(buttonX, buttonY + 19, buttonX + buttonWidth, buttonY + 20, QuestRankTheme.BORDER);
        Component retry = Component.translatable("questrank.button.retry");
        graphics.drawString(font, retry, buttonX + (buttonWidth - font.width(retry)) / 2, buttonY + 6,
                QuestRankTheme.TEXT_PRIMARY, false);
        retryBounds = new int[] {buttonX, buttonY, buttonWidth, 20};
    }

    private void renderList(GuiGraphics graphics, int left, int panelWidth, int contentTop, int contentBottom,
                            int mouseX, int mouseY) {
        if (viewModel == null) {
            return;
        }
        List<RankingRow> rows = viewModel.rows();
        if (rows.isEmpty()) {
            return;
        }
        int viewportHeight = Math.max(0, contentBottom - contentTop);
        if (viewportHeight <= 0) {
            return;
        }
        if (layout.viewportHeight() != viewportHeight) {
            int previousScroll = layout.scrollOffsetPx();
            layout = new RankingLayout(viewportHeight);
            layout.setScrollOffsetPx(previousScroll, rows.size());
            if (selectedIndex >= 0) {
                layout.ensureVisible(selectedIndex, rows.size());
            }
        }
        int first = layout.firstVisibleRow(rows.size());
        int last = layout.lastVisibleRow(rows.size());
        graphics.enableScissor(left + 1, contentTop, left + panelWidth - 1, contentBottom);
        for (int i = first; i <= last && i < rows.size(); i++) {
            RankingRow row = rows.get(i);
            boolean rowHovered = RankingRowRenderer.render(graphics, font, row, left, panelWidth,
                    contentTop + layout.rowY(i), i == selectedIndex, mouseX, mouseY);
            if (rowHovered) {
                hoveredRow = row;
            }
        }
        graphics.disableScissor();
    }

    private void drawFooter(GuiGraphics graphics, ClientSessionView view, int left, int top, int panelWidth,
                            int panelHeight) {
        int footerY = top + panelHeight - FOOTER_HEIGHT + 5;
        if (view.hasData() && view.data().participantsTruncated()) {
            graphics.drawString(font, Component.translatable("questrank.state.truncated",
                            view.data().entries().size(), view.data().participantCount()),
                    left + PADDING, footerY, QuestRankTheme.TEXT_MUTED, false);
            footerY += 10;
        }
        if (view.requestIdsExhausted()) {
            graphics.drawString(font, Component.translatable("questrank.state.ids_exhausted"),
                    left + PADDING, footerY, QuestRankTheme.TEXT_MUTED, false);
        }
    }

    private void drawCentered(GuiGraphics graphics, Component text, int left, int panelWidth, int y) {
        graphics.drawString(font, text, left + (panelWidth - font.width(text)) / 2, y,
                QuestRankTheme.TEXT_SECONDARY, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (refreshBounds != null
                && mouseX >= refreshBounds[0] && mouseX < refreshBounds[0] + refreshBounds[2]
                && mouseY >= refreshBounds[1] && mouseY < refreshBounds[1] + refreshBounds[3]) {
            playButtonClick();
            ClientSyncHooks.refreshRanking();
            return true;
        }
        if (retryBounds != null
                && mouseX >= retryBounds[0] && mouseX < retryBounds[0] + retryBounds[2]
                && mouseY >= retryBounds[1] && mouseY < retryBounds[1] + retryBounds[3]) {
            playButtonClick();
            ClientSyncHooks.refreshRanking();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (viewModel == null || viewModel.rows().isEmpty() || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        double rawDelta = -scrollY * QuestRankTheme.ROW_HEIGHT;
        int delta = (int) Math.max(-MAX_SCROLL_STEP_PX, Math.min(MAX_SCROLL_STEP_PX, rawDelta));
        if (delta == 0) {
            return true;
        }
        layout.scrollByPx(delta, viewModel.rows().size());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC se atiende antes que navegación y refresco: una reasignación de teclas no puede
        // impedir cerrar la pantalla (M1).
        if (keyCode == 256) { // GLFW_KEY_ESCAPE
            onClose();
            return true;
        }
        if (QuestRankKeys.OPEN_RANKING.matches(keyCode, scanCode)) {
            // La tecla que abre también cierra. Se ignora mientras la tecla sigue mantenida desde
            // la apertura, para que el auto-repeat no cierre la pantalla inmediatamente.
            if (!openKeyHeld) {
                openKeyHeld = true;
                onClose();
            }
            return true;
        }
        if (viewModel != null && !viewModel.rows().isEmpty()) {
            int rowCount = viewModel.rows().size();
            switch (keyCode) {
                case 264 -> { // GLFW_KEY_DOWN
                    selectedIndex = selectedIndex < 0 ? 0 : Math.min(rowCount - 1, selectedIndex + 1);
                    layout.ensureVisible(selectedIndex, rowCount);
                    return true;
                }
                case 265 -> { // GLFW_KEY_UP
                    selectedIndex = selectedIndex < 0 ? 0 : Math.max(0, selectedIndex - 1);
                    layout.ensureVisible(selectedIndex, rowCount);
                    return true;
                }
                case 267 -> { // GLFW_KEY_PAGE_DOWN
                    selectedIndex = selectedIndex < 0 ? 0
                            : Math.min(rowCount - 1, selectedIndex + visibleRows());
                    layout.ensureVisible(selectedIndex, rowCount);
                    return true;
                }
                case 266 -> { // GLFW_KEY_PAGE_UP
                    selectedIndex = selectedIndex < 0 ? 0 : Math.max(0, selectedIndex - visibleRows());
                    layout.ensureVisible(selectedIndex, rowCount);
                    return true;
                }
                case 268 -> { // GLFW_KEY_HOME
                    selectedIndex = 0;
                    layout.ensureVisible(selectedIndex, rowCount);
                    return true;
                }
                case 269 -> { // GLFW_KEY_END
                    selectedIndex = rowCount - 1;
                    layout.ensureVisible(selectedIndex, rowCount);
                    return true;
                }
                default -> {
                }
            }
        }
        if (QuestRankKeys.REFRESH_RANKING.matches(keyCode, scanCode)) {
            ClientSyncHooks.refreshRanking();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (QuestRankKeys.OPEN_RANKING.matches(keyCode, scanCode)) {
            openKeyHeld = false;
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private static void playButtonClick() {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private int visibleRows() {
        return Math.max(1, layout.viewportHeight() / QuestRankTheme.ROW_HEIGHT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        super.onClose();
    }
}
