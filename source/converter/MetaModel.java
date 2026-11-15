// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// MetaModel ('MMdl', version 14) converter, 360 (big-endian) -> PC (little-endian).
//
// Layout worked out from the 360/PC pairs (see NOTES.md, "MetaModel format"):
//   header 0x00-0x67: u32 words, except 0x58-0x63 = six u16 counts c1..c6
//   six tables at the offsets in header words 0x34,0x38,0x3C,0x40,0x44,0x48:
//     t1: c1 x 8 u32                         (hash lists)
//     t2: c2 x 12 words, words 8 and 11 are u16 pairs
//     t3: c3 x 3 words, word 2 is a u16 pair
//     t4: c4 x 6 u32 (property records), then c4 u32 pointers to them
//     t5: c5 x 4 words, word 2 is a u16 pair
//     t6: c6 x 9 words, word 8 is a u16 pair, then c6 u32 pointers
//   after the last table: the string pool, raw bytes.
// Everything that is not a u16 pair or a string is a 32-bit word (offsets, hashes, GUID words).
//
// Usage: java MetaModel.java check <x360Dir> <pcDir>     convert every same-named pair and compare
//        java MetaModel.java convert <in> <out>
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class MetaModel {
    static int be32(byte[] b, int o) { return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF); }
    static int be16(byte[] b, int o) { return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF); }

    // word classes: 'S' swap32, 'H' two swap16, 'R' raw
    static char[] classify(byte[] x) {
        if (x.length < 0x68 || be32(x, 0) != 0x4D4D646C) throw new IllegalArgumentException("not an MMdl MetaModel");
        int n = x.length / 4;
        char[] c = new char[n];
        Arrays.fill(c, 'S');
        for (int o = 0x58; o < 0x68; o += 4) c[o / 4] = 'H';
        int[] cnt = new int[7];
        for (int i = 0; i < 7; i++) cnt[i] = be16(x, 0x58 + i * 2);
        int[] words = {8, 12, 3, 6, 3, 9, 9};
        int[][] half = {{}, {8, 11}, {2}, {}, {2}, {8}, {8}};
        int[][] strRefs = {{}, {1, 2}, {0}, {1, 2}, {}, {1, 2}, {1, 2}};   // words holding string-pool offsets
        int end = 0x68, lastStr = -1, firstStr = Integer.MAX_VALUE;
        List<Integer> propValues = new ArrayList<>();      // table 4 value words (may be string offsets)
        for (int t = 0; t < 7; t++) {
            int off = be32(x, 0x34 + t * 4);
            if (cnt[t] == 0) continue;
            if (off < 0x68 || off % 4 != 0) throw new IllegalArgumentException("table " + (t + 1) + " offset " + Integer.toHexString(off));
            for (int e = 0; e < cnt[t]; e++) {
                int base = off + e * words[t] * 4;
                for (int h : half[t]) c[(base + h * 4) / 4] = 'H';
                for (int s : strRefs[t]) { int r = be32(x, base + s * 4); lastStr = Math.max(lastStr, r); firstStr = Math.min(firstStr, r); }
                // property record [value, name, owner, type, hash, size]: the value is a u32 (also the
                // size-2 Visibility/VisibilityMask bit masks: the PC's own helmets store e.g. 0x00100001
                // as 01 00 10 00), and may point at a string
                if (t == 3) propValues.add(be32(x, base));
            }
            end = Math.max(end, off + cnt[t] * words[t] * 4);
        }
        if (end > x.length || lastStr >= x.length) throw new IllegalArgumentException("tables run past the end");
        // Pointer lists between the tables and the strings are u32 (the default). The string pool runs
        // from the first referenced string (rounded down to 4) to the end of the last one (rounded up).
        // Anything after it (float data) is 32-bit words.
        if (lastStr >= 0) {
            if (firstStr < end) throw new IllegalArgumentException("string ref inside the tables");
            int z = lastStr; while (z < x.length && x[z] != 0) z++;
            int poolEnd = (z + 1 + 3) & ~3;
            // string-valued properties (e.g. a .meta.xml path) put their strings right after the names:
            // extend the pool while a property value points at a string starting at its end
            for (boolean grew = true; grew; ) {
                grew = false;
                for (int v : propValues)
                    if (v > z && v <= poolEnd && v < x.length && x[v - 1] == 0 && isText(x, v)) {
                        int e = v; while (e < x.length && x[e] != 0) e++;
                        z = e; poolEnd = (e + 1 + 3) & ~3; grew = true;
                    }
            }
            for (int o = firstStr & ~3; o < poolEnd && o + 4 <= x.length; o += 4) c[o / 4] = 'R';
        }
        return c;
    }

    /** printable ASCII (at least 2 chars) up to a NUL: a string, not float data that follows the pool */
    static boolean isText(byte[] x, int v) {
        int e = v;
        while (e < x.length && x[e] != 0) { if (x[e] < 0x20 || x[e] > 0x7E) return false; e++; }
        return e < x.length && e - v >= 2;
    }

    static byte[] convert(byte[] x) {
        char[] c = classify(x);
        byte[] p = x.clone();
        for (int i = 0; i < c.length; i++) {
            int o = i * 4;
            if (c[i] == 'S') { p[o] = x[o + 3]; p[o + 1] = x[o + 2]; p[o + 2] = x[o + 1]; p[o + 3] = x[o]; }
            else if (c[i] == 'H') { p[o] = x[o + 1]; p[o + 1] = x[o]; p[o + 2] = x[o + 3]; p[o + 3] = x[o + 2]; }
        }
        return p;
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("convert")) { Files.write(Path.of(a[2]), convert(Files.readAllBytes(Path.of(a[1])))); return; }
        Map<String, Path> pc = new HashMap<>();
        try (Stream<Path> s = Files.walk(Path.of(a[2]))) { s.filter(p -> p.toString().endsWith(".MetaModel")).forEach(p -> pc.putIfAbsent(p.getFileName().toString().toLowerCase(), p)); }
        Set<String> seen = new HashSet<>();
        int pairs = 0, exact = 0;
        List<Path> xs;
        try (Stream<Path> s = Files.walk(Path.of(a[1]))) { xs = s.filter(p -> p.toString().endsWith(".MetaModel")).sorted().toList(); }
        for (Path xp : xs) {
            String n = xp.getFileName().toString().toLowerCase();
            byte[] x = Files.readAllBytes(xp);
            String status;
            try { convert(x); status = "parses"; } catch (Exception e) { status = "FAIL " + e.getMessage(); }
            Path pp = pc.get(n);
            if (pp == null || !seen.add(n)) { if (!status.equals("parses")) System.out.println(n + ": " + status); continue; }
            pairs++;
            byte[] p = Files.readAllBytes(pp);
            if (!status.equals("parses")) { System.out.println(n + ": " + status); continue; }
            byte[] y = convert(x);
            List<String> bad = new ArrayList<>();
            if (y.length != p.length) bad.add("size " + y.length + " vs " + p.length);
            else for (int o = 0; o < y.length; o += 4)
                if (!Arrays.equals(y, o, Math.min(o + 4, y.length), p, o, Math.min(o + 4, p.length))) bad.add(Integer.toHexString(o));
            if (bad.isEmpty()) exact++;
            System.out.println(n + ": " + (bad.isEmpty() ? "EXACT" : "differs at " + bad));
        }
        System.out.println("pairs " + pairs + ", exact " + exact);
    }
}
