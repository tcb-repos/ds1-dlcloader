// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Dead Space (2008) PC - console DLC loader.
//
// The PC exe keeps the console DLC pipeline (.store parser, DLC stream manager, store/inventory classes),
// but its platform content device is a stub, so no DLC package is ever found. This DLL feeds
// <game>\dlc\<pack>\*.store files straight to the game's .store parser, after which the game's own
// DLC stream manager queues and loads the pack's .str streams.
//
// Loaded as a dinput8.dll proxy. Calls go on to dinput8_next.dll in the game folder if present
// (so it can chain with another dinput8 mod), otherwise to the system dinput8.dll.
//
// Addresses are for the GOG 1.0.0.222 exe (MD5 7AF30BF2170CE44FC967D45FD81EE453) and are checked at startup.
// See NOTES.md ("PC DLC loader") for where each one comes from.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <intrin.h>
#include <unknwn.h>
#include <share.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cstdarg>
#include <string>
#include <vector>
#include "MinHook.h"

// ---- Game addresses ------------------------------------------------------------------------------
static const uintptr_t ADDR_STORE_PARSER  = 0x0051F960; // __thiscall(handler, entry, char* text, len), RET 0xC
static const uintptr_t ADDR_CONTENT_UPDATE = 0x0078B840; // content manager per-frame update, __thiscall(this), plain RET
static const uintptr_t ADDR_STREAM_OPEN   = 0x006C8AD0; // stream loader open, __thiscall(this, path, a2..a6), RET 0x18
static const uintptr_t ADDR_STR_STORE_EXT = 0x00C70FBC; // ".store"
static const uintptr_t ADDR_STR_PDLC_WAIT = 0x00C7B1AC; // "pdlc_wait"
static void** const    PTR_CONTENT_MGR    = (void**)0x00F62A4C;
static void** const    PTR_DLC_SYSTEM     = (void**)0x00F62A50; // object built by 0x51F5E0; +0x10 = .store handler
static void** const    PTR_DLC_STREAM_MGR = (void**)0x00F62AE8;

static const size_t CONTENT_ENTRY_SIZE = 0x150; // content-file entry (pool at 0xF5B900 uses 0x150 per entry)
static const size_t ENTRY_ROOT   = 0x008;      // char[0x80] content root, prefixed to every stream name
static const size_t ENTRY_FILE   = 0x088;      // char[0x80] file path (the parser takes a display name from it)
static const size_t ENTRY_SIZE   = 0x108;      // uint32 file size

// ---- Logging -------------------------------------------------------------------------------------
static FILE* g_log;
static CRITICAL_SECTION g_logLock;
static char g_gameDir[MAX_PATH];

static void Log(const char* fmt, ...)
{
    if (!g_log) return;
    EnterCriticalSection(&g_logLock);
    SYSTEMTIME t; GetLocalTime(&t);
    fprintf(g_log, "[%02d:%02d:%02d.%03d] ", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds);
    va_list ap; va_start(ap, fmt); vfprintf(g_log, fmt, ap); va_end(ap);
    fputc('\n', g_log);
    fflush(g_log);
    LeaveCriticalSection(&g_logLock);
}

static bool MentionsDlc(const char* s);

// ---- Config (dlcloader.ini next to the exe) ------------------------------------------------------
static bool g_logAllStreams = false;   // [log] AllStreams=1 logs every stream open, not just DLC ones
static bool g_logFilesAll = false;    // [log] Files=2 logs every CreateFileA/W
static bool g_logFiles = true;         // [log] Files=1 logs CreateFileA/FindFirstFileA calls that touch dlc\

// [profile] Dir=profile  - keeps settings.txt and saves in <game>\<Dir>\ instead of the shared
// %LOCALAPPDATA%\Electronic Arts\Dead Space and Documents\Electronic Arts\Dead Space folders, so this
// install can't pick up (or overwrite) another install's settings and saves. Dir= (empty) turns it off.
static char g_profileDir[MAX_PATH] = "profile";
static bool g_dlcEnabled = true;       // [dlc] Enabled=0 keeps the hook (profile redirect, logging) but injects no DLC

// [language] Locale=fr_FR  - the game reads its language from the registry value
// HKLM\SOFTWARE\Electronic Arts\Dead Space\Locale (shared by every install) and its global text from
// text_assets\text_assets_global.str/.toc, which GOG's language_setup.exe overwrites with a copy from
// text_assets\<lang>\. With Locale set, the loader answers the game's registry read with it and redirects
// those two files to text_assets\<lang>\ (lang = the locale up to '_'), so nothing on disk or in the
// registry changes. Values (text\localetable.txt): en_US, fr_FR, de, es, it. Empty = the game's own setting.
static char g_locale[32] = "";
static char g_textDir[16] = "";

