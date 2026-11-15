// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Audio side of the SBK conversion: EA-XMA (360, codec 3) -> PCM via ffmpeg, PCM <-> EA-XAS v1 (PC, codec 4).
//
// Sample = EA SNR header (u32 BE ver:4 codec:4 ch-1:6 rate:18, u32 BE type:2 loop:1 samples:29,
// [u32 loop start]) + one SNS block (u8 flag, u24 size, u32 samples, payload).
//   codec 3 payload: u32 BE (payload size * 4), then XMA2 packets (0x800 each, last one cut short)
//   codec 4 payload: XAS v1 frames, 0x4C bytes per channel per 128 samples, channels interleaved per frame
//     frame (per channel): 4 subframe headers of 2 x u16 LE
//       [coef index:4 | sample0 & ~0xF] [shift code:4 | sample1 & ~0xF]   (shift = 20 - code)
//       then 15 rows x 4 bytes: row r, byte n = subframe n samples (2r+2, 2r+3), high nibble first
//
// Usage: java SbkAudio.java xma2wav <bank> <index> <out.wav>
//        java SbkAudio.java xas2wav <bank> <index> <out.wav>
//        java SbkAudio.java compare <a.wav> <b.wav>          normalized correlation
//        java SbkAudio.java roundtrip <in.wav> <out.wav>     encode to XAS and decode again
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class SbkAudio {
    /** ffmpeg is supplied by the user: -Dffmpeg=<path>, else the FFMPEG environment variable, else PATH. */
    static String FFMPEG = findFfmpeg();
    static String findFfmpeg() {
        String p = System.getProperty("ffmpeg");
        if (p == null || p.isBlank()) p = System.getenv("FFMPEG");
        if (p != null && !p.isBlank()) return p;
        try {   // the small ffmpeg build shipped with the DLC Loader: <tools>\ffmpeg\ffmpeg.exe
            Path own = StrConvert.toolsDir().toAbsolutePath().normalize().resolve("ffmpeg").resolve("ffmpeg.exe");
            if (Files.isRegularFile(own)) return own.toString();
        } catch (RuntimeException ignored) {}
        String path = System.getenv("PATH");
        if (path != null)
            for (String dir : path.split(File.pathSeparator))
                for (String n : new String[]{"ffmpeg.exe", "ffmpeg"})
                    if (!dir.isBlank() && Files.isRegularFile(Path.of(dir.trim(), n))) return Path.of(dir.trim(), n).toString();
        return "ffmpeg";   // starting it will fail with the message below
    }
    /** Does this ffmpeg have the decoders the converter needs (xma2 for Xbox 360, mp3 for PS3)? null = yes. */
    static String checkDecoders(String ffmpeg) {
        try {
            Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-decoders").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            List<String> missing = new ArrayList<>();
            if (!out.matches("(?s).*\\sxma2\\s.*")) missing.add("xma2");
            if (!out.matches("(?s).*\\smp3(float)?\\s.*")) missing.add("mp3");
            return missing.isEmpty() ? null : "it is missing the " + String.join(" and ", missing) + " decoder";
        } catch (Exception e) { return "it can't be started (" + e.getMessage() + ")"; }
    }
    static Process startFfmpeg(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(FFMPEG);
        cmd.addAll(Arrays.asList(args));
        try { return new ProcessBuilder(cmd).redirectErrorStream(true).start(); }
        catch (IOException e) {
            throw new IOException("ffmpeg not found (" + FFMPEG + "). Audio conversion needs ffmpeg: the DLC Loader includes one in tools\\ffmpeg; otherwise put it on PATH, set the FFMPEG "
                + "environment variable, or pass -Dffmpeg=<path to ffmpeg.exe>.", e);
        }
    }

    static int rd(byte[] b, int o, boolean be) {
        return be ? (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255)
                  : (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | (b[o + 3] & 255) << 24;
    }

    record Sample(int codec, int channels, int rate, int samples, boolean loop, int loopStart, int headerLen, byte[] payload, int blockSamples) {}

    static int sampleOffset(byte[] b, int index) {
        boolean be = b[0] == 'k';
        int sd = rd(b, 0x10, be), n = rd(b, sd + 4, be);
        for (int i = 0; i < n; i++) {
            int e = sd + 8 + i * 16;
            if (rd(b, e, be) == index) return sd + rd(b, e + 8, be);
        }
        throw new IllegalArgumentException("no sample " + index);
    }

    static Sample parse(byte[] b, int off) {
        int h = rd(b, off, true), h2 = rd(b, off + 4, true);
        boolean loop = ((h2 >>> 29) & 1) != 0;
        int hl = 8 + (loop ? 4 : 0);
        int p = off + hl;
        int size = rd(b, p, true) & 0xFFFFFF;
        int bs = rd(b, p + 4, true);
        byte[] pay = Arrays.copyOfRange(b, p + 8, p + size);
        return new Sample((h >>> 24) & 15, ((h >>> 18) & 63) + 1, h & 0x3FFFF, h2 & 0x1FFFFFFF, loop, loop ? rd(b, off + 8, true) : 0, hl, pay, bs);
    }

    // ---------- XMA ----------
    static short[] decodeXma(Sample s) throws Exception {
        byte[] pay = s.payload();
        int dataLen = pay.length - 4;
        int packets = (dataLen + 0x7FF) / 0x800;
        byte[] data = new byte[packets * 0x800];
        System.arraycopy(pay, 4, data, 0, dataLen);
        ByteBuffer fmt = ByteBuffer.allocate(18 + 34).order(ByteOrder.LITTLE_ENDIAN);
        fmt.putShort((short) 0x166).putShort((short) s.channels()).putInt(s.rate()).putInt(s.rate() * s.channels() * 2)
           .putShort((short) 0x800).putShort((short) 16).putShort((short) 34);
        fmt.putShort((short) 1).putInt(s.channels() == 2 ? 3 : 4).putInt(s.samples()).putInt(0x10000)
           .putInt(0).putInt(s.samples()).putInt(0).putInt(0).put((byte) 0).put((byte) 4).putShort((short) 1);
        Path tmp = Files.createTempFile("eaxma", ".xma"), out = Files.createTempFile("eaxma", ".wav");
        try {
            Files.write(tmp, riff(fmt.array(), data));
            Process pr = startFfmpeg("-hide_banner", "-loglevel", "error", "-y", "-i", tmp.toString(), "-f", "s16le", "-acodec", "pcm_s16le", out.toString());
            String log = new String(pr.getInputStream().readAllBytes());
            if (pr.waitFor() != 0) throw new IOException("ffmpeg failed: " + log);
            if (!log.isBlank()) System.err.println("ffmpeg: " + log.trim());
            byte[] pcm = Files.readAllBytes(out);
            short[] sm = new short[pcm.length / 2];
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sm);
            return sm;
        } finally { Files.deleteIfExists(tmp); Files.deleteIfExists(out); }
    }

    static byte[] riff(byte[] fmt, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + fmt.length + 8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(4 + 8 + fmt.length + 8 + data.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(fmt.length).put(fmt);
        b.put("data".getBytes()).putInt(data.length).put(data);
        return b.array();
    }

    // ---------- XAS v1 ----------
    static final int[] TABLE = {0, 240, 460, 392, 0, 0, -208, -220};

    static short[] decodeXas(byte[] pay, int channels, int samples) {
        int frames = pay.length / (0x4C * channels);
        short[] out = new short[frames * 128 * channels];
        for (int f = 0; f < frames; f++)
            for (int c = 0; c < channels; c++) {
                int base = (f * channels + c) * 0x4C;
                int[][] s = new int[4][32];
                int[] c0 = new int[4], c1 = new int[4], sh = new int[4];
                for (int n = 0; n < 4; n++) {
                    int v = (short) ((pay[base + n * 4] & 255) | (pay[base + n * 4 + 1] & 255) << 8);
                    c0[n] = TABLE[v & 3]; c1[n] = TABLE[(v & 3) + 4];
                    s[n][0] = v & ~0xF;
                    v = (short) ((pay[base + n * 4 + 2] & 255) | (pay[base + n * 4 + 3] & 255) << 8);
                    sh[n] = 20 - (v & 0xF);
                    s[n][1] = v & ~0xF;
                }
                for (int m = 2, q = base + 16; m < 32; m += 2)
                    for (int n = 0; n < 4; n++, q++) {
                        int by = pay[q] & 255;
                        int lv = ((by >> 4) << 28 >> 28) << sh[n];
                        s[n][m] = clip((lv + s[n][m - 1] * c0[n] + s[n][m - 2] * c1[n] + 0x80) >> 8);
                        lv = ((by & 15) << 28 >> 28) << sh[n];
                        s[n][m + 1] = clip((lv + s[n][m] * c0[n] + s[n][m - 1] * c1[n] + 0x80) >> 8);
                    }
                for (int n = 0; n < 4; n++) for (int i = 0; i < 32; i++) out[((f * 128) + n * 32 + i) * channels + c] = (short) s[n][i];
            }
        return Arrays.copyOf(out, Math.min(out.length, samples * channels));
    }

    static int clip(int v) { return v < -32768 ? -32768 : v > 32767 ? 32767 : v; }

    // Encoder: per 32-sample subframe, try the 4 predictors x 16 shifts and keep the lowest error.
    static byte[] encodeXas(short[] pcm, int channels, int samples) {
        int frames = (samples + 127) / 128;
        byte[] out = new byte[frames * channels * 0x4C];
        for (int f = 0; f < frames; f++)
            for (int c = 0; c < channels; c++) {
                int base = (f * channels + c) * 0x4C;
                for (int n = 0; n < 4; n++) {
                    int[] in = new int[32];
                    for (int i = 0; i < 32; i++) {
                        int idx = f * 128 + n * 32 + i;
                        in[i] = idx < samples ? pcm[idx * channels + c] : 0;
                    }
                    int s0 = in[0] & ~0xF, s1 = in[1] & ~0xF;
                    long best = Long.MAX_VALUE; int bc = 0, bsh = 0; int[] bn = null;
                    for (int coef = 0; coef < 4; coef++)
                        for (int code = 0; code < 16; code++) {
                            int shift = 20 - code;
                            int[] nib = new int[32];
                            long err = 0;
                            int p1 = s1, p2 = s0;
                            for (int i = 2; i < 32 && err < best; i++) {
                                int pred = p1 * TABLE[coef] + p2 * TABLE[coef + 4];
                                // want (lv + pred + 0x80) >> 8 ~= in[i], lv = q << shift
                                double want = ((in[i] << 8) - pred - 0x80) / (double) (1 << shift);
                                int q = (int) Math.round(want);
                                q = Math.max(-8, Math.min(7, q));
                                int r = clip(((q << shift) + pred + 0x80) >> 8);
                                // try neighbour too (rounding through >>8 and clipping)
                                int q2 = q + (want > q ? 1 : -1);
                                if (q2 >= -8 && q2 <= 7) {
                                    int r2 = clip(((q2 << shift) + pred + 0x80) >> 8);
                                    if (Math.abs(r2 - in[i]) < Math.abs(r - in[i])) { q = q2; r = r2; }
                                }
                                nib[i] = q & 15;
                                long d = r - in[i]; err += d * d;
                                p2 = p1; p1 = r;
                            }
                            if (err < best) { best = err; bc = coef; bsh = code; bn = nib; }
                        }
                    int h0 = s0 | bc, h1 = s1 | bsh;
                    out[base + n * 4] = (byte) h0; out[base + n * 4 + 1] = (byte) (h0 >> 8);
                    out[base + n * 4 + 2] = (byte) h1; out[base + n * 4 + 3] = (byte) (h1 >> 8);
                    for (int m = 2, r = 0; m < 32; m += 2, r++) out[base + 16 + r * 4 + n] = (byte) (bn[m] << 4 | bn[m + 1]);
                }
            }
        return out;
    }

    // ---------- WAV ----------
    static void writeWav(Path p, short[] s, int ch, int rate) throws IOException {
        ByteBuffer fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        fmt.putShort((short) 1).putShort((short) ch).putInt(rate).putInt(rate * ch * 2).putShort((short) (ch * 2)).putShort((short) 16);
        ByteBuffer d = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : s) d.putShort(v);
        Files.write(p, riff(fmt.array(), d.array()));
    }
    static short[] readWav(Path p, int[] chRate) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
        b.position(12);
        short[] out = null;
        while (b.remaining() >= 8) {
            byte[] id = new byte[4]; b.get(id); int len = b.getInt();
            String t = new String(id);
            if (t.equals("fmt ")) { b.getShort(); chRate[0] = b.getShort(); chRate[1] = b.getInt(); b.position(b.position() + len - 8); }
            else if (t.equals("data")) { out = new short[len / 2]; b.asShortBuffer().get(out); b.position(b.position() + len); }
            else b.position(b.position() + len);
        }
        return out;
    }

    static double correlate(short[] a, short[] b) {
        int n = Math.min(a.length, b.length);
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < n; i++) { ab += a[i] * (double) b[i]; aa += a[i] * (double) a[i]; bb += b[i] * (double) b[i]; }
        return ab / Math.sqrt(aa * bb + 1e-9);
    }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "xma2wav", "xas2wav" -> {
                byte[] b = Files.readAllBytes(Path.of(a[1]));
                Sample s = parse(b, sampleOffset(b, Integer.parseInt(a[2])));
                short[] pcm = a[0].equals("xma2wav") ? decodeXma(s) : decodeXas(s.payload(), s.channels(), s.samples());
                System.out.printf("codec %d ch %d rate %d header samples %d decoded %d%n", s.codec(), s.channels(), s.rate(), s.samples(), pcm.length / s.channels());
                writeWav(Path.of(a[3]), pcm, s.channels(), s.rate());
            }
            case "compare" -> {
                int[] cr = new int[2];
                short[] x = readWav(Path.of(a[1]), cr), y = readWav(Path.of(a[2]), cr);
                // also search a small offset (codec delay)
                int bestOff = 0; double best = -2;
                for (int off = -2048; off <= 2048; off += 1) {
                    double c = correlateShift(x, y, off);
                    if (c > best) { best = c; bestOff = off; }
                }
                System.out.printf("samples %d / %d, correlation at 0: %.4f, best %.4f at offset %d%n", x.length, y.length, correlate(x, y), best, bestOff);
            }
            case "roundtrip" -> {
                int[] cr = new int[2];
                short[] x = readWav(Path.of(a[1]), cr);
                int n = x.length / cr[0];
                short[] y = decodeXas(encodeXas(x, cr[0], n), cr[0], n);
                double se = 0, ss = 0;
                for (int i = 0; i < x.length; i++) { double d = x[i] - y[i]; se += d * d; ss += x[i] * (double) x[i]; }
                System.out.printf("SNR %.1f dB, correlation %.5f%n", 10 * Math.log10(ss / (se + 1e-9)), correlate(x, y));
                writeWav(Path.of(a[2]), y, cr[0], cr[1]);
            }
        }
    }

    static double correlateShift(short[] a, short[] b, int off) {
        double ab = 0, aa = 0, bb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i += 4) {
            int j = i + off;
            if (j < 0 || j >= b.length) continue;
            ab += a[i] * (double) b[j]; aa += a[i] * (double) a[i]; bb += b[j] * (double) b[j];
        }
        return ab / Math.sqrt(aa * bb + 1e-9);
    }
}
