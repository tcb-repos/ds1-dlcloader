// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Dead Space .str ("ols3" BE / "3slo" LE) container walker.
// Usage: java StrDump.java [-v] <file-or-dir>...
//   Prints a per-resource-type summary; -v also lists every resource.
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class StrDump {
    static boolean verbose;
    static Path extractDir;   // -x: write each resource as <dir>/<str>/<name>.<type> (+ .shdr)
    static String filter;     // -f: only extract resources whose name contains this
    static java.io.ByteArrayOutputStream cur;
    static int curWant;
    static Path curPath;

    static void flush() throws Exception {
        if (cur != null && curPath != null) Files.write(curPath, cur.toByteArray());
        cur = null; curPath = null;
    }

    // EA RefPack (0x10FB) decompressor
    static byte[] refpack(byte[] s, int p, int end) {
        int f = s[p] & 0xFF; p += 2;
        int n = (f & 0x80) != 0 ? 4 : 3;
        if ((f & 0x01) != 0) p += n;
        int size = 0;
        for (int i = 0; i < n; i++) size = (size << 8) | (s[p++] & 0xFF);
        byte[] o = new byte[size];
        int w = 0;
        while (p < end && w < size) {
            int b0 = s[p] & 0xFF, plain, len = 0, off = 0;
            if (b0 < 0x80) { int b1 = s[p + 1] & 0xFF; p += 2; plain = b0 & 3; len = ((b0 & 0x1C) >> 2) + 3; off = ((b0 & 0x60) << 3) + b1 + 1; }
            else if (b0 < 0xC0) { int b1 = s[p + 1] & 0xFF, b2 = s[p + 2] & 0xFF; p += 3; plain = b1 >> 6; len = (b0 & 0x3F) + 4; off = ((b1 & 0x3F) << 8) + b2 + 1; }
            else if (b0 < 0xE0) { int b1 = s[p + 1] & 0xFF, b2 = s[p + 2] & 0xFF, b3 = s[p + 3] & 0xFF; p += 4; plain = b0 & 3; len = ((b0 & 0x0C) << 6) + b3 + 5; off = ((b0 & 0x10) << 12) + (b1 << 8) + b2 + 1; }
            else if (b0 < 0xFC) { p++; plain = ((b0 & 0x1F) << 2) + 4; }
            else { p++; plain = b0 & 3; }
            for (int i = 0; i < plain && w < size; i++) o[w++] = s[p++];
            for (int i = 0; i < len && w < size; i++, w++) o[w] = o[w - off];
            if (b0 >= 0xFC) break;
        }
        return o;
    }
    static Map<String, long[]> total = new TreeMap<>(); // type -> {count, bytes}
    static Map<String, Integer> topTags = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-v")) { verbose = true; continue; }
            if (a.equals("-x")) { extractDir = Path.of(args[++i]); continue; }
            if (a.equals("-f")) { filter = args[++i].toLowerCase(); continue; }
            Path p = Path.of(a);
            if (Files.isDirectory(p)) try (Stream<Path> s = Files.walk(p)) { s.filter(f -> f.toString().endsWith(".str")).sorted().forEach(files::add); }
            else files.add(p);
        }
        for (Path f : files) dump(f);
        System.out.println("=== TOTAL by resource type (count, data bytes)");
        total.forEach((k, v) -> System.out.printf("  %-10s %6d %12d%n", k, v[0], v[1]));
        System.out.println("=== chunk tags seen: " + topTags);
    }

    static void dump(Path f) throws Exception {
        byte[] d = Files.readAllBytes(f);
        String magic = new String(d, 0, 4, "US-ASCII");
        boolean be = magic.equals("ols3");
        if (!be && !magic.equals("3slo")) { System.out.println(f + ": not a str (" + magic + ")"); return; }
        Rd r = new Rd(d, be);
        int hdr = r.u32(4);
        System.out.printf("--- %s  [%s ver=%08X size=%d]%n", f.getFileName(), be ? "BE" : "LE", r.u32(8), d.length);
        Map<String, long[]> per = new TreeMap<>();
        int o = hdr;
        String pendingType = null, pendingName = null;
        while (o + 8 <= d.length) {
            String tag = r.tag(o);
            int size = r.u32(o + 4);
            if (size < 8 || o + size > d.length) { if (verbose) System.out.printf("  stop @0x%X tag=%s size=%d%n", o, tag, size); break; }
            topTags.merge(tag, 1, Integer::sum);
            if (tag.equals("SHOC")) {
                String sub = r.tag(o + 8);
                topTags.merge("SHOC/" + sub, 1, Integer::sum);
                if (sub.equals("SHDR")) {
                    int p = o + 12;
                    long typeHash = r.u32(p) & 0xFFFFFFFFL;
                    int dataSize = r.u32(p + 0x1C);
                    List<String> strs = strings(d, p + 0x20, o + size);
                    String type = strs.isEmpty() ? String.format("%08X", typeHash) : strs.get(strs.size() - 1);
                    pendingType = type;
                    pendingName = strs.size() > 1 ? strs.get(1) : (strs.isEmpty() ? "?" : strs.get(0));
                    long[] t = per.computeIfAbsent(type, k -> new long[2]); t[0]++; t[1] += dataSize;
                    long[] g = total.computeIfAbsent(type, k -> new long[2]); g[0]++; g[1] += dataSize;
                    if (verbose) System.out.printf("  @%07X %-6s hash=%08X flags=%08X size=%8d  %s%n", o, type, typeHash, r.u32(p + 4), dataSize, String.join(" | ", strs));
                    if (extractDir != null) {
                        flush();
                        String nm = strs.isEmpty() ? String.format("res_%X", o) : strs.get(0);
                        String srcPath = strs.size() > 1 ? strs.get(1).toLowerCase() : "";
                        if (filter == null || nm.toLowerCase().contains(filter) || srcPath.contains(filter)) {
                            String fn = nm.replaceAll("[\\\\/:*?\"<>|{}]", "_");
                            if (!fn.toLowerCase().endsWith("." + type.toLowerCase())) fn += "." + type;
                            Path dir = extractDir.resolve(f.getFileName().toString());
                            Files.createDirectories(dir);
                            curPath = dir.resolve(fn);
                            Files.write(dir.resolve(fn + ".shdr"), Arrays.copyOfRange(d, o, o + size));
                            cur = new java.io.ByteArrayOutputStream();
                            curWant = dataSize;
                        }
                    }
                } else if (sub.equals("SDAT") && cur != null) {
                    cur.write(d, o + 12, Math.min(size - 12, curWant - cur.size()));
                } else if (sub.equals("Rpak") && cur != null) {
                    int clen = r.u32(o + 12);
                    byte[] u = refpack(d, o + 16, o + 16 + clen);
                    cur.write(u, 0, Math.min(u.length, curWant - cur.size()));
                } else if (verbose && !sub.equals("SDAT") && !sub.equals("Rpak")) {
                    System.out.printf("  @%07X SHOC/%s size=%d%n", o, sub, size);
                }
            } else if (verbose) {
                System.out.printf("  @%07X %s size=%d%n", o, tag, size);
            }
            o += size;
        }
        flush();
        per.forEach((k, v) -> System.out.printf("    %-10s x%-4d %10d bytes%n", k, v[0], v[1]));
    }

    static List<String> strings(byte[] d, int from, int to) {
        List<String> out = new ArrayList<>();
        int s = from;
        for (int i = from; i < to; i++) {
            if (d[i] == 0) {
                if (i > s) out.add(new String(d, s, i - s, java.nio.charset.StandardCharsets.ISO_8859_1));
                s = i + 1;
            }
        }
        return out;
    }

    record Rd(byte[] d, boolean be) {
        int u32(int o) {
            int a = d[o] & 0xFF, b = d[o + 1] & 0xFF, c = d[o + 2] & 0xFF, e = d[o + 3] & 0xFF;
            return be ? (a << 24 | b << 16 | c << 8 | e) : (e << 24 | c << 16 | b << 8 | a);
        }
        String tag(int o) {
            char[] c = new char[4];
            for (int i = 0; i < 4; i++) c[i] = (char) (d[be ? o + i : o + 3 - i] & 0xFF);
            return new String(c);
        }
    }
}
