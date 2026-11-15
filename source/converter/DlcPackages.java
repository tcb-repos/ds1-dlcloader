// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Package-level pipeline: takes the user's own Dead Space DLC downloads (Xbox 360 STFS packages, PS3
// .pkg files, or .zip files containing either), extracts them, converts every pack and writes
// <output>\dlc\<pack>\ for the PC game. Used by DlcLoaderGui; also runnable from the command line:
//
//   java DlcPackages.java --pc <Dead Space PC folder> [--out <folder>] [--prefer 360|ps3] [--skip-unsupported] <files...>
//
// --out defaults to the PC folder. When a pack is present for both consoles, --prefer picks the source
// (default 360). A pack is only written when all its files convert; an existing dlc\<pack> is replaced.
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

public class DlcPackages {

    /** One pack found in an extracted package: its folder (holding <name>.store) and source console. */
    record Pack(String name, Path root, boolean ps3, String from, String region) {
        String console() { return ps3 ? "PS3" + (region.isEmpty() ? "" : " " + region) : "Xbox 360"; }
        /** Within one console, European PS3 packages win over USA ones (they have all 11 languages; USA has 5). */
        int regionRank() { return region.equals("Europe") ? 0 : region.equals("USA") ? 2 : 1; }
    }

    public interface Progress { void step(int done, int total, String what); }

    // ------------------------------------------------------------------ input
    enum Kind { ZIP, PS3_PKG, X360_STFS, UNKNOWN }

    static Kind kind(Path f) throws IOException {
        byte[] m = new byte[4];
        try (InputStream in = Files.newInputStream(f)) { if (in.readNBytes(m, 0, 4) < 4) return Kind.UNKNOWN; }
        if (m[0] == 'P' && m[1] == 'K' && m[2] == 3 && m[3] == 4) return Kind.ZIP;
        if ((m[0] & 255) == 0x7F && m[1] == 'P' && m[2] == 'K' && m[3] == 'G') return Kind.PS3_PKG;
        String s = new String(m, "ISO-8859-1");
        if (s.equals("CON ") || s.equals("LIVE") || s.equals("PIRS")) return Kind.X360_STFS;
        return Kind.UNKNOWN;
    }

    /** Extracts every package in the inputs (zips are opened recursively) into work\ and returns the packs found. */
    static List<Pack> findPacks(List<Path> inputs, Path work, PrintStream log) throws Exception {
        List<Pack> packs = new ArrayList<>();
        int[] n = {0};
        for (Path f : inputs) collect(f, work, log, packs, n, f.getFileName().toString());
        return packs;
    }

    static void collect(Path f, Path work, PrintStream log, List<Pack> packs, int[] n, String from) throws Exception {
        if (Files.isDirectory(f)) {                      // a folder: every package file inside it
            List<Path> inner;
            try (Stream<Path> s = Files.walk(f)) { inner = s.filter(Files::isRegularFile).sorted().toList(); }
            for (Path p : inner) if (kind(p) != Kind.UNKNOWN) collect(p, work, log, packs, n, p.getFileName().toString());
            return;
        }
        switch (kind(f)) {
            case ZIP -> {
                Path dir = work.resolve("zip" + (n[0]++));
                unzip(f, dir);
                List<Path> inner;
                try (Stream<Path> s = Files.walk(dir)) { inner = s.filter(Files::isRegularFile).sorted().toList(); }
                for (Path p : inner) if (kind(p) != Kind.UNKNOWN) collect(p, work, log, packs, n, from);
            }
            case PS3_PKG -> {
                Path dir = work.resolve("pkg" + (n[0]++));
                log.println("Extracting PS3 package " + f.getFileName() + " (from " + from + ")");
                runQuietly(() -> PkgExtract.main(new String[]{f.toString(), dir.toString()}));
                addPacks(dir, true, from, ps3Region(f), packs, log);
            }
            case X360_STFS -> {
                Path dir = work.resolve("stfs" + (n[0]++));
                log.println("Extracting Xbox 360 package " + f.getFileName() + " (from " + from + ")");
                runQuietly(() -> StfsExtract.main(new String[]{f.toString(), dir.toString()}));
                addPacks(dir, false, from, "", packs, log);
            }
            case UNKNOWN -> log.println("Skipped " + f.getFileName() + ": not a Dead Space DLC package (360 STFS, PS3 .pkg or .zip)");
        }
    }

