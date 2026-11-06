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
        if (HANDLE t = CreateThread(nullptr, 0, Later, nullptr, 0, nullptr)) CloseHandle(t);
    }
    return TRUE;
}
