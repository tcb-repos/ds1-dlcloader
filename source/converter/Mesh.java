// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Mesh ('EAGM' version 0x21) converter, 360 (big-endian, platform 1) -> PC (little-endian, platform 3).
// See NOTES.md "Mesh format". Summary of what is known:
//   header 0x00-0xFF   u32, except u16 pairs at 0x3C/0x40/0x44; 0x08 = platform (360 1, PC 3)
//     0x0C file size, 0x20 string pool, 0x34 part count, 0x38 bone count, 0x4C relocation table,
//     0x50 part table (0xA0 bytes per part), 0x54 bounds, 0x60 bone table (16 bytes per bone),
//     0x64 bind matrices (64 bytes per bone), 0x68 vertex stream descriptors, 0x6C index descriptor
//   part (0xA0): +0x04 and +0x28 raw bytes, +0x24 +0x7C u16 pairs, +0x20 bounds (8 x vec4),
//     +0x30 draw record (u32 index count, u32 first index, u16 first vertex, u16 vertex count,
//     u16 code (PS3 5, else 4), u16 range), +0x38 the part's vertex block, +0x40 its first index,
//     +0x48 its bone remap bytes (raw; all parts' remaps sit together before the bone table)
//   Streams are global: the UV stream and the u16 index buffer cover all parts.
//   stream descriptor (16): size, count, u16 stride + 2 raw bytes, offset
//   index descriptor (16): byte size, count, u16 + 2 raw bytes, offset; indices are u16
//   skinned vertex (descriptor strides 0x14 / 0x08), main stream 32 bytes per vertex:
//     pos 3 x f32 | normal packed u32 (same bits) | tangent: 360 4 x s8 (x,y,z,w) -> PC 10:10:10:2 |
//     bone indices 4 x u8 | weights 4 x u16 ... (last 12 bytes checked below)
//     second stream: UV 2 x f32
//   relocation table (at 0x4C to the end): pairs of u32 (location, value)
//
// Usage: java Mesh.java check <x360file> <pcfile>      convert and compare word by word
//        java Mesh.java convert <in> <out>
import java.nio.file.*;
import java.util.*;

