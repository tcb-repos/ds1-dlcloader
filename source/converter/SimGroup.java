// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// SimGroup (.simgroup.bin) layout parser and 360 -> PC converter.
//
//   java SimGroup.java check <PC install>     parse every PC SimGroup and report coverage problems
//   (StrConvert calls SimGroup.convert for 360 resources)
//
// Layout (from the PC exe: loader 0x6F5D20, object builder 0x6F4AD0, property iterator 0x739FB0):
//   0x00 header, 12 u32: 0,0,'SimG',1,id,flags,objCount,?,tablesSize,stringsSize,objTableOff,?
//   0x30 object table: objCount u32 file offsets of object entries
//        tables: tablesSize bytes of u32 words (16-byte GUIDs, value tuples)
//        strings: stringsSize bytes, raw
//        property area: class blocks (see below)
//        object entries
//   Object entry (at a table offset): +0x00..0x1B runtime area (zero), +0x1C bytes [flags][?][c][d],
//        +0x20 relptr or 0 (schema block: u32 hash, u32 count, count u32), +0x24 relptr, +0x28 class hash, +0x2C (c + d) u32 relptrs; the last d point to class blocks.
//        (flags & 1) == 0 entries use a linked-list form instead (not seen in DLC files; reported).
//   Class block: u32 class hash, u16 field count, then a presence bitmask of (count+7)/8 bytes (bit set =
//        field absent), padded to 4 bytes from the block start, then one u32 per present field.
//        Larger values are self-relative offsets to data in the tables/strings regions.
// Conversion: every u32 is byte-swapped except the u16 count, the bitmask/padding bytes, the strings
// region and the 4 flag bytes of each entry, which are kept as they are. Class-block field values
// NN 00 00 00 (NN = 01..1F) are one-byte fields and keep their bytes, except the fields in U32_FIELDS,
// which the PC stores as 32-bit flags (checked against all 590 PC SimGroups by ByteFieldCheck.java).
import java.nio.file.*;
import java.util.*;

public class SimGroup {
    interface Rd { int u32(int o); int u16(int o); }

    // Byte classes per offset: 'S' swap u32 (on a 4-aligned start), 'h' u16 (swap 2), 'r' raw byte, 0 = unclassified
    static char[] classify(byte[] d, boolean be, List<String> problems) { return classify(d, be, problems, new HashMap<>()); }

    /** valueWords receives every class-block field value: offset -> "classhash:fieldindex" (hex hash, decimal index). */
    static char[] classify(byte[] d, boolean be, List<String> problems, Map<Integer, String> valueWords) {
        Rd r = be ? new Rd() {
            public int u32(int o) { return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16 | (d[o + 2] & 0xFF) << 8 | (d[o + 3] & 0xFF); }
            public int u16(int o) { return (d[o] & 0xFF) << 8 | (d[o + 1] & 0xFF); }
        } : new Rd() {
            public int u32(int o) { return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24; }
            public int u16(int o) { return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8; }
        };
        char[] c = new char[d.length];
        if (r.u32(8) != 0x53696D47) { problems.add("bad magic"); return c; }
        int count = r.u32(0x18), tablesSize = r.u32(0x20), stringsSize = r.u32(0x24), tbl = r.u32(0x28);
        mark(c, 0, 0x30, 'S', problems, "header");
        mark(c, tbl, tbl + count * 4, 'S', problems, "object table");
        int tablesStart = tbl + count * 4, stringsStart = tablesStart + tablesSize, propStart = stringsStart + stringsSize;
        mark(c, tablesStart, stringsStart, 'S', problems, "tables");
        mark(c, stringsStart, propStart, 'r', problems, "strings");
        Set<Integer> blocks = new TreeSet<>();
        int firstEntry = d.length;
        for (int i = 0; i < count; i++) {
            int e = r.u32(tbl + i * 4);
            firstEntry = Math.min(firstEntry, e);
            int flags = d[e + 0x1C] & 0xFF, skip = d[e + 0x1E] & 0xFF, nb = d[e + 0x1F] & 0xFF;
            mark(c, e, e + 0x1C, 'S', problems, "entry runtime area");
            mark(c, e + 0x1C, e + 0x20, 'r', problems, "entry flags");
            int end = e + 0x2C + (skip + nb) * 4;
            mark(c, e + 0x20, end, 'S', problems, "entry words");
            if ((flags & 1) == 0) { problems.add(String.format("entry @%X uses the linked-list form (flags %02X)", e, flags)); continue; }
            for (int k = 0; k < nb; k++) { int p = e + 0x2C + (skip + k) * 4; blocks.add(p + r.u32(p)); }
            // +0x20: relptr (or 0) to a schema block: u32 hash, u32 count, count x u32 (all plain u32 words)
            int sch = r.u32(e + 0x20);
            if (sch != 0) {
                int s = e + 0x20 + sch, n = r.u32(s + 4);
                if (s < propStart || s + 8 + n * 4 > firstEntryOf(r, tbl, count, d.length)) problems.add(String.format("schema block @%X out of range", s));
                else mark(c, s, s + 8 + n * 4, 'S', problems, "schema block");
            }
        }
        for (int b : blocks) {
            if (b < propStart || b >= firstEntry) { problems.add(String.format("class block @%X outside the property area", b)); continue; }
            int n = r.u16(b + 4), maskLen = (n + 7) >> 3;
            int vals = ((6 + maskLen + 3) & ~3) + b;
            int present = 0;
            for (int f = 0; f < n; f++) if ((d[b + 6 + (f >> 3)] & (1 << (f & 7))) == 0) present++;
            mark(c, b, b + 4, 'S', problems, "block hash");
            mark(c, b + 4, b + 6, 'h', problems, "block count");
            mark(c, b + 6, vals, 'r', problems, "block mask");
            mark(c, vals, vals + present * 4, 'S', problems, "block values");
            for (int f = 0, k = 0; f < n; f++)
                if ((d[b + 6 + (f >> 3)] & (1 << (f & 7))) == 0) valueWords.put(vals + (k++) * 4, String.format("%08x:%d", r.u32(b), f));
        }
        // Anything left in the property area / between entries: zero padding is fine, data is a problem.
        for (int o = propStart; o < d.length; o++)
            if (c[o] == 0 && d[o] != 0) { problems.add(String.format("unclassified non-zero byte @%X", o)); break; }
        return c;
    }

