// dinput8.dll: loads SkyrimAYN.dll into Skyrim without SKSE, then hands every DirectInput call to the
// real dinput8.dll. Skyrim imports DINPUT8.dll, and Wine uses a copy in the game folder first
// (WINEDLLOVERRIDES dinput8=n,b). When SKSE is running, SKSE loads the plugin instead and this only forwards.
#include <windows.h>
#include <string>

static HMODULE g_real = nullptr;
static FARPROC Real(const char* name) {
    if (!g_real) {
        wchar_t sys[MAX_PATH];
        GetSystemDirectoryW(sys, MAX_PATH);
        g_real = LoadLibraryW((std::wstring(sys) + L"\\dinput8.dll").c_str());
    }
    return g_real ? GetProcAddress(g_real, name) : nullptr;
}

static volatile LONG g_once = 0;
static void StartCompanion() {
    if (InterlockedExchange(&g_once, 1)) return;
    if (GetModuleHandleW(L"skse64_1_7_104.dll")) return;   // SKSE is running and loads the plugin itself
    wchar_t exe[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(nullptr, exe, MAX_PATH * 2);
    std::wstring dir(exe, n);
    dir = dir.substr(0, dir.find_last_of(L"\\/"));
    HMODULE m = LoadLibraryW((dir + L"\\Data\\SKSE\\Plugins\\SkyrimAYN.dll").c_str());
    if (!m) return;
    if (auto start = reinterpret_cast<void (*)()>(GetProcAddress(m, "SkyrimAYN_Standalone"))) start();
}

// Fallback if the game never calls DirectInput8Create (it does, while creating its input devices).
static DWORD WINAPI Later(void*) { Sleep(20000); StartCompanion(); return 0; }

// ---- Windows message boxes --------------------------------------------------------------------------
// On the Thor nothing can press a Windows message box: no mouse or touch reaches it, so the game sits at
// 0 FPS forever. SKSE shows one when a plugin fails to load ("A DLL plugin has failed to load correctly...
// Exit game?") or the Address Library is old. Answer those so the game keeps going without the broken
// plugin; SKSE has already written the reason to skse64.log, which the app reads and shows the player.
static int Answer(UINT type) {
    switch (type & MB_TYPEMASK) {
        case MB_YESNO: case MB_YESNOCANCEL: return IDNO;       // "Exit game?" / "Visit the webpage?" -> keep playing
        case MB_RETRYCANCEL: return IDCANCEL;
        case MB_CANCELTRYCONTINUE: return IDCONTINUE;
        case MB_ABORTRETRYIGNORE: return IDIGNORE;
        default: return IDOK;
    }
}
static int WINAPI QuietBoxA(HWND, LPCSTR, LPCSTR, UINT type) { return Answer(type); }
static int WINAPI QuietBoxW(HWND, LPCWSTR, LPCWSTR, UINT type) { return Answer(type); }

// Points one entry of a module's import table at `to`; returns what was there (nullptr if not imported).
static void* PatchImport(HMODULE mod, const char* dll, const char* fn, void* to) {
    if (!mod) return nullptr;
    auto base = reinterpret_cast<BYTE*>(mod);
    auto nt = reinterpret_cast<IMAGE_NT_HEADERS*>(base + reinterpret_cast<IMAGE_DOS_HEADER*>(base)->e_lfanew);
    auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (!dir.VirtualAddress) return nullptr;
    for (auto imp = reinterpret_cast<IMAGE_IMPORT_DESCRIPTOR*>(base + dir.VirtualAddress); imp->Name; ++imp) {
        if (_stricmp(reinterpret_cast<char*>(base + imp->Name), dll)) continue;
        auto names = reinterpret_cast<IMAGE_THUNK_DATA*>(base + (imp->OriginalFirstThunk ? imp->OriginalFirstThunk : imp->FirstThunk));
        auto slots = reinterpret_cast<IMAGE_THUNK_DATA*>(base + imp->FirstThunk);
        for (; names->u1.AddressOfData; ++names, ++slots) {
            if (IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) continue;
            auto byName = reinterpret_cast<IMAGE_IMPORT_BY_NAME*>(base + names->u1.AddressOfData);
            if (strcmp(reinterpret_cast<char*>(byName->Name), fn)) continue;
            void* was = reinterpret_cast<void*>(slots->u1.Function);
            DWORD old;
            if (!VirtualProtect(&slots->u1.Function, sizeof(void*), PAGE_READWRITE, &old)) return nullptr;
            slots->u1.Function = reinterpret_cast<ULONG_PTR>(to);
            VirtualProtect(&slots->u1.Function, sizeof(void*), old, &old);
            return was;
        }
    }
    return nullptr;
}

static void QuietMessageBoxes(HMODULE mod) {
    PatchImport(mod, "user32.dll", "MessageBoxA", reinterpret_cast<void*>(QuietBoxA));
    PatchImport(mod, "user32.dll", "MessageBoxW", reinterpret_cast<void*>(QuietBoxW));
}

// The SKSE loader injects skse64_1_7_104.dll after Windows has loaded the game's imports (this DLL among
// them), so SKSE isn't there yet when DllMain runs. SKSE then wraps the game's _initterm_e import and calls
// the previous one, ours, when its wrapper is done: that's after SKSE is loaded and before it loads plugins
// and reports their errors.
typedef int (__cdecl* InitTermE)(int (__cdecl**)(void), int (__cdecl**)(void));
static InitTermE g_initTermE = nullptr;
static int __cdecl HookedInitTermE(int (__cdecl** a)(void), int (__cdecl** b)(void)) {
    QuietMessageBoxes(GetModuleHandleW(L"skse64_1_7_104.dll"));
    return g_initTermE(a, b);
}

extern "C" {
HRESULT WINAPI Proxy_DirectInput8Create(HINSTANCE inst, DWORD ver, REFIID riid, LPVOID* out, LPUNKNOWN outer) {
    StartCompanion();
    auto f = reinterpret_cast<HRESULT(WINAPI*)(HINSTANCE, DWORD, REFIID, LPVOID*, LPUNKNOWN)>(Real("DirectInput8Create"));
    return f ? f(inst, ver, riid, out, outer) : E_FAIL;
}
HRESULT WINAPI Proxy_DllCanUnloadNow() { return S_FALSE; }
HRESULT WINAPI Proxy_DllGetClassObject(REFCLSID c, REFIID i, LPVOID* o) {
    auto f = reinterpret_cast<HRESULT(WINAPI*)(REFCLSID, REFIID, LPVOID*)>(Real("DllGetClassObject"));
    return f ? f(c, i, o) : CLASS_E_CLASSNOTAVAILABLE;
}
HRESULT WINAPI Proxy_DllRegisterServer() { auto f = reinterpret_cast<HRESULT(WINAPI*)()>(Real("DllRegisterServer")); return f ? f() : E_FAIL; }
HRESULT WINAPI Proxy_DllUnregisterServer() { auto f = reinterpret_cast<HRESULT(WINAPI*)()>(Real("DllUnregisterServer")); return f ? f() : E_FAIL; }
LPCVOID WINAPI Proxy_GetdfDIJoystick() { auto f = reinterpret_cast<LPCVOID(WINAPI*)()>(Real("GetdfDIJoystick")); return f ? f() : nullptr; }
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(inst);
        g_initTermE = reinterpret_cast<InitTermE>(PatchImport(GetModuleHandleW(nullptr),
            "api-ms-win-crt-runtime-l1-1-0.dll", "_initterm_e", reinterpret_cast<void*>(HookedInitTermE)));
        if (HANDLE t = CreateThread(nullptr, 0, Later, nullptr, 0, nullptr)) CloseHandle(t);
    }
    return TRUE;
}
