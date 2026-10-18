package dev.pepsi.questrank.core.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Hash FNV-1a de 64 bits, exclusivamente para invalidación en memoria (contrato §8.2, §8.3).
 * No es un hash criptográfico: las colisiones son inherentes a su tamaño y aceptables para la
 * detección de cambios. No se persiste ni se transmite.
 *
 * <p>Codificación canónica (no ambigua por construcción):
 * <ul>
 *   <li>Enteros: big-endian; los valores con signo se codifican por sus bits en complemento a dos
 *       (sin campo de signo separado).</li>
 *   <li>Booleanos: un byte (0x00/0x01).</li>
 *   <li>UUID: 16 bytes = msb (8 big-endian) + lsb (8 big-endian).</li>
 *   <li>Cadenas: 4 bytes big-endian con la longitud en <em>bytes</em> UTF-8 + los bytes UTF-8.</li>
 *   <li>Colecciones: se codifican con el prefijo de cardinalidad en la clase que las recorre.</li>
 * </ul>
 * El consumo de bytes usa {@code & 0xFF} (sin extensión de signo).
 */
public final class Fnv1a64 {

    private static final long OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long PRIME = 0x100000001B3L;

    private long hash = OFFSET_BASIS;

    public Fnv1a64 putByte(int value) {
        hash ^= (value & 0xFF);
        hash *= PRIME;
        return this;
    }

    public Fnv1a64 putBoolean(boolean value) {
        return putByte(value ? 1 : 0);
    }

    public Fnv1a64 putInt(int value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            putByte((value >>> shift) & 0xFF);
        }
        return this;
    }

    public Fnv1a64 putLong(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            putByte((int) (value >>> shift) & 0xFF);
        }
        return this;
    }

    public Fnv1a64 putUuid(UUID value) {
        putLong(value.getMostSignificantBits());
        putLong(value.getLeastSignificantBits());
        return this;
    }

    public Fnv1a64 putString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(bytes.length);
        for (byte b : bytes) {
            putByte(b & 0xFF);
        }
        return this;
    }

    public long value() {
        return hash;
    }
}