// Release builds (build.bat release, DLCLOADER_RELEASE) default to the game's normal save/settings
// folders and no file logging; the development build keeps the TestInstall defaults.
#ifdef DLCLOADER_RELEASE
static const char* const DEFAULT_PROFILE_DIR = "";
static const int DEFAULT_LOG_FILES = 0;
#else
static const char* const DEFAULT_PROFILE_DIR = "profile";
static const int DEFAULT_LOG_FILES = 1;
#endif

static void LoadConfig()
{
    char ini[MAX_PATH];
    snprintf(ini, sizeof ini, "%s\\dlcloader.ini", g_gameDir);
    g_logAllStreams = GetPrivateProfileIntA("log", "AllStreams", 0, ini) != 0;
    { int f = GetPrivateProfileIntA("log", "Files", DEFAULT_LOG_FILES, ini); g_logFiles = f != 0; g_logFilesAll = f >= 2; }
    GetPrivateProfileStringA("profile", "Dir", DEFAULT_PROFILE_DIR, g_profileDir, MAX_PATH, ini);
    g_dlcEnabled = GetPrivateProfileIntA("dlc", "Enabled", 1, ini) != 0;
    GetPrivateProfileStringA("language", "Locale", "", g_locale, sizeof g_locale, ini);
    g_textDir[0] = 0;
    for (size_t i = 0; g_locale[i] && g_locale[i] != '_' && i + 1 < sizeof g_textDir; ++i) {
        char ch = g_locale[i];
        if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'))) { g_textDir[0] = 0; break; }
        g_textDir[i] = (char)(ch | 0x20);
        g_textDir[i + 1] = 0;
    }
}

// ---- Profile redirect ----------------------------------------------------------------------------
static const char* ProfileFolderFor(int csidl)
{
    switch (csidl & 0xFF) {
    case 0x05: return "Documents";     // CSIDL_PERSONAL      -> Electronic Arts\Dead Space\ds_slot_*.deadspacesaved
    case 0x1A: return "AppData";       // CSIDL_APPDATA
    case 0x1C: return "LocalAppData";  // CSIDL_LOCAL_APPDATA -> Electronic Arts\Dead Space\settings.txt, controls.rmp
    default:   return nullptr;
    }
}

static bool BuildProfilePath(int csidl, const char* subDir, char* out)
{
    const char* folder = ProfileFolderFor(csidl);
    if (!folder || !g_profileDir[0]) return false;
    char path[MAX_PATH];
    if (snprintf(path, sizeof path, "%s\\%s\\%s%s%s", g_gameDir, g_profileDir, folder,
                 subDir && *subDir ? "\\" : "", subDir ? subDir : "") >= (int)sizeof path)
        return false;
    for (char* p = path + 3; *p; ++p)
        if (*p == '\\') { *p = 0; CreateDirectoryA(path, nullptr); *p = '\\'; }
    CreateDirectoryA(path, nullptr);
    strcpy(out, path);
    return true;
}

typedef HRESULT (WINAPI* SHGetA_Fn)(HWND, int, HANDLE, DWORD, LPCSTR, LPSTR);
typedef HRESULT (WINAPI* SHGetW_Fn)(HWND, int, HANDLE, DWORD, LPCWSTR, LPWSTR);
static SHGetA_Fn g_origSHGetA;
static SHGetW_Fn g_origSHGetW;

static HRESULT WINAPI SHGetADetour(HWND hwnd, int csidl, HANDLE token, DWORD flags, LPCSTR subDir, LPSTR path)
{
    if (path && BuildProfilePath(csidl, subDir, path)) {
        Log("Profile redirect (A): csidl 0x%X '%s' -> %s", csidl, subDir ? subDir : "", path);
        return S_OK;
    }
    return g_origSHGetA(hwnd, csidl, token, flags, subDir, path);
}

static HRESULT WINAPI SHGetWDetour(HWND hwnd, int csidl, HANDLE token, DWORD flags, LPCWSTR subDir, LPWSTR path)
{
    char subA[MAX_PATH] = "", outA[MAX_PATH];
    if (subDir) WideCharToMultiByte(CP_ACP, 0, subDir, -1, subA, MAX_PATH, nullptr, nullptr);
    if (path && BuildProfilePath(csidl, subA, outA)) {
        MultiByteToWideChar(CP_ACP, 0, outA, -1, path, MAX_PATH);
        Log("Profile redirect (W): csidl 0x%X '%s' -> %s", csidl, subA, outA);
        return S_OK;
    }
    return g_origSHGetW(hwnd, csidl, token, flags, subDir, path);
}

// ---- Build check ---------------------------------------------------------------------------------
static bool BytesAt(uintptr_t addr, const void* expect, size_t n)
{
    __try { return memcmp(reinterpret_cast<const void*>(addr), expect, n) == 0; }
    __except (EXCEPTION_EXECUTE_HANDLER) { return false; }
}

