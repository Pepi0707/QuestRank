package dev.pepsi.questrank.client.integration;

import dev.pepsi.questrank.QuestRank;
import net.minecraft.resources.ResourceLocation;

/**
 * Identificador puro del clic lateral, separado del adaptador de FTB para poder verificarlo en
 * pruebas sin cargar clases de FTB Library (contrato §10.1, §11.6).
 *
 * <p>El JSON declara {@code custom:questrank:open}; FTB Library elimina el prefijo
 * {@code custom:} y despacha {@code questrank:open}. Ambos extremos deben coincidir.
 */
public final class SidebarClickTarget {

    public static final String CLICK_VALUE = "questrank:open";
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(QuestRank.MOD_ID, "open");

    private SidebarClickTarget() {
    }

    public static boolean matches(ResourceLocation id) {
        return ID.equals(id);
    }
}
