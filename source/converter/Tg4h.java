// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// tg4h (texture header) 360 -> PC conversion, plus a self-test against extracted PC pairs.
//   java Tg4h.java test <x360Dir> <pcDir>        (test-ps3 <ps3Dir> <pcDir> for PS3 headers)
//
// Layout (both platforms): fixed 0x30-byte header, a block of constant words from 0x30 to hdr[0x2C],
// a "tail" of references, then NUL-terminated strings: <aux strings...> <group> <name>.
//   0x00 u32 offset of the group string        0x18 u32 offset of the name string
//   0x04 u8 3, u8 platform (360=1, PC=0), u16 1
//   0x08,0x0C,0x10 u32                          0x14 u32 hash
//   0x1C u32 pixel data size                    0x20 u16 width, u16 height
//   0x24 u8,u8, u8 mip count, u8 version (360 = PC + 1; PS3 = PC)
// PS3 headers use the same big-endian layout without the 0x927E6ABF constant; texture data is already PC form.
//   0x28 u32 0x30 (start of constants)          0x2C u32 end of constants
// Constants: 360 has an extra word 0x927E6ABF that PC doesn't.
// Tail, PC: per aux string {u8 0, u32 offset}, then {u8 1, u32 data size}.
//           360: different packing; its byte at (constEnd + 4) is the offset of the first string.
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class Tg4h {
    static int be32(byte[] b, int o) { return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF); }
    static int be16(byte[] b, int o) { return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF); }

    public static byte[] convert(byte[] x) { return convert(x, false); }

    /** ps3 = the header comes from the PS3 release (its version byte already matches PC). */
    public static byte[] convert(byte[] x, boolean ps3) {
        int constEnd = be32(x, 0x2C);
        int strStart = x[constEnd + 4] & 0xFF;
        List<String> strs = new ArrayList<>();
        int s = strStart;
        for (int i = strStart; i < x.length; i++) if (x[i] == 0) { strs.add(new String(x, s, i - s, java.nio.charset.StandardCharsets.ISO_8859_1)); s = i + 1; }
        if (strs.size() < 3) throw new IllegalStateException("tg4h: expected >= 3 strings, got " + strs);
        int nAux = strs.size() - 2;

        List<Integer> consts = new ArrayList<>();
        for (int o = 0x30; o < constEnd; o += 4) { int w = be32(x, o); if (w != 0x927E6ABF) consts.add(w); }

        int pcConstEnd = 0x30 + consts.size() * 4;
        int tailLen = nAux * 5 + 5;
        int pcStrStart = pcConstEnd + tailLen;
        int[] off = new int[strs.size()];
        int p = pcStrStart;
        for (int i = 0; i < strs.size(); i++) { off[i] = p; p += strs.get(i).length() + 1; }
        int total = p;
        int dataSize = be32(x, 0x1C);

        Le w = new Le(total);
        w.u32(off[nAux]);                                  // group string
        w.u8(x[4] & 0xFF); w.u8(0); w.u16(be16(x, 6));
        w.u32(be32(x, 0x08)); w.u32(be32(x, 0x0C)); w.u32(be32(x, 0x10));
        w.u32(be32(x, 0x14));
        w.u32(off[nAux + 1]);                              // name string
        w.u32(dataSize);
        w.u16(be16(x, 0x20)); w.u16(be16(x, 0x22));
        w.u8(x[0x24] & 0xFF); w.u8(x[0x25] & 0xFF); w.u8(x[0x26] & 0xFF); w.u8((x[0x27] & 0xFF) - (ps3 ? 0 : 1));
        w.u32(0x30); w.u32(pcConstEnd);
        for (int c : consts) w.u32(c);
        for (int i = 0; i < nAux; i++) { w.u8(0); w.u32(off[i]); }
        w.u8(1); w.u32(dataSize);
        for (String str : strs) { w.bytes(str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)); w.u8(0); }
        return w.done();
    }

    static class Le {
        byte[] b; int p;
        Le(int n) { b = new byte[n]; }
        void u8(int v) { b[p++] = (byte) v; }
        void u16(int v) { u8(v); u8(v >> 8); }
        void u32(int v) { u16(v); u16(v >>> 16); }
        void bytes(byte[] v) { System.arraycopy(v, 0, b, p, v.length); p += v.length; }
        byte[] done() { if (p != b.length) throw new IllegalStateException("size " + p + " != " + b.length); return b; }
    }

    static boolean ps3Test;
    public static void main(String[] a) throws Exception {
        if (!a[0].equals("test") && !a[0].equals("test-ps3")) return;
        ps3Test = a[0].equals("test-ps3");
        Map<String, Path> pc = new HashMap<>();
        try (Stream<Path> s = Files.walk(Path.of(a[2]))) { s.filter(f -> f.toString().endsWith(".tg4h")).forEach(f -> pc.putIfAbsent(f.getFileName().toString(), f)); }
        int pairs = 0, exact = 0, exactButHash = 0, groupDiffers = 0, fail = 0, total = 0, errors = 0;
        Set<String> seen = new HashSet<>();
        try (Stream<Path> s = Files.walk(Path.of(a[1]))) {
            for (Path f : s.filter(q -> q.toString().endsWith(".tg4h")).sorted().toList()) {
                total++;
                byte[] c;
                try { c = convert(Files.readAllBytes(f), ps3Test); } catch (Exception e) { errors++; System.out.println("ERROR " + f + ": " + e); continue; }
                Path q = pc.get(f.getFileName().toString());
                if (q == null || !seen.add(f.getFileName().toString())) continue;
                pairs++;
                byte[] e = Files.readAllBytes(q);
                if (Arrays.equals(c, e)) { exact++; continue; }
                // same apart from the hash at 0x14?
                if (c.length == e.length) {
                    byte[] c2 = c.clone(); System.arraycopy(e, 0x14, c2, 0x14, 4);
                    if (Arrays.equals(c2, e)) { exactButHash++; continue; }
                }
                String gx = groupOf(c), ge = groupOf(e);
                if (!gx.equals(ge)) { groupDiffers++; continue; }
                fail++;
                if (fail <= 5) System.out.println("MISMATCH " + f.getFileName() + "\n  conv " + hex(c) + "\n  pc   " + hex(e));
            }
        }
        System.out.printf("converted %d (errors %d); pairs %d: exact %d, exact except hash %d, group string differs %d, MISMATCH %d%n",
                total, errors, pairs, exact, exactButHash, groupDiffers, fail);
    }
    static String groupOf(byte[] b) { int o = (b[0] & 0xFF) | (b[1] & 0xFF) << 8; int e = o; while (e < b.length && b[e] != 0) e++; return new String(b, o, e - o); }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02X", x)); return s.toString(); }
}
