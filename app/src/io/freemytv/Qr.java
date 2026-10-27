package io.freemytv;

import java.nio.charset.StandardCharsets;

/**
 * Codificador de QR code só para mostrar o endereço do Controle pelo celular: modo byte, correção L, versões 1 a 6
 * (até 134 bytes). O resultado é a grade de módulos (true = escuro), sem a margem.
 */
final class Qr {
    private Qr() {}

    // Por versão (índice = versão - 1): palavras de dados, palavras de correção por bloco e número de blocos.
    private static final int[] DATA = {19, 34, 55, 80, 108, 136}, EC = {7, 10, 15, 20, 26, 18}, BLOCKS = {1, 1, 1, 1, 1, 2};
    // O centro do único padrão de alinhamento (o da versão 1 não tem).
    private static final int[] ALIGN = {0, 18, 22, 26, 30, 34};

    /** A grade para `text`, ou null se não couber em 134 bytes. */
    static boolean[][] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int v = 0;
        while (v < DATA.length && bytes.length + 2 > DATA[v]) v++;   // modo, tamanho e terminador gastam 2 palavras
        if (v == DATA.length) return null;
        byte[] all = interleave(dataWords(bytes, DATA[v]), v);
        int size = 17 + 4 * (v + 1);
        boolean[][] m = new boolean[size][size], fn = new boolean[size][size];
        drawFunctions(m, fn, v, size);
        place(m, fn, all, size);
        int best = 0, bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(m, fn, mask, size);
            drawFormat(m, fn, mask, size);
            int p = penalty(m, size);
            if (p < bestPenalty) {
                bestPenalty = p;
                best = mask;
            }
            applyMask(m, fn, mask, size);   // desfaz
        }
        applyMask(m, fn, best, size);
        drawFormat(m, fn, best, size);
        return m;
    }

    // MARK: - Dados e correção de erros

    private static byte[] dataWords(byte[] bytes, int capacity) {
        byte[] data = new byte[capacity];
        int[] pos = {0};
        put(data, pos, 4, 4);   // modo byte
        put(data, pos, bytes.length, 8);
        for (byte b : bytes) put(data, pos, b & 0xff, 8);
        pos[0] = Math.min(pos[0] + 4, capacity * 8);   // terminador
        pos[0] = (pos[0] + 7) & ~7;
        for (int i = pos[0] / 8, pad = 0xEC; i < capacity; i++, pad ^= 0xEC ^ 0x11) data[i] = (byte) pad;
        return data;
    }

    private static void put(byte[] buf, int[] pos, int value, int bits) {
        for (int i = bits - 1; i >= 0; i--, pos[0]++) buf[pos[0] >> 3] |= ((value >>> i) & 1) << (7 - (pos[0] & 7));
    }

    private static byte[] interleave(byte[] data, int v) {
        int blocks = BLOCKS[v], per = data.length / blocks, ec = EC[v];
        byte[][] ecs = new byte[blocks][];
        for (int b = 0; b < blocks; b++) ecs[b] = remainder(data, b * per, per, ec);
        byte[] out = new byte[data.length + blocks * ec];
        int n = 0;
        for (int i = 0; i < per; i++) for (int b = 0; b < blocks; b++) out[n++] = data[b * per + i];
        for (int i = 0; i < ec; i++) for (int b = 0; b < blocks; b++) out[n++] = ecs[b][i];
        return out;
    }

    /** Reed-Solomon sobre GF(256) (polinômio 0x11D): o resto da divisão dos dados pelo gerador. */
    private static byte[] remainder(byte[] data, int off, int len, int degree) {
        int[] gen = new int[degree];
        gen[degree - 1] = 1;
        for (int i = 0, root = 1; i < degree; i++, root = mul(root, 2)) {
            for (int j = 0; j < degree; j++) {
                gen[j] = mul(gen[j], root);
                if (j + 1 < degree) gen[j] ^= gen[j + 1];
            }
        }
        int[] rem = new int[degree];
        for (int i = off; i < off + len; i++) {
            int factor = (data[i] & 0xff) ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, degree - 1);
            rem[degree - 1] = 0;
            for (int j = 0; j < degree; j++) rem[j] ^= mul(gen[j], factor);
        }
        byte[] out = new byte[degree];
        for (int i = 0; i < degree; i++) out[i] = (byte) rem[i];
        return out;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // MARK: - A grade

    private static void set(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    private static void drawFunctions(boolean[][] m, boolean[][] fn, int v, int size) {
        for (int i = 0; i < size; i++) {
            set(m, fn, 6, i, i % 2 == 0);
            set(m, fn, i, 6, i % 2 == 0);
        }
        int[][] finders = {{3, 3}, {size - 4, 3}, {3, size - 4}};
        for (int[] f : finders) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = f[0] + dx, y = f[1] + dy, dist = Math.max(Math.abs(dx), Math.abs(dy));
                    if (x >= 0 && x < size && y >= 0 && y < size) set(m, fn, x, y, dist != 2 && dist != 4);
                }
            }
        }
        if (ALIGN[v] != 0) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(m, fn, ALIGN[v] + dx, ALIGN[v] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormat(m, fn, 0, size);   // só para marcar o lugar; o formato de verdade vem depois da máscara
    }

    /** Os 15 bits de formato (correção L e a máscara), nas duas cópias. */
    private static void drawFormat(boolean[][] m, boolean[][] fn, int mask, int size) {
        int data = (1 << 3) | mask, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(m, fn, 8, i, bit(bits, i));
        set(m, fn, 8, 7, bit(bits, 6));
        set(m, fn, 8, 8, bit(bits, 7));
        set(m, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, fn, 8, size - 15 + i, bit(bits, i));
        set(m, fn, 8, size - 8, true);   // o módulo escuro fixo
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    private static void place(boolean[][] m, boolean[][] fn, byte[] all, int size) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j, y = ((right + 1) & 2) == 0 ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < all.length * 8) {
                        m[y][x] = bit(all[i >>> 3] & 0xff, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean flip;
                switch (mask) {
                    case 0: flip = (x + y) % 2 == 0; break;
                    case 1: flip = y % 2 == 0; break;
                    case 2: flip = x % 3 == 0; break;
                    case 3: flip = (x + y) % 3 == 0; break;
                    case 4: flip = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: flip = x * y % 2 + x * y % 3 == 0; break;
                    case 6: flip = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: flip = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (flip && !fn[y][x]) m[y][x] = !m[y][x];
            }
        }
    }

    /** As quatro regras de pontuação da norma: quanto menor, melhor a leitura. */
    private static int penalty(boolean[][] m, int size) {
        int total = 0, dark = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1, window = 0;
                for (int b = 0; b < size; b++) {
                    boolean cell = pass == 0 ? m[a][b] : m[b][a];
                    if (pass == 0 && cell) dark++;
                    if (b > 0 && cell == (pass == 0 ? m[a][b - 1] : m[b - 1][a])) {
                        run++;
                        if (run == 5) total += 3;
                        else if (run > 5) total++;
                    } else {
                        run = 1;
                    }
                    window = ((window << 1) | (cell ? 1 : 0)) & 0x7FF;
                    if (b >= 10 && (window == 0x5D || window == 0x5D0)) total += 40;   // 1011101 com 4 claros de um lado
                }
            }
        }
        for (int y = 0; y + 1 < size; y++) {
            for (int x = 0; x + 1 < size; x++) {
                if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) total += 3;
            }
        }
        int cells = size * size;
        int steps = (Math.abs(dark * 20 - cells * 10) + cells - 1) / cells - 1;   // a cada 5% fora de 50% escuro
        return total + Math.max(0, steps) * 10;
    }
}