    /** PS3 region from the package's content ID (header 0x30): "EP..." Europe, "UP..." USA. */
    static String ps3Region(Path pkg) throws IOException {
        byte[] h = new byte[0x32];
        try (InputStream in = Files.newInputStream(pkg)) { in.readNBytes(h, 0, h.length); }
        return h[0x30] == 'E' ? "Europe" : h[0x30] == 'U' ? "USA" : "";
    }

    static void addPacks(Path dir, boolean ps3, String from, String region, List<Pack> packs, PrintStream log) throws IOException {
        List<Path> stores;
        try (Stream<Path> s = Files.walk(dir)) { stores = s.filter(p -> p.toString().toLowerCase().endsWith(".store")).sorted().toList(); }
        if (stores.isEmpty()) log.println("  no DLC packs in it (no .store file)");
        for (Path st : stores) {
            String name = st.getFileName().toString();
            name = name.substring(0, name.length() - ".store".length()).toLowerCase();
            Pack p = new Pack(name, st.getParent(), ps3, from, region);
            packs.add(p);
            log.println("  found pack '" + name + "' (" + p.console() + ")");
        }
    }

    static void unzip(Path zip, Path dir) throws IOException {
        Files.createDirectories(dir);
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path p = dir.resolve(e.getName()).normalize();
                if (!p.startsWith(dir.normalize())) throw new IOException("unsafe path in zip: " + e.getName());
                if (e.isDirectory()) { Files.createDirectories(p); continue; }
                Files.createDirectories(p.getParent());
                Files.copy(z, p, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    interface Job { void run() throws Exception; }
    /** The extractors print a file list to stdout; keep it out of the user's log. */
    static synchronized void runQuietly(Job j) throws Exception {
        PrintStream old = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try { j.run(); } finally { System.setOut(old); }
    }

    // ------------------------------------------------------------------ choose + convert
    /** One pack per name; when both consoles have it, take the preferred one. */
    static List<Pack> choose(List<Pack> all, boolean preferPs3, PrintStream log) {
        Map<String, Pack> pick = new TreeMap<>();
        for (Pack p : all) {
            Pack old = pick.get(p.name());
            if (old == null || (old.ps3() != preferPs3 && p.ps3() == preferPs3)
                    || (old.ps3() == p.ps3() && p.regionRank() < old.regionRank())) pick.put(p.name(), p);
        }
        for (Pack p : pick.values()) {
            long both = all.stream().filter(q -> q.name().equals(p.name())).map(Pack::ps3).distinct().count();
            if (both > 1) log.println("Pack '" + p.name() + "' is available from both consoles; using " + p.console());
        }
        List<Pack> packs = new ArrayList<>(pick.values());
        // bundles: a pack whose items all appear in another (bigger) pack is left out
        Map<Pack, Store> st = new HashMap<>();
        for (Pack p : packs) try { st.put(p, Store.read(p.root().resolve(p.name() + ".store"))); } catch (Exception e) { st.put(p, new Store(List.of())); }
        packs.removeIf(p -> packs.stream().anyMatch(q -> q != p && st.get(q).items().size() > st.get(p).items().size()
            && st.get(q).streams().containsAll(st.get(p).streams()) && logged(log, "Pack '" + p.name() + "' is already included in '" + q.name() + "'; skipping it")));
        // id collisions (Elite on 360 and Obsidian on PS3 both use store items 133-135, weapon slots 34-36,
        // suit store item 3 and suit slot 9): the pack from the preferred console keeps its numbers, the
        // other gets free ones (manifest + the SimGroup fields in IdRemap)
        packs.sort(Comparator.comparing((Pack p) -> p.ps3() != preferPs3).thenComparing(Pack::name));
        List<Pack> kept = new ArrayList<>();
        remaps.clear();
        for (Pack p : packs) {
            Pack clash = null; String what = null;
            for (Pack k : kept) { what = st.get(p).clash(st.get(k)); if (what != null) { clash = k; break; } }
            if (clash != null) {
                List<Store> others = new ArrayList<>();
                for (Pack q : packs) if (q != p) others.add(st.get(q));
                IdRemap r = st.get(p).remapAgainst(others);
                remaps.put(p.name(), r);
                st.put(p, st.get(p).remapped(r));
                log.println("Pack '" + p.name() + "' (" + p.console() + ") uses the same " + what + " as '" + clash.name()
                    + "'; giving '" + p.name() + "' new numbers: " + r);
            }
            kept.add(p);
        }
        kept.sort(Comparator.comparing(Pack::name));
        return kept;
    }

    /** Packs that get new numbers (from choose), by pack name. */
    static final Map<String, IdRemap> remaps = new HashMap<>();

    static boolean logged(PrintStream log, String s) { log.println(s); return true; }

    /** A pack's .store manifest: version, n weapons x [gp stream, store id, stream, weapon slot],
     *  n suits x [uniform stream, store id, suit stream, suit slot]. */
    record Store(List<String[]> items) {
        static Store read(Path f) throws IOException {
            List<String> t = new ArrayList<>(Arrays.asList(Files.readString(f).trim().split("\\s+")));
            List<String[]> items = new ArrayList<>();
            int i = 1;                                   // skip the version
            for (int group = 0; group < 2 && i < t.size(); group++) {
                int n = Integer.parseInt(t.get(i++));
                for (int k = 0; k < n && i + 3 < t.size(); k++, i += 4)
                    items.add(new String[]{group == 0 ? "weapon" : "suit", t.get(i), t.get(i + 1), t.get(i + 2), t.get(i + 3)});
            }
            return new Store(items);
        }
        Set<String> streams() { Set<String> s = new HashSet<>(); for (String[] it : items) { s.add(it[1].toLowerCase()); s.add(it[3].toLowerCase()); } return s; }
        /** Free numbers for this pack, above everything the other packs use (same scheme as the reference pack):
         *  weapon store items move to the next free block of ten keeping their last digit (133 -> 203),
         *  weapon slots start 5 above the highest used, suit store item and suit slot take the next free one. */
        IdRemap remapAgainst(List<Store> others) {
            // Numbers used by every official console pack (Xbox 360 + PS3), whether or not it is part of this
            // conversion, so a remapped pack never clashes with one installed later:
            // weapon store items up to 195, weapon slots up to 78, suit store items 1-4, suit slots 7-10.
            int maxStore = 195, maxWSlot = 78, maxSStore = 4, maxSSlot = 10;
            Set<Integer> usedStore = new HashSet<>();
            for (Store o : others) for (String[] it : o.items) {
                int id = Integer.parseInt(it[2]), slot = Integer.parseInt(it[4]);
                usedStore.add(id);
                if (it[0].equals("weapon")) { maxStore = Math.max(maxStore, id); maxWSlot = Math.max(maxWSlot, slot); }
                else { maxSStore = Math.max(maxSStore, id); maxSSlot = Math.max(maxSSlot, slot); }
            }
            IdRemap r = new IdRemap();
            int block = (maxStore / 10 + 1) * 10, wslot = maxWSlot + 5;
            for (String[] it : items) {
                int id = Integer.parseInt(it[2]), slot = Integer.parseInt(it[4]);
                if (it[0].equals("weapon")) {
                    int n = block + id % 10;
                    while (usedStore.contains(n) || r.store.containsValue(n)) n++;
                    r.store.put(id, n);
                    r.weapon.put(slot, wslot++);
                } else {
                    r.store.put(id, ++maxSStore);
                    r.suit.put(slot, ++maxSSlot);
                }
            }
            return r;
        }

        Store remapped(IdRemap r) {
            List<String[]> out = new ArrayList<>();
            for (String[] it : items) {
                String[] c = it.clone();
                c[2] = String.valueOf(r.store.getOrDefault(Integer.parseInt(it[2]), Integer.parseInt(it[2])));
                c[4] = String.valueOf(r.map(it[0]).getOrDefault(Integer.parseInt(it[4]), Integer.parseInt(it[4])));
                out.add(c);
            }
            return new Store(out);
        }

        /** The .store file text for these items, in the original format (version 1, weapons, suits). */
        String text() {
            String nl = "\r\n";                        // the console manifests use CRLF
            StringBuilder sb = new StringBuilder("1").append(nl);
            for (String kind : new String[]{"weapon", "suit"}) {
                List<String[]> l = items.stream().filter(i -> i[0].equals(kind)).toList();
                sb.append(l.size()).append(nl);
                for (String[] i : l) sb.append(i[1]).append(nl).append(i[2]).append(nl).append(i[3]).append(nl).append(i[4]).append(nl);
            }
            return sb.toString();
        }

        /** what this pack shares with another pack in a way that collides, or null */
        String clash(Store o) {
            for (String[] a : items) for (String[] b : o.items) {
                if (a[1].equalsIgnoreCase(b[1])) continue;                         // same item: not a clash
                if (a[2].equals(b[2])) return "store item number (" + a[2] + ")";
                if (a[0].equals(b[0]) && a[4].equals(b[4])) return a[0] + " slot (" + a[4] + ")";
            }
            return null;
        }
    }

    /** Converts the chosen packs into out\dlc\. Returns the names of packs that failed. */
    static List<String> convert(List<Pack> packs, Path pcDir, Path out, boolean skipUnsupported, PrintStream log, Progress prog) throws Exception {
        log.println("Indexing the PC game files (first run takes a minute)...");
        StrConvert.setup(pcDir);
        List<String> failed = new ArrayList<>();
        int total = 0;
        for (Pack p : packs) total += strFiles(p.root()).size();
        int done = 0;
        Path dlc = out.resolve("dlc");
        Files.createDirectories(dlc);
        for (Pack p : packs) {
            log.println();
            log.println("=== " + p.name() + " (" + p.console() + ", from " + p.from() + ")");
            Path stage = dlc.resolve("." + p.name() + ".converting");
            deleteTree(stage);
            boolean ok = true;
            IdRemap remap = remaps.get(p.name());
            StrConvert.idRemap = remap;
            try {
                try (Stream<Path> s = Files.walk(p.root())) {
                    for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                        String n = f.getFileName().toString().toLowerCase();
                        Path rel = p.root().relativize(f), dst = stage.resolve(rel.toString());
                        Files.createDirectories(dst.getParent());
                        if (n.endsWith(".store")) {
                            if (remap == null) Files.copy(f, dst);
                            else Files.writeString(dst, Store.read(f).remapped(remap).text());
                        }
                        else if (n.endsWith(".str")) {
                            prog.step(done, total, p.name() + ": " + rel);
                            StrConvert.convertFile(f, dst, skipUnsupported, Set.of(), null, log);
                            done++;
                        }
                    }
                }
            } catch (Exception e) {
                ok = false;
                log.println("FAILED: " + p.name() + ": " + e.getMessage());
                log.println("  (this pack uses a format the converter doesn't support yet; it was not installed)");
            } finally {
                StrConvert.idRemap = null;
            }
            if (ok && remap != null) log.println("  new numbers applied to " + StrConvert.idRemapFields + " data fields");
            StrConvert.idRemapFields = 0;
            if (ok) {
                Path target = dlc.resolve(p.name());
                deleteTree(target);
                Files.move(stage, target);
                log.println("installed " + target);
            } else {
                deleteTree(stage);
                failed.add(p.name());
                done = 0; for (Pack q : packs) if (q == p) break; else done += strFiles(q.root()).size();
            }
        }
        prog.step(total, total, "done");
        return failed;
    }

    static List<Path> strFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) { return s.filter(f -> f.toString().toLowerCase().endsWith(".str")).toList(); }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) { for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(q); }
    }

    // ------------------------------------------------------------------ loader
    enum LoaderState { NOT_SHIPPED, NONE, SAME, OURS_OLDER, OTHER }

    /** The release loader shipped with the converter: <tools>\..\loader\dinput8.dll. */
    static Path shippedLoader() { return StrConvert.toolsDir().toAbsolutePath().normalize().getParent().resolve("loader").resolve("dinput8.dll"); }

    static LoaderState loaderState(Path game) throws IOException {
        Path src = shippedLoader(), dst = game.resolve("dinput8.dll");
        if (!Files.isRegularFile(src)) return LoaderState.NOT_SHIPPED;
        if (!Files.exists(dst)) return LoaderState.NONE;
        byte[] a = Files.readAllBytes(src), b = Files.readAllBytes(dst);
        if (Arrays.equals(a, b)) return LoaderState.SAME;
        return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).contains("dlcloader starting") ? LoaderState.OURS_OLDER : LoaderState.OTHER;
    }

    /** Copies the loader into the game folder. Another program's dinput8.dll is kept as dinput8.dll.bak. */
    static void installLoader(Path game, PrintStream log) throws IOException {
        Path dst = game.resolve("dinput8.dll");
        LoaderState st = loaderState(game);
        switch (st) {
            case NOT_SHIPPED -> log.println("The loader DLL (loader\\dinput8.dll) is missing from the DLC Loader folder; not installed.");
            case SAME -> log.println("Loader DLL already installed.");
            case OTHER -> {
                Path bak = game.resolve("dinput8.dll.bak");
                Files.copy(dst, bak, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(shippedLoader(), dst, StandardCopyOption.REPLACE_EXISTING);
                log.println("Installed the loader DLL. The previous dinput8.dll (another mod) was saved as " + bak.getFileName());
            }
            default -> { Files.copy(shippedLoader(), dst, StandardCopyOption.REPLACE_EXISTING); log.println("Installed the loader DLL: " + dst); }
        }
    }

    /** Is this a Dead Space PC install? */
    static boolean isGameFolder(Path p) { return p != null && Files.isRegularFile(p.resolve("Dead Space.exe")); }

    /** The game opens its files with full paths in 260-character buffers (Windows MAX_PATH). Its longest
     *  file (global_assets\...\ch12_landing_pad_to_supply_depot_audio.str) is 150 characters, so a game
     *  folder path longer than about 108 characters makes level loads crash, with or without DLC.
     *  Returns the number of game files whose full path is 260 characters or more (0 = fine). */
    static int tooLongPaths(Path game) throws IOException {
        int base = game.toAbsolutePath().normalize().toString().length();
        try (var s = Files.walk(game)) {
            return (int) s.filter(Files::isRegularFile)
                .filter(f -> base + 1 + game.relativize(f).toString().length() >= 260).count();
        }
    }
    static final int MAX_GAME_PATH = 108;

    // ------------------------------------------------------------------ command line
    public static void main(String[] a) throws Exception {
        Path pc = null, out = null; boolean ps3 = false, skip = false; List<Path> in = new ArrayList<>();
        for (int i = 0; i < a.length; i++) switch (a[i]) {
            case "--pc" -> pc = Path.of(a[++i]);
            case "--out" -> out = Path.of(a[++i]);
            case "--prefer" -> ps3 = a[++i].equalsIgnoreCase("ps3");
            case "--skip-unsupported" -> skip = true;
            default -> in.add(Path.of(a[i]));
        }
        if (!isGameFolder(pc)) throw new IllegalArgumentException("--pc must be the Dead Space PC folder (with Dead Space.exe)");
        if (out == null) out = pc;
        Path work = Files.createTempDirectory("dsdlc");
        try {
            List<Pack> packs = choose(findPacks(in, work, System.out), ps3, System.out);
            List<String> failed = convert(packs, pc, out, skip, System.out, (d, t, w) -> {});
            System.out.println("\n" + (packs.size() - failed.size()) + " of " + packs.size() + " packs converted" + (failed.isEmpty() ? "" : "; failed: " + failed));
        } finally { deleteTree(work); }
    }
}
