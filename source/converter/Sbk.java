// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// SBK sound bank converter, 360/PS3 ('knbs', big-endian tables; 360 EA-XMA or PS3 EALayer3 samples) -> PC ('sbnk',
// little-endian tables, EA-XAS samples). The PS3 tables are identical to the 360 ones.
//
// Tables (everything before the 'sdat' block at header word 0x10) are 32-bit words except:
//   - event records (offset table at 0x70, count at 0x28, records 0x48 bytes): +0x04, +0x14, +0x18 raw bytes
//   - words 0x000N0000 (N = 1..0xFF): two u16
//   - words 0x00NN0101: two u16
//   - a 0x000002xx word next to a 0x0000007F word: raw bytes (sample reference)
//   - event lookup rows at header 0x4C: the key column is raw bytes (hash bytes kept as-is)
// These rules were learned by aligning the Big Gun bank with the stock PC Force Gun bank (SbkAlign.java).
// sdat: magic, count, count x [index, 0x0313BABE, offset from sdat, 0xFEEDFEED], then the samples.
// Samples: if a PC bank has the same sound (same channels/rate/length and decoded audio correlates
// >= 0.98) its PC sample is copied; otherwise the XMA is decoded (ffmpeg) and encoded to XAS.
//
// Usage: java Sbk.java convert <in.sbk> <out.sbk> <pcBankDir...>
//        java Sbk.java check <in.sbk> <alignment.txt>      compare table classes with SbkAlign -all output
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class Sbk {
    static int be(byte[] b, int o) { return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255); }
    static int le(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24; }
    static void putLe(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >>> 24); }
    static void putBe(byte[] b, int o, int v) { b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v; }

    static char[] classify(byte[] x) {
        if (be(x, 0) != 0x6B6E6273) throw new IllegalArgumentException("not a 360 sound bank");
        int sd = be(x, 0x10);
        char[] c = new char[sd / 4];
        Arrays.fill(c, 'S');
        int n = be(x, 0x28), base = 0x70 + n * 4;
        for (int i = 0; i < n; i++) {
            int r = base + be(x, 0x70 + i * 4);
            for (int k : new int[]{4, 0x14, 0x18}) c[(r + k) / 4] = 'R';
        }
        for (int i = 0; i < c.length; i++) {
            if (c[i] != 'S') continue;
            int v = be(x, i * 4), hi = v >>> 16, lo = v & 0xFFFF;
            if (lo == 0 && hi >= 1 && hi <= 0xFF) c[i] = 'H';
            else if (lo == 0x0101 && hi < 0x100) c[i] = 'H';
            else if ((v & 0xFFFFFF00) == 0x200 && ((i > 0 && be(x, i * 4 - 4) == 0x7F) || (i + 1 < c.length && be(x, i * 4 + 4) == 0x7F))) c[i] = 'R';
        }
        // event lookup rows (section at header 0x4C, 3-word section header, then 24-byte rows
        // [0, 0, key, hash, 0, index]): the key is the event hash stored with the same bytes on both platforms
        int rows = be(x, 0x4C);
        // The first row's key is a normal u32 (it matches the PC bank exactly); every later key, read with
        // its bytes unchanged, is one of the hashes in the table's sorted hash column.
        if (rows > 0 && rows < sd)
            for (int o = rows + 12 + 24; o + 24 <= sd; o += 24)
                if (be(x, o) == 0 && be(x, o + 4) == 0 && be(x, o + 16) == 0) c[(o + 8) / 4] = 'R';
        return c;
    }

    record PcSample(Path bank, byte[] data) {}

    public static void main(String[] a) throws Exception {
        if (a[0].equals("check")) { check(a); return; }
        byte[] x = Files.readAllBytes(Path.of(a[1]));
        List<Path> pcBanks = new ArrayList<>();
        for (int i = 3; i < a.length; i++)
            try (Stream<Path> s = Files.walk(Path.of(a[i]))) { s.filter(p -> p.toString().toLowerCase().endsWith(".sbk")).forEach(pcBanks::add); }
        List<byte[]> banks = new ArrayList<>();
        for (Path pb : pcBanks) banks.add(Files.readAllBytes(pb));
        Files.write(Path.of(a[2]), convert(x, banks, System.out));
    }

    static byte[] convert(byte[] x, List<byte[]> pcBanks, PrintStream log) throws Exception {
        char[] c = classify(x);
        int sd = be(x, 0x10);
        // PC sample index by key
        Map<String, List<byte[]>> pcByKey = new HashMap<>();
        for (byte[] b : pcBanks) {
            if (b[0] != 's') continue;
            int psd = le(b, 0x10);
            if (psd + 8 > b.length) continue;
            int pn = le(b, psd + 4);
            for (int i = 0; i < pn; i++) {
                int e = psd + 8 + i * 16;
                if (e + 16 > b.length || le(b, e + 4) != 0x0313BABE) break;
                int off = psd + le(b, e + 8);
                if (off + 16 > b.length) continue;
                SbkAudio.Sample s = SbkAudio.parse(b, off);
                if (s.codec() != 4) continue;
                int len = s.headerLen() + 8 + s.payload().length;
                pcByKey.computeIfAbsent(key(s), k -> new ArrayList<>()).add(Arrays.copyOfRange(b, off, off + len));
            }
        }
        // tables
        byte[] tables = new byte[sd];
        for (int i = 0; i < c.length; i++) {
            int o = i * 4;
            switch (c[i]) {
                case 'S' -> { tables[o] = x[o + 3]; tables[o + 1] = x[o + 2]; tables[o + 2] = x[o + 1]; tables[o + 3] = x[o]; }
                case 'H' -> { tables[o] = x[o + 1]; tables[o + 1] = x[o]; tables[o + 2] = x[o + 3]; tables[o + 3] = x[o + 2]; }
                default -> System.arraycopy(x, o, tables, o, 4);
            }
        }
        // samples
        int n = be(x, sd + 4);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] idx = new int[n], offs = new int[n];
        int head = 8 + n * 16;
        int copied = 0, encoded = 0;
        for (int i = 0; i < n; i++) {
            int e = sd + 8 + i * 16;
            if (be(x, e + 4) != 0x0313BABE) throw new IllegalArgumentException("sdat entry " + i);
            idx[i] = be(x, e);
            SbkAudio.Sample s = SbkAudio.parse(x, sd + be(x, e + 8));
            short[] pcm = switch (s.codec()) {
                case 3 -> SbkAudio.decodeXma(s);                                                       // Xbox 360: EA-XMA
                case 6, 7 -> EaLayer3.decodeSample(s.payload(), s.channels(), s.samples(), SbkAudio.FFMPEG); // PS3: EALayer3 v2
                default -> throw new IllegalArgumentException("sample " + idx[i] + ": codec " + s.codec() + " not supported");
            };
            pcm = Arrays.copyOf(pcm, s.samples() * s.channels());
            byte[] out = null;
            double best = 0;
            for (byte[] cand : pcByKey.getOrDefault(key(s), List.of())) {
                SbkAudio.Sample ps = SbkAudio.parse(cand, 0);
                double corr = SbkAudio.correlate(pcm, SbkAudio.decodeXas(ps.payload(), ps.channels(), ps.samples()));
                if (corr > best) { best = corr; if (corr >= 0.98) out = cand; }
            }
            String how;
            if (out != null) { copied++; how = String.format("PC sample (correlation %.4f)", best); }
            else { out = encodeSample(s, pcm); encoded++; how = "transcoded " + (s.codec() == 3 ? "XMA" : "EALayer3") + " -> XAS" + (best > 0 ? String.format(" (closest PC twin only %.3f)", best) : ""); }
            log.printf("  sample %2d: %dch %dHz %d samples -> %s%n", idx[i], s.channels(), s.rate(), s.samples(), how);
            offs[i] = head + body.size();
            body.write(out);
        }
        byte[] sdat = new byte[head + body.size()];
        putLe(sdat, 0, 0x74616473); putLe(sdat, 4, n);
        for (int i = 0; i < n; i++) {
            int e = 8 + i * 16;
            putLe(sdat, e, idx[i]); putLe(sdat, e + 4, 0x0313BABE); putLe(sdat, e + 8, offs[i]); putLe(sdat, e + 12, 0xFEEDFEED);
        }
        System.arraycopy(body.toByteArray(), 0, sdat, head, body.size());
        byte[] p = new byte[sd + sdat.length];
        System.arraycopy(tables, 0, p, 0, sd);
        System.arraycopy(sdat, 0, p, sd, sdat.length);
        putLe(p, 0x0C, sdat.length); putLe(p, 0x14, sdat.length);
        log.printf("  %d samples: %d copied from PC banks, %d transcoded; %d -> %d bytes%n", n, copied, encoded, x.length, p.length);
        return p;
    }

    static String key(SbkAudio.Sample s) { return s.channels() + "/" + s.rate() + "/" + s.samples() + "/" + s.loop() + "/" + s.loopStart(); }

    static byte[] encodeSample(SbkAudio.Sample s, short[] pcm) {
        byte[] frames = SbkAudio.encodeXas(pcm, s.channels(), s.samples());
        int hl = s.headerLen();
        byte[] o = new byte[hl + 8 + frames.length];
        putBe(o, 0, 4 << 24 | (s.channels() - 1) << 18 | s.rate());
        putBe(o, 4, (s.loop() ? 1 << 29 : 0) | s.samples());
        if (s.loop()) putBe(o, 8, s.loopStart());
        putBe(o, hl, 8 + frames.length);           // flag 0x00, u24 block size
        putBe(o, hl + 4, s.samples());
        System.arraycopy(frames, 0, o, hl + 8, frames.length);
        return o;
    }

    static void check(String[] a) throws Exception {
        byte[] x = Files.readAllBytes(Path.of(a[1]));
        char[] c = classify(x);
        int agree = 0, disagree = 0;
        for (String l : Files.readAllLines(Path.of(a[2]))) {
            String[] f = l.trim().split("\\s+");
            if (f.length < 3 || !f[0].matches("[0-9A-F]{5}")) continue;
            char k = f[2].charAt(0);
            if (k != 'S' && k != 'H' && k != 'R') continue;
            int o = Integer.parseInt(f[0], 16);
            if (c[o / 4] == k) agree++;
            else { disagree++; System.out.println("  " + l + "   classifier says " + c[o / 4]); }
        }
        Map<Character, Integer> t = new TreeMap<>();
        for (char k : c) t.merge(k, 1, Integer::sum);
        System.out.println("aligned words: agree " + agree + ", disagree " + disagree + "; classes " + t);
    }
}
