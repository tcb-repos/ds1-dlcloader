// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Converts a Dead Space Xbox 360 or PS3 .str ("ols3", big-endian) to PC format ("3slo", little-endian).
//
//   java StrConvert.java <in.str> <out.str> [--pc <PC game install>] [--skip-unsupported] [--report <file>]
//
// Per resource:
//   1. If the PC install has a resource with the same name, type and source path (--pc: the PC install,
//      indexed into tools\cache\pcindex-<id>.tsv on first use, its dlc\ folder excluded), its PC header
//      and data are copied as-is.
//   2. Otherwise it is converted by type (see convertData). Types without a converter fail the run,
//      or are left out with --skip-unsupported (listed in the report).
// Needs Tg4h.java and VfxSchema.java next to it (JDK 22+ multi-file source launch) and
// tools\schema\vfx.txt.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class StrConvert {
    static final int BLOCK = 0x20000;

    record Res(byte[] shdr, String type, List<String> strs, byte[] data) {
        String name() { return strs.isEmpty() ? "?" : strs.get(0); }
        String src() { return strs.size() > 1 ? strs.get(1) : ""; }
    }

    // ---------------------------------------------------------------- read
    static int be32(byte[] b, int o) { return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF); }
    static int le32(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24; }

    static List<String> strings(byte[] d, int from, int to) {
        List<String> out = new ArrayList<>(); int s = from;
        for (int i = from; i < to; i++) if (d[i] == 0) { if (i > s) out.add(new String(d, s, i - s, StandardCharsets.ISO_8859_1)); s = i + 1; }
        return out;
    }

    static List<Res> read360(byte[] d) {
        if (!new String(d, 0, 4, StandardCharsets.US_ASCII).equals("ols3")) throw new IllegalArgumentException("not a 360 .str (ols3)");
        List<Res> out = new ArrayList<>();
        int o = be32(d, 4);
        byte[] shdr = null; List<String> strs = null; ByteArrayOutputStream data = null; int want = 0; String type = null;
        while (o + 8 <= d.length) {
            String tag = new String(d, o, 4, StandardCharsets.ISO_8859_1);
            int size = be32(d, o + 4);
            if (size < 8 || o + size > d.length) break;
            if (tag.equals("SHOC")) {
                String sub = new String(d, o + 8, 4, StandardCharsets.ISO_8859_1);
                if (sub.equals("SHDR")) {
                    if (shdr != null) out.add(new Res(shdr, type, strs, data.toByteArray()));
                    shdr = Arrays.copyOfRange(d, o, o + size);
                    strs = strings(d, o + 12 + 0x20, o + size);
                    type = strs.get(strs.size() - 1);
                    want = be32(d, o + 12 + 0x1C);
                    data = new ByteArrayOutputStream();
                } else if (sub.equals("SDAT")) {
                    data.write(d, o + 12, Math.min(size - 12, want - data.size()));
                } else throw new IllegalStateException("unexpected 360 chunk SHOC/" + sub + " @" + Integer.toHexString(o));
            } else if (!tag.equals("FILL")) throw new IllegalStateException("unexpected 360 chunk " + tag + " @" + Integer.toHexString(o));
            o += size;
        }
        if (shdr != null) out.add(new Res(shdr, type, strs, data.toByteArray()));
        return out;
    }

    // ---- PC install index: every resource in every PC .str, by name|type|source path -> (file, SHDR offset).
    // Built once and cached in tools\pcindex.tsv (delete it to rebuild).
    record PcLoc(Path file, int off) {}

    static Map<String, PcLoc> indexPcInstall(Path install, Path cache) throws IOException {
        Map<String, PcLoc> idx = new HashMap<>();
        if (install == null) return idx;
        if (Files.exists(cache)) {
            for (String l : Files.readAllLines(cache)) { String[] t = l.split("\t"); idx.put(t[0], new PcLoc(install.resolve(t[1]), Integer.parseInt(t[2]))); }
            return idx;
        }
        List<Path> files;
        // skip the install's dlc folder: converted DLC there must never be mistaken for PC originals
        Path dlc = install.resolve("dlc");
        try (Stream<Path> s = Files.walk(install)) { files = s.filter(f -> f.toString().toLowerCase().endsWith(".str") && !f.startsWith(dlc)).sorted().toList(); }
        StringBuilder sb = new StringBuilder();
        for (Path f : files) {
            byte[] d = Files.readAllBytes(f);
            if (d.length < 12 || d[0] != '3' || d[1] != 's') continue;
            int o = le32(d, 4);
            while (o + 12 <= d.length) {
                int size = le32(d, o + 4);
                if (size < 8 || o + size > d.length) break;
                if (d[o] == 'C' && d[o + 8] == 'R' && d[o + 9] == 'D' && d[o + 10] == 'H' && d[o + 11] == 'S') {   // SHDR
                    List<String> st = strings(d, o + 12 + 0x20, o + size);
                    if (st.size() >= 3) {
                        String k = key(st.get(0), st.get(st.size() - 1), st.get(1));
                        if (!idx.containsKey(k)) {
                            idx.put(k, new PcLoc(f, o));
                            sb.append(k).append('\t').append(install.relativize(f)).append('\t').append(o).append('\n');
                        }
                    }
                }
                o += size;
            }
        }
        Files.createDirectories(cache.toAbsolutePath().getParent());
        Files.writeString(cache, sb);
        return idx;
    }

    /** Reads a PC resource: its SHDR chunk and its data (SDAT and RefPack-compressed Rpak chunks). */
    static byte[][] readPc(PcLoc loc) throws IOException {
        byte[] d = Files.readAllBytes(loc.file());
        int o = loc.off(), size = le32(d, o + 4);
        byte[] shdr = Arrays.copyOfRange(d, o, o + size);
        int want = le32(d, o + 12 + 0x1C);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        o += size;
        while (o + 12 <= d.length && data.size() < want) {
            int cs = le32(d, o + 4);
            String sub = new String(new byte[]{d[o + 11], d[o + 10], d[o + 9], d[o + 8]}, StandardCharsets.ISO_8859_1);
            if (sub.equals("SDAT")) data.write(d, o + 12, Math.min(cs - 12, want - data.size()));
            else if (sub.equals("Rpak")) { byte[] u = StrDump.refpack(d, o + 16, o + 16 + le32(d, o + 12)); data.write(u, 0, Math.min(u.length, want - data.size())); }
            else break;
            o += cs;
        }
        if (data.size() != want) throw new IllegalStateException("PC resource at " + loc + ": got " + data.size() + " of " + want + " bytes");
        return new byte[][]{shdr, data.toByteArray()};
    }
    // Platform-specific build outputs carry a target suffix in the source path (360 ".XEN", PS3 ".PS3",
    // PC ".WIN"); strip it so the same asset matches across platforms.
    static String key(String name, String type, String src) {
        String s = src.toLowerCase();
        if (s.endsWith(".xen") || s.endsWith(".win") || s.endsWith(".ps3")) s = s.substring(0, s.length() - 4);
        return (name + "|" + type + "|" + s).toLowerCase();
    }

    // ---------------------------------------------------------------- convert
    static boolean ps3;                 // source file is from the PS3 release
    static IdRemap idRemap;             // new store/slot numbers for the pack being converted, or null
    static int idRemapFields;           // SimGroup fields changed by idRemap (for the log)
    static Map<String, Character> vfxSchema;
    static Set<String> vfxUnknown = new TreeSet<>();

    /** Converts one resource's data. Returns null if the type has no converter. */
    static byte[] convertData(Res r) {
        switch (r.type()) {
            case "tg4h": return Tg4h.convert(r.data(), ps3);
            case "tg4d": {                                     // 360: 16-bit swapped; PS3: already PC form
                if (ps3) return r.data();
                byte[] d = r.data().clone(); for (int i = 0; i + 1 < d.length; i += 2) { byte t = d[i]; d[i] = d[i + 1]; d[i + 1] = t; } return d;
            }
            case "VFX": return VfxSchema.convert(r.data(), vfxSchema, vfxUnknown);
            case "LHR": case "LUA": return r.data();          // plain text / byte-identical on both platforms
            case "LH2": return convertLh2(r.data());
            case "SimGroup": {
                byte[] d = SimGroup.convert(r.data());
                if (idRemap != null) idRemapFields += idRemap.apply(d);   // new store/slot numbers for a clashing pack
                return d;
            }
            case "MetaModel": return MetaModel.convert(r.data());
            case "Material": try { return Material.convert(r.data()); } catch (Exception e) { throw new IllegalStateException("Material " + r.src() + ": " + e.getMessage(), e); }
            case "Mesh": return Mesh.fixU(r.data(), Mesh.convert(r.data(), ps3));
            case "SBK": try { return Sbk.convert(r.data(), pcBanks(), sbkLog); } catch (Exception e) { throw new IllegalStateException("SBK " + r.src() + ": " + e.getMessage(), e); }
            default: return null;
        }
    }

    // PC sound banks from the install, for reusing PC samples of the same sounds (loaded once)
    static Map<String, PcLoc> pcIndex;
    static PrintStream sbkLog = System.out;
    static List<byte[]> pcBankCache;
    static List<byte[]> pcBanks() throws IOException {
        if (pcBankCache == null) {
            pcBankCache = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int bad = 0;
            for (var e : pcIndex.entrySet())
                if (e.getKey().split("\\|")[1].equals("sbk") && seen.add(e.getKey())) {
                    try { pcBankCache.add(readPc(e.getValue())[1]); } catch (IllegalStateException ex) { bad++; }   // split/streamed banks
                }
            sbkLog.println("  (loaded " + pcBankCache.size() + " PC sound banks for sample matching, " + bad + " unreadable skipped)");
        }
        return pcBankCache;
    }

    /** LH2 string table: "2HCL" magic + 7 header words, `count` hashes, `count` string offsets, then strings.
     *  All numbers are u32 (hash order is numeric, so it's the same on both platforms); strings are bytes. */
    static byte[] convertLh2(byte[] x) {
        if (x[0] != '2' || x[1] != 'H' || x[2] != 'C' || x[3] != 'L') throw new IllegalStateException("LH2: bad magic");
        int count = be32(x, 0x10);
        int words = 8 + count * 2;
        byte[] p = x.clone();
        for (int i = 0; i < words; i++) putLe(p, i * 4, be32(x, i * 4));
        return p;
    }

    /** 360 SHDR chunk (incl. SHOC header) -> PC SHDR chunk, with the data size set to newSize. */
    static byte[] convertShdr(byte[] x, String type, int newSize) {
        byte[] p = new byte[x.length];
        // chunk header: SHOC <size> SHDR -> COHS <size LE> RDHS
        p[0] = 'C'; p[1] = 'O'; p[2] = 'H'; p[3] = 'S';
        putLe(p, 4, x.length);
        p[8] = 'R'; p[9] = 'D'; p[10] = 'H'; p[11] = 'S';
        int b = 12;
        int w0 = be32(x, b);
        if (type.equals("Mesh") && w0 == 0x9DC401B7) w0 = 0x5393AC01;     // Mesh format hash differs per platform
        putLe(p, b, w0);
        int fA = (x[b + 4] & 0xFF) << 8 | (x[b + 5] & 0xFF), fB = (x[b + 6] & 0xFF) << 8 | (x[b + 7] & 0xFF);
        if (type.equals("SBK") && fA == 0x0040) fA = 0x0080;               // sound bank flag differs per platform
        p[b + 4] = (byte) fA; p[b + 5] = (byte) (fA >> 8); p[b + 6] = (byte) fB; p[b + 7] = (byte) (fB >> 8);
        for (int i = 2; i < 7; i++) putLe(p, b + i * 4, be32(x, b + i * 4));
        putLe(p, b + 0x1C, newSize);
        System.arraycopy(x, b + 0x20, p, b + 0x20, x.length - b - 0x20);   // strings
        return p;
    }
    static void putLe(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >>> 24); }

    // ---------------------------------------------------------------- write
    static class PcWriter {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PcWriter() { out.writeBytes(new byte[]{'3', 's', 'l', 'o', 0x0C, 0, 0, 0, 0x02, 0x00, 0x03, 0x01}); }
        int pos() { return out.size(); }
        int room() { return BLOCK - pos() % BLOCK; }
        void fill(int n) {            // FILL chunk of n bytes (n >= 8)
            byte[] f = new byte[n]; f[0] = 'L'; f[1] = 'L'; f[2] = 'I'; f[3] = 'F'; putLe(f, 4, n); out.writeBytes(f);
        }
        void fillToBlock() { int r = room(); if (r == BLOCK) return; if (r < 8) throw new IllegalStateException("can't pad " + r + " bytes"); fill(r); }
        /** Chunks never cross a 128 KB block boundary; pad to the next block if this one won't fit. */
        void ensure(int need) { if (room() < need) fillToBlock(); }
        void shdr(byte[] chunk) { ensure(chunk.length + 8); out.writeBytes(chunk); }
        void sdat(byte[] data) {
            int p = 0;
            while (p < data.length) {
                if (room() < 12 + 16) fillToBlock();
                int n = Math.min(data.length - p, room() - 12);
                int padded = (n + 3) & ~3;
                if (12 + padded > room()) { n = (room() - 12) & ~3; padded = n; }
                // never leave 1..7 bytes before the block end: a FILL chunk needs at least 8
                int left = room() - 12 - padded;
                if (left > 0 && left < 8) {
                    if (n > 8) { n -= 8; padded = (n + 3) & ~3; }        // move 8 bytes into the next chunk
                    else { fillToBlock(); continue; }                     // tiny chunk: start it in the next block
                }
                byte[] c = new byte[12 + padded];
                c[0] = 'C'; c[1] = 'O'; c[2] = 'H'; c[3] = 'S'; putLe(c, 4, c.length); c[8] = 'T'; c[9] = 'A'; c[10] = 'D'; c[11] = 'S';
                System.arraycopy(data, p, c, 12, n);
                out.writeBytes(c);
                p += n;
            }
        }
        byte[] finish() { fillToBlock(); return out.toByteArray(); }
    }

    // ---------------------------------------------------------------- main
    public static void main(String[] a) throws Exception {
        Path in = Path.of(a[0]), outPath = Path.of(a[1]);
        Path pcDir = null, report = null; boolean skip = false;
        Set<String> omit = new HashSet<>();
        for (int i = 2; i < a.length; i++) switch (a[i]) {
            case "--pc" -> pcDir = Path.of(a[++i]);
            case "--skip-unsupported" -> skip = true;
            case "--report" -> report = Path.of(a[++i]);
            case "--omit" -> omit.add(a[++i]);          // leave out every resource of this type (for testing)
            default -> throw new IllegalArgumentException(a[i]);
        }
        setup(pcDir);
        convertFile(in, outPath, skip, omit, report, System.out);
    }

    /** Folder with the converter's own data (schema, cache); -Ddlcconv.tools=<dir>, default .\tools. */
    static Path toolsDir() {
        String p = System.getProperty("dlcconv.tools");
        if (p != null) return Path.of(p);
        return Files.isDirectory(Path.of("schema")) ? Path.of(".") : Path.of("tools");
    }

    static Path setupFor;
    /** Loads the format rules and indexes the PC install (cached per install path in tools\cache). */
    static synchronized void setup(Path pcDir) throws Exception {
        if (vfxSchema == null) vfxSchema = VfxSchema.load(toolsDir().resolve("schema").resolve("vfx.txt"));
        Path norm = pcDir == null ? null : pcDir.toAbsolutePath().normalize();
        if (pcIndex != null && Objects.equals(norm, setupFor)) return;
        String id = norm == null ? "none" : Integer.toHexString(norm.toString().toLowerCase().hashCode());
        pcIndex = indexPcInstall(norm, toolsDir().resolve("cache").resolve("pcindex-" + id + ".tsv"));
        pcBankCache = null;
        setupFor = norm;
    }

    /** Converts one 360/PS3 .str to a PC .str and returns the summary. Throws on a resource
     *  without a converter unless skip is set. Call setup() first. */
    static String convertFile(Path in, Path outPath, boolean skip, Set<String> omit, Path report, PrintStream log) throws Exception {
        Map<String, PcLoc> pc = pcIndex;
        sbkLog = log;
        byte[] inBytes = Files.readAllBytes(in);
        // source platform from the container version: 360 00 02 04 01, PS3 00 02 06 01 (both big-endian 'ols3')
        ps3 = inBytes.length > 11 && inBytes[10] == 0x06;
        log.println("source: " + (ps3 ? "PS3" : "Xbox 360") + " (" + in.getFileName() + ")");
        List<Res> res = read360(inBytes);
        PcWriter w = new PcWriter();
        StringBuilder rep = new StringBuilder();
        Map<String, int[]> stats = new TreeMap<>(); // type -> {reused, converted, skipped}
        List<String> skipped = new ArrayList<>();
        for (Res r : res) {
            int[] st = stats.computeIfAbsent(r.type(), k -> new int[3]);
            if (omit.contains(r.type())) { st[2]++; rep.append("OMITTED  ").append(r.type()).append(' ').append(r.src()).append('\n'); continue; }
            PcLoc orig = pc.get(key(r.name(), r.type(), r.src()));
            if (orig != null) {
                byte[][] pr = readPc(orig);
                w.shdr(pr[0]);
                w.sdat(pr[1]);
                st[0]++; rep.append("reuse    ").append(r.type()).append(' ').append(r.src()).append('\n');
                continue;
            }
            byte[] d;
            String why = "no converter for this type";
            try { d = convertData(r); }
            catch (RuntimeException e) {                // a converter that doesn't handle this variant yet
                if (!skip) throw new IllegalStateException(r.type() + " (" + r.src() + "): " + e.getMessage(), e);
                d = null; why = e.getMessage();
            }
            if (d == null) {
                if (!skip) throw new IllegalStateException("no converter for " + r.type() + " (" + r.src() + "); use --skip-unsupported to leave it out");
                st[2]++; skipped.add(r.type() + " " + r.src());
                rep.append("SKIPPED  ").append(r.type()).append(' ').append(r.src()).append("  [").append(why).append("]\n");
                continue;
            }
            w.shdr(convertShdr(r.shdr(), r.type(), d.length));
            w.sdat(d);
            st[1]++; rep.append("convert  ").append(r.type()).append(' ').append(r.src()).append('\n');
        }
        byte[] outBytes = w.finish();
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, outBytes);

        StringBuilder sum = new StringBuilder();
        sum.append(String.format("%s -> %s (%d bytes, %d resources)%n", in.getFileName(), outPath.getFileName(), outBytes.length, res.size()));
        stats.forEach((k, v) -> sum.append(String.format("  %-10s reused %3d  converted %3d  skipped %3d%n", k, v[0], v[1], v[2])));
        if (!vfxUnknown.isEmpty()) sum.append("  VFX words with no learned rule (swapped as u32): ").append(vfxUnknown).append('\n');
        log.print(sum);
        if (report != null) Files.writeString(report, sum + "\n" + rep);
        return sum.toString();
    }
}

