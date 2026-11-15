// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// VFX (.vfb particle effect) 360 -> PC word-swap schema, learned from 360/PC pairs.
//   java VfxSchema.java learn <x360Dir> <pcDir> <schemaOut.txt>
//   java VfxSchema.java check <x360Dir> <pcDir> <schema.txt>
//
// Structure (360, big-endian):
//   0x00 char[16] name (raw bytes)  0x10 u32 version (9)  0x14 u32  0x18 u32  0x1C u8 entry count + 3 pad
//   0x20..0x4F header words, then (count-1) 0x24-byte entries from 0x50, then chunks.
//   Chunk: FourCC tag (swapped like a u32: ".prt" -> "trp."), then fields.
//   .prt: +0x04 hdrSize(0x1C) +0x08 +0x0C +0x10 nameAreaSize +0x14 recordSize, name area (raw bytes,
//         '#'-padded name) at +0x18, then the record.
// Each word is keyed by where it sits in that structure; the learned class says how to convert it:
//   S swap32, I keep bytes, H swap each u16. Unlisted keys default to S.
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class VfxSchema {
    static int be32(byte[] b, int o) { return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF); }

    static boolean isTag(byte[] x, int o) {
        return o + 4 <= x.length && x[o] == '.' && Character.isLowerCase(x[o + 1]) && Character.isLowerCase(x[o + 2]) && Character.isLowerCase(x[o + 3]);
    }

    /** Computes a structural key for every aligned word. "!I" = forced raw bytes (strings). */
    static String[] keys(byte[] x) {
        String[] k = new String[x.length / 4];
        int count = x.length > 0x1C ? x[0x1C] & 0xFF : 0;
        int entEnd = 0x50 + Math.max(0, count - 1) * 0x24;
        String tag = null; int tagOff = 0, nameEnd = 0, recStart = 0, recSize = 0;
        for (int o = 0; o + 4 <= x.length; o += 4) {
            if (o >= entEnd && isTag(x, o)) {
                tag = new String(x, o, 4, java.nio.charset.StandardCharsets.ISO_8859_1); tagOff = o;
                if (tag.equals(".prt") && o + 0x18 <= x.length) {
                    int nameArea = be32(x, o + 0x10); recSize = be32(x, o + 0x14);
                    recStart = o + 0x18 + nameArea;
                    nameEnd = o + 0x18 + Math.max(nameArea, 0x14);   // the '#'-padded name is always 20 bytes
                } else { recStart = 0; nameEnd = 0; }
            }
            String key;
            if (o < 0x10) key = "!I";
            else if (o < 0x50) key = "HDR+" + h(o);
            else if (o < entEnd) key = "ENT+" + h((o - 0x50) % 0x24);
            else if (tag == null) key = "PRE+" + h(o - entEnd);
            else if (tag.equals(".prt") && o >= tagOff + 0x18 && o < nameEnd) key = nameIsHash(x, tagOff + 0x18, nameEnd) && be32(x, o) != 0x23232323 ? "!S" : "!I";
            else if (tag.equals(".prt") && o >= recStart) key = ".prt/" + h(recSize) + "+" + h(o - recStart);
            else key = tag + "+" + h(o - tagOff);
            k[o / 4] = key;
        }
        return k;
    }
    static String h(int v) { return String.format("%04X", v); }

    // A .prt name field is '#'-padded text, or (for unnamed emitters) '#' words followed by a repeated
    // u32 hash. Text stays as bytes; a hash gets swapped. Decided by the first non-'#' word.
    static boolean nameIsHash(byte[] x, int from, int to) {
        for (int o = from; o + 4 <= to; o += 4) {
            if (be32(x, o) == 0x23232323) continue;
            for (int i = 0; i < 4; i++) { int c = x[o + i] & 0xFF; if (c < 0x20 || c >= 0x7F) return true; }
            return false;
        }
        return false;
    }

    static char cls(byte[] x, byte[] p, int o) {
        boolean id = x[o] == p[o] && x[o + 1] == p[o + 1] && x[o + 2] == p[o + 2] && x[o + 3] == p[o + 3];
        boolean sw = x[o] == p[o + 3] && x[o + 1] == p[o + 2] && x[o + 2] == p[o + 1] && x[o + 3] == p[o];
        boolean hh = x[o] == p[o + 1] && x[o + 1] == p[o] && x[o + 2] == p[o + 3] && x[o + 3] == p[o + 2];
        boolean lo = x[o] == p[o] && x[o + 1] == p[o + 1] && x[o + 2] == p[o + 3] && x[o + 3] == p[o + 2];  // [u8][u8][u16]
        boolean hi = x[o] == p[o + 1] && x[o + 1] == p[o] && x[o + 2] == p[o + 2] && x[o + 3] == p[o + 3];  // [u16][u8][u8]
        if (sw && id) return 'E';
        if (sw) return 'S';
        if (id) return 'I';
        if (hh) return 'H';
        if (lo) return 'L';
        if (hi) return 'F';
        return 'X';
    }

    static List<Path[]> pairs(String xd, String pd) throws Exception {
        Map<String, Path> pc = new HashMap<>();
        try (Stream<Path> s = Files.walk(Path.of(pd))) { s.filter(f -> f.toString().toLowerCase().endsWith(".vfx")).forEach(f -> pc.putIfAbsent(f.getFileName().toString().toLowerCase(), f)); }
        List<Path[]> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        try (Stream<Path> s = Files.walk(Path.of(xd))) {
            for (Path f : s.filter(q -> q.toString().toLowerCase().endsWith(".vfx")).sorted().toList()) {
                String n = f.getFileName().toString().toLowerCase();
                if (pc.containsKey(n) && seen.add(n)) out.add(new Path[]{f, pc.get(n)});
            }
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("learn")) learn(a[1], a[2], a[3]);
        else if (a[0].equals("values")) values(a[1], a[2], a[3].split(","));
        else if (a[0].equals("debug")) debug(a[1], a[2], a[3]);
        else check(a[1], a[2], a[3]);
    }

    // Shows the chunk context of the first few wrongly converted words.
    static void debug(String xd, String pd, String schemaPath) throws Exception {
        var schema = load(Path.of(schemaPath)); int shown = 0;
        for (Path[] pr : pairs(xd, pd)) {
            byte[] x = Files.readAllBytes(pr[0]), p = Files.readAllBytes(pr[1]); if (x.length != p.length) continue;
            byte[] c = convert(x, schema, null); String[] k = keys(x);
            for (int o = 0; o + 4 <= x.length; o += 4) {
                if (c[o] == p[o] && c[o + 1] == p[o + 1] && c[o + 2] == p[o + 2] && c[o + 3] == p[o + 3]) continue;
                if (shown++ >= 5) return;
                int t = o; while (t > 0 && !isTag(x, t)) t -= 4;
                System.out.printf("%s @%X key %s  (tag@%X nameArea=%X rec=%X)%n", pr[0].getFileName(), o, k[o / 4], t, be32(x, t + 0x10), be32(x, t + 0x14));
                for (int q = t; q < Math.min(o + 8, x.length); q += 4)
                    System.out.printf("   %4X %02X%02X%02X%02X -> %02X%02X%02X%02X  %s%n", q, x[q], x[q+1], x[q+2], x[q+3], p[q], p[q+1], p[q+2], p[q+3], k[q / 4]);
                break;
            }
        }
    }

    // Prints distinct 360 -> PC values for the given keys.
    static void values(String xd, String pd, String[] want) throws Exception {
        Map<String, Set<String>> m = new TreeMap<>();
        for (Path[] pr : pairs(xd, pd)) {
            byte[] x = Files.readAllBytes(pr[0]), p = Files.readAllBytes(pr[1]);
            if (x.length != p.length) continue;
            String[] k = keys(x);
            for (int i = 0; i < k.length; i++)
                for (String w : want)
                    if (k[i].equals(w)) {
                        int o = i * 4;
                        m.computeIfAbsent(w, q -> new TreeSet<>()).add(String.format("%02X%02X%02X%02X->%02X%02X%02X%02X", x[o], x[o+1], x[o+2], x[o+3], p[o], p[o+1], p[o+2], p[o+3]));
                    }
        }
        m.forEach((k, v) -> System.out.println(k + ": " + v));
    }

    static final String CLASSES = "SIHLF";

    /** Bitmask of the classes (CLASSES order) that turn x's word into p's word. */
    static int compatible(byte[] x, byte[] p, int o) {
        int m = 0;
        for (int c = 0; c < CLASSES.length(); c++) {
            byte[] t = new byte[4];
            byte[] src = Arrays.copyOfRange(x, o, o + 4);
            applyWord(src, 0, t, CLASSES.charAt(c));
            if (t[0] == p[o] && t[1] == p[o + 1] && t[2] == p[o + 2] && t[3] == p[o + 3]) m |= 1 << c;
        }
        return m;
    }

    static void applyWord(byte[] x, int o, byte[] p, char c) {
        System.arraycopy(x, o, p, o, 4);
        switch (c) {
            case 'S', 'X' -> { p[o] = x[o + 3]; p[o + 1] = x[o + 2]; p[o + 2] = x[o + 1]; p[o + 3] = x[o]; }
            case 'H' -> { p[o] = x[o + 1]; p[o + 1] = x[o]; p[o + 2] = x[o + 3]; p[o + 3] = x[o + 2]; }
            case 'L' -> { p[o + 2] = x[o + 3]; p[o + 3] = x[o + 2]; }
            case 'F' -> { p[o] = x[o + 1]; p[o + 1] = x[o]; }
            default -> { }
        }
    }

    // Per key: AND together the classes compatible with each sample, so the result fits all of them.
    static void learn(String xd, String pd, String out) throws Exception {
        Map<String, Integer> m = new TreeMap<>();
        Map<String, Integer> samples = new TreeMap<>();
        for (Path[] pr : pairs(xd, pd)) {
            byte[] x = Files.readAllBytes(pr[0]), p = Files.readAllBytes(pr[1]);
            if (x.length != p.length) continue;
            String[] k = keys(x);
            for (int i = 0; i < k.length; i++) {
                if (k[i].startsWith("!")) continue;
                m.merge(k[i], compatible(x, p, i * 4), (a, b) -> a & b);
                samples.merge(k[i], 1, Integer::sum);
            }
        }
        int none = 0;
        try (var w = Files.newBufferedWriter(Path.of(out))) {
            for (var e : m.entrySet()) {
                int mask = e.getValue();
                if (mask == 0) { none++; System.out.println("NO SINGLE CLASS FITS " + e.getKey() + " (" + samples.get(e.getKey()) + " samples)"); w.write(e.getKey() + " S\n"); continue; }
                char best = CLASSES.charAt(Integer.numberOfTrailingZeros(mask));
                w.write(e.getKey() + " " + best + "\n");
            }
        }
        System.out.println("keys " + m.size() + ", unresolved " + none + " -> " + out);
    }

    public static Map<String, Character> load(Path schema) throws Exception {
        Map<String, Character> s = new HashMap<>();
        for (String l : Files.readAllLines(schema)) { if (l.isBlank()) continue; String[] t = l.split(" "); s.put(t[0], t[1].charAt(0)); }
        return s;
    }

    /** Converts one 360 VFX. Keys never seen in training are collected in `unknown` (they default to swap32). */
    public static byte[] convert(byte[] x, Map<String, Character> schema, Set<String> unknown) {
        byte[] p = x.clone();
        String[] k = keys(x);
        for (int i = 0; i < k.length; i++) {
            int o = i * 4;
            char c;
            if (k[i].equals("!I")) c = 'I';
            else if (k[i].equals("!S")) c = 'S';
            else {
                Character s = schema.get(k[i]);
                if (s == null) { if (unknown != null) unknown.add(k[i]); c = 'S'; } else c = s;
            }
            applyWord(x, o, p, c);
        }
        return p;
    }

    static void check(String xd, String pd, String schemaPath) throws Exception {
        Map<String, Character> schema = load(Path.of(schemaPath));
        int ok = 0, bad = 0; long badWords = 0, words = 0;
        Map<String, Integer> badKeys = new TreeMap<>();
        for (Path[] pr : pairs(xd, pd)) {
            byte[] x = Files.readAllBytes(pr[0]), p = Files.readAllBytes(pr[1]);
            if (x.length != p.length) continue;
            byte[] c = convert(x, schema, null);
            words += x.length / 4;
            if (Arrays.equals(c, p)) { ok++; continue; }
            bad++;
            String[] k = keys(x);
            for (int o = 0; o + 4 <= x.length; o += 4)
                if (c[o] != p[o] || c[o + 1] != p[o + 1] || c[o + 2] != p[o + 2] || c[o + 3] != p[o + 3]) { badWords++; badKeys.merge(k[o / 4], 1, Integer::sum); }
        }
        System.out.printf("pairs exact %d, differing %d; wrong words %d of %d%n", ok, bad, badWords, words);
        badKeys.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15).forEach(e -> System.out.println("  " + e.getKey() + " x" + e.getValue()));
    }
}