static bool CheckBuild()
{
    static const uint8_t parserPrologue[] = { 0x55, 0x8B, 0xEC, 0x83, 0xE4, 0xF8, 0x81, 0xEC, 0xC4, 0x01, 0x00, 0x00 };
    static const uint8_t openPrologue[]   = { 0x55, 0x8B, 0xEC, 0x83, 0xE4, 0xF8, 0x81, 0xEC, 0x2C, 0x01, 0x00, 0x00 };
    struct { uintptr_t a; const void* b; size_t n; const char* what; } checks[] = {
        { ADDR_STR_STORE_EXT, ".store", 7, "\".store\" string" },
        { ADDR_STR_PDLC_WAIT, "pdlc_wait", 10, "\"pdlc_wait\" string" },
        { ADDR_STORE_PARSER, parserPrologue, sizeof parserPrologue, ".store parser prologue" },
        { ADDR_STREAM_OPEN, openPrologue, sizeof openPrologue, "stream open prologue" },
    };
    bool ok = true;
    for (auto& c : checks)
        if (!BytesAt(c.a, c.b, c.n)) { Log("BUILD MISMATCH at %08X (%s)", (unsigned)c.a, c.what); ok = false; }
    return ok;
}

// ---- Weapon remove fix ---------------------------------------------------------------------------
// FUN_00507510 removes a weapon from the list of loaded weapon streams (store sell/swap). It looks up
// the weapon's stream handle with FUN_006CA770 (call at 0x507575) and only unloads + removes the entry
// if that equals the handle stored in the entry ([EDI+0x1C]). For DLC weapons the lookup misses (their
// streams are opened through the DLC path), so the entry is never removed and the list overflows after a
// few trades. Fix (same idea as the other console-DLC loader): after the lookup, if the entry has a
// stored handle, use it. Each correction is logged.
static const uintptr_t ADDR_WEAPON_LOOKUP_CALL = 0x00507575;   // E8 F6 31 1C 00  call 0x006CA770
static const uintptr_t ADDR_STREAM_LOOKUP      = 0x006CA770;
static uintptr_t g_streamLookup = ADDR_STREAM_LOOKUP;
static int g_weaponFixCount = 0;

static int __stdcall WeaponLookupResult(int found, int stored)
{
    if (stored > 0 && found != stored) {
        if (g_weaponFixCount < 50) Log("Weapon remove fix: stream lookup gave %08X, using the weapon's stored handle %08X", found, stored);
        g_weaponFixCount++;
        return stored;
    }
    return found;
}

// Replaces the call: same arguments (ECX, EDX, one stack argument), callee pops it (RET 4).
static __declspec(naked) void WeaponLookupStub()
{
    __asm {
        push dword ptr [esp + 4]
        call dword ptr [g_streamLookup]
        push dword ptr [edi + 0x1C]
        push eax
        call WeaponLookupResult
        ret 4
    }
}

static void InstallWeaponRemoveFix()
{
    static const uint8_t expect[] = { 0xE8, 0xF6, 0x31, 0x1C, 0x00 };
    uint8_t* site = reinterpret_cast<uint8_t*>(ADDR_WEAPON_LOOKUP_CALL);
    if (!BytesAt(ADDR_WEAPON_LOOKUP_CALL, expect, sizeof expect)) { Log("weapon remove fix: call site not as expected, not installed"); return; }
    DWORD old;
    if (!VirtualProtect(site, 5, PAGE_EXECUTE_READWRITE, &old)) { Log("weapon remove fix: VirtualProtect failed"); return; }
    int32_t rel = static_cast<int32_t>(reinterpret_cast<uintptr_t>(&WeaponLookupStub) - (ADDR_WEAPON_LOOKUP_CALL + 5));
    memcpy(site + 1, &rel, 4);
    VirtualProtect(site, 5, old, &old);
    FlushInstructionCache(GetCurrentProcess(), site, 5);
    Log("weapon remove fix installed at %08X", (unsigned)ADDR_WEAPON_LOOKUP_CALL);
}

// ---- DLC injection -------------------------------------------------------------------------------
typedef void (__thiscall* StoreParserFn)(void* handler, void* entry, char* text, uint32_t len);

struct DlcPack {
    std::string name;      // folder name under dlc\  (e.g. "biggunpack")
    std::string storeFile; // full path of the .store
};

static std::vector<DlcPack> FindPacks()
{
    std::vector<DlcPack> packs;
    char pattern[MAX_PATH];
    snprintf(pattern, sizeof pattern, "%s\\dlc\\*", g_gameDir);
    WIN32_FIND_DATAA fd;
    HANDLE h = FindFirstFileA(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) { Log("No dlc folder (%s)", pattern); return packs; }
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) || fd.cFileName[0] == '.') continue;
        char storePat[MAX_PATH];
        snprintf(storePat, sizeof storePat, "%s\\dlc\\%s\\*.store", g_gameDir, fd.cFileName);
        WIN32_FIND_DATAA sd;
        HANDLE hs = FindFirstFileA(storePat, &sd);
        if (hs == INVALID_HANDLE_VALUE) { Log("dlc\\%s has no .store file - skipped", fd.cFileName); continue; }
        do {
            DlcPack p;
            p.name = fd.cFileName;
            p.storeFile = std::string(g_gameDir) + "\\dlc\\" + fd.cFileName + "\\" + sd.cFileName;
            packs.push_back(p);
        } while (FindNextFileA(hs, &sd));
        FindClose(hs);
    } while (FindNextFileA(h, &fd));
    FindClose(h);
    return packs;
}

