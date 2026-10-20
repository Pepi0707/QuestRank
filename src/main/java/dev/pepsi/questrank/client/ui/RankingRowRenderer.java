package dev.pepsi.questrank.client.ui;

import dev.pepsi.questrank.client.skin.SkinCache;
import dev.pepsi.questrank.client.skin.SkinRequest;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;

/**
 * Renderizado de una fila (contrato §10.2–§10.4): posición, caras de miembros, nombre, clase,
 * C/N, porcentaje y barra de progreso. El líder usa tarjeta más alta y acento dorado; el podio usa
 * plata/bronce. Sin etiquetas de rol.
 *
 * <p>El tooltip de un jugador individual no se muestra: la fila ya contiene nombre, clase y C/N.
 * Para equipos se muestra únicamente la lista de miembros, cada uno con su cara y su nombre, sin
 * repetir el nombre del equipo ni una cabecera de sección.
 */
public final class RankingRowRenderer {

    private static final int RANK_WIDTH = 20;
    private static final int FACE_GAP = 2;
    private static final int NAME_GAP = 4;
    private static final int TOOLTIP_PADDING = 4;
    private static final int TOOLTIP_LINE_HEIGHT = 18;
    private static final int TOOLTIP_FACE_GAP = 4;
    private static final int MAX_TOOLTIP_MEMBERS = 12;

    private RankingRowRenderer() {
    }

    /** Dibuja una fila y devuelve si el cursor la está señalando. */
    public static boolean render(GuiGraphics graphics, Font font, RankingRow row, int panelLeft,
                                 int panelWidth, int rowY, boolean selected, int mouseX, int mouseY) {
        int rowHeight = QuestRankTheme.rowHeight(row.index());
        int cardLeft = panelLeft + 4;
        int cardRight = panelLeft + panelWidth - 4;
        boolean hovered = mouseX >= cardLeft && mouseX < cardRight
                && mouseY >= rowY && mouseY < rowY + rowHeight - 2;
        graphics.fill(cardLeft, rowY, cardRight, rowY + rowHeight - 2,
                hovered ? QuestRankTheme.HOVER : QuestRankTheme.PANEL);

        int accent = row.index() == 0 ? QuestRankTheme.GOLD
                : row.index() == 1 ? QuestRankTheme.SILVER
                : row.index() == 2 ? QuestRankTheme.BRONZE
                : QuestRankTheme.BORDER;
        int rankColor = row.index() <= 2 ? accent : QuestRankTheme.TEXT_SECONDARY;
        graphics.fill(cardLeft, rowY, cardLeft + 2, rowY + rowHeight - 2, accent);

        if (selected) {
            graphics.fill(cardLeft, rowY, cardRight, rowY + 1, QuestRankTheme.FOCUS);
            graphics.fill(cardLeft, rowY + rowHeight - 3, cardRight, rowY + rowHeight - 2,
                    QuestRankTheme.FOCUS);
            graphics.fill(cardLeft, rowY, cardLeft + 1, rowY + rowHeight - 2, QuestRankTheme.FOCUS);
            graphics.fill(cardRight - 1, rowY, cardRight, rowY + rowHeight - 2, QuestRankTheme.FOCUS);
        }

        int barY = rowY + rowHeight - 9;
        // Mantiene el mismo hueco vertical nombre→barra en filas normales y en la fila líder.
        int nameY = barY - 17;
        String rank = Integer.toString(row.index() + 1);
        int rankX = panelLeft + 10 + (RANK_WIDTH - font.width(rank)) / 2;
        graphics.drawString(font, rank, rankX, nameY, rankColor, false);

        int x = panelLeft + 12 + RANK_WIDTH;
        int faceY = rowY + (rowHeight - 2 - QuestRankTheme.FACE_SIZE) / 2;
        List<RankingRow.MemberFace> faces = row.faces();
        if (faces.isEmpty()) {
            drawPlaceholderFace(graphics, x, faceY);
            x += QuestRankTheme.FACE_SIZE + FACE_GAP;
        } else {
            for (RankingRow.MemberFace face : faces) {
                drawFace(graphics, face, x, faceY);
                x += QuestRankTheme.FACE_SIZE + FACE_GAP;
            }
        }
        int extraMembers = row.extraMembers();
        if (extraMembers > 0) {
            String extra = "+" + extraMembers;
            graphics.drawString(font, extra, x, nameY, QuestRankTheme.TEXT_MUTED, false);
            x += font.width(extra) + NAME_GAP;
        } else {
            x += NAME_GAP;
        }

        String score = RankingViewModel.scoreText(row.completed(), row.total());
        int scoreX = cardRight - 4 - font.width(score);
        Component kind = Component.translatable(RankingViewModel.kindKey(row.party()));
        int kindX = scoreX - 8 - font.width(kind);
        boolean showKind = kindX - x >= 24;
        int nameRight = showKind ? kindX - 6 : scoreX - 8;
        String displayName = fit(font, row.name(), Math.max(0, nameRight - x));
        graphics.drawString(font, displayName, x, nameY, QuestRankTheme.TEXT_PRIMARY, false);
        if (showKind) {
            graphics.drawString(font, kind, kindX, nameY, QuestRankTheme.TEXT_MUTED, false);
        }
        graphics.drawString(font, score, scoreX, nameY, QuestRankTheme.TEXT_SECONDARY, false);

        String percent = RankingViewModel.percentageText(row.completed(), row.total());
        int percentX = cardRight - 4 - font.width(percent);
        graphics.drawString(font, percent, percentX, rowY + rowHeight - 13, QuestRankTheme.TEXT_MUTED, false);

        int barX = x;
        int barWidth = Math.max(0, scoreX - 6 - barX);
        graphics.fill(barX, barY, barX + barWidth, barY + QuestRankTheme.PROGRESS_BAR_HEIGHT,
                QuestRankTheme.BORDER);
        int filled = RankingViewModel.barFill(row.completed(), row.total(), barWidth);
        if (filled > 0) {
            graphics.fill(barX, barY, barX + filled, barY + QuestRankTheme.PROGRESS_BAR_HEIGHT,
                    QuestRankTheme.PROGRESS);
        }
        return hovered;
    }

