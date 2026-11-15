// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Xbox 360 STFS (CON/LIVE/PIRS) package extractor.
// Usage: java StfsExtract.java <package> <outDir>
// Writes the contained files plus _package.txt (header metadata).
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class StfsExtract {
    static byte[] d;
    static int sex;            // 0 = female (1 hash table per level), 1 = male (2)
    static int blockSep;
    static long firstHashTable;
    static int topLevel;
    static int[] blockStep = new int[2];
    static long topTableAddr;

    static int u8(long o) { return d[(int) o] & 0xFF; }
    static int be16(long o) { return (u8(o) << 8) | u8(o + 1); }
    static long be32(long o) { return ((long) u8(o) << 24) | (u8(o + 1) << 16) | (u8(o + 2) << 8) | u8(o + 3); }
    static int be24(long o) { return (u8(o) << 16) | (u8(o + 1) << 8) | u8(o + 2); }
    static int le24(long o) { return u8(o) | (u8(o + 1) << 8) | (u8(o + 2) << 16); }
    static int le16(long o) { return u8(o) | (u8(o + 1) << 8); }

    static String utf16be(long o, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxBytes; i += 2) {
            char c = (char) be16(o + i);
            if (c == 0) break;
            sb.append(c);
        }
        return sb.toString();
    }

    static long backingDataBlock(long b) {
        long r = (((b + 0xAA) / 0xAA) << sex) + b;
        if (b < 0xAA) return r;
        if (b < 0x70E4) return r + (((b + 0x70E4) / 0x70E4) << sex);
        return (1L << sex) + r + (((b + 0x70E4) / 0x70E4) << sex);
    }

    static long blockAddr(long b) { return (backingDataBlock(b) << 12) + firstHashTable; }

    static long level0HashBlock(long b) {
        if (b < 0xAA) return 0;
        long n = (b / 0xAA) * blockStep[0];
        n += ((b / 0x70E4) + 1) << sex;
        if (b / 0x70E4 == 0) return n;
        return n + (1L << sex);
    }

    static long level1HashBlock(long b) {
        if (b < 0x70E4) return blockStep[0];
        return (1L << sex) + (b / 0x70E4) * blockStep[1];
    }

    static long hashEntryAddr(long b) {
        long a = (level0HashBlock(b) << 12) + firstHashTable + (b % 0xAA) * 0x18;
        if (sex == 0) return a; // female: single table copy
        switch (topLevel) {
            case 0: a += (blockSep & 2) << 0xB; break;
            case 1: a += (u8(topTableAddr + (b / 0xAA) * 0x18 + 0x14) & 0x40) << 6; break;
            default: {
                long l1off = (u8(topTableAddr + (b / 0x70E4) * 0x18 + 0x14) & 0x40) << 6;
                long pos = (level1HashBlock(b) << 12) + firstHashTable + l1off + ((b / 0xAA) % 0xAA) * 0x18;
                a += (u8(pos + 0x14) & 0x40) << 6;
            }
        }
        return a;
    }

    static int nextBlock(int b) { return be24(hashEntryAddr(b) + 0x15); }

    record Entry(String name, int flags, int blocks, int start, int parent, long size) {
        boolean dir() { return (flags & 0x80) != 0; }
        boolean consecutive() { return (flags & 0x40) != 0; }
    }

    public static void main(String[] a) throws Exception {
        d = Files.readAllBytes(Path.of(a[0]));
        Path out = Path.of(a[1]);
        Files.createDirectories(out);
        String magic = new String(d, 0, 4, StandardCharsets.US_ASCII);
        long headerSize = be32(0x340);
        long contentType = be32(0x344);
        long titleId = be32(0x360);
        blockSep = u8(0x37B);
        sex = (~blockSep) & 1;
        int ftBlockCount = le16(0x37C);
        int ftBlock = le24(0x37E);
        long allocBlocks = be32(0x395);
        firstHashTable = (headerSize + 0x0FFF) & 0xFFFFF000L;
        if (sex == 0) { blockStep[0] = 0xAB; blockStep[1] = 0x718F; } else { blockStep[0] = 0xAC; blockStep[1] = 0x723A; }
        topLevel = allocBlocks <= 0xAA ? 0 : allocBlocks <= 0x70E4 ? 1 : 2;
        long topBlock = topLevel == 0 ? 0 : topLevel == 1 ? level1HashBlock(0) : (blockStep[1] + (1L << sex)); // level-2 table
        topTableAddr = (topBlock << 12) + firstHashTable + ((sex == 1) ? ((blockSep & 2) << 0xB) : 0);

        String display = utf16be(0x411, 0x80);
        String desc = utf16be(0xD11, 0x80);
        String publisher = utf16be(0x1611, 0x80);
        String titleName = utf16be(0x1691, 0x80);

        // File table
        List<Entry> entries = new ArrayList<>();
        int blk = ftBlock;
        for (int i = 0; i < ftBlockCount; i++) {
            long base = blockAddr(blk);
            for (int e = 0; e < 64; e++) {
                long o = base + e * 0x40L;
                int flags = u8(o + 0x28);
                int nameLen = flags & 0x3F;
                if (nameLen == 0) continue;
                String name = new String(d, (int) o, nameLen, StandardCharsets.US_ASCII);
                entries.add(new Entry(name, flags, le24(o + 0x29), le24(o + 0x2F), (short) be16(o + 0x32), be32(o + 0x34)));
            }
            blk = nextBlock(blk);
        }

        StringBuilder info = new StringBuilder();
        info.append("magic=").append(magic).append('\n');
        info.append(String.format("titleId=%08X contentType=%08X headerSize=0x%X sex=%s topLevel=%d allocBlocks=%d%n",
                titleId, contentType, headerSize, sex == 0 ? "female" : "male", topLevel, allocBlocks));
        info.append("displayName=").append(display).append('\n');
        info.append("description=").append(desc).append('\n');
        info.append("publisher=").append(publisher).append('\n');
        info.append("titleName=").append(titleName).append('\n');
        info.append("files:\n");

        for (int idx = 0; idx < entries.size(); idx++) {
            Entry e = entries.get(idx);
            String rel = pathOf(entries, idx);
            info.append(String.format("  %s%s  size=%d blocks=%d start=%d%s%n", rel, e.dir() ? "/" : "",
                    e.size(), e.blocks(), e.start(), e.consecutive() ? " consecutive" : ""));
            Path p = out.resolve(rel).normalize();
            if (!p.startsWith(out.normalize()) || rel.contains(":")) throw new IllegalStateException("unsafe path in package: " + rel);
            if (e.dir()) { Files.createDirectories(p); continue; }
            Files.createDirectories(p.getParent());
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(p))) {
                long remaining = e.size();
                int b = e.start();
                for (int i = 0; i < e.blocks() && remaining > 0; i++) {
                    int n = (int) Math.min(0x1000, remaining);
                    os.write(d, (int) blockAddr(b), n);
                    remaining -= n;
                    b = e.consecutive() ? b + 1 : nextBlock(b);
                }
            }
        }
        // Thumbnail
        long thumbSize = be32(0x1712);
        if (thumbSize > 0 && thumbSize < 0x4000) Files.write(out.resolve("_thumbnail.png"), Arrays.copyOfRange(d, 0x171A, (int) (0x171A + thumbSize)));
        Files.writeString(out.resolve("_package.txt"), info.toString());
        System.out.print(info);
    }

    static String pathOf(List<Entry> es, int idx) {
        Entry e = es.get(idx);
        String p = e.name();
        int parent = e.parent();
        int guard = 0;
        while (parent >= 0 && parent < es.size() && guard++ < 64) {
            p = es.get(parent).name() + "/" + p;
            parent = es.get(parent).parent();
        }
        return p;
    }
}