static bool ReadText(const std::string& path, std::vector<char>& out)
{
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    out.assign(n + 1, 0);
    size_t got = fread(out.data(), 1, n, f);
    fclose(f);
    out.resize(got + 1);
    out[got] = 0;
    return true;
}

// SEH can't live in a function with C++ objects that need unwinding, so the guarded call is on its own.
static DWORD CallParser(StoreParserFn parser, void* handler, void* entry, char* text, uint32_t len)
{
    __try { parser(handler, entry, text, len); return 0; }
    __except (EXCEPTION_EXECUTE_HANDLER) { return GetExceptionCode(); }
}

static int ParseStore(const DlcPack& pack, StoreParserFn parser, void* handler)
{
    std::vector<char> text;
    if (!ReadText(pack.storeFile, text)) { Log("  cannot read %s", pack.storeFile.c_str()); return 0; }
    Log("  %s (%u bytes): %.*s", pack.storeFile.c_str(), (unsigned)(text.size() - 1), 200, text.data());

    // The stream manager keeps a pointer to the entry, so it must outlive this call.
    uint8_t* entry = static_cast<uint8_t*>(VirtualAlloc(nullptr, 0x1000, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE));
    if (!entry) return 0;
    char root[0x80];
    snprintf(root, sizeof root, "dlc\\%s\\", pack.name.c_str());           // relative to the game folder,
    strncpy(reinterpret_cast<char*>(entry + ENTRY_ROOT), root, 0x7F);      // like frontend\frontend.str
    const char* fileName = strrchr(pack.storeFile.c_str(), '\\') + 1;
    snprintf(reinterpret_cast<char*>(entry + ENTRY_FILE), 0x80, "%s%s", root, fileName);
    *reinterpret_cast<uint32_t*>(entry + ENTRY_SIZE) = (uint32_t)(text.size() - 1);

    // strtok in the parser writes into the buffer; give it a heap copy that also stays alive.
    char* buf = static_cast<char*>(HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, text.size()));
    memcpy(buf, text.data(), text.size());

    if (DWORD ex = CallParser(parser, handler, entry, buf, (uint32_t)(text.size() - 1))) {
        Log("  EXCEPTION %08X in the .store parser for %s", ex, pack.name.c_str());
        return 0;
    }
    Log("  parsed, root \"%s\"", root);
    return 1;
}

static void InjectDlc()
{
    void* dlcSystem = *PTR_DLC_SYSTEM;
    void* handler = dlcSystem ? *reinterpret_cast<void**>(static_cast<uint8_t*>(dlcSystem) + 0x10) : nullptr;
    Log("Injecting DLC: content mgr %p, DLC system %p, .store handler %p, stream mgr %p",
        *PTR_CONTENT_MGR, dlcSystem, handler, *PTR_DLC_STREAM_MGR);
    auto packs = FindPacks();
    Log("Found %u .store file(s)", (unsigned)packs.size());
    int ok = 0;
    for (auto& p : packs) ok += ParseStore(p, reinterpret_cast<StoreParserFn>(ADDR_STORE_PARSER), handler);
    Log("DLC injection done: %d of %u parsed", ok, (unsigned)packs.size());
}

// ---- Hooks ---------------------------------------------------------------------------------------
// No stack arguments: the caller (0x71720B) does MOV ECX,ESI / CALL [vtbl+4] with nothing pushed.
typedef void (__fastcall* ContentUpdateFn)(void* self, void* edx);
static ContentUpdateFn g_origContentUpdate;
static bool g_injected;
static unsigned g_updateCalls;

static void __fastcall ContentUpdateDetour(void* self, void* edx)
{
    ++g_updateCalls;
    if (!g_injected && g_dlcEnabled && *PTR_DLC_SYSTEM && *PTR_DLC_STREAM_MGR) {
        g_injected = true;
        Log("Content update #%u: DLC system is up", g_updateCalls);
        InjectDlc();
    }
    g_origContentUpdate(self, edx);
}

