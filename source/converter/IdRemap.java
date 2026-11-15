// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Store item numbers and weapon/suit slots of a DLC pack, and where they live in its SimGroups.
// Used to give a pack new numbers when it clashes with another (Elite on 360 and Obsidian on PS3
// both use store items 133-135, weapon slots 34-36, suit store item 3 and suit slot 9).
// The fields (class hash : field index) were found from the reference pack's remapped Elite and are
// checked on every pack by RemapCheck.java.
import java.util.*;

public class IdRemap {
    static final Set<String> STORE = Set.of("96d26cb1:1", "4ecfbe13:32", "e7cf170f:19");   // DLCItemStoreList id + weapon store ids
    static final Set<String> WEAPON_SLOT = Set.of("7dd2e2ae:2", "ab62aa4e:0", "332d5a20:42"); // DLCWeaponStreamAttributes slot + uses
    static final Set<String> SUIT_SLOT = Set.of("0575c2f4:0", "99134670:0");

    /** "store", "weapon", "suit" or null */
    static String kind(String field) {
        return STORE.contains(field) ? "store" : WEAPON_SLOT.contains(field) ? "weapon" : SUIT_SLOT.contains(field) ? "suit" : null;
    }

    /** old -> new numbers per kind */
    final Map<Integer, Integer> store = new HashMap<>(), weapon = new HashMap<>(), suit = new HashMap<>();

    boolean isEmpty() { return store.isEmpty() && weapon.isEmpty() && suit.isEmpty(); }

    Map<Integer, Integer> map(String kind) { return kind.equals("store") ? store : kind.equals("weapon") ? weapon : suit; }

    /** Applies the remap to a PC (little-endian) SimGroup in place; returns the number of fields changed. */
    int apply(byte[] pc) {
        Map<Integer, String> v = new HashMap<>();
        SimGroup.classify(pc, false, new ArrayList<>(), v);
        int n = 0;
        for (var e : v.entrySet()) {
            String k = kind(e.getValue());
            if (k == null) continue;
            int o = e.getKey();
            int x = (pc[o] & 255) | (pc[o + 1] & 255) << 8 | (pc[o + 2] & 255) << 16 | (pc[o + 3] & 255) << 24;
            Integer y = map(k).get(x);
            if (y == null) continue;
            pc[o] = (byte) (int) y; pc[o + 1] = (byte) (y >> 8); pc[o + 2] = (byte) (y >> 16); pc[o + 3] = (byte) (y >>> 24);
            n++;
        }
        return n;
    }

    @Override public String toString() { return "store " + store + ", weapon slots " + weapon + ", suit slots " + suit; }
}
