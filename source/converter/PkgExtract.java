// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Extracts a retail PS3 .pkg (outer layer only): AES-128-CTR with the PS3 package key, counter =
// header IV (0x70) + block index from the start of the data area (header 0x20).
// Item table at data start: count (header 0x14) x 32 bytes:
//   name offset u32, name size u32, data offset u64, data size u64, flags u32, pad u32 (all BE).
// Usage: java PkgExtract.java <file.pkg> <outDir>
import java.math.BigInteger;
import java.nio.file.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public class PkgExtract {
    static final byte[] PS3_PKG_KEY = hex("2E7B71D7C9C9A14EA3221F188828B8F8");

    static byte[] hex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16); return b; }
    static long be(byte[] b, int o, int n) { long v = 0; for (int i = 0; i < n; i++) v = v << 8 | (b[o + i] & 255); return v; }

    static byte[] pkg, iv;
    static long dataOff;

    // decrypt [rel, rel+len) of the data area
    static byte[] dec(long rel, int len) throws Exception {
        long start = rel & ~15L;
        int skip = (int) (rel - start);
        BigInteger ctr = new BigInteger(1, iv).add(BigInteger.valueOf(start / 16));
        byte[] c = ctr.toByteArray(), c16 = new byte[16];
        for (int i = 0; i < 16 && i < c.length; i++) c16[15 - i] = c[c.length - 1 - i];
        Cipher ci = Cipher.getInstance("AES/CTR/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(PS3_PKG_KEY, "AES"), new IvParameterSpec(c16));
        byte[] in = new byte[skip + len];
        System.arraycopy(pkg, (int) (dataOff + start), in, 0, Math.min(in.length, pkg.length - (int) (dataOff + start)));
        byte[] out = ci.doFinal(in);
        byte[] r = new byte[len];
        System.arraycopy(out, skip, r, 0, len);
        return r;
    }

    public static void main(String[] a) throws Exception {
        pkg = Files.readAllBytes(Path.of(a[0]));
        if (be(pkg, 0, 4) != 0x7F504B47L) throw new IllegalArgumentException("not a PKG");
        if (be(pkg, 4, 2) != 0x8000 || be(pkg, 6, 2) != 1) throw new IllegalArgumentException("not a retail PS3 PKG");
        int items = (int) be(pkg, 0x14, 4);
        dataOff = be(pkg, 0x20, 8);
        iv = java.util.Arrays.copyOfRange(pkg, 0x70, 0x80);
        System.out.println("content id: " + new String(pkg, 0x30, 0x24).trim() + ", items " + items);
        byte[] tab = dec(0, items * 32);
        Path out = Path.of(a[1]);
        for (int i = 0; i < items; i++) {
            int o = i * 32;
            long no = be(tab, o, 4), ns = be(tab, o + 4, 4), off = be(tab, o + 8, 8), size = be(tab, o + 16, 8);
            int flags = (int) be(tab, o + 24, 4);
            String name = new String(dec(no, (int) ns), "UTF-8").replace("\0", "");
            boolean dir = (flags & 0xFF) == 4;
            System.out.printf("  %-60s %10d  flags %08x%s%n", name, size, flags, dir ? "  (dir)" : "");
            if (name.contains("..") || name.startsWith("/") || name.contains(":")) throw new IllegalArgumentException("unsafe name " + name);
            Path p = out.resolve(name).normalize();
            if (!p.startsWith(out.normalize())) throw new IllegalArgumentException("unsafe path " + name);
            if (dir) { Files.createDirectories(p); continue; }
            Files.createDirectories(p.getParent());
            Files.write(p, dec(off, (int) size));
        }
    }
}