// 0x6C8AD0 open(path, a2, flags, prefixPtr, prefixStr, viaContent). All args passed through as raw
// 32-bit values (no float types, so nothing is reinterpreted on the way through).
// viaContent=1 is what the DLC stream manager passes: 0x6C9690 then calls 0x6C7AC0, which keeps the
// path relative and flags the request (+0x4C=1) to be read through the platform content device.
// On PC that device is a stub, so the read never happens and level loading waits forever.
// For dlc\ streams we pass 0 instead, so they take the normal path (0x6C7E40: <game>\ prefix,
// plain file read), the same path base streams like text_assets\text\D8CBB618 take.
typedef uint32_t (__fastcall* StreamOpenFn)(void* self, void* edx, const char* path, uint32_t a2, uint32_t flags,
                                             uint32_t prefixPtr, uint32_t prefixStr, uint32_t viaContent);
static StreamOpenFn g_origStreamOpen;

static uint32_t __fastcall StreamOpenDetour(void* self, void* edx, const char* path, uint32_t a2, uint32_t flags,
                                            uint32_t prefixPtr, uint32_t prefixStr, uint32_t viaContent)
{
    bool dlc = MentionsDlc(path);
    uint32_t via = (dlc && (viaContent & 0xFF)) ? 0 : viaContent;
    uint32_t r = g_origStreamOpen(self, edx, path, a2, flags, prefixPtr, prefixStr, via);
    if (g_logAllStreams || dlc)
        Log("StreamOpen(\"%s\", %u, flags %u, %08X, %08X, via %u%s) -> %u", path ? path : "(null)", a2, flags,
            prefixPtr, prefixStr, viaContent, via != viaContent ? " -> 0, read from disk" : "", r);
    return r;
}

// ---- SimGroup class loaders (diagnostics) --------------------------------------------------------
// DLCItemStoreList::Load (0x50F8A0) and DLCWeaponStreamAttributes::Load (0x506AE0), both
// __thiscall(object, entry), RET 4. Logging what they read proves a converted SimGroup parses.
static const uintptr_t ADDR_STORELIST_LOAD = 0x0050F8A0;
static const uintptr_t ADDR_WEAPONATTR_LOAD = 0x00506AE0;
static uint32_t* const WEAPON_DLC_TABLE = (uint32_t*)0x00F447A8;   // 0x96 entries x 8 u32, filled by 0x506AE0
typedef void (__fastcall* ClassLoadFn)(void* self, void* edx, void* entry);
static ClassLoadFn g_origStoreListLoad, g_origWeaponAttrLoad;

static void __fastcall StoreListLoadDetour(void* self, void* edx, void* entry)
{
    g_origStoreListLoad(self, edx, entry);
    const uint32_t* o = static_cast<const uint32_t*>(self);
    Log("DLCItemStoreList loaded: object %p, store item %d, GUID %08X-%08X-%08X-%08X",
        self, (int)o[0x4C / 4], o[0x50 / 4], o[0x54 / 4], o[0x58 / 4], o[0x5C / 4]);
}

static void __fastcall WeaponAttrLoadDetour(void* self, void* edx, void* entry)
{
    static uint32_t before[0x96 * 8];
    memcpy(before, WEAPON_DLC_TABLE, sizeof before);
    g_origWeaponAttrLoad(self, edx, entry);
    int changed = 0;
    for (int i = 0; i < 0x96; i++) {
        if (memcmp(before + i * 8, WEAPON_DLC_TABLE + i * 8, 32) == 0) continue;
        const uint32_t* t = WEAPON_DLC_TABLE + i * 8;
        Log("DLCWeaponStreamAttributes loaded: slot %d = %08X %08X %08X %08X | %08X %08X %08X %08X",
            i, t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7]);
        changed++;
    }
    if (!changed) Log("DLCWeaponStreamAttributes loaded: object %p (no table slot changed)", self);
}

static bool MentionsDlc(const char* s)
{
    if (!s) return false;
    // A "dlc" path component ("dlc\..." or "...\dlc\..."), or a pdlc_ stream name. Plain substring
    // matching is no good: the project folder itself is called "DLC Converter".
    for (const char* p = s; *p; ++p) {
        bool atStart = p == s || p[-1] == '\\' || p[-1] == '/';
        if (atStart && (p[0] | 0x20) == 'd' && (p[1] | 0x20) == 'l' && (p[2] | 0x20) == 'c' && (p[3] == '\\' || p[3] == '/'))
            return true;
        if (_strnicmp(p, "pdlc_", 5) == 0) return true;
    }
    return false;
}

// ---- Language override ([language] Locale) --------------------------------------------------------
// The exe reads its registry key by enumerating every value (RegEnumValueA, called from 0x41EC8F and
// 0x41ED2D) into its option table, which 0x615C20 then looks "Locale" up in. So the override rewrites the
// data of the value named Locale as the exe enumerates it.
typedef LSTATUS (WINAPI* RegEnumValueAFn)(HKEY, DWORD, LPSTR, LPDWORD, LPDWORD, LPDWORD, LPBYTE, LPDWORD);
static RegEnumValueAFn g_origRegEnumValueA;
static uintptr_t g_exeBase, g_exeEnd;

