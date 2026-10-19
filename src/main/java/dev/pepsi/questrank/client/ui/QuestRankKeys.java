package dev.pepsi.questrank.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Teclas del cliente (contrato §10.1, §10.6): la de apertura queda **sin asignar por defecto**
 * ({@link GLFW#GLFW_KEY_UNKNOWN}) y la de refresco se usa dentro de la pantalla.
 */
public final class QuestRankKeys {

    public static final String CATEGORY = "key.categories.questrank";

    public static final KeyMapping OPEN_RANKING = new KeyMapping(
            "key.questrank.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    public static final KeyMapping REFRESH_RANKING = new KeyMapping(
            "key.questrank.refresh", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);

    private QuestRankKeys() {
    }
}
