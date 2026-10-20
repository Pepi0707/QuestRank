package dev.pepsi.questrank.client.skin;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidad de una petición de skin (§11.4): UUID canónico y nombre visible del miembro.
 *
 * <p>El nombre se conserva porque no todas las fuentes de skins resuelven únicamente por
 * UUID; CustomSkinLoader y la API vanilla pueden necesitar el nombre del perfil. La caché
 * usa ambos campos para que un cambio de nombre no reutilice una textura obsoleta.
 */
public record SkinRequest(UUID id, String name) {

    public SkinRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    /** Nombre utilizable como perfil; si no hay nombre, conserva el comportamiento por UUID. */
    public String profileName() {
        return name.isBlank() ? id.toString() : name;
    }
}