static LSTATUS WINAPI RegEnumValueADetour(HKEY key, DWORD index, LPSTR name, LPDWORD nameLen, LPDWORD reserved,
                                          LPDWORD type, LPBYTE data, LPDWORD cb)
{
    uintptr_t from = reinterpret_cast<uintptr_t>(_ReturnAddress());
    DWORD cbIn = cb ? *cb : 0;
    LSTATUS r = g_origRegEnumValueA(key, index, name, nameLen, reserved, type, data, cb);
    if ((r != ERROR_SUCCESS && r != ERROR_MORE_DATA) || !g_locale[0] || !name || _stricmp(name, "Locale") != 0 ||
        from < g_exeBase || from >= g_exeEnd)
        return r;
    DWORD need = (DWORD)strlen(g_locale) + 1;
    if (type) *type = REG_SZ;
    if (data && cb && cbIn >= need) {
        memcpy(data, g_locale, need);
        *cb = need;
        Log("Locale registry value -> %s (from [language] Locale)", g_locale);
        return ERROR_SUCCESS;
    }
    if (cb) *cb = need > *cb ? need : *cb;
    return data ? ERROR_MORE_DATA : ERROR_SUCCESS;
}

// text_assets\text_assets_global.str/.toc -> text_assets\<lang>\..., if that file exists.
static bool RedirectTextAsset(const char* in, char* out, size_t outLen)
{
    if (!g_textDir[0] || !in) return false;
    static const char* const names[] = { "text_assets_global.str", "text_assets_global.toc" };
    size_t n = strlen(in);
    for (const char* f : names) {
        size_t fl = strlen(f), dl = strlen("text_assets") + 1 + fl;
        if (n < dl || _stricmp(in + n - fl, f) != 0) continue;
        const char* dir = in + n - dl;
        if (_strnicmp(dir, "text_assets", 11) != 0 || (dir[11] != '\\' && dir[11] != '/')) continue;
        if (dir > in && dir[-1] != '\\' && dir[-1] != '/' && dir[-1] != ':') continue;
        if (snprintf(out, outLen, "%.*s%s\\%s", (int)(n - fl), in, g_textDir, f) >= (int)outLen) return false;
        return GetFileAttributesA(out) != INVALID_FILE_ATTRIBUTES;
    }
    return false;
}

typedef HANDLE (WINAPI* CreateFileAFn)(LPCSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE);
static CreateFileAFn g_origCreateFileA;
static HANDLE WINAPI CreateFileADetour(LPCSTR name, DWORD acc, DWORD share, LPSECURITY_ATTRIBUTES sa, DWORD disp, DWORD flags, HANDLE tmpl)
{
    char alt[MAX_PATH * 2];
    if (RedirectTextAsset(name, alt, sizeof alt)) { Log("Text redirect: %s -> %s", name, alt); name = alt; }
    HANDLE h = g_origCreateFileA(name, acc, share, sa, disp, flags, tmpl);
    if (g_logFiles && (g_logFilesAll || MentionsDlc(name)))
        Log("CreateFileA(\"%s\") -> %s (err %lu)", name, h == INVALID_HANDLE_VALUE ? "FAILED" : "ok",
            h == INVALID_HANDLE_VALUE ? GetLastError() : 0);
    return h;
}

static bool MentionsDlcW(const wchar_t* s)
{
    if (!s) return false;
    char a[MAX_PATH * 2];
    WideCharToMultiByte(CP_ACP, 0, s, -1, a, sizeof a, nullptr, nullptr);
    return MentionsDlc(a);
}

typedef HANDLE (WINAPI* CreateFileWFn)(LPCWSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE);
static CreateFileWFn g_origCreateFileW;
static HANDLE WINAPI CreateFileWDetour(LPCWSTR name, DWORD acc, DWORD share, LPSECURITY_ATTRIBUTES sa, DWORD disp, DWORD flags, HANDLE tmpl)
{
    char a[MAX_PATH * 2], alt[MAX_PATH * 2];
    wchar_t altW[MAX_PATH * 2];
    if (g_textDir[0] && name && WideCharToMultiByte(CP_ACP, 0, name, -1, a, sizeof a, nullptr, nullptr) &&
        RedirectTextAsset(a, alt, sizeof alt) && MultiByteToWideChar(CP_ACP, 0, alt, -1, altW, MAX_PATH * 2)) {
        Log("Text redirect: %s -> %s", a, alt);
        name = altW;
    }
    HANDLE h = g_origCreateFileW(name, acc, share, sa, disp, flags, tmpl);
    if (g_logFiles && (g_logFilesAll || MentionsDlcW(name)))
        Log("CreateFileW(\"%ls\") -> %s (err %lu)", name, h == INVALID_HANDLE_VALUE ? "FAILED" : "ok",
            h == INVALID_HANDLE_VALUE ? GetLastError() : 0);
    return h;
}

