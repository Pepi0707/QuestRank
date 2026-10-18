package dev.pepsi.questrank.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Miembro de un participante (contrato §8.3, §9.3): identidad, nombre y estado de conexión.
 * Los nombres vacíos son válidos (el contrato no impone restricciones sobre el contenido).
 */
public record MemberInfo(UUID id, String name, boolean online) {

    public MemberInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
