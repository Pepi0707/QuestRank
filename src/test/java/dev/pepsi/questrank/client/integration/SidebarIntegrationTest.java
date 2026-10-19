package dev.pepsi.questrank.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Contrato entre el recurso del botón lateral y el listener de {@code CustomClickEvent}
 * (contrato §10.1). No carga clases de FTB Library: solo verifica el id y los metadatos del JSON.
 */
class SidebarIntegrationTest {

    @Test
    void clickValueMatchesListenerId() throws Exception {
        JsonArray click = button().getAsJsonArray("click");
        assertEquals(1, click.size());
        String value = click.get(0).getAsString();
        assertEquals("custom:" + SidebarClickTarget.CLICK_VALUE, value);

        ResourceLocation dispatched = ResourceLocation.parse(value.substring("custom:".length()));
        assertTrue(SidebarClickTarget.matches(dispatched),
                "FTB Library elimina el prefijo custom: y despacha el ResourceLocation restante");
    }

    @Test
    void foreignClicksAreNotClaimed() {
        assertFalse(SidebarClickTarget.matches(ResourceLocation.fromNamespaceAndPath("questrank", "other")));
        assertFalse(SidebarClickTarget.matches(ResourceLocation.fromNamespaceAndPath("ftblibrary", "open")));
    }

    @Test
    void metadataDeclaresOriginalIconAndRequiredMod() throws Exception {
        JsonObject button = button();
        assertEquals("questrank:icons/ranking", button.getAsJsonArray("icon").get(0).getAsString());
        assertEquals("questrank", button.getAsJsonArray("required_mods").get(0).getAsString());
        assertFalse(button.has("tooltip"),
                "FTB Library añade sidebar_button.<namespace>.<path>; un tooltip JSON igual lo duplica");
    }

    @Test
    void originalIconResourceIsPresentAndSquare() throws Exception {
        try (InputStream stream = SidebarIntegrationTest.class.getResourceAsStream(
                "/assets/questrank/textures/icons/ranking.png")) {
            assertNotNull(stream, "el icono original debe estar en el classpath");
            byte[] png = stream.readAllBytes();
            assertTrue(png.length >= 24, "PNG demasiado corto");
            assertEquals(0x89, png[0] & 0xFF);
            assertEquals(0x50, png[1] & 0xFF);
            assertEquals(0x4E, png[2] & 0xFF);
            assertEquals(0x47, png[3] & 0xFF);
            int width = java.nio.ByteBuffer.wrap(png, 16, 4).getInt();
            int height = java.nio.ByteBuffer.wrap(png, 20, 4).getInt();
            assertEquals(16, width);
            assertEquals(16, height);
        }
    }

    @Test
    void langDeclaresSidebarTooltipInBothLocales() throws Exception {
        assertEquals("QuestRank",
                lang("en_us").get("sidebar_button.questrank.ranking").getAsString());
        assertEquals("QuestRank",
                lang("es_es").get("sidebar_button.questrank.ranking").getAsString());
    }

    private static JsonObject button() throws Exception {
        return json("/assets/questrank/sidebar_buttons/ranking.json");
    }

    private static JsonObject lang(String locale) throws Exception {
        return json("/assets/questrank/lang/" + locale + ".json");
    }

    private static JsonObject json(String path) throws Exception {
        try (InputStream stream = SidebarIntegrationTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "recurso del classpath: " + path);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject();
        }
    }
}