typedef DWORD (WINAPI* GetFileAttributesAFn)(LPCSTR);
static GetFileAttributesAFn g_origGetFileAttributesA;
static DWORD WINAPI GetFileAttributesADetour(LPCSTR name)
{
    DWORD r = g_origGetFileAttributesA(name);
    if (g_logFiles && MentionsDlc(name)) Log("GetFileAttributesA(\"%s\") -> %08lX", name, r);
    return r;
}

typedef BOOL (WINAPI* GetFileAttributesExAFn)(LPCSTR, GET_FILEEX_INFO_LEVELS, LPVOID);
static GetFileAttributesExAFn g_origGetFileAttributesExA;
static BOOL WINAPI GetFileAttributesExADetour(LPCSTR name, GET_FILEEX_INFO_LEVELS lvl, LPVOID info)
{
    BOOL r = g_origGetFileAttributesExA(name, lvl, info);
    if (g_logFiles && MentionsDlc(name)) Log("GetFileAttributesExA(\"%s\") -> %d", name, r);
    return r;
}

typedef HANDLE (WINAPI* FindFirstFileAFn)(LPCSTR, LPWIN32_FIND_DATAA);
static FindFirstFileAFn g_origFindFirstFileA;
static HANDLE WINAPI FindFirstFileADetour(LPCSTR name, LPWIN32_FIND_DATAA fd)
{
    HANDLE h = g_origFindFirstFileA(name, fd);
    // Our own scan of dlc\ goes through here too; only log the game's calls (made after injection).
    if (g_logFiles && g_injected && name && (MentionsDlc(name) || strstr(name, ":\\")))
        Log("FindFirstFileA(\"%s\") -> %s", name, h == INVALID_HANDLE_VALUE ? "none" : fd->cFileName);
    return h;
}

static bool Hook(uintptr_t target, void* detour, void** orig, const char* what)
{
    MH_STATUS s = MH_CreateHook(reinterpret_cast<void*>(target), detour, orig);
    if (s == MH_OK) s = MH_EnableHook(reinterpret_cast<void*>(target));
    Log("hook %-20s %08X: %s", what, (unsigned)target, s == MH_OK ? "ok" : MH_StatusToString(s));
    return s == MH_OK;
}

static void Init()
{
    LoadConfig();
    Log("dlcloader starting in %s", g_gameDir);
    // The game opens files by full path in MAX_PATH buffers; its longest file is 150 characters below the
    // game folder, so a longer folder path overflows them and level loads crash (with or without DLC).
    if (strlen(g_gameDir) > 108)
        Log("WARNING: the game folder path is %u characters. Over 108, some of the game's own files pass the "
            "260-character Windows path limit and the game crashes when loading levels. Move the game to a shorter path.",
            (unsigned)strlen(g_gameDir));
    if (!CheckBuild()) { Log("Unsupported exe build - DLC loader disabled"); return; }
    if (MH_Initialize() != MH_OK) { Log("MH_Initialize failed"); return; }
    if (g_profileDir[0]) {
        HMODULE shell = GetModuleHandleA("shell32.dll");   // the exe imports it, so it is normally mapped already
        if (!shell) shell = LoadLibraryA("shell32.dll");
        Hook(reinterpret_cast<uintptr_t>(GetProcAddress(shell, "SHGetFolderPathAndSubDirA")), reinterpret_cast<void*>(&SHGetADetour),
             reinterpret_cast<void**>(&g_origSHGetA), "SHGetFolderPath..A");
        Hook(reinterpret_cast<uintptr_t>(GetProcAddress(shell, "SHGetFolderPathAndSubDirW")), reinterpret_cast<void*>(&SHGetWDetour),
             reinterpret_cast<void**>(&g_origSHGetW), "SHGetFolderPath..W");
        Log("Settings and saves go to %s\\%s", g_gameDir, g_profileDir);
    }
    Hook(ADDR_CONTENT_UPDATE, reinterpret_cast<void*>(&ContentUpdateDetour), reinterpret_cast<void**>(&g_origContentUpdate), "content update");
    Hook(ADDR_STORELIST_LOAD, reinterpret_cast<void*>(&StoreListLoadDetour), reinterpret_cast<void**>(&g_origStoreListLoad), "DLCItemStoreList");
    Hook(ADDR_WEAPONATTR_LOAD, reinterpret_cast<void*>(&WeaponAttrLoadDetour), reinterpret_cast<void**>(&g_origWeaponAttrLoad), "DLCWeaponStreamAttr");
    Hook(ADDR_STREAM_OPEN, reinterpret_cast<void*>(&StreamOpenDetour), reinterpret_cast<void**>(&g_origStreamOpen), "stream open");
    InstallWeaponRemoveFix();
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    if (g_locale[0]) {
        HMODULE exe = GetModuleHandleA(nullptr);
        auto nt = reinterpret_cast<IMAGE_NT_HEADERS*>(reinterpret_cast<BYTE*>(exe) + reinterpret_cast<IMAGE_DOS_HEADER*>(exe)->e_lfanew);
        g_exeBase = reinterpret_cast<uintptr_t>(exe);
        g_exeEnd = g_exeBase + nt->OptionalHeader.SizeOfImage;
        HMODULE adv = GetModuleHandleA("advapi32.dll");
        if (!adv) adv = LoadLibraryA("advapi32.dll");
        Hook(reinterpret_cast<uintptr_t>(GetProcAddress(adv, "RegEnumValueA")), reinterpret_cast<void*>(&RegEnumValueADetour),
             reinterpret_cast<void**>(&g_origRegEnumValueA), "RegEnumValueA");
        Log("Language override: Locale %s, text folder text_assets\\%s", g_locale, g_textDir[0] ? g_textDir : "(none)");
    }
    if (!g_logFiles && !g_textDir[0]) return;   // the file API hooks below only log, except the text redirect
    Hook(reinterpret_cast<uintptr_t>(GetProcAddress(k32, "CreateFileA")), reinterpret_cast<void*>(&CreateFileADetour),
         reinterpret_cast<void**>(&g_origCreateFileA), "CreateFileA");
    Hook(reinterpret_cast<uintptr_t>(GetProcAddress(k32, "CreateFileW")), reinterpret_cast<void*>(&CreateFileWDetour),
         reinterpret_cast<void**>(&g_origCreateFileW), "CreateFileW");
    if (!g_logFiles) return;
    Hook(reinterpret_cast<uintptr_t>(GetProcAddress(k32, "FindFirstFileA")), reinterpret_cast<void*>(&FindFirstFileADetour),
         reinterpret_cast<void**>(&g_origFindFirstFileA), "FindFirstFileA");
    Hook(reinterpret_cast<uintptr_t>(GetProcAddress(k32, "GetFileAttributesA")), reinterpret_cast<void*>(&GetFileAttributesADetour),
         reinterpret_cast<void**>(&g_origGetFileAttributesA), "GetFileAttributesA");
    Hook(reinterpret_cast<uintptr_t>(GetProcAddress(k32, "GetFileAttributesExA")), reinterpret_cast<void*>(&GetFileAttributesExADetour),
         reinterpret_cast<void**>(&g_origGetFileAttributesExA), "GetFileAttributesExA");
}

