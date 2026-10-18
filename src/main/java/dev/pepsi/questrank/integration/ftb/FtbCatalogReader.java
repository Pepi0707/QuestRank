package dev.pepsi.questrank.integration.ftb;

import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.pepsi.questrank.core.catalog.CatalogEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador de lectura del catálogo vivo de FTB Quests (contrato §4, §4.8, §8.2).
 *
 * <p>Solo lectura, sin caché, sin listeners y sin estado global; requiere el hilo del servidor
 * (§12.1). {@link Optional#empty()} = catálogo no disponible (servidor sin arrancar, fichero aún
 * no cargado o cargándose): estado válido y distinto del catálogo vacío (instancia presente con
 * cero misiones, N = 0). Las anomalías estructurales (misión sin capítulo) fallan de forma
 * explícita en vez de descartar misiones.
 */
public final class FtbCatalogReader {

    private FtbCatalogReader() {
    }

    /**
     * Lee el catálogo vivo.
     *
     * @return vacío si el catálogo no está disponible; si lo está, su fotografía (posiblemente
     *         con cero entradas)
     * @throws IllegalStateException si se invoca fuera del hilo del servidor o si el catálogo
     *                               presenta una anomalía estructural
     */
    public static Optional<FtbCatalog> read() {
        Optional<ServerQuestFile> maybeFile = ServerQuestFile.getInstance();
        if (maybeFile.isEmpty()) {
            return Optional.empty();
        }
        ServerQuestFile file = maybeFile.get();
        FtbThreading.requireServerThread(file.server);
        if (file.isLoading()) {
            return Optional.empty();
        }
        List<CatalogEntry> entries = new ArrayList<>();
        file.forAllQuests(quest -> entries.add(toCatalogEntry(quest)));
        return Optional.of(new FtbCatalog(catalogIdentity(file), entries));
    }

    private static CatalogEntry toCatalogEntry(Quest quest) {
        Chapter chapter = quest.getQuestChapter();
        if (chapter == null) {
            throw new IllegalStateException("quest " + quest.getId() + " has no chapter (structural anomaly)");
        }
        return new CatalogEntry(quest.getId(), chapter.getId(), chapter.isAlwaysInvisible(), quest.getTags());
    }

    /**
     * Identidad de la instancia viva del catálogo (entrada de F_cat, §8.2): el hash de identidad
     * del objeto observado, ensanchado sin signo a 64 bits.
     *
     * <p>Limitación documentada: una recarga sobre la misma instancia ({@code load()}) no es
     * observable sin listeners o estado, que esta fase no introduce; cualquier cambio de contenido
     * del catálogo se detecta igualmente por el componente ∀q de F_cat, y una recarga con
     * contenido idéntico produce por diseño el mismo F_cat (no hay cambio funcional). Las
     * colisiones de 32 bits solo podrían afectar a dos instancias con contenido y configuración
     * idénticos, sin efecto sobre el ranking reconstruido.
     */
    private static long catalogIdentity(ServerQuestFile file) {
        return System.identityHashCode(file) & 0xFFFFFFFFL;
    }
}