public class Mesh {
    static int be32(byte[] b, int o) { return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255); }
    static int be16(byte[] b, int o) { return (b[o] & 255) << 8 | (b[o + 1] & 255); }
    static void le32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >>> 24); }

    // per-byte region tags, for the check output
    static String[] tag;
    static final int PART = 0xA0;

    static byte[] convert(byte[] x) { return convert(x, false); }

    /** ps3: the mesh comes from the PS3 release (platform 2; tangent bytes stored w,z,y,x). */
    static boolean ps3Src;
    static byte[] convert(byte[] x, boolean ps3) {
        ps3Src = ps3;
        if (be32(x, 0) != 0x4541474D || be32(x, 4) != 0x21) throw new IllegalArgumentException("not an EAGM v21 mesh");
        if (be32(x, 0x0C) != x.length) throw new IllegalArgumentException("size field " + be32(x, 0x0C) + " != " + x.length);
        int n = x.length;
        char[] c = new char[n / 4];          // S swap32, H swap16 pairs, R raw, V vertex (handled separately)
        tag = new String[n / 4];
        Arrays.fill(c, 'S'); Arrays.fill(tag, "other");
        byte[] p = x.clone();
        for (int o = 0; o < 0x100; o += 4) tag[o / 4] = "header";
        for (int o : new int[]{0x3C, 0x40, 0x44}) c[o / 4] = 'H';

        int parts = be32(x, 0x34), partOff = be32(x, 0x50);
        int strOff = be32(x, 0x20), relOff = be32(x, 0x4C);
        int boneOff = be32(x, 0x60), descOff = be32(x, 0x68), idxDesc = be32(x, 0x6C);
        // stream descriptors run from descOff up to the index descriptor
        List<int[]> streams = new ArrayList<>();
        for (int d = descOff; d + 16 <= idxDesc; d += 16) {
            if (be32(x, d) == 0) continue;
            streams.add(new int[]{be32(x, d), be32(x, d + 4), be16(x, d + 8), be32(x, d + 12), x[d + 10], x[d + 11]});
            u16raw(c, tag, d + 8, "desc");
        }
        u16raw(c, tag, idxDesc + 8, "desc");
        if (!(streams.size() == 2 && streams.get(0)[2] == 0x14 && streams.get(1)[2] == 8)) {
            StringBuilder sb = new StringBuilder();
            for (int[] s : streams) sb.append(String.format(" [stride %x count %d]", s[2], s[1]));
            throw new IllegalArgumentException("vertex format not supported yet:" + sb);
        }
        // indices: one u16 buffer shared by all parts
        int idxBytes = be32(x, idxDesc), idxStart = be32(x, idxDesc + 12);
        if (idxStart == 0) idxStart = be32(x, partOff + 0x40);
        if (idxStart + idxBytes > n) throw new IllegalArgumentException("index buffer out of range");
        for (int o = idxStart; o + 2 <= idxStart + idxBytes; o += 2) { p[o] = x[o + 1]; p[o + 1] = x[o]; }
        for (int o = idxStart & ~3; o < ((idxStart + idxBytes + 3) & ~3); o += 4) { c[o / 4] = 'V'; tag[o / 4] = "index"; }
        // parts (0xA0 each); +0x30 points to the part's draw record:
        //   u32 index count, u32 first index, u16 first vertex, u16 vertex count, u16 code, u16 range
        int totalVerts = 0, remapStart = Integer.MAX_VALUE;
        for (int i = 0; i < parts; i++) {
            int q = partOff + i * PART;
            for (int o = q; o < q + PART; o += 4) tag[o / 4] = "part";
            c[(q + 4) / 4] = 'R'; c[(q + 0x28) / 4] = 'R';
            for (int o : new int[]{0x24, 0x7C}) c[(q + o) / 4] = 'H';
            int rec = be32(x, q + 0x30);
            if (rec <= 0 || rec + 16 > n) throw new IllegalArgumentException("part " + i + " draw record out of range");
            c[(rec + 8) / 4] = 'H'; c[(rec + 12) / 4] = 'H';
            for (int o = rec; o < rec + 16; o += 4) tag[o / 4] = "draw";
            int vtx = be32(x, q + 0x38), remap = be32(x, q + 0x48), count = be16(x, rec + 10);
            if (remap > 0) remapStart = Math.min(remapStart, remap);
            if (vtx + count * 32 > n) throw new IllegalArgumentException("part " + i + " vertices out of range");
            for (int v = 0; v < count; v++) skinnedVertex(x, p, vtx + v * 32);
            for (int o = vtx; o < vtx + count * 32; o += 4) { c[o / 4] = 'V'; tag[o / 4] = "vtx1"; }
            totalVerts += count;
        }
        if (totalVerts != streams.get(0)[1]) throw new IllegalArgumentException("part vertex counts " + totalVerts + " != " + streams.get(0)[1]);
        // bone remap bytes of all parts: raw, up to the bone table
        if (remapStart < boneOff) for (int o = remapStart; o < boneOff; o += 4) { c[o / 4] = 'R'; tag[o / 4] = "remap"; }
        // second stream (UV, f32 pairs) for all vertices: default S
        int uv = streams.get(1)[3];
        for (int o = uv; o < uv + totalVerts * 8 && o < n; o += 4) tag[o / 4] = "vtx2";
        // string pool: raw, from strOff to the next 16-aligned block that is a stream (or the relocations)
        int strEnd = relOff;
        for (int[] s : streams) if (s[3] > strOff && s[3] < strEnd) strEnd = s[3];
        for (int o = strOff; o < strEnd; o += 4) { c[o / 4] = 'R'; tag[o / 4] = "strings"; }
        for (int o = relOff; o < n; o += 4) tag[o / 4] = "reloc";
        if (boneOff > 0) for (int o = boneOff; o < be32(x, 0x64); o += 4) tag[o / 4] = "bones";
        if (be32(x, 0x64) > 0) for (int o = be32(x, 0x64); o < strOff; o += 4) tag[o / 4] = "matrices";

        for (int i = 0; i < c.length; i++) {
            int o = i * 4;
            if (c[i] == 'S') { p[o] = x[o + 3]; p[o + 1] = x[o + 2]; p[o + 2] = x[o + 1]; p[o + 3] = x[o]; }
            else if (c[i] == 'H') { p[o] = x[o + 1]; p[o + 1] = x[o]; p[o + 2] = x[o + 3]; p[o + 3] = x[o + 2]; }
        }
        le32(p, 8, 3);
        // draw record code (u16 at +0x0C): PC always 4, 360 skinned 4, PS3 5 (per-platform code)
        if (ps3Src)
            for (int i = 0; i < parts; i++) {
                int q = be32(x, partOff + i * PART + 0x30) + 0x0C;
                if ((p[q] & 255) == 5 && p[q + 1] == 0) p[q] = 4;
            }
        return p;
    }

    static void u16raw(char[] c, String[] tag, int o, String t) { c[o / 4] = 'U'; tag[o / 4] = t; }

    static int dec(int b) { // signed byte -> signed 10-bit normalized
        int v = Math.round((byte) b / 127f * 511f);
        return v & 0x3FF;
    }

    static void skinnedVertex(byte[] x, byte[] p, int o) {
        for (int k = 0; k < 16; k += 4) { p[o + k] = x[o + k + 3]; p[o + k + 1] = x[o + k + 2]; p[o + k + 2] = x[o + k + 1]; p[o + k + 3] = x[o + k]; }
        int tx = ps3Src ? o + 19 : o + 16, step = ps3Src ? -1 : 1;
        int t = dec(x[tx]) | dec(x[tx + step]) << 10 | dec(x[tx + 2 * step]) << 20 | (x[tx + 3 * step] < 0 ? 3 << 30 : 0);
        le32(p, o + 16, t);
        // bone indices raw
        System.arraycopy(x, o + 20, p, o + 20, 4);
        // weights: 4 x u16
        for (int k = 24; k < 32; k += 2) { p[o + k] = x[o + k + 1]; p[o + k + 1] = x[o + k]; }
    }

    public static void main(String[] a) throws Exception {
        byte[] x = Files.readAllBytes(Path.of(a[1]));
        boolean ps3 = a[0].endsWith("-ps3");          // convert-ps3 / check-ps3: PS3 source
        byte[] y = convert(x, ps3);
        if (a[0].startsWith("convert")) {
            // the 'U' words (u16 + 2 raw bytes) are fixed here, after the bulk swap
            Files.write(Path.of(a[2]), fixU(x, y));
            return;
        }
        y = fixU(x, y);
        byte[] p = Files.readAllBytes(Path.of(a[2]));
        System.out.println("sizes " + y.length + " / " + p.length);
        Map<String, int[]> per = new TreeMap<>();
        List<String> first = new ArrayList<>();
        for (int o = 0; o + 4 <= Math.min(y.length, p.length); o += 4) {
            boolean ok = Arrays.equals(y, o, o + 4, p, o, o + 4);
            int[] s = per.computeIfAbsent(tag[o / 4], k -> new int[2]);
            s[0]++; if (!ok) { s[1]++; if (first.size() < 40) first.add(String.format("%05X %s 360=%02x%02x%02x%02x got=%02x%02x%02x%02x pc=%02x%02x%02x%02x", o, tag[o / 4],
                x[o], x[o + 1], x[o + 2], x[o + 3], y[o], y[o + 1], y[o + 2], y[o + 3], p[o], p[o + 1], p[o + 2], p[o + 3])); }
        }
        per.forEach((k, v) -> System.out.printf("  %-9s words %6d  wrong %6d%n", k, v[0], v[1]));
        first.forEach(System.out::println);
    }

    static byte[] fixU(byte[] x, byte[] y) {
        int descOff = be32(x, 0x68), idxDesc = be32(x, 0x6C);
        for (int d = descOff; d + 16 <= idxDesc + 16; d += 16) {
            int o = d + 8;
            y[o] = x[o + 1]; y[o + 1] = x[o]; y[o + 2] = x[o + 2]; y[o + 3] = x[o + 3];
        }
        return y;
    }
}
