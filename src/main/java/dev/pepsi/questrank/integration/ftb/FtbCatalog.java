package dev.pepsi.questrank.integration.ftb;

import dev.pepsi.questrank.core.catalog.CatalogEntry;
import java.util.List;
import java.util.Objects;

/**
 * Fotografía de solo lectura del catálogo vivo para el núcleo (contrato §4, §8.2).
 *
 * @param identity identidad de la instancia viva del catálogo (entrada "identidad del catálogo
 *                 vivo" de F_cat, §8.2)
 * @param entries  proyección estática de todas las misiones vivas; el núcleo aplica U (§4)
 */
public record FtbCatalog(long identity, List<CatalogEntry> entries) {

    public FtbCatalog {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
