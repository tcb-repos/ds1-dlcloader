// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// EALayer3 (EA's MP3 variant; EAAC codecs 0x06 "v2 PCM" / 0x07 "v2 spike", PS3 Dead Space banks) -> PCM.
// Each EA frame holds one MP3 granule (MPEG2/2.5) or half a frame (MPEG1, two EA frames per MP3 frame).
// We rebuild standard MP3 frames (highest bitrate; bit reservoir when a frame overflows its slot) with main_data_begin = 0 (no bit reservoir) and decode
// them with ffmpeg's MP3 decoder. Frame layout follows vgmstream's mpeg_custom_utils_ealayer3.c (ISC):
//   v2 frame: 1b extended, 1b stereo, 2b reserved, 12b frame size (bytes)
//             [if extended: 2b offset mode, 10b offset samples, 10b pcm samples, 10b common size]
//             [common part, unless extended with common size 0]   [pcm samples x channels x s16 BE, packed]
//   common:   2b version index, 2b rate index, 2b channel mode, 2b mode extension, 1b granule index,
//             [MPEG1 granule 1: 4b scfsi per channel], per channel 12b main data size + 32b + 15b (MPEG1) / 19b (MPEG2)
//             side info bits, then the main data, padded to a byte.
// All bit fields are MSB-first.
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class EaLayer3 {
    static final int[] VERSION = {3, -1, 2, 1};
    static final int[][] RATE = {{11025, 12000, 8000}, {}, {22050, 24000, 16000}, {44100, 48000, 32000}};

    static class Bits {
        final byte[] b; long pos;   // bit position
        Bits(byte[] b, long pos) { this.b = b; this.pos = pos; }
        long get(int n) { long v = 0; for (int i = 0; i < n; i++, pos++) v = v << 1 | ((b[(int) (pos >> 3)] >> (7 - (pos & 7))) & 1); return v; }
    }
    static class Out {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); int cur, n;
        void put(long v, int bits) { for (int i = bits - 1; i >= 0; i--) { cur = cur << 1 | (int) ((v >> i) & 1); if (++n == 8) { o.write(cur); cur = 0; n = 0; } } }
        void align() { while (n != 0) put(0, 1); }
        int size() { return o.size(); }
    }

    static class Frame {
        int size, pre, pcmSamples, offsetMode, offsetSamples, commonSize;
        boolean hasCommon;
        int versionIndex, rateIndex, channelMode, modeExt, granule, channels;
        boolean mpeg1;
        int[] scfsi = new int[2], mainSize = new int[2]; long[] o1 = new long[2], o2 = new long[2];
        long dataBit;        // absolute bit offset of main data
        int pcmOffset;       // byte offset of PCM samples
    }

    static Frame parse(byte[] d, int off) {
        Bits bs = new Bits(d, off * 8L);
        Frame f = new Frame();
        boolean ext = bs.get(1) == 1;
        int stereo = (int) bs.get(1);
        bs.get(2);
        f.size = (int) bs.get(12);
        f.pre = 2;
        if (ext) { f.offsetMode = (int) bs.get(2); f.offsetSamples = (int) bs.get(10); f.pcmSamples = (int) bs.get(10); f.commonSize = (int) bs.get(10); f.pre += 4; }
        f.hasCommon = !ext || f.commonSize != 0;
        int common = 0;
        if (f.hasCommon) {
            long start = bs.pos;
            f.versionIndex = (int) bs.get(2); f.rateIndex = (int) bs.get(2); f.channelMode = (int) bs.get(2); f.modeExt = (int) bs.get(2);
            int ver = VERSION[f.versionIndex];
            if (ver < 0 || f.rateIndex == 3) throw new IllegalStateException("bad EALayer3 frame at " + off);
            f.mpeg1 = ver == 1;
            f.channels = f.channelMode == 3 ? 1 : 2;
            int other2 = f.mpeg1 ? 15 : 19;
            f.granule = (int) bs.get(1);
            if (f.mpeg1 && f.granule == 1) for (int i = 0; i < f.channels; i++) f.scfsi[i] = (int) bs.get(4);
            int dataBits = 0;
            for (int i = 0; i < f.channels; i++) { f.mainSize[i] = (int) bs.get(12); f.o1[i] = bs.get(32); f.o2[i] = bs.get(other2); dataBits += f.mainSize[i]; }
            f.dataBit = bs.pos;
            long base = bs.pos - start;
            long total = base + dataBits;
            common = (int) ((total + 7) / 8);
        } else f.channels = stereo + 1;
        f.pcmOffset = off + f.pre + common;
        int expect = f.pre + common + 2 * f.pcmSamples * f.channels;
        if (expect != f.size) throw new IllegalStateException("EALayer3 frame size " + f.size + " != " + expect + " at " + off);
        return f;
    }

    // Layer III bitrates (kbps) by index 1..14
    static final int[] BR1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    static final int[] BR2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    /** Smallest standard bitrate index whose frame (no padding) holds `need` bytes. */
    static int bitrateIndex(Frame f, int need) {
        int sr = RATE[f.versionIndex][f.rateIndex];
        for (int i = 1; i < 15; i++) if (frameSize(f, i) >= need) return i;
        throw new IllegalStateException("rebuilt MP3 frame needs " + need + " bytes, more than the largest bitrate allows at " + sr + " Hz");
    }
    static int frameSize(Frame f, int idx) {
        int sr = RATE[f.versionIndex][f.rateIndex];
        return f.mpeg1 ? 144 * BR1[idx] * 1000 / sr : 72 * BR2[idx] * 1000 / sr;
    }

    static void copyMain(byte[] d, Frame f, Out o) {
        Bits bs = new Bits(d, f.dataBit);
        for (int i = 0; i < f.channels; i++) for (int k = 0; k < f.mainSize[i]; k++) o.put(bs.get(1), 1);
    }

    static void header(Frame f, Out o, int br) {
        o.put(0x7FF, 11); o.put(f.versionIndex, 2); o.put(1, 2); o.put(1, 1); o.put(br, 4); o.put(f.rateIndex, 2);
        o.put(0, 1); o.put(0, 1); o.put(f.channelMode, 2); o.put(f.modeExt, 2); o.put(1, 1); o.put(1, 1); o.put(0, 2);
    }

    // One rebuilt MP3 frame before layout: header frame, side info (without main_data_begin), main data bytes.
    record Pending(Frame f, byte[] side, int sideBits, byte[] main) {}

    /** Rebuilds an MP3 stream from an EALayer3 SNS payload; raw PCM blocks go to pcmParts.
     *  All frames use the highest bitrate; when a frame's main data doesn't fit its own slot it
     *  starts earlier, in the unused tail of previous frames (MP3 bit reservoir, main_data_begin). */
    static byte[] toMp3(byte[] d, List<short[]> pcmParts, int[] chOut) {
        List<Pending> frames = new ArrayList<>();
        int off = 0;
        Frame g0 = null;
        while (off + 2 <= d.length) {
            Frame f = parse(d, off);
            if (f.pcmSamples > 0) {
                short[] s = new short[f.pcmSamples * f.channels];
                for (int i = 0; i < s.length; i++) s[i] = (short) ((d[f.pcmOffset + i * 2] & 255) << 8 | (d[f.pcmOffset + i * 2 + 1] & 255));
                pcmParts.add(s);
            }
            if (f.hasCommon) {
                chOut[0] = f.channels;
                if (f.mpeg1) {
                    if (f.granule == 0) g0 = f;
                    else if (g0 != null) {
                        Out side = new Out();
                        side.put(0, f.channels == 1 ? 5 : 3);
                        for (int i = 0; i < f.channels; i++) side.put(f.scfsi[i], 4);
                        for (Frame g : new Frame[]{g0, f}) for (int i = 0; i < g.channels; i++) { side.put(g.mainSize[i], 12); side.put(g.o1[i], 32); side.put(g.o2[i], 15); }
                        Out main = new Out(); copyMain(d, g0, main); copyMain(d, f, main); main.align();
                        int bits = side.size() * 8 + side.n; side.align();
                        frames.add(new Pending(g0, side.o.toByteArray(), bits, main.o.toByteArray()));
                        g0 = null;
                    }
                } else {
                    Out side = new Out();
                    side.put(0, f.channels == 1 ? 1 : 2);
                    for (int i = 0; i < f.channels; i++) { side.put(f.mainSize[i], 12); side.put(f.o1[i], 32); side.put(f.o2[i], 19); }
                    Out main = new Out(); copyMain(d, f, main); main.align();
                    int bits = side.size() * 8 + side.n; side.align();
                    frames.add(new Pending(f, side.o.toByteArray(), bits, main.o.toByteArray()));
                }
            }
            off += f.size;
        }
        // layout in the main-data stream: frame k's slot starts at pos; its data starts main_data_begin bytes earlier
        int n = frames.size();
        int[] cap = new int[n], begin = new int[n];
        long pos = 0, prevEnd = 0;
        for (int k = 0; k < n; k++) {
            Pending fr = frames.get(k);
            boolean m1 = fr.f().mpeg1;
            int sideBytes = (fr.sideBits() + (m1 ? 9 : 8) + 7) / 8;
            cap[k] = frameSize(fr.f(), 14) - 4 - sideBytes;
            long s = Math.max(prevEnd, pos - (m1 ? 511 : 255));
            if (s + fr.main().length > pos + cap[k]) throw new IllegalStateException("MP3 frame " + k + " does not fit even with the bit reservoir");
            begin[k] = (int) (pos - s);
            prevEnd = s + fr.main().length;
            pos += cap[k];
        }
        byte[] stream = new byte[(int) pos];
        pos = 0;
        for (int k = 0; k < n; k++) { System.arraycopy(frames.get(k).main(), 0, stream, (int) (pos - begin[k]), frames.get(k).main().length); pos += cap[k]; }
        ByteArrayOutputStream mp3 = new ByteArrayOutputStream();
        pos = 0;
        for (int k = 0; k < n; k++) {
            Pending fr = frames.get(k);
            boolean m1 = fr.f().mpeg1;
            Out o = new Out();
            header(fr.f(), o, 14);
            o.put(begin[k], m1 ? 9 : 8);
            Bits sb = new Bits(fr.side(), 0);
            for (int i = 0; i < fr.sideBits(); i++) o.put(sb.get(1), 1);
            o.align();
            mp3.writeBytes(o.o.toByteArray());
            mp3.write(stream, (int) pos, cap[k]);
            pos += cap[k];
        }
        return mp3.toByteArray();
    }

    /** Full decode: MP3 part via ffmpeg. lead = number of leading samples to drop from the MP3 output. */
    static short[] decode(byte[] payload, int channels, int samples, String ffmpeg) throws Exception {
        List<short[]> pcm = new ArrayList<>();
        int[] ch = {channels};
        byte[] mp3 = toMp3(payload, pcm, ch);
        Path in = Files.createTempFile("eal3", ".mp3"), out = Files.createTempFile("eal3", ".raw");
        try {
            Files.write(in, mp3);
            Process p = SbkAudio.startFfmpeg("-hide_banner", "-loglevel", "error", "-y", "-f", "mp3", "-i", in.toString(),
                "-f", "s16le", "-acodec", "pcm_s16le", out.toString());
            String log = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) throw new IOException("ffmpeg failed: " + log);
            byte[] raw = Files.readAllBytes(out);
            short[] dec = new short[raw.length / 2];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(dec);
            lastPcmParts = pcm;
            return dec;
        } finally { Files.deleteIfExists(in); Files.deleteIfExists(out); }
    }
    static List<short[]> lastPcmParts;

    /** MP3 decoder delay (529) + one granule (576) that the EA encoder prepends. */
    static final int DISCARD = 1105;

    /** Decodes one EALayer3 sample to exactly `samples` frames (interleaved): the raw PCM block at the
     *  start of the stream gives the first samples, the MP3 decode continues after it.
     *  Checked on Dead Space PS3 banks against the 360 XMA decode of the same sounds (correlation >= 0.99). */
    static short[] decodeSample(byte[] payload, int channels, int samples, String ffmpeg) throws Exception {
        short[] dec = decode(payload, channels, samples, ffmpeg);
        short[] out = new short[samples * channels];
        int lead = lastPcmParts.isEmpty() ? 0 : Math.min(lastPcmParts.get(0).length, out.length);
        if (lead > 0) System.arraycopy(lastPcmParts.get(0), 0, out, 0, lead);
        int from = (DISCARD + lead / channels) * channels;
        if (from < dec.length) System.arraycopy(dec, from, out, lead, Math.min(dec.length - from, out.length - lead));
        return out;
    }

    public static void main(String[] a) throws Exception {
        // java EaLayer3.java <bank.sbk> <index> <out.wav>   (raw MP3 decode, no alignment; for study)
        byte[] b = Files.readAllBytes(Path.of(a[0]));
        SbkAudio.Sample s = SbkAudio.parse(b, SbkAudio.sampleOffset(b, Integer.parseInt(a[1])));
        short[] dec = decode(s.payload(), s.channels(), s.samples(), SbkAudio.FFMPEG);
        int pcmTotal = 0; for (short[] p : lastPcmParts) pcmTotal += p.length / s.channels();
        System.out.printf("codec %d ch %d rate %d header samples %d; mp3 decoded %d; pcm blocks %d (%d samples)%n",
            s.codec(), s.channels(), s.rate(), s.samples(), dec.length / s.channels(), lastPcmParts.size(), pcmTotal);
        if (a.length > 3) {   // optional: drop this many leading sample frames
            int drop = Math.min(dec.length, Integer.parseInt(a[3]) * s.channels());
            dec = Arrays.copyOfRange(dec, drop, dec.length);
        }
        SbkAudio.writeWav(Path.of(a[2]), dec, s.channels(), s.rate());
    }
}