    static int firstEntryOf(Rd r, int tbl, int count, int len) {
        int m = len;
        for (int i = 0; i < count; i++) m = Math.min(m, r.u32(tbl + i * 4));
        return m;
    }

    static void mark(char[] c, int from, int to, char k, List<String> problems, String what) {
        if (from < 0 || to > c.length || from > to) { problems.add(String.format("%s range %X..%X out of bounds", what, from, to)); return; }
        for (int i = from; i < to; i++) {
            if (c[i] != 0 && c[i] != k) { problems.add(String.format("%s overlaps another region @%X", what, i)); return; }
            c[i] = k;
        }
    }

    /** Fields that are 32-bit flag words even when their console value looks like NN 00 00 00: the PC game's
     *  own SimGroups store them as 0xNN000000 too (ByteFieldCheck.java). Gun (332d5a20) field 10: the stock
     *  PC Ripper has 0x08000000; the console DLC Rippers have the same value, which must be swapped. */
    static final Set<String> U32_FIELDS = Set.of("332d5a20:10");

    /** 360 (big-endian) SimGroup -> PC. Throws if the layout doesn't parse cleanly. */
    public static byte[] convert(byte[] x) {
        List<String> problems = new ArrayList<>();
        Map<Integer, String> values = new HashMap<>();
        char[] c = classify(x, true, problems, values);
        if (!problems.isEmpty()) throw new IllegalStateException("SimGroup: " + problems);
        byte[] p = x.clone();
        for (int o = 0; o < x.length; ) {
            // A field value NN 00 00 00 with NN = 01..1F is a one-byte field (flag or small enum) plus
            // padding: keep its bytes. (As a u32 it would be over 16 million, as a float below 1e-20;
            // floats with zero low bits such as 2.0 = 40 00 00 00 start at 3F.)
            if (values.containsKey(o) && !U32_FIELDS.contains(values.get(o))
                    && x[o] >= 1 && x[o] <= 0x1F && x[o + 1] == 0 && x[o + 2] == 0 && x[o + 3] == 0) { o += 4; continue; }
            switch (c[o]) {
                case 'S' -> { if (o + 4 <= x.length) { p[o] = x[o + 3]; p[o + 1] = x[o + 2]; p[o + 2] = x[o + 1]; p[o + 3] = x[o]; } o += 4; }
                case 'h' -> { p[o] = x[o + 1]; p[o + 1] = x[o]; o += 2; }
                default -> o++;
            }
        }
        return p;
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("show")) {     // show <PC install> <key substring>: hex + class map of one PC SimGroup
            Map<String, StrConvert.PcLoc> idx = StrConvert.indexPcInstall(Path.of(a[1]), Path.of("tools", "pcindex.tsv"));
            for (var e : idx.entrySet()) {
                if (!e.getKey().contains("|simgroup|") || !e.getKey().contains(a[2])) continue;
                byte[] d = StrConvert.readPc(e.getValue())[1];
                List<String> pr = new ArrayList<>();
                char[] c = classify(d, false, pr);
                System.out.println(e.getKey() + " " + pr);
                for (int o = 0; o < d.length; o += 16) {
                    StringBuilder h = new StringBuilder(String.format("%04X: ", o)), k = new StringBuilder();
                    for (int i = o; i < Math.min(o + 16, d.length); i++) { h.append(String.format("%02X ", d[i])); k.append(c[i] == 0 ? (d[i] == 0 ? '.' : '?') : c[i]); }
                    System.out.println(h + " " + k);
                }
                return;
            }
            return;
        }
        if (!a[0].equals("check")) return;
        Map<String, StrConvert.PcLoc> idx = StrConvert.indexPcInstall(Path.of(a[1]), Path.of("tools", "pcindex.tsv"));
        int files = 0, clean = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        for (var e : idx.entrySet()) {
            if (!e.getKey().contains("|simgroup|")) continue;
            byte[] d = StrConvert.readPc(e.getValue())[1];
            files++;
            List<String> problems = new ArrayList<>();
            classify(d, false, problems);
            if (problems.isEmpty()) { clean++; continue; }
            for (String s : problems) kinds.merge(s.replaceAll("@[0-9A-F]+|[0-9A-F]+\\.\\.[0-9A-F]+|flags [0-9A-F]+", "#"), 1, Integer::sum);
            if (files - clean <= 5) System.out.println(e.getKey() + ": " + problems.subList(0, Math.min(3, problems.size())));
        }
        System.out.printf("PC SimGroups: %d, parse cleanly: %d%n", files, clean);
        kinds.forEach((k, v) -> System.out.println("  " + v + "x " + k));
    }
}
