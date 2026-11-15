// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Material (magic 0AA0DEED) converter: 360 version 15 (big-endian) -> PC version 16 (little-endian).
//
// 360 v15 layout:
//   0x00 magic, 0x04 version, 0x08..0x2C u32 words (0x28 = u16 pair), 0x2C shader hash,
//   0x30 u16 pair, 0x34 dataOff, 0x38 strOff, 0x3C nParams,
//   0x40 nParams records of 7 u32: nameHash, type, nameStr, 0, 0, dataOffset, valueStr
//   data block at dataOff (16-aligned, u32 values), string pool at strOff (raw) to the end.
// PC v16 differences:
//   4 words inserted after 0x30: hashA, u16 pair (n, b), hashB, u16 pair (n + b, c)
//   b + c records appended: copies of some params (extra passes), with their data copied to the end
//   of the data block. Records start at 0x50, data is 16-aligned after them. Layout 264add01 uses
//   b = 0 (one extra list); layout 363a388f repeats the whole list as two passes (b and c).
// hashA, hashB, b and which params get copied depend on (layout word 0x1C, shader word 0x2C); the
// table is learned from the PC install's materials (tools/schema/material.txt). Copied params are
// named "name#k" = the k-th param with that name (names can repeat).
//
// Usage: java Material.java learn <pcDir...>           write tools/schema/material.txt
//        java Material.java check <x360Dir> <pcDir>     convert same-named pairs and compare
//        java Material.java convert <in> <out>
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class Material {
    static final Path TABLE = StrConvert.toolsDir().resolve("schema").resolve("material.txt");

    static int rd(byte[] b, int o, boolean be) {
        return be ? (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255)
                  : (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24;
    }
    static void le(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >>> 24); }
    static String str(byte[] b, int o) { int e = o; while (e < b.length && b[e] != 0) e++; return new String(b, o, e - o); }

    /** a = hashA, b = hashB, passB = how many of the copies form the first extra pass, extra = "name#k" in order */
    record Rule(int a, int b, int passB, List<String> extra) {}

    static Map<String, Rule> table() throws Exception {
        Map<String, Rule> m = new HashMap<>();
        for (String l : Files.readAllLines(TABLE)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            m.put(f[0], new Rule(Integer.parseUnsignedInt(f[1], 16), Integer.parseUnsignedInt(f[2], 16), Integer.parseInt(f[3]),
                f[4].isEmpty() ? List.of() : List.of(f[4].split(","))));
        }
        return m;
    }

    /** index of the k-th param named nm ("nm#k"), or -1 */
    static int find(String[] names, String ref) {
        int h = ref.lastIndexOf('#');
        String nm = ref.substring(0, h); int k = Integer.parseInt(ref.substring(h + 1));
        for (int i = 0; i < names.length; i++) if (names[i].equals(nm) && k-- == 0) return i;
        return -1;
    }
    static Map<String, Rule> cached;

    static byte[] convert(byte[] x) throws Exception {
        if (cached == null) cached = table();
        if (rd(x, 0, true) != 0x0AA0DEED || rd(x, 4, true) != 15) throw new IllegalArgumentException("not a v15 360 material");
        String shader = String.format("%08x:%08x", rd(x, 0x1C, true), rd(x, 0x2C, true));
        Rule r = cached.get(shader);
        if (r == null) throw new IllegalArgumentException("layout:shader " + shader + " not seen on PC");
        int dataOff = rd(x, 0x34, true), strOff = rd(x, 0x38, true), n = rd(x, 0x3C, true);
        int dataLen = strOff - dataOff;
        // params, and which ones get copied
        int[][] rec = new int[n][7];
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            for (int w = 0; w < 7; w++) rec[i][w] = rd(x, 0x40 + i * 28 + w * 4, true);
            names[i] = str(x, strOff + rec[i][2]);
        }
        List<int[]> extra = new ArrayList<>();
        int extraData = 0;
        for (String nm : r.extra()) {
            int k = find(names, nm);
            if (k < 0) throw new IllegalArgumentException("param " + nm + " missing for " + shader);
            extra.add(new int[]{k});
            extraData += paramSize(rec, k, n, dataLen);
        }
        int total = n + extra.size();
        int recEnd = 0x50 + total * 28;
        int newData = (recEnd + 15) & ~15;
        int newStr = newData + dataLen + extraData;
        int poolLen = x.length - strOff;
        byte[] p = new byte[newStr + poolLen];
        // header: words 0x00..0x2C swap32 except 0x28 (u16 pair); 0x30 u16 pair
        for (int o = 0; o < 0x34; o += 4) {
            if (o == 0x28 || o == 0x30) { p[o] = x[o + 1]; p[o + 1] = x[o]; p[o + 2] = x[o + 3]; p[o + 3] = x[o + 2]; }
            else le(p, o, rd(x, o, true));
        }
        le(p, 4, 16);
        int pb = r.passB(), pc = extra.size() - pb;
        le(p, 0x34, r.a()); le(p, 0x38, n | pb << 16); le(p, 0x3C, r.b()); le(p, 0x40, (n + pb) | pc << 16);
        le(p, 0x44, newData); le(p, 0x48, newStr); le(p, 0x4C, total);
        for (int i = 0; i < n; i++) for (int w = 0; w < 7; w++) le(p, 0x50 + i * 28 + w * 4, rec[i][w]);
        int at = dataLen;
        for (int e = 0; e < extra.size(); e++) {
            int k = extra.get(e)[0];
            int[] c = rec[k].clone();
            int sz = paramSize(rec, k, n, dataLen);
            for (int w = 0; w < 7; w++) le(p, 0x50 + (n + e) * 28 + w * 4, c[w]);
            le(p, 0x50 + (n + e) * 28 + 20, at);
            for (int o = 0; o < sz; o += 4) le(p, newData + at + o, rd(x, dataOff + rec[k][5] + o, true));
            at += sz;
        }
        for (int o = 0; o + 4 <= dataLen; o += 4) le(p, newData + o, rd(x, dataOff + o, true));
        System.arraycopy(x, strOff, p, newStr, poolLen);
        return p;
    }

    static int sizeAt(int[] off, int total, int k, int dataLen) {
        int next = dataLen;
        for (int i = 0; i < total; i++) if (off[i] > off[k] && off[i] < next) next = off[i];
        return next - off[k];
    }

    // a param's data size: distance to the next param's data offset (params are laid out in order)
    static int paramSize(int[][] rec, int k, int n, int dataLen) {
        int start = rec[k][5], next = dataLen;
        for (int i = 0; i < n; i++) if (rec[i][5] > start && rec[i][5] < next) next = rec[i][5];
        return next - start;
    }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "convert" -> Files.write(Path.of(a[2]), convert(Files.readAllBytes(Path.of(a[1]))));
            case "learn" -> learn(Arrays.copyOfRange(a, 1, a.length));
            case "check" -> check(Path.of(a[1]), Path.of(a[2]));
        }
    }

    /** learn --pc <PC install> | <dirs of extracted .Material files...> */
    static void learn(String[] dirs) throws Exception {
        Map<String, Map<String, Integer>> seen = new TreeMap<>();
        List<byte[]> all = new ArrayList<>();
        if (dirs.length == 2 && dirs[0].equals("--pc")) {          // every material in the PC install
            StrConvert.setup(Path.of(dirs[1]));
            for (var e : StrConvert.pcIndex.entrySet())
                if (e.getKey().split("\\|")[1].equals("material"))
                    try { all.add(StrConvert.readPc(e.getValue())[1]); } catch (IllegalStateException ignored) {}
            System.out.println(all.size() + " PC materials read");
        } else for (String d : dirs) {
            try (Stream<Path> s = Files.walk(Path.of(d))) { for (Path f : s.filter(p -> p.toString().endsWith(".Material")).toList()) all.add(Files.readAllBytes(f)); }
        }
        {
            for (byte[] b : all) {
                if (rd(b, 0, false) != 0x0AA0DEED || rd(b, 4, false) != 16) continue;
                String shader = String.format("%08x:%08x", rd(b, 0x1C, false), rd(b, 0x2C, false));
                int n = rd(b, 0x38, false) & 0xFFFF, pb = rd(b, 0x38, false) >>> 16, total = rd(b, 0x4C, false);
                int dataOff = rd(b, 0x44, false), strOff = rd(b, 0x48, false);
                if (n > total || 0x50 + total * 28 > b.length) continue;
                String[] names = new String[total];
                for (int i = 0; i < total; i++) names[i] = str(b, strOff + rd(b, 0x50 + i * 28 + 8, false));
                // data size of param i (by the next higher data offset)
                int[] off = new int[total];
                for (int i = 0; i < total; i++) off[i] = rd(b, 0x50 + i * 28 + 20, false);
                List<String> ex = new ArrayList<>();
                boolean[] used = new boolean[n];
                for (int i = n; i < total; i++) {
                    // the original this copy came from: same record (except the data offset) and same data
                    int match = -1;
                    for (int j = 0; j < n && match < 0; j++) {
                        boolean same = names[j].equals(names[i]);
                        for (int w : new int[]{0, 1, 2, 3, 4, 6}) same &= rd(b, 0x50 + j * 28 + w * 4, false) == rd(b, 0x50 + i * 28 + w * 4, false);
                        int sz = sizeAt(off, total, j, strOff - dataOff);
                        same &= dataOff + off[i] + sz <= b.length && Arrays.equals(b, dataOff + off[j], dataOff + off[j] + sz, b, dataOff + off[i], dataOff + off[i] + sz);
                        if (same && !used[j]) match = j;
                    }
                    if (match < 0) for (int j = 0; j < n; j++) if (names[j].equals(names[i])) { match = j; break; }   // data differs: first by name
                    if (match < 0) { ex = null; break; }
                    if (match < n) used[match] = true;
                    int occ = 0; for (int j = 0; j < match; j++) if (names[j].equals(names[match])) occ++;
                    ex.add(names[match] + "#" + occ);
                }
                if (ex == null) continue;
                String key = String.format("%08x\t%08x\t%d\t%s", rd(b, 0x34, false), rd(b, 0x3C, false), pb, String.join(",", ex));
                seen.computeIfAbsent(shader, k -> new TreeMap<>()).merge(key, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder("# layout(0x1C):shader(0x2C)\thashA\thashB\tfirst-pass copies\tcopied params name#k   (learned from PC materials by Material.java learn)\n");
        seen.forEach((sh, m) -> {
            if (m.size() > 1) System.out.println("layout:shader " + sh + " has " + m.size() + " variants: " + m);
            String best = m.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            sb.append(sh).append('\t').append(best).append('\n');
        });
        Files.createDirectories(TABLE.getParent());
        Files.writeString(TABLE, sb.toString());
        System.out.println(seen.size() + " shaders written to " + TABLE);
    }

    static void check(Path xd, Path pd) throws Exception {
        Map<String, Path> pc = new HashMap<>();
        try (Stream<Path> s = Files.walk(pd)) { s.filter(p -> p.toString().endsWith(".Material")).forEach(p -> pc.putIfAbsent(p.getFileName().toString().toLowerCase(), p)); }
        List<Path> xs;
        try (Stream<Path> s = Files.walk(xd)) { xs = s.filter(p -> p.toString().endsWith(".Material")).sorted().toList(); }
        Set<String> done = new HashSet<>();
        int pairs = 0, exact = 0, fails = 0, conv = 0;
        for (Path xp : xs) {
            String nm = xp.getFileName().toString().toLowerCase();
            byte[] x = Files.readAllBytes(xp), y;
            try { y = convert(x); conv++; } catch (Exception e) { fails++; System.out.println("FAIL " + xp + ": " + e.getMessage()); continue; }
            Path pp = pc.get(nm);
            if (pp == null || !done.add(nm)) continue;
            pairs++;
            byte[] p = Files.readAllBytes(pp);
            if (Arrays.equals(y, p)) { exact++; continue; }
            List<String> bad = new ArrayList<>();
            for (int o = 0; o < Math.min(y.length, p.length) && bad.size() < 12; o += 4)
                if (!Arrays.equals(y, o, Math.min(o + 4, y.length), p, o, Math.min(o + 4, p.length))) bad.add(Integer.toHexString(o));
            System.out.println(nm + ": size " + y.length + " vs " + p.length + ", differs at " + bad);
        }
        System.out.println("converted " + conv + ", failed " + fails + "; pairs " + pairs + ", exact " + exact);
    }
}