// ---- dinput8 proxy -------------------------------------------------------------------------------
static HMODULE g_realDinput;

static FARPROC RealProc(const char* name)
{
    if (!g_realDinput) {
        char path[MAX_PATH];
        snprintf(path, sizeof path, "%s\\dinput8_next.dll", g_gameDir);
        if (GetFileAttributesA(path) != INVALID_FILE_ATTRIBUTES) g_realDinput = LoadLibraryA(path);
        if (!g_realDinput) {
            GetSystemDirectoryA(path, MAX_PATH);
            strcat(path, "\\dinput8.dll");
            g_realDinput = LoadLibraryA(path);
        }
        Log("dinput8 forwarded to %s", path);
    }
    return g_realDinput ? GetProcAddress(g_realDinput, name) : nullptr;
}

extern "C" HRESULT WINAPI Proxy_DirectInput8Create(HINSTANCE inst, DWORD ver, REFIID riid, LPVOID* out, LPUNKNOWN outer)
{
    typedef HRESULT (WINAPI* Fn)(HINSTANCE, DWORD, REFIID, LPVOID*, LPUNKNOWN);
    Fn f = reinterpret_cast<Fn>(RealProc("DirectInput8Create"));
    return f ? f(inst, ver, riid, out, outer) : E_FAIL;
}

extern "C" HRESULT WINAPI Proxy_DllCanUnloadNow()
{
    typedef HRESULT (WINAPI* Fn)();
    Fn f = reinterpret_cast<Fn>(RealProc("DllCanUnloadNow"));
    return f ? f() : S_FALSE;
}

extern "C" HRESULT WINAPI Proxy_DllGetClassObject(REFCLSID clsid, REFIID riid, LPVOID* out)
{
    typedef HRESULT (WINAPI* Fn)(REFCLSID, REFIID, LPVOID*);
    Fn f = reinterpret_cast<Fn>(RealProc("DllGetClassObject"));
    return f ? f(clsid, riid, out) : E_FAIL;
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(inst);
        GetModuleFileNameA(nullptr, g_gameDir, MAX_PATH);
        if (char* s = strrchr(g_gameDir, '\\')) *s = 0;
        InitializeCriticalSection(&g_logLock);
        char logPath[MAX_PATH];
        snprintf(logPath, sizeof logPath, "%s\\dlcloader.log", g_gameDir);
        g_log = _fsopen(logPath, "w", _SH_DENYWR);
        // The hooks only patch code; nothing here waits on another thread, so this is safe under the loader lock.
        Init();
    }
    return TRUE;
}

