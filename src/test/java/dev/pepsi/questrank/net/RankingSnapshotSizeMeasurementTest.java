package dev.pepsi.questrank.net;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.model.MemberInfo;
import dev.pepsi.questrank.core.model.ParticipantKind;
import dev.pepsi.questrank.core.model.Ranking;
import dev.pepsi.questrank.core.model.RankingEntry;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * Arnés reproducible de medición del tamaño serializado de {@code RankingSnapshot} (§9.4.1):
 * usa el códec real de producción y escenarios fijos; imprime una línea «MEASURE» por escenario
 * (los resultados se transcriben al informe de fase con la revisión y el comando exactos).
 *
 * <p>Esta medición sintética no sustituye la prueba de carga real con 100 participantes (decisión
 * A-4): son evidencias separadas y la segunda se registra como no verificada si no puede
 * ejecutarse. El objetivo &lt;256 KB es una aspiración {@code [NO VERIF]}; el límite duro es 1 MiB.
 * Los tiempos de codificación son informativos, no un criterio (§12.3).
 */
class RankingSnapshotSizeMeasurementTest {

    private static final long EPOCH = 42L;

    @Test
    void measureEncodedSnapshotSizes() {
        report("100-ascii-realista", payload(100, 12, "Alice_", "member_"));
        report("512-ascii-topes", payload(512, 12, "p".repeat(64), "m".repeat(16)));
        report("512-unicode-topes", payload(512, 12, "漢".repeat(64), "字".repeat(16)));
        report("n0-catalogo-vacio", payload(0, 0, "", ""));
        report("n1-un-participante", payload(1, 1, "p", "m"));
    }

    private static RankingSnapshotPayload payload(int participants, int membersPer, String participantName, String memberName) {
        List<RankingEntry> entries = new ArrayList<>(participants);
        long total = participants == 0 ? 0L : 100L;
        for (int i = 0; i < participants; i++) {
            List<MemberInfo> members = new ArrayList<>(membersPer);
            for (int m = 0; m < membersPer; m++) {
                members.add(new MemberInfo(new UUID(0, i * 1000L + m + 1), memberName, true));
            }
            entries.add(new RankingEntry(new UUID(0, i + 1),
                    i % 2 == 0 ? ParticipantKind.PARTY : ParticipantKind.PLAYER,
                    participantName, 0xABCDEF, i % 10L, total, members));
        }
        return RankingSnapshotPayload.of(1L, EPOCH, 1L, 1L, new Ranking(total, entries));
    }

    private static void report(String label, RankingSnapshotPayload payload) {
        long min = Long.MAX_VALUE;
        long max = 0L;
        long sum = 0L;
        int size = 0;
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            RankingSnapshotPayload.CODEC.encode(buf, payload);
            long elapsed = System.nanoTime() - start;
            size = buf.readableBytes();
            buf.release();
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
            sum += elapsed;
        }
        assertTrue(size >= 0);
        System.out.printf("MEASURE snapshot[%s] bytes=%d entries=%d encodeUs(min/avg/max)=%.1f/%.1f/%.1f%n",
                label, size, payload.entries().size(), min / 1000.0, sum / 100.0 / 1000.0, max / 1000.0);
    }
}