    /**
     * Tooltip de equipo: caras y nombres de los miembros, sin nombre del equipo, clase ni cabecera.
     * Los jugadores individuales no tienen tooltip porque la fila ya muestra toda su información.
     */
    public static void renderTooltip(GuiGraphics graphics, Font font, RankingRow row, int mouseX, int mouseY) {
        if (!row.party()) {
            return;
        }
        List<RankingRow.MemberFace> members = row.members();
        int shown = Math.min(members.size(), MAX_TOOLTIP_MEMBERS);
        int hidden = Math.max(0, row.memberCount() - shown);
        if (shown == 0 && hidden == 0) {
            return;
        }

        Component hiddenLine = Component.translatable("questrank.tooltip.more_members", hidden);
        int maxNameWidth = 0;
        for (int i = 0; i < shown; i++) {
            maxNameWidth = Math.max(maxNameWidth, font.width(members.get(i).name()));
        }
        if (hidden > 0) {
            maxNameWidth = Math.max(maxNameWidth, font.width(hiddenLine));
        }
        int panelWidth = TOOLTIP_PADDING * 2 + QuestRankTheme.FACE_SIZE + TOOLTIP_FACE_GAP + maxNameWidth;
        int lines = shown + (hidden > 0 ? 1 : 0);
        int panelHeight = TOOLTIP_PADDING * 2 + lines * TOOLTIP_LINE_HEIGHT;

        Minecraft minecraft = Minecraft.getInstance();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int panelX = Math.max(4, Math.min(mouseX + 12, screenWidth - panelWidth - 4));
        int panelY = Math.max(4, Math.min(mouseY - 12, screenHeight - panelHeight - 4));

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, QuestRankTheme.BACKGROUND);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, QuestRankTheme.BORDER);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight,
                QuestRankTheme.BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, QuestRankTheme.BORDER);
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight,
                QuestRankTheme.BORDER);

        int textX = panelX + TOOLTIP_PADDING + QuestRankTheme.FACE_SIZE + TOOLTIP_FACE_GAP;
        for (int i = 0; i < shown; i++) {
            RankingRow.MemberFace member = members.get(i);
            int lineY = panelY + TOOLTIP_PADDING + i * TOOLTIP_LINE_HEIGHT;
            drawFace(graphics, member, panelX + TOOLTIP_PADDING, lineY);
            int color = member.online() ? QuestRankTheme.TEXT_PRIMARY : QuestRankTheme.TEXT_MUTED;
            graphics.drawString(font, fit(font, member.name(), maxNameWidth), textX, lineY + 4, color, false);
        }
        if (hidden > 0) {
            int lineY = panelY + TOOLTIP_PADDING + shown * TOOLTIP_LINE_HEIGHT;
            graphics.drawString(font, hiddenLine, textX, lineY + 4, QuestRankTheme.TEXT_MUTED, false);
        }
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        if (maxWidth <= ellipsisWidth) {
            return font.plainSubstrByWidth(text, maxWidth);
        }
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + ellipsis;
    }

    private static void drawFace(GuiGraphics graphics, RankingRow.MemberFace face, int x, int y) {
        SkinCache<PlayerSkin> cache = ClientSkins.cache();
        SkinRequest request = new SkinRequest(face.id(), face.name());
        PlayerSkin skin = cache.peek(request);
        if (skin != null) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(graphics, skin, x, y,
                    QuestRankTheme.FACE_SIZE);
        } else {
            cache.request(request);
            drawPlaceholderFace(graphics, x, y);
        }
    }

    private static void drawPlaceholderFace(GuiGraphics graphics, int x, int y) {
        int size = QuestRankTheme.FACE_SIZE;
        graphics.fill(x, y, x + size, y + size, 0xFF262B36);
        graphics.fill(x + 4, y + 3, x + size - 4, y + 10, 0xFF3A4150);
        graphics.fill(x + 2, y + 11, x + size - 2, y + size, 0xFF3A4150);
    }

    /** Caché compartida y acotada; la resolución es la API vanilla de skins (§11.4). */
    private static final class ClientSkins {

        private static final SkinCache<PlayerSkin> CACHE = new SkinCache<>(SkinCache.DEFAULT_CAPACITY,
                request -> Minecraft.getInstance().getSkinManager().getOrLoad(
                        new com.mojang.authlib.GameProfile(request.id(), request.profileName())));

        static SkinCache<PlayerSkin> cache() {
            return CACHE;
        }
    }
}
