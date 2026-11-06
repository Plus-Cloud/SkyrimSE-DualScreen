// SkyrimAYN.dll: SKSE64 plugin that feeds the Skyrim AYN bottom-screen companion.
// v0.10: list of mods the game loaded.
// v0.11: mirror of the game HUD compass markers (CompassTargetDataA) at 386048.
// v0.9: item card details (damage/armor rating, weapon/armor kind, first effect).
// v0.8: commands on the UI queue, app-held pause, fast travel, active effects.
// v0.7: character name/race, skills, nearby undiscovered places, quest doors, HUD hiding.
// v0.6: quest journal (names, objectives with alias names filled in); file grows to 384 KiB.
// v0.5: interior awareness (quest targets and enemies inside the player's cell).
// v0.4: map markers (locations, quest targets, hostile and allied actors); file grows to 256 KiB.
// v0.3: heartbeat continues on menus/loading screens.
// v0.2: live player state, inventory, spells, favorites; equip/use/unequip and spell hotkeys.
// Layout: docs/shared-memory.md (v2). Built against SKSE 2.3.1 source for Skyrim SE 1.7.104.
//
// Threading: a background thread only *schedules* work. All game reads and actions run in
// a TaskDelegate on Skyrim's main thread (SKSE task interface), which is also the only writer
// of the shared file's plugin regions.

#include "skse64/PluginAPI.h"
#include "skse64/gamethreads.h"
#include "skse64_common/skse_version.h"
#include "skse64/GameReferences.h"
#include "skse64/GameForms.h"
#include "skse64/GameObjects.h"
#include "skse64/GameData.h"
#include "skse64/GameExtraData.h"
#include "skse64/GameFormComponents.h"
#include "skse64/GameRTTI.h"
#include "skse64/GameMenus.h"
#include "skse64/ScaleformMovie.h"
#include "skse64/ScaleformValue.h"
#include "skse64/Hooks_UI.h"

#include <windows.h>
#include <shlobj.h>
#include <atomic>
#include <cmath>
#include <string>
#include <unordered_map>
#include <vector>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>
#include <algorithm>
#include <cmath>
#include "skse64/GameAPI.h"

// ---------------------------------------------------------------- plugin info
extern "C" __declspec(dllexport) SKSEPluginVersionData SKSEPlugin_Version = {
    SKSEPluginVersionData::kVersion,
    19,                                 // plugin version 0.19
    "SkyrimAYN",
    "Skyrim AYN",
    "",
    0,                                  // versionIndependenceEx
    0,                                  // versionIndependence: built for one runtime
    { RUNTIME_VERSION_1_7_104, 0 },
    0,
};

// ---------------------------------------------------------------- layout (docs/shared-memory.md v2)
namespace L {
constexpr uint32_t kMagic = 0x4E594153, kTotal = 393216, kPage = 4096;
constexpr uint16_t kVersion = 4;
constexpr uint32_t kSeq = 12, kFrame = 16, kUptime = 24, kPid = 32, kRuntime = 36, kSkse = 40,
                   kFlags = 44, kTickHz = 48, kListSeqCopy = 52, kSeqEnd = 60;
constexpr uint32_t kAckSeq = 3072, kAckResult = 3076, kAckUptime = 3080;
constexpr uint32_t kCmd = 4096;
constexpr uint32_t kLists = 8192, kItems = 8256, kItemSize = 120, kMaxItems = 800;   // v0.9: 120-byte items with stats
constexpr uint32_t kSpells = 104448, kSpellSize = 80, kMaxSpells = 300;
constexpr uint32_t fMmap = 1, fFile = 2, fPlayer = 4, fLists = 8, fInGame = 16, fMarkers = 32, fQuests = 64;
// v0.18: the game is showing something (its main menu, a loading screen or the world): the app's loading
// screen stays up until then, so the player never sits on Skyrim's black start-up window.
constexpr uint32_t fShowing = 128;
enum Cmd : uint32_t { Ping = 1, EquipItem = 2, UnequipItem = 3, UseItem = 4, SetSpellHotkey = 5, SetHud = 6, FastTravel = 7, TrackQuest = 8, SaveGame = 9 };
constexpr uint32_t kPauseWant = 4120, kPauseBeat = 4124;   // app-held pause request (byte) and its heartbeat
constexpr uint32_t kEffects = 700, kEffectEntries = 704, kEffectSize = 48, kMaxEffects = 12;
}

// ---------------------------------------------------------------- globals
static PluginHandle          g_handle = kPluginHandle_Invalid;
static SKSETaskInterface*    g_task = nullptr;
static HMODULE               g_module = nullptr;
static FILE*                 g_log = nullptr;
static uint32_t              g_runtime = 0, g_skse = 0;
static std::wstring          g_path;
static uint32_t              g_tickHz = 30;
static HANDLE                g_file = INVALID_HANDLE_VALUE, g_mapping = nullptr;
static uint8_t*              g_view = nullptr;
static std::atomic<bool>     g_taskQueued{false};
static std::atomic<ULONGLONG> g_lastRun{0};   // when a TickTask last ran on the main thread
static CRITICAL_SECTION      g_page0Lock;     // page 0 is written by the main thread and the scheduler
static ULONGLONG             g_start = 0;

// ---------------------------------------------------------------- main-thread / UI-thread queues
// Under SKSE we use its task interface. Without SKSE (the app's own dinput8.dll loads us), we hook the
// same two game calls SKSE does (BSTaskPool::ProcessTasks, UIManager::ProcessCommands) and run our queues there.
class TaskDelegate; class UIDelegate_v1;
static bool                  g_standalone = false;
static std::atomic<bool>     g_started{false};
static CRITICAL_SECTION      g_qLock;
static std::vector<TaskDelegate*>  g_mainQ;
static std::vector<UIDelegate_v1*> g_uiQ;
static void QueueMain(TaskDelegate* t);
static void QueueUi(UIDelegate_v1* t);

// Main-thread state.
static uint32_t g_seq = 0, g_listSeq = 0, g_lastCmd = 0, g_ackSeq = 0, g_ackResult = 0;
static uint64_t g_frame = 0, g_ackUptime = 0;
static ULONGLONG g_lastListBuild = 0;
static bool g_listsDirty = true;

static void Log(const char* fmt, ...) {
    if (!g_log) return;
    SYSTEMTIME t; GetLocalTime(&t);
    fprintf(g_log, "[%02d:%02d:%02d.%03d] ", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds);
    va_list ap; va_start(ap, fmt); vfprintf(g_log, fmt, ap); va_end(ap);
    fputc('\n', g_log); fflush(g_log);
}

template <class T> static void Put(uint32_t off, T v) { *reinterpret_cast<volatile T*>(g_view + off) = v; }
template <class T> static T Get(uint32_t off) { return *reinterpret_cast<const volatile T*>(g_view + off); }
static void PutStr(uint32_t off, uint32_t len, const char* s) {
    size_t n = s ? strnlen(s, len - 1) : 0;
    memcpy(g_view + off, s ? s : "", n);
    memset(g_view + off + n, 0, len - n);
}

// ---------------------------------------------------------------- game helpers
static const char* NameOf(TESForm* form) {
    if (!form) return "";
    TESFullName* fn = DYNAMIC_CAST(form, TESForm, TESFullName);
    return (fn && fn->name.data) ? fn->name.data : "";
}

static UInt32 AV(const char* name) {
    static std::unordered_map<std::string, UInt32> cache;
    auto it = cache.find(name);
    if (it != cache.end()) return it->second;
    UInt32 id = LookupActorValueByName(name);
    cache[name] = id;
    return id;
}

static float GlobalValue(UInt32 formId) {
    TESForm* f = LookupFormByID(formId);
    return f ? *reinterpret_cast<float*>(reinterpret_cast<uint8_t*>(f) + 0x34) : 0.f;
}

static uint8_t CategoryOf(TESForm* f) {
    switch (f->formType) {
        case kFormType_Weapon: return 0;
        case kFormType_Armor: return 1;
        case kFormType_Potion: {
            AlchemyItem* a = DYNAMIC_CAST(f, TESForm, AlchemyItem);
            return (a && a->IsFood()) ? 3 : 2;
        }
        case kFormType_Ingredient: return 4;
        case kFormType_ScrollItem: return 5;
        case kFormType_Book: return 6;
        case kFormType_Misc: return 7;
        case kFormType_Ammo: return 8;
        case kFormType_Key: return 9;
        case kFormType_SoulGem: return 10;
        case kFormType_Light: return 11;
        default: return 12;
    }
}

struct ItemAgg { SInt32 count = 0; uint8_t flags = 0; };

/** Base container + container changes, like SKSE's GetNumItems. */
static void CollectInventory(PlayerCharacter* pc, std::vector<std::pair<TESForm*, ItemAgg>>& out, SInt32& gold) {
    std::unordered_map<TESForm*, ItemAgg> agg;
    std::vector<TESForm*> order;
    auto touch = [&](TESForm* f) -> ItemAgg& {
        auto it = agg.find(f);
        if (it == agg.end()) { order.push_back(f); return agg[f]; }
        return it->second;
    };

    if (TESContainer* base = DYNAMIC_CAST(pc->baseForm, TESForm, TESContainer)) {
        for (UInt32 i = 0; i < base->numEntries; ++i) {
            TESContainer::Entry* e = base->entries[i];
            if (e && e->form && e->form->formType != kFormType_LeveledItem) touch(e->form).count += e->count;
        }
    }
    auto* changes = static_cast<ExtraContainerChanges*>(pc->extraData.GetByType(kExtraData_ContainerChanges));
    if (changes && changes->data && changes->data->objList) {
        for (auto it = changes->data->objList->Begin(); !it.End(); ++it) {
            InventoryEntryData* e = it.Get();
            if (!e || !e->type) continue;
            ItemAgg& a = touch(e->type);
            a.count += e->countDelta;
            if (e->extendDataList) {
                for (auto x = e->extendDataList->Begin(); !x.End(); ++x) {
                    BaseExtraList* xl = x.Get();
                    if (!xl) continue;
                    if (xl->HasType(kExtraData_Worn)) a.flags |= 1;
                    if (xl->HasType(kExtraData_WornLeft)) a.flags |= 2;
                    if (xl->HasType(kExtraData_Hotkey)) a.flags |= 4;
                }
            }
        }
    }
    gold = 0;
    out.clear();
    for (TESForm* f : order) {
        ItemAgg& a = agg[f];
        if (f->formID == 0xF) { gold = a.count; continue; }  // Gold001
        if (a.count > 0) out.emplace_back(f, a);
    }
}

static void AddSpell(std::vector<TESForm*>& v, TESForm* f) {
    if (!f) return;
    for (TESForm* x : v) if (x == f) return;
    v.push_back(f);
}

static void CollectSpells(PlayerCharacter* pc, std::vector<TESForm*>& out) {
    out.clear();
    for (UInt32 i = 0; i < pc->addedSpells.Length(); ++i) AddSpell(out, pc->addedSpells.Get(i));
    TESActorBase* base = DYNAMIC_CAST(pc->baseForm, TESForm, TESActorBase);
    if (base && base->spellList.data) {
        auto* d = reinterpret_cast<TESSpellList::Data*>(base->spellList.data);
        for (UInt32 i = 0; d->spells && i < d->numSpells; ++i) AddSpell(out, d->spells[i]);
        for (UInt32 i = 0; d->shouts && i < d->numShouts; ++i) AddSpell(out, d->shouts[i]);
    }
    if (pc->race && pc->race->spellList.data) {
        auto* d = reinterpret_cast<TESSpellList::Data*>(pc->race->spellList.data);
        for (UInt32 i = 0; d->spells && i < d->numSpells; ++i) AddSpell(out, d->spells[i]);
    }
}

static uint8_t SchoolOf(SpellItem* s) {
    if (!s || s->effectItemList.count == 0) return 0;
    MagicItem::EffectItem* e = s->effectItemList[0];
    if (!e || !e->mgef) return 0;
    switch (e->mgef->properties.school) {
        case 18: return 1; case 19: return 2; case 20: return 3; case 21: return 4; case 22: return 5;
        default: return 0;
    }
}

// ---------------------------------------------------------------- writers (main thread)
static void BeginPage0() { Put<uint32_t>(L::kSeq, ++g_seq); MemoryBarrier(); }
static void EndPage0() {
    MemoryBarrier(); ++g_seq;
    Put<uint32_t>(L::kSeqEnd, g_seq); MemoryBarrier(); Put<uint32_t>(L::kSeq, g_seq);
}

/** The player's active effects as the game's Active Effects menu lists them, one row per source. */
static void WriteEffects(PlayerCharacter* pc) {
    uint32_t n = 0;
    tList<ActiveEffect>* list = pc->magicTarget.GetActiveEffects();
    std::vector<MagicItem*> seen;
    if (list) {
        for (UInt32 i = 0, c = list->Count(); i < c && n < L::kMaxEffects; ++i) {
            ActiveEffect* e = list->GetNthItem(i);
            if (!e || !e->item || !e->effect || !e->effect->mgef) continue;
            if (e->flags & 0x8000) continue;                                       // inactive
            UInt32 mflags = e->effect->mgef->properties.flags;
            if (mflags & EffectSetting::Properties::kEffectType_HideInUI) continue;
            if (std::find(seen.begin(), seen.end(), e->item) != seen.end()) continue;
            const char* name = NameOf(e->item);
            if (!name || !*name) continue;
            seen.push_back(e->item);
            uint32_t o = L::kEffectEntries + n * L::kEffectSize;
            PutStr(o, 40, name);
            float left = e->duration > 0 ? e->duration - e->elapsed : -1.f;
            Put<float>(o + 40, left);
            bool bad = (mflags & (EffectSetting::Properties::kEffectType_Hostile | EffectSetting::Properties::kEffectType_Detrimental)) != 0;
            SpellItem* sp = DYNAMIC_CAST(e->item, MagicItem, SpellItem);
            if (sp && sp->data.type == 1) bad = true;                                 // disease
            Put<uint8_t>(o + 44, bad ? 1 : 0);
            ++n;
        }
    }
    Put<uint8_t>(L::kEffects, (uint8_t)n);
}

/** The mods the game actually loaded (not the base game, DLC or Creation Club): proof that installs worked. */
static void WriteLoadedMods() {
    DataHandler* dh = *g_dataHandler;
    if (!dh) return;
    static const char* base[] = { "skyrim.esm", "update.esm", "dawnguard.esm", "hearthfires.esm", "dragonborn.esm", "_resourcepack.esl" };
    uint16_t n = 0;
    auto add = [&](tArray<ModInfo*>& arr) {
        for (UInt32 i = 0; i < arr.count && n < 36; ++i) {
            ModInfo* m = arr[i];
            if (!m) continue;
            bool skip = _strnicmp(m->name, "cc", 2) == 0;
            for (const char* b : base) if (_stricmp(m->name, b) == 0) skip = true;
            if (skip) continue;
            PutStr(1288 + n * 48, 48, m->name);
            ++n;
        }
    };
    add(dh->modList.loadedMods);
    add(dh->modList.loadedCCMods);
    Put<uint16_t>(1284, n);

}

static void WritePlayer(PlayerCharacter* pc, SInt32 gold, uint32_t& flags) {
    if (!pc || !pc->parentCell) return;
    ActorValueOwner& av = pc->actorValueOwner;
    Put<float>(64, av.GetCurrent(AV("Health")));  Put<float>(68, av.GetMaximum(AV("Health")));
    Put<float>(72, av.GetCurrent(AV("Magicka"))); Put<float>(76, av.GetMaximum(AV("Magicka")));
    Put<float>(80, av.GetCurrent(AV("Stamina"))); Put<float>(84, av.GetMaximum(AV("Stamina")));
    Put<float>(88, pc->pos.x); Put<float>(92, pc->pos.y); Put<float>(96, pc->pos.z); Put<float>(100, pc->rot.z);
    Put<uint32_t>(104, pc->currentWorldSpace ? pc->currentWorldSpace->formID : 0);
    Put<uint32_t>(108, pc->parentCell->formID);
    Put<uint16_t>(112, CALL_MEMBER_FN(pc, GetLevel)());
    Put<uint8_t>(114, pc->IsInCombat() ? 1 : 0);
    Put<uint8_t>(115, 0);
    PutStr(116, 64, NameOf(reinterpret_cast<TESForm*>(pc->locationAC8)));
    Put<int32_t>(180, gold);
    Put<float>(184, av.GetCurrent(AV("InventoryWeight")));
    Put<float>(188, av.GetCurrent(AV("CarryWeight")));
    float xp = 0.f;
    if (pc->skills && pc->skills->data && pc->skills->data->levelPointsMax > 0)
        xp = pc->skills->data->levelPoints / pc->skills->data->levelPointsMax;
    Put<float>(192, xp);
    TESForm* right = pc->GetEquippedObject(false);
    TESForm* left = pc->GetEquippedObject(true);
    Put<uint32_t>(196, right ? right->formID : 0);
    Put<uint32_t>(200, left ? left->formID : 0);
    Put<uint32_t>(204, pc->equippedShout ? pc->equippedShout->formID : 0);
    PutStr(208, 48, NameOf(right));
    PutStr(256, 48, NameOf(left));
    PutStr(304, 48, NameOf(pc->equippedShout));
    Put<uint8_t>(352, (pc->actorState.flags04 & ActorState::kState_Sneaking) ? 1 : 0);
    Put<uint8_t>(353, pc->actorState.IsWeaponDrawn() ? 1 : 0);
    Put<uint8_t>(354, (pc->parentCell->unk040 & 1) ? 1 : 0);   // interior cell
    Put<float>(356, GlobalValue(0x38));                       // GameHour
    Put<uint32_t>(360, (uint32_t)GlobalValue(0x39));          // GameDaysPassed
    PutStr(364, 48, NameOf(pc->currentWorldSpace));
    Put<uint16_t>(412, (uint16_t)GlobalValue(0x37));          // GameDay (1-31)
    Put<uint8_t>(414, (uint8_t)GlobalValue(0x36));            // GameMonth (0-11)
    Put<uint16_t>(416, (uint16_t)GlobalValue(0x35));          // GameYear
    PutStr(420, 48, CALL_MEMBER_FN(pc, GetReferenceName)());  // character name
    PutStr(468, 32, NameOf(pc->race));                        // race
    static const char* kSkills[18] = { "OneHanded", "TwoHanded", "Marksman", "Block", "Smithing", "HeavyArmor",
        "LightArmor", "Pickpocket", "Lockpicking", "Sneak", "Alchemy", "Speechcraft", "Alteration", "Conjuration",
        "Destruction", "Illusion", "Restoration", "Enchanting" };
    for (int i = 0; i < 18; ++i) {
        float level = av.GetBase(AV(kSkills[i]));
        float prog = 0.f;
        if (pc->skills && pc->skills->data) {
            auto& ld = pc->skills->data->levelData[i];
            if (ld.pointsMax > 0) prog = ld.points / ld.pointsMax;
        }
        Put<float>(500 + i * 8, level);
        Put<float>(504 + i * 8, prog < 0 ? 0 : (prog > 1 ? 1 : prog));
    }
    WriteEffects(pc);
    WriteLoadedMods();
    flags |= L::fPlayer | L::fInGame;
}

static void WriteLists(PlayerCharacter* pc, SInt32& goldOut) {
    std::vector<std::pair<TESForm*, ItemAgg>> items;
    CollectInventory(pc, items, goldOut);
    std::vector<TESForm*> spells;
    CollectSpells(pc, spells);
    MagicFavorites* fav = MagicFavorites::GetSingleton();

    Put<uint32_t>(L::kLists, ++g_listSeq);  // odd: writing
    MemoryBarrier();
    uint32_t n = 0;
    for (auto& [f, a] : items) {
        if (n >= L::kMaxItems) break;
        uint32_t o = L::kItems + n * L::kItemSize;
        Put<uint32_t>(o, f->formID);
        Put<int32_t>(o + 4, a.count);
        Put<uint8_t>(o + 8, CategoryOf(f));
        Put<uint8_t>(o + 9, a.flags);
        TESWeightForm* w = DYNAMIC_CAST(f, TESForm, TESWeightForm);
        TESValueForm* v = DYNAMIC_CAST(f, TESForm, TESValueForm);
        Put<float>(o + 12, w ? w->weight : 0.f);
        Put<int32_t>(o + 16, v ? (int32_t)v->value : 0);
        PutStr(o + 20, 52, NameOf(f));
        // Item card details, like the game's inventory: damage or armor rating, kind, and the first effect.
        float stat = 0.f; uint8_t sub = 0xFF;
        MagicItem* magic = nullptr;
        if (TESObjectWEAP* wp = DYNAMIC_CAST(f, TESForm, TESObjectWEAP)) {
            stat = (float)wp->damage.attackDamage; sub = wp->gameData.type;
            magic = wp->enchantable.enchantment;
        } else if (TESObjectARMO* ar = DYNAMIC_CAST(f, TESForm, TESObjectARMO)) {
            stat = ar->armorValTimes100 / 100.f; sub = (uint8_t)ar->bipedObject.data.weightClass;
            magic = ar->enchantable.enchantment;
        } else {
            magic = DYNAMIC_CAST(f, TESForm, MagicItem);    // potions, poisons, food, scrolls, ingredients
        }
        Put<uint8_t>(o + 10, sub);
        Put<uint8_t>(o + 11, 0);
        Put<float>(o + 72, stat);
        float mag = 0.f; uint32_t dur = 0; const char* eff = "";
        if (magic && magic->effectItemList.count > 0) {
            MagicItem::EffectItem* e = magic->effectItemList[0];
            if (e && e->mgef) { mag = e->magnitude; dur = e->duration; eff = NameOf(e->mgef); }
        }
        Put<float>(o + 76, mag);
        Put<uint32_t>(o + 80, dur);
        PutStr(o + 84, 36, eff);
        ++n;
    }
    Put<uint32_t>(L::kLists + 4, n);

    uint32_t m = 0;
    for (TESForm* f : spells) {
        if (m >= L::kMaxSpells) break;
        uint8_t kind = 0, school = 0, flags = 0;
        float cost = 0.f;
        if (f->formType == kFormType_Shout) {
            kind = 1;
            if (pc->equippedShout == f) flags |= 8;
        } else if (SpellItem* s = DYNAMIC_CAST(f, TESForm, SpellItem)) {
            UInt32 t = s->data.type;
            if (t == SpellItem::kTypeSpell) kind = 0;
            else if (t == SpellItem::kTypePower) kind = 2;
            else if (t == SpellItem::kTypeLesserPower) kind = 3;
            else continue;  // abilities, diseases, etc. aren't castable
            school = SchoolOf(s);
            cost = (float)s->GetMagickaCost();
            if (cost > 1e6f) cost = 0.f;
            if (pc->rightHandSpell == s) flags |= 1;
            if (pc->leftHandSpell == s) flags |= 2;
            if (kind != 0 && pc->equippedShout == f) flags |= 8;
        } else continue;
        if (fav && fav->IsFavorited(f)) flags |= 4;
        uint32_t o = L::kSpells + m * L::kSpellSize;
        Put<uint32_t>(o, f->formID);
        Put<uint8_t>(o + 4, kind);
        Put<uint8_t>(o + 5, flags);
        Put<uint8_t>(o + 6, school);
        Put<uint8_t>(o + 7, 0);
        Put<float>(o + 8, cost);
        PutStr(o + 12, 68, NameOf(f));
        ++m;
    }
    Put<uint32_t>(L::kLists + 8, m);
    MemoryBarrier();
    ++g_listSeq;
    Put<uint32_t>(L::kLists + 12, g_listSeq);
    MemoryBarrier();
    Put<uint32_t>(L::kLists, g_listSeq);
}

// ---------------------------------------------------------------- commands (main thread)

// Forward declarations (defined with the map markers further down).
template <class T> static T Rd(const void* p, size_t off);
static TESWorldSpace* WorldOf(TESObjectREFR* r);
static TESWorldSpace* g_mapWorld = nullptr;

// ---------------------------------------------------------------- game HUD visibility (v0.7)
// The app can hide parts of the game's HUD (the companion shows them instead). Applied on the UI
// thread and re-applied twice a second, because the HUD resets element visibility on its own.
static std::atomic<uint32_t> g_hudMask{0};      // 1 compass, 2 health/magicka/stamina, 4 enemy health
static uint32_t g_hudApplied = 0xFFFFFFFF;
static bool g_hudLogged = false;

class HudTask : public UIDelegate_v1 {
public:
    explicit HudTask(uint32_t mask) : m_mask(mask) {}
    void Run() override {
        MenuManager* mm = MenuManager::GetSingleton();
        if (!mm) return;
        BSFixedString hudName("HUD Menu");
        IMenu* menu = mm->GetMenu(&hudName);
        if (!menu || !menu->view) return;
        static const struct { uint32_t bit; const char* path; } parts[] = {
            { 1, "_root.HUDMovieBaseInstance.CompassShoutMeterHolder" },
            { 2, "_root.HUDMovieBaseInstance.Health" },
            { 2, "_root.HUDMovieBaseInstance.Magica" },
            { 2, "_root.HUDMovieBaseInstance.Stamina" },
            { 4, "_root.HUDMovieBaseInstance.EnemyHealth_mc" },
        };
        for (auto& part : parts) {
            std::string var = std::string(part.path) + "._visible";
            if (!g_hudLogged) {
                GFxValue probe;
                Log("hud: %s %s", part.path, menu->view->GetVariable(&probe, part.path) ? "found" : "MISSING");
            }
            GFxValue v;
            v.SetBool((m_mask & part.bit) == 0);
            menu->view->SetVariable(var.c_str(), &v, 0);
        }
        g_hudLogged = true;
    }
    void Dispose() override { delete this; }
private:
    uint32_t m_mask;
};

// ---------------------------------------------------------------- the game's own compass (v0.11)
// The HUD keeps the compass markers it draws in CompassTargetDataA, 4 numbers each: heading (degrees),
// alpha, icon frame, scale. We copy them (and the frame numbers for quest/enemy/etc., the visible
// half-arc and the compass angle) so the companion's compass shows exactly what the game's does.
namespace C {
    constexpr uint32_t kBase = 386048, kEntries = 386112, kSize = 16, kMax = 200;
    // header: 0 seq, 4 count, 8 angle (deg), 12 half-arc (deg), 16 u16 frames[6]: quest, questDoor, playerSet, enemy, locations, undiscovered, 28 valid
}
static uint32_t g_cseq = 0;
class CompassTask : public UIDelegate_v1 {
public:
    void Run() override {
        MenuManager* mm = MenuManager::GetSingleton();
        if (!mm || !g_view) return;
        BSFixedString hudName("HUD Menu");
        IMenu* menu = mm->GetMenu(&hudName);
        if (!menu || !menu->view) return;
        GFxMovieView* v = menu->view;
        auto num = [v](const char* path, double& out) {
            GFxValue x; if (!v->GetVariable(&x, path) || x.GetType() != GFxValue::kType_Number) return false;
            out = x.GetNumber(); return true;
        };
        double zero = 0, three = 0, rectX = 0, mask = 0;
        bool ok = num("_root.HUDMovieBaseInstance.CompassZeroX", zero) && num("_root.HUDMovieBaseInstance.CompassThreeSixtyX", three) &&
                  num("_root.HUDMovieBaseInstance.CompassRect._x", rectX) &&
                  num("_root.HUDMovieBaseInstance.CompassShoutMeterHolder.Compass.CompassMask_mc._width", mask);
        if (!ok || three == zero) return;
        double span = fabs(three - zero);
        float angle = (float)fmod((rectX - zero) / (three - zero) * 360.0 + 720.0, 360.0);
        float half = (float)(mask * 180.0 / span);
        static const char* frames[] = { "CompassMarkerQuest", "CompassMarkerQuestDoor", "CompassMarkerPlayerSet",
                                        "CompassMarkerEnemy", "CompassMarkerLocations", "CompassMarkerUndiscovered" };
        GFxValue arr;
        uint32_t n = 0;
        Put<uint32_t>(C::kBase, ++g_cseq); MemoryBarrier();
        if (v->GetVariable(&arr, "_root.HUDMovieBaseInstance.CompassTargetDataA") && arr.GetType() == GFxValue::kType_Array) {
            uint32_t len = arr.GetArraySize() / 4;
            for (uint32_t i = 0; i < len && n < C::kMax; ++i) {
                GFxValue h, a, f, sc;
                arr.GetElement(i * 4, &h); arr.GetElement(i * 4 + 1, &a); arr.GetElement(i * 4 + 2, &f); arr.GetElement(i * 4 + 3, &sc);
                if (h.GetType() != GFxValue::kType_Number || f.GetType() != GFxValue::kType_Number) continue;
                uint32_t o = C::kEntries + n * C::kSize;
                Put<float>(o, (float)h.GetNumber());
                Put<float>(o + 4, a.GetType() == GFxValue::kType_Number ? (float)a.GetNumber() : 100.f);
                Put<uint16_t>(o + 8, (uint16_t)f.GetNumber());
                Put<float>(o + 12, sc.GetType() == GFxValue::kType_Number ? (float)sc.GetNumber() : 100.f);
                ++n;
            }
        }
        Put<uint32_t>(C::kBase + 4, n);
        Put<float>(C::kBase + 8, angle);
        Put<float>(C::kBase + 12, half);
        for (int i = 0; i < 6; ++i) {
            double fv = 0; std::string path = std::string("_root.HUDMovieBaseInstance.") + frames[i];
            num(path.c_str(), fv);
            Put<uint16_t>(C::kBase + 16 + i * 2, (uint16_t)fv);
        }
        Put<uint32_t>(C::kBase + 28, 1);
        MemoryBarrier(); Put<uint32_t>(C::kBase, ++g_cseq);
        static int logged = 0;
        if (logged < 3 && n > 0) {
            ++logged;
            Log("compass: n=%u angle=%.1f half=%.1f first: h=%.1f a=%.0f f=%.0f", n, angle, half,
                *reinterpret_cast<volatile float*>(g_view + C::kEntries), *reinterpret_cast<volatile float*>(g_view + C::kEntries + 4),
                (double)*reinterpret_cast<volatile uint16_t*>(g_view + C::kEntries + 8));
        }
    }
    void Dispose() override { delete this; }
};
static std::atomic<bool> g_compassQueued{false};
class CompassTaskOnce : public CompassTask {
public:
    void Run() override { CompassTask::Run(); g_compassQueued = false; }
};

static ULONGLONG g_lastHud = 0;
static std::atomic<bool> g_inGame{false};
static void ApplyHud(ULONGLONG now) {
    // The compass is mirrored every frame: by our UI hook without SKSE, by CompassPump under SKSE.
    uint32_t mask = g_hudMask.load();
    if (mask == g_hudApplied && (mask == 0 || now - g_lastHud < 500)) return;
    g_hudApplied = mask;
    g_lastHud = now;
    QueueUi(new HudTask(mask));
}

// The game's own spell/shout equip (what the EquipSpell / EquipShout console commands call), 1.7.104.
// hand: 0 left, 1 right, 2 voice (powers), 3 instant.
typedef void (*_EquipSpellFn)(EquipManager*, Actor*, SpellItem*, UInt32 hand);
typedef void (*_EquipShoutFn)(EquipManager*, Actor*, TESShout*);
static RelocAddr<_EquipSpellFn> EquipSpellFn(0x006DC560);
static RelocAddr<_EquipShoutFn> EquipShoutFn(0x006DC5D0);

static std::atomic<bool> g_refreshMarkers{false};

class SaveTask : public TaskDelegate {
public:
    void Run() override {
        if (BGSSaveLoadManager* slm = BGSSaveLoadManager::GetSingleton()) { slm->Save("Overheat Save"); Log("saved: Overheat Save"); }
    }
    void Dispose() override { delete this; }
};

static uint32_t RunCommand(uint32_t id, const uint32_t a[4]) {
    PlayerCharacter* pc = *g_thePlayer;
    if (id == L::Ping) return a[0];
    if (!pc || !pc->parentCell) return 1;
    if (id == L::SaveGame) {
        // A normal save through the game's own save manager (what the console's "save" does). Run from the
        // main loop's task pool, like SKSE's Game.SaveGame: from inside UI command processing it stalls.
        if (!BGSSaveLoadManager::GetSingleton()) return 3;
        QueueMain(new SaveTask());
        return 0;
    }
    if (id == L::SetHud) { g_hudMask = a[0]; return 0; }   // a[0] is a mask, not a form
    TESForm* form = LookupFormByID(a[0]);
    if (!form) return 2;
    EquipManager* em = EquipManager::GetSingleton();
    switch (id) {
        case L::EquipItem: {
            if (!em) return 3;
            // Spells, powers and shouts aren't items: equip them the way the game's console does.
            if (SpellItem* sp = DYNAMIC_CAST(form, TESForm, SpellItem)) {
                UInt32 t = sp->data.type;
                UInt32 hand = (t == SpellItem::kTypePower || t == SpellItem::kTypeLesserPower) ? 2 : (a[1] == 2 ? 0 : 1);
                EquipSpellFn(em, pc, sp, hand);
                g_listsDirty = true;
                return 0;
            }
            if (form->formType == kFormType_Shout) {
                EquipShoutFn(em, pc, static_cast<TESShout*>(form));
                g_listsDirty = true;
                return 0;
            }
            BGSEquipSlot* slot = a[1] == 1 ? GetRightHandSlot() : a[1] == 2 ? GetLeftHandSlot() : nullptr;
            SInt32 count = 1;
            if (form->formType == kFormType_Ammo) {
                std::vector<std::pair<TESForm*, ItemAgg>> items; SInt32 g;
                CollectInventory(pc, items, g);
                for (auto& [f, ag] : items) if (f == form) count = ag.count;
            }
            if (!em) return 3;
            CALL_MEMBER_FN(em, EquipItem)(pc, form, nullptr, count, slot, true, false, false, nullptr);
            g_listsDirty = true;
            return 0;
        }
        case L::UseItem:
            if (!em) return 3;
            CALL_MEMBER_FN(em, EquipItem)(pc, form, nullptr, 1, nullptr, true, false, false, nullptr);
            g_listsDirty = true;
            return 0;
        case L::UnequipItem:
            if (!em) return 3;
            CALL_MEMBER_FN(em, UnequipItem)(pc, form, nullptr, 1, nullptr, false, false, true, false, nullptr);
            g_listsDirty = true;
            return 0;
        case L::FastTravel: {
            // Like the map's fast travel: not in combat, only to discovered places; time passes with distance.
            PlayerCharacter* pc = *g_thePlayer;
            TESObjectREFR* dest = DYNAMIC_CAST(LookupFormByID(a[0]), TESForm, TESObjectREFR);
            if (!pc || !dest) return 1;
            if (pc->IsInCombat()) return 2;
            BSExtraData* x = dest->extraData.GetByType(kExtraData_MapMarker);
            const uint8_t* data = x ? Rd<const uint8_t*>(x, 0x10) : nullptr;
            if (!data || !(data[0x10] & 2)) return 3;             // not discovered
            TESWorldSpace* ws = WorldOf(dest) ? WorldOf(dest) : g_mapWorld;
            float dx = dest->pos.x - pc->pos.x, dy = dest->pos.y - pc->pos.y;
            float hours = sqrtf(dx * dx + dy * dy) / 8000.f;
            if (hours > 48.f) hours = 48.f;
            if (TESForm* hour = LookupFormByID(0x38)) *reinterpret_cast<float*>(reinterpret_cast<uint8_t*>(hour) + 0x34) += hours;
            UInt32 none = *g_invalidRefHandle;
            NiPoint3 pos = dest->pos, rot = dest->rot;
            MoveRefrToPosition(pc, &none, dest->parentCell, ws, &pos, &rot);
            Log("fast travel to %08X (%.1f h)", dest->formID, hours);
            return 0;
        }
        case L::SetHud:
            g_hudMask = a[0];
            return 0;
        case L::TrackQuest: {
            // The journal's "track" toggle: the quest's Active flag (0x800), which drives its compass and map markers.
            if (form->formType != kFormType_Quest) return 3;
            uint16_t* qf = reinterpret_cast<uint16_t*>(reinterpret_cast<uint8_t*>(form) + 0xDC);
            if (a[1]) *qf |= 0x800; else *qf &= ~0x800;
            g_refreshMarkers = true;
            Log("track quest %08X -> %u", form->formID, a[1]);
            return 0;
        }
        case L::SetSpellHotkey: {
            MagicFavorites* fav = MagicFavorites::GetSingleton();
            if (!fav || a[1] > 7) return 3;
            fav->SetHotkey(form, (SInt8)a[1]);
            g_listsDirty = true;
            return 0;
        }
        default:
            return 0xFFFFFFFFu;
    }
}

// ---------------------------------------------------------------- map markers (v3, main thread)
// Region 131072..262143. Raw walks over game tables are guarded with SEH: a bad offset on some
// future runtime drops the markers instead of crashing Skyrim.
namespace M {
constexpr uint32_t kBase = 131072, kEntries = 131136, kSize = 64, kMax = 2040;
enum Kind : uint8_t { Location = 0, QuestTarget = 1, Hostile = 2, Ally = 3 };
enum Flag : uint8_t { Discovered = 1, Main = 2, Interior = 4, Hidden = 8 };
}
struct Marker { float x, y, z; uint8_t kind, flags; uint16_t type; uint32_t formId; const char* name; };

static std::vector<Marker> g_locations;
static TESWorldSpace* g_locationsWorld = nullptr;
static ULONGLONG g_lastMarkers = 0, g_lastLocations = 0;

template <class T> static T Rd(const void* p, size_t off) { return *reinterpret_cast<const T*>(reinterpret_cast<const uint8_t*>(p) + off); }

static TESObjectCELL* PersistentCell(TESWorldSpace* ws) { return ws ? Rd<TESObjectCELL*>(ws, 0x88) : nullptr; }
/** The cell a reference belongs to, even when it isn't loaded (persistent refs keep their home cell in extra data). */
static TESObjectCELL* CellOf(TESObjectREFR* r) {
    if (!r) return nullptr;
    if (r->parentCell) return r->parentCell;
    __try {
        if (BSExtraData* x = r->extraData.GetByType(kExtraData_PersistentCell)) return Rd<TESObjectCELL*>(x, 0x10);
    } __except (EXCEPTION_EXECUTE_HANDLER) {}
    return nullptr;
}
static TESWorldSpace* WorldOf(TESObjectREFR* r) { TESObjectCELL* c = CellOf(r); return c ? c->worldSpace : nullptr; }

/**
 * True when [child] is [parent] or one of its child worldspaces (Whiterun, Windhelm, Solitude... sit inside
 * Tamriel and share its coordinates). The parent field isn't mapped for 1.7.104, so look for the pointer.
 */
static TESWorldSpace* FindParentPtr(TESWorldSpace* child, TESWorldSpace* parent) {
    __try {
        for (size_t off = 0x28; off < 0x300; off += 8)
            if (Rd<void*>(child, off) == (void*)parent) return parent;
    } __except (EXCEPTION_EXECUTE_HANDLER) {}
    return nullptr;
}
static bool InWorld(TESWorldSpace* child, TESWorldSpace* parent) {
    if (!child || !parent) return false;
    if (child == parent) return true;
    static std::unordered_map<TESWorldSpace*, TESWorldSpace*> parents;
    auto it = parents.find(child);
    if (it == parents.end()) it = parents.emplace(child, FindParentPtr(child, parent)).first;
    return it->second == parent;
}

/** [ws] and every worldspace inside it. */
static std::vector<TESWorldSpace*> WorldFamily(TESWorldSpace* ws) {
    std::vector<TESWorldSpace*> out{ ws };
    if (DataHandler* dh = *g_dataHandler)
        for (UInt32 i = 0; i < dh->arrWRLD.count; ++i)
            if (TESWorldSpace* w = static_cast<TESWorldSpace*>(dh->arrWRLD[i])) if (w != ws && InWorld(w, ws)) out.push_back(w);
    return out;
}

typedef void (*RefFn)(TESObjectREFR*, void*);
static void WalkCellRefs(TESObjectCELL* cell, RefFn fn, void* ctx) {
    __try {
        if (!cell) return;
        auto& rd = cell->refData;
        if (!rd.refArray || rd.maxSize == 0 || rd.maxSize > (1u << 22)) return;
        for (UInt32 i = 0; i < rd.maxSize; ++i) {
            auto& e = rd.refArray[i];
            if (e.unk08 && e.ref) fn(e.ref, ctx);
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {}
}

// Exterior cells currently attached (the loaded grid around the player).
static void AttachedCells(TESWorldSpace* ws, std::vector<TESObjectCELL*>& out) {
    __try {
        const uint8_t* map = reinterpret_cast<const uint8_t*>(ws) + 0x58;  // BSTHashMap<CellID, TESObjectCELL*>
        UInt32 cap = Rd<UInt32>(map, 0x0C);
        const uint8_t* entries = Rd<const uint8_t*>(map, 0x28);
        if (!entries || cap == 0 || cap > (1u << 20)) return;
        for (UInt32 i = 0; i < cap; ++i) {
            const uint8_t* e = entries + i * 0x18;
            if (!Rd<void*>(e, 0x10)) continue;
            TESObjectCELL* c = Rd<TESObjectCELL*>(e, 0x08);
            if (c && c->unk044 == 7) out.push_back(c);  // cellState: attached
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) { out.clear(); }
}

static bool IsInterior(TESObjectCELL* c) { return c && (c->unk040 & 1); }
static bool Usable(TESObjectREFR* r) { return r && !(r->flags & (0x20 | 0x800)); }  // deleted / disabled

static void LocationFn(TESObjectREFR* r, void* ctx) {
    if (!Usable(r)) return;
    BSExtraData* x = r->extraData.GetByType(kExtraData_MapMarker);
    if (!x) return;
    const uint8_t* data = Rd<const uint8_t*>(x, 0x10);       // MapMarkerData*
    if (!data) return;
    uint8_t mflags = data[0x10];
    uint8_t f = (mflags & 2) ? M::Discovered : 0;
    if (!(mflags & 1)) f |= M::Hidden;                       // not on the map yet: compass only, when close
    Marker m{ r->pos.x, r->pos.y, r->pos.z, M::Location, f,
              Rd<uint16_t>(data, 0x12), r->formID, Rd<const char*>(data, 0x08) };
    static_cast<std::vector<Marker>*>(ctx)->push_back(m);
}

struct ActorCtx { PlayerCharacter* pc; std::vector<Marker>* out; float range2; uint8_t flags; };
static void ActorFn(TESObjectREFR* r, void* p) {
    auto* c = static_cast<ActorCtx*>(p);
    if (!Usable(r) || r == c->pc || r->formType != kFormType_Character) return;
    float dx = r->pos.x - c->pc->pos.x, dy = r->pos.y - c->pc->pos.y;
    if (dx * dx + dy * dy > c->range2) return;
    Actor* a = static_cast<Actor*>(r);
    if (a->IsDead(1)) return;
    uint8_t kind;
    if (a->flags1 & Actor::kFlags_IsPlayerTeammate) kind = M::Ally;
    else if (a->IsInCombat() && CALL_MEMBER_FN(a, IsHostileToActor)(c->pc)) kind = M::Hostile;
    else return;
    c->out->push_back(Marker{ r->pos.x, r->pos.y, r->pos.z, kind, c->flags, 0, r->formID, NameOf(r->baseForm) });
}

static TESObjectREFR* RefFromHandle(UInt32 handle) {
    NiPointer<TESObjectREFR> ref;
    UInt32 h = handle;
    if (!LookupREFRByHandle(h, ref)) return nullptr;
    return ref;  // the game keeps its own reference; we only read it this frame
}

// Quest-target conditions. The game only points at targets whose conditions pass; most are
// GetStage checks on the quest itself (e.g. MQ101's waypoints through Helgen Keep). We evaluate
// the common quest functions and treat anything else as true.
static bool AsQuest(const void* p, uint16_t& stage, uint16_t& flags) {
    __try {
        const TESForm* f = static_cast<const TESForm*>(p);
        if (!f || f->formType != kFormType_Quest) return false;
        stage = Rd<uint16_t>(f, 0x228);
        flags = Rd<uint16_t>(f, 0xDC);
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) { return false; }
}
static bool Compare(float lhs, uint8_t cmp, float rhs) {
    switch (cmp & 0xE0) {
        case 0x00: return lhs == rhs;
        case 0x20: return lhs != rhs;
        case 0x40: return lhs > rhs;
        case 0x60: return lhs >= rhs;
        case 0x80: return lhs < rhs;
        case 0xA0: return lhs <= rhs;
        default: return true;
    }
}
static bool EvalOne(const Condition* c) {
    if (c->comparisonType & Condition::kComparisonFlag_Global) return true;   // compares to a global: not handled
    float rhs; memcpy(&rhs, &c->compareValue, 4);
    uint16_t stage = 0, qflags = 0;
    switch (c->functionId) {
        case 58:   // GetStage
            if (!AsQuest(reinterpret_cast<const void*>(c->param1), stage, qflags)) return true;
            return Compare((float)stage, c->comparisonType, rhs);
        case 56:   // GetQuestRunning
            if (!AsQuest(reinterpret_cast<const void*>(c->param1), stage, qflags)) return true;
            return Compare((qflags & 1) && !(qflags & 2) ? 1.f : 0.f, c->comparisonType, rhs);
        default:
            return true;
    }
}
/** Skyrim condition lists: OR binds an item to the next one; groups are ANDed. */
static bool EvalConditions(const Condition* c) {
    bool all = true, group = false, open = false;
    for (int guard = 0; c && guard < 64; c = c->next, ++guard) {
        group = group || EvalOne(c);
        open = true;
        if (c->comparisonType & Condition::kComparisonFlag_Or) continue;
        all = all && group;
        group = false; open = false;
    }
    if (open) all = all && group;
    return all;
}

// Targets of the displayed objectives of active (tracked) quests, resolved through each
// quest's alias -> ref handle map.
struct QuestCtx { std::vector<Marker>* out; TESWorldSpace* ws; TESObjectCELL* interiorCell; };

/** For a ref inside an interior, the exterior door (in [ws]) that leads into that interior; up to 2 hops. */
static TESObjectREFR* g_doorHit = nullptr;
struct DoorCtx { TESWorldSpace* ws; TESObjectCELL* next; int hops; };
static void DoorFn(TESObjectREFR* r, void* p) {
    auto* c = static_cast<DoorCtx*>(p);
    if (g_doorHit || !Usable(r)) return;
    BSExtraData* x = r->extraData.GetByType(kExtraData_Teleport);
    if (!x) return;
    const uint8_t* tele = Rd<const uint8_t*>(x, 0x10);         // DoorTeleportData*
    if (!tele) return;
    TESObjectREFR* other = RefFromHandle(Rd<UInt32>(tele, 0x00));
    TESObjectCELL* oc = CellOf(other);
    if (!other || !oc) return;
    if (!IsInterior(oc)) {
        if (InWorld(oc->worldSpace, c->ws)) g_doorHit = other;
    } else if (!c->next && oc != r->parentCell) {
        c->next = oc;
    }
}
/** Outside-in: an exterior door (always loaded, in its world's persistent cell) whose other side is in [interior]. */
struct OuterDoorCtx { TESObjectCELL* interior; TESObjectREFR* hit; };
static void OuterDoorFn(TESObjectREFR* r, void* p) {
    auto* c = static_cast<OuterDoorCtx*>(p);
    if (c->hit || !Usable(r)) return;
    BSExtraData* x = r->extraData.GetByType(kExtraData_Teleport);
    if (!x) return;
    const uint8_t* tele = Rd<const uint8_t*>(x, 0x10);
    if (!tele) return;
    TESObjectREFR* other = RefFromHandle(Rd<UInt32>(tele, 0x00));
    if (other && CellOf(other) == c->interior) c->hit = r;
}
static TESObjectREFR* DoorInto(TESObjectCELL* interior, TESWorldSpace* ws) {
    g_doorHit = nullptr;
    DoorCtx c{ ws, nullptr, 0 };
    WalkCellRefs(interior, DoorFn, &c);
    if (!g_doorHit && c.next) { DoorCtx c2{ ws, nullptr, 1 }; WalkCellRefs(c.next, DoorFn, &c2); }
    if (!g_doorHit) {
        // The interior isn't loaded: look from outside, through every door in the world and its cities.
        for (TESWorldSpace* w : WorldFamily(ws)) {
            OuterDoorCtx oc{ interior, nullptr };
            WalkCellRefs(PersistentCell(w), OuterDoorFn, &oc);
            if (oc.hit) { g_doorHit = oc.hit; break; }
        }
    }
    return g_doorHit;
}

static void PushQuestTarget(QuestCtx& c, TESObjectREFR* r, TESQuest* q, uint8_t qtype) {
    uint8_t flags = qtype == 1 ? M::Main : 0;
    TESObjectCELL* cell = CellOf(r);
    if (!cell) return;
    if (IsInterior(cell)) {
        if (cell != c.interiorCell) {
            // Inside somewhere else: like the HUD, point at the door that leads there.
            if (c.interiorCell) return;
            TESObjectREFR* d = DoorInto(cell, c.ws);
            if (!d) return;
            r = d;
        } else flags |= M::Interior;
    } else if (!InWorld(cell->worldSpace, c.ws)) return;
    for (const Marker& m : *c.out)   // two targets behind the same door: one marker
        if (m.kind == M::QuestTarget && m.formId == q->formID && fabsf(m.x - r->pos.x) < 1.f && fabsf(m.y - r->pos.y) < 1.f) return;
    c.out->push_back(Marker{ r->pos.x, r->pos.y, r->pos.z, M::QuestTarget, flags, qtype, q->formID, q->fullName.name.data });
}
static std::atomic<int> g_qtLog{0};
static void QuestTargets(TESQuest* q, QuestCtx& c) {
    const bool log = g_qtLog > 0;
    __try {
        uint16_t qflags = Rd<uint16_t>(q, 0xDC);
        if ((qflags & 0x1) == 0 || (qflags & 0x2) || (qflags & 0x800) == 0) return;  // enabled, not completed, active
        uint8_t qtype = Rd<uint8_t>(q, 0xDF);
        if (log) Log("qt: %08X '%s' flags=%04X", q->formID, q->fullName.name.data ? q->fullName.name.data : "", qflags);
        const uint8_t* aliasMap = reinterpret_cast<const uint8_t*>(q) + 0x70;  // BSTHashMap<u32 alias, ObjectRefHandle> (verified on 1.7.104)
        UInt32 cap = Rd<UInt32>(aliasMap, 0x0C);
        const uint8_t* entries = Rd<const uint8_t*>(aliasMap, 0x28);
        if (!entries || cap > 4096) return;
        for (const uint8_t* node = reinterpret_cast<const uint8_t*>(q) + 0xF8; node; node = Rd<const uint8_t*>(node, 8)) {
            const uint8_t* obj = Rd<const uint8_t*>(node, 0);
            if (!obj) continue;
            if (log) Log("qt:   objective idx=%u state=%u ntargets=%u", Rd<uint16_t>(obj, 0x1C), obj[0x1F], Rd<UInt32>(obj, 0x18));
            if (obj[0x1F] != 1) continue;                                  // state: displayed
            const uint8_t* const* targets = Rd<const uint8_t* const*>(obj, 0x10);
            UInt32 n = Rd<UInt32>(obj, 0x18);
            if (!targets || n > 64) continue;
            for (UInt32 t = 0; t < n; ++t) {
                if (!targets[t]) continue;
                bool cond = EvalConditions(Rd<const Condition*>(targets[t], 0x08));
                UInt32 alias = Rd<UInt32>(targets[t], 0x10);
                if (log) Log("qt:     target %u alias=%08X cond=%d", t, alias, cond ? 1 : 0);
                if (!cond) continue;
                if (alias > 0xFFFF) alias &= 0xFF;
                for (UInt32 i = 0; i < cap; ++i) {
                    const uint8_t* e = entries + i * 0x10;
                    if (!Rd<void*>(e, 8) || Rd<UInt32>(e, 0) != alias) continue;
                    TESObjectREFR* r = RefFromHandle(Rd<UInt32>(e, 4));
                    if (log) Log("qt:       alias hit handle=%08X ref=%p cell=%p interior=%d", Rd<UInt32>(e, 4), r, r ? r->parentCell : nullptr, r ? (int)IsInterior(r->parentCell) : -1);
                    if (r) PushQuestTarget(c, r, q, qtype);
                }
            }
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {}
}

static void WriteMarkers(PlayerCharacter* pc, ULONGLONG now) {
    const bool interior = IsInterior(pc->parentCell);
    if (pc->currentWorldSpace && !interior) g_mapWorld = pc->currentWorldSpace;
    if (!g_mapWorld) g_mapWorld = DYNAMIC_CAST(LookupFormByID(0x3C), TESForm, TESWorldSpace);  // Tamriel
    TESWorldSpace* ws = g_mapWorld;
    if (!ws) return;

    if (ws != g_locationsWorld || now - g_lastLocations > 5000) {
        g_locations.clear();
        // The world and its cities (Whiterun, Solitude, ... are child worlds sharing its coordinates;
        // their own map markers live in the city world's persistent cell).
        for (TESWorldSpace* w : WorldFamily(ws)) WalkCellRefs(PersistentCell(w), LocationFn, &g_locations);
        g_locationsWorld = ws;
        g_lastLocations = now;
    }

    std::vector<Marker> dyn;
    static int qtRuns = 0;
    g_qtLog = (++qtRuns % 100 == 20) ? 1 : 0;   // diagnostics: one pass every ~30 s
    QuestCtx qc{ &dyn, ws, interior ? pc->parentCell : nullptr };
    if (DataHandler* dh = *g_dataHandler)
        for (UInt32 i = 0; i < dh->quests.count; ++i)
            if (TESQuest* q = dh->quests[i]) QuestTargets(q, qc);

    ActorCtx ac{ pc, &dyn, 9000.f * 9000.f, (uint8_t)(interior ? M::Interior : 0) };
    if (!interior) {
        std::vector<TESObjectCELL*> cells;
        AttachedCells(ws, cells);
        for (TESObjectCELL* cell : cells) WalkCellRefs(cell, ActorFn, &ac);
        WalkCellRefs(PersistentCell(ws), ActorFn, &ac);
    } else {
        WalkCellRefs(pc->parentCell, ActorFn, &ac);  // interior: one cell
    }

    Put<uint32_t>(M::kBase, Get<uint32_t>(M::kBase) | 1);  // odd: writing
    MemoryBarrier();
    uint32_t n = 0;
    auto emit = [&](const Marker& m) {
        if (n >= M::kMax) return;
        uint32_t o = M::kEntries + n * M::kSize;
        Put<float>(o, m.x); Put<float>(o + 4, m.y); Put<float>(o + 8, m.z);
        Put<uint8_t>(o + 12, m.kind); Put<uint8_t>(o + 13, m.flags); Put<uint16_t>(o + 14, m.type);
        Put<uint32_t>(o + 16, m.formId);
        PutStr(o + 20, 44, m.name);
        ++n;
    };
    for (auto& m : dyn) emit(m);
    for (auto& m : g_locations) emit(m);
    Put<uint32_t>(M::kBase + 4, n);
    Put<uint32_t>(M::kBase + 8, ws->formID);
    MemoryBarrier();
    uint32_t seq = (Get<uint32_t>(M::kBase) | 1) + 1;
    Put<uint32_t>(M::kBase + 12, seq);
    MemoryBarrier();
    Put<uint32_t>(M::kBase, seq);
}

// ---------------------------------------------------------------- quest journal (v4, main thread)
// Region 262144..393215, rewritten every 2 s: quests with objectives shown in the journal.
namespace Q {
constexpr uint32_t kBase = 262144, kEntries = 262208, kSize = 1024, kMax = 120;
constexpr uint32_t kObj = 72, kObjSize = 112, kMaxObj = 8;
enum Flag : uint8_t { Active = 1, Completed = 2, Failed = 4 };
}
static ULONGLONG g_lastQuests = 0;

/** Display name of the ref filling [aliasName] in [q], or null. SEH-guarded raw walk. */
static const char* AliasRefName(TESQuest* q, const char* aliasName) {
    __try {
        UInt32 id = 0xFFFFFFFF;
        for (UInt32 i = 0; i < q->aliases.count; ++i) {
            BGSBaseAlias* a = q->aliases.entries[i];
            if (a && a->name.data && _stricmp(a->name.data, aliasName) == 0) { id = a->aliasId; break; }
        }
        if (id == 0xFFFFFFFF) return nullptr;
        const uint8_t* map = reinterpret_cast<const uint8_t*>(q) + 0x70;
        UInt32 cap = Rd<UInt32>(map, 0x0C);
        const uint8_t* entries = Rd<const uint8_t*>(map, 0x28);
        if (!entries || cap > 4096) return nullptr;
        for (UInt32 i = 0; i < cap; ++i) {
            const uint8_t* e = entries + i * 0x10;
            if (!Rd<void*>(e, 8) || Rd<UInt32>(e, 0) != id) continue;
            TESObjectREFR* r = RefFromHandle(Rd<UInt32>(e, 4));
            if (!r) return nullptr;
            const char* n = CALL_MEMBER_FN(r, GetReferenceName)();
            return (n && *n) ? n : NameOf(r->baseForm);
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {}
    return nullptr;
}

/** Objective text with <Alias=X> / <Alias.ShortName=X> tokens filled in, other tokens dropped. */
static std::string ResolveText(TESQuest* q, const char* src) {
    std::string out;
    if (!src) return out;
    for (const char* p = src; *p;) {
        if (*p == '<') {
            const char* end = strchr(p, '>');
            if (end) {
                std::string tok(p + 1, end);
                size_t eq = tok.find('=');
                if (tok.rfind("Alias", 0) == 0 && eq != std::string::npos) {
                    std::string name = tok.substr(eq + 1);
                    const char* n = AliasRefName(q, name.c_str());
                    out += n ? n : name;
                }
                p = end + 1;
                continue;
            }
        }
        out += *p++;
    }
    return out;
}

struct ObjInfo { uint16_t index; uint8_t state; const char* text; };

/** Collects journal-visible objectives of [q] (raw walk). Returns false if the quest has none. */
static bool JournalObjectives(TESQuest* q, ObjInfo* out, int& n, uint16_t& flags, uint8_t& type, uint16_t& stage) {
    n = 0;
    __try {
        flags = Rd<uint16_t>(q, 0xDC);
        type = Rd<uint8_t>(q, 0xDF);
        stage = Rd<uint16_t>(q, 0x228);
        for (const uint8_t* node = reinterpret_cast<const uint8_t*>(q) + 0xF8; node && n < 32; node = Rd<const uint8_t*>(node, 8)) {
            const uint8_t* obj = Rd<const uint8_t*>(node, 0);
            if (!obj) continue;
            uint8_t st = obj[0x1F];
            if (st != 1 && st != 3 && st != 5) continue;     // displayed, completed-displayed, failed-displayed
            out[n++] = ObjInfo{ Rd<uint16_t>(obj, 0x1C), st, Rd<const char*>(obj, 0x00) };
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) { n = 0; }
    return n > 0;
}

static void WriteQuests() {
    DataHandler* dh = *g_dataHandler;
    if (!dh) return;
    Put<uint32_t>(Q::kBase, Get<uint32_t>(Q::kBase) | 1);  // odd: writing
    MemoryBarrier();
    uint32_t count = 0;
    ObjInfo objs[32];
    for (UInt32 i = 0; i < dh->quests.count && count < Q::kMax; ++i) {
        TESQuest* q = dh->quests[i];
        if (!q) continue;
        int n; uint16_t qflags = 0, stage = 0; uint8_t type = 0;
        if (!JournalObjectives(q, objs, n, qflags, type, stage)) continue;
        const char* name = q->fullName.name.data;
        if ((!name || !*name) && type != 6) continue;          // unnamed: only misc objectives
        std::sort(objs, objs + n, [](const ObjInfo& a, const ObjInfo& b) { return a.index > b.index; });  // newest first
        uint32_t o = Q::kEntries + count * Q::kSize;
        uint8_t f = ((qflags & 0x800) ? Q::Active : 0) | ((qflags & 0x2) ? Q::Completed : 0) | ((qflags & 0x40) ? Q::Failed : 0);
        Put<uint32_t>(o, q->formID);
        Put<uint8_t>(o + 4, type);
        Put<uint8_t>(o + 5, f);
        Put<uint8_t>(o + 6, (uint8_t)(n < (int)Q::kMaxObj ? n : Q::kMaxObj));
        Put<uint8_t>(o + 7, 0);
        Put<uint16_t>(o + 8, stage);
        Put<uint16_t>(o + 10, 0);
        PutStr(o + 12, 60, name ? name : "");
        for (int k = 0; k < n && k < (int)Q::kMaxObj; ++k) {
            uint32_t oo = o + Q::kObj + k * Q::kObjSize;
            Put<uint8_t>(oo, objs[k].state);
            Put<uint8_t>(oo + 1, 0);
            Put<uint16_t>(oo + 2, objs[k].index);
            PutStr(oo + 4, 108, ResolveText(q, objs[k].text).c_str());
        }
        ++count;
    }
    Put<uint32_t>(Q::kBase + 4, count);
    MemoryBarrier();
    uint32_t seq = (Get<uint32_t>(Q::kBase) | 1) + 1;
    Put<uint32_t>(Q::kBase + 12, seq);
    MemoryBarrier();
    Put<uint32_t>(Q::kBase, seq);
}

// ---------------------------------------------------------------- the per-tick task
/**
 * Runs the app's command on the UI task queue: it's processed every frame even while the game is
 * paused (like the game's own inventory), so equipping from a paused bottom screen still works.
 */
static std::atomic<bool> g_cmdQueued{false};
class CmdTask : public UIDelegate_v1 {
public:
    void Run() override {
        uint32_t cmd[6];
        for (int i = 0; i < 6; ++i) cmd[i] = Get<uint32_t>(L::kCmd + i * 4);
        if (cmd[0] != g_lastCmd) {
            g_lastCmd = cmd[0];
            const ULONGLONG now = GetTickCount64();
            uint32_t result = RunCommand(cmd[1], cmd + 2);
            PlayerCharacter* pc = *g_thePlayer;
            if (g_listsDirty && pc && pc->parentCell) {
                SInt32 gold = 0;
                WriteLists(pc, gold);
                g_lastListBuild = now;
                g_listsDirty = false;
            }
            EnterCriticalSection(&g_page0Lock);
            g_ackResult = result;
            g_ackSeq = cmd[0];
            g_ackUptime = now - g_start;
            BeginPage0();
            Put<uint32_t>(L::kListSeqCopy, g_listSeq);
            Put<uint32_t>(L::kAckSeq, g_ackSeq);
            Put<uint32_t>(L::kAckResult, g_ackResult);
            Put<uint64_t>(L::kAckUptime, g_ackUptime);
            EndPage0();
            LeaveCriticalSection(&g_page0Lock);
            FlushViewOfFile(g_view, 0);
            if (cmd[1] != L::Ping) Log("cmd %u id=%u form=%08X arg1=%u -> %u", cmd[0], cmd[1], cmd[2], cmd[3], result);
        }
        g_cmdQueued = false;
    }
    void Dispose() override { delete this; }
};

// ---------------------------------------------------------------- pause (held by the app)
// The app holds a pause while its menus are open, like the game's own menus. It must refresh the
// heartbeat; if the app goes away the pause lets go by itself.
static bool g_paused = false;
static uint32_t g_lastBeat = 0;
static ULONGLONG g_beatAt = 0;
static bool g_pauseLogged = false;
static void ApplyPause(ULONGLONG now) {
    Main* main = *g_main;
    if (!main) return;
    uint32_t beat = Get<uint32_t>(L::kPauseBeat);
    if (beat != g_lastBeat) { g_lastBeat = beat; g_beatAt = now; }
    bool want = Get<uint8_t>(L::kPauseWant) != 0 && now - g_beatAt < 2000;
    if (want == g_paused) return;
    // Main: two event-sink vtables (0x00, 0x08), then quitGame..freezeNextFrame at 0x10..0x17 (verified on 1.7.104).
    volatile uint8_t* freeze = reinterpret_cast<uint8_t*>(main) + 0x16;   // Main::freezeTime
    if (!g_pauseLogged) {
        const uint8_t* f = reinterpret_cast<const uint8_t*>(main);
        for (int r = 0; r < 0x40; r += 16)
            Log("pause: Main+%02X %02X %02X %02X %02X %02X %02X %02X %02X  %02X %02X %02X %02X %02X %02X %02X %02X", r,
                f[r], f[r+1], f[r+2], f[r+3], f[r+4], f[r+5], f[r+6], f[r+7], f[r+8], f[r+9], f[r+10], f[r+11], f[r+12], f[r+13], f[r+14], f[r+15]);
        g_pauseLogged = true;
    }
    *freeze = want ? 1 : 0;
    g_paused = want;
    Log("pause %s", want ? "on" : "off");
}

// Whether the game is visibly up (main menu, loading screen or the world). Checked on the UI queue because
// the main-thread task queue (TickTask) often doesn't run on the main menu, which left the app's loading
// screen over a menu that was already up.
static std::atomic<bool> g_showing{false};
static std::atomic<bool> g_showQueued{false};

class ShowingTask : public UIDelegate_v1 {
public:
    void Run() override {
        if (!g_showing) {
            MenuManager* mm = MenuManager::GetSingleton();
            UIStringHolder* names = UIStringHolder::GetSingleton();
            if (mm && names && (mm->IsMenuOpen(&names->mainMenu) || mm->IsMenuOpen(&names->loadingMenu))) {
                g_showing = true;
                Log("game showing after %llu ms", GetTickCount64() - g_start);
            }
        }
        g_showQueued = false;
    }
    void Dispose() override { delete this; }
};

class TickTask : public TaskDelegate {
public:
    void Run() override {
        const ULONGLONG now = GetTickCount64();
        PlayerCharacter* pc = *g_thePlayer;
        const bool inGame = pc && pc->parentCell;
        g_inGame = inGame;

        // 1. Commands run on the UI task queue (see CmdTask), so they also work while paused.

        // 2. Lists: after actions, and once a second.
        static SInt32 gold = 0;
        uint32_t flags = L::fMmap;
        if (inGame && (g_listsDirty || now - g_lastListBuild > 1000)) {
            WriteLists(pc, gold);
            g_lastListBuild = now;
            g_listsDirty = false;
        }
        if (inGame && g_listSeq) flags |= L::fLists;

        if (inGame) ApplyHud(now);

        // Menus are checked on the UI queue (ShowingTask), which runs on the main menu; this covers being in game.
        if (inGame && !g_showing.exchange(true)) Log("game showing (in game) after %llu ms", now - g_start);
        if (g_showing) flags |= L::fShowing;

        // 3. Map markers, twice a second.
        if (inGame && (now - g_lastMarkers > 300 || g_refreshMarkers.exchange(false))) { WriteMarkers(pc, now); g_lastMarkers = now; }
        if (inGame && g_lastMarkers) flags |= L::fMarkers;

        // Quest journal, every 2 s.
        if (inGame && now - g_lastQuests > 2000) { WriteQuests(); g_lastQuests = now; }
        if (inGame && g_lastQuests) flags |= L::fQuests;

        // 4. Page 0: heartbeat, player, ack.
        EnterCriticalSection(&g_page0Lock);
        BeginPage0();
        Put<uint64_t>(L::kFrame, ++g_frame);
        Put<uint64_t>(L::kUptime, now - g_start);
        if (inGame) WritePlayer(pc, gold, flags);
        Put<uint32_t>(L::kListSeqCopy, g_listSeq);
        Put<uint32_t>(L::kAckSeq, g_ackSeq);
        Put<uint32_t>(L::kAckResult, g_ackResult);
        Put<uint64_t>(L::kAckUptime, g_ackUptime);
        Put<uint32_t>(L::kFlags, flags);
        EndPage0();
        LeaveCriticalSection(&g_page0Lock);
        FlushViewOfFile(g_view, 0);

        g_lastRun = GetTickCount64();
        g_taskQueued = false;
    }
    void Dispose() override { delete this; }
};

// Under SKSE: keep one compass task queued at all times. SKSE runs UI tasks once per frame, so the
// mirror refreshes every frame (the 30 Hz scheduler alone made the bottom-screen compass lag).
static DWORD WINAPI CompassPump(void*) {
    for (;;) {
        Sleep(4);
        if (g_inGame.load(std::memory_order_relaxed) && !g_compassQueued.exchange(true)) QueueUi(new CompassTaskOnce());
    }
}

static DWORD WINAPI Scheduler(void*) {
    const DWORD sleepMs = 1000 / g_tickHz;
    for (;;) {
        Sleep(sleepMs);
        const ULONGLONG tick = GetTickCount64();
        ApplyPause(tick);
        if (Get<uint32_t>(L::kCmd) != g_lastCmd) {
            bool q = false;
            if (g_cmdQueued.compare_exchange_strong(q, true)) QueueUi(new CmdTask());
        }
        if (!g_showing && !g_showQueued.exchange(true)) QueueUi(new ShowingTask());
        bool expected = false;
        if (g_taskQueued.compare_exchange_strong(expected, true)) { QueueMain(new TickTask()); continue; }

        // The game only runs SKSE tasks from its world loop, so on the main menu and loading
        // screens the queued tick waits. Keep the heartbeat going so the app knows Skyrim is alive.
        const ULONGLONG now = GetTickCount64();
        if (now - g_lastRun < 250) continue;
        EnterCriticalSection(&g_page0Lock);
        BeginPage0();
        Put<uint64_t>(L::kFrame, ++g_frame);
        Put<uint64_t>(L::kUptime, now - g_start);
        if (g_showing) Put<uint32_t>(L::kFlags, Get<uint32_t>(L::kFlags) | L::fShowing | L::fMmap);
        EndPage0();
        LeaveCriticalSection(&g_page0Lock);
        FlushViewOfFile(g_view, 0);
    }
}

// ---------------------------------------------------------------- queues and hooks (standalone)
static void QueueMain(TaskDelegate* t) {
    if (!g_standalone) { g_task->AddTask(t); return; }
    EnterCriticalSection(&g_qLock); g_mainQ.push_back(t); LeaveCriticalSection(&g_qLock);
}
static void QueueUi(UIDelegate_v1* t) {
    if (!g_standalone) { g_task->AddUITask(t); return; }
    EnterCriticalSection(&g_qLock); g_uiQ.push_back(t); LeaveCriticalSection(&g_qLock);
}
template <class T> static void Drain(std::vector<T*>& q) {
    std::vector<T*> run;
    EnterCriticalSection(&g_qLock); run.swap(q); LeaveCriticalSection(&g_qLock);
    for (T* t : run) { t->Run(); t->Dispose(); }
}

typedef void (*_ThisFn)(void*);
static _ThisFn g_origTasks = nullptr, g_origUi = nullptr;
static void HookTasks(void* pool) { g_origTasks(pool); Drain(g_mainQ); }
static CompassTask* g_frameCompass = nullptr;
static void HookUi(void* ui) {
    g_origUi(ui);
    Drain(g_uiQ);
    // Every rendered frame, so the bottom screen's compass moves exactly with the game's.
    if (g_inGame.load(std::memory_order_relaxed) && g_frameCompass) g_frameCompass->Run();
}

/** Executable memory within +-2 GB of the game, for jump stubs a 5-byte call can reach. */
static uint8_t* NearPage(uintptr_t at) {
    SYSTEM_INFO si; GetSystemInfo(&si);
    const uintptr_t g = si.dwAllocationGranularity;
    for (uintptr_t a = (at & ~(g - 1)) - g; a > at - 0x7FF00000; a -= g)
        if (void* p = VirtualAlloc(reinterpret_cast<void*>(a), 4096, MEM_RESERVE | MEM_COMMIT, PAGE_EXECUTE_READWRITE)) return static_cast<uint8_t*>(p);
    return nullptr;
}
static uint8_t* g_stubs = nullptr;
static size_t g_stubUsed = 0;

/** Points the `call rel32` at [site] to [fn]; returns the original target (0 if [site] isn't a call). */
static uintptr_t HookCall(uintptr_t site, void* fn) {
    uint8_t* s = reinterpret_cast<uint8_t*>(site);
    if (s[0] != 0xE8 || !g_stubs) return 0;
    const uintptr_t orig = site + 5 + *reinterpret_cast<int32_t*>(s + 1);
    uint8_t* st = g_stubs + g_stubUsed; g_stubUsed += 16;
    st[0] = 0xFF; st[1] = 0x25; *reinterpret_cast<uint32_t*>(st + 2) = 0;     // jmp [rip+0]
    *reinterpret_cast<uint64_t*>(st + 6) = reinterpret_cast<uint64_t>(fn);
    FlushInstructionCache(GetCurrentProcess(), st, 16);
    DWORD old;
    VirtualProtect(s, 5, PAGE_EXECUTE_READWRITE, &old);
    *reinterpret_cast<int32_t*>(s + 1) = static_cast<int32_t>(reinterpret_cast<intptr_t>(st) - static_cast<intptr_t>(site + 5));
    VirtualProtect(s, 5, old, &old);
    FlushInstructionCache(GetCurrentProcess(), s, 5);
    return orig;
}

/** The game's file version, packed like SKSE's RUNTIME_VERSION (0 if unreadable). */
static uint32_t ExeVersion() {
    wchar_t path[MAX_PATH * 2];
    GetModuleFileNameW(nullptr, path, MAX_PATH * 2);
    DWORD h = 0, n = GetFileVersionInfoSizeW(path, &h);
    if (!n) return 0;
    std::vector<uint8_t> buf(n);
    VS_FIXEDFILEINFO* fi = nullptr; UINT len = 0;
    if (!GetFileVersionInfoW(path, 0, n, buf.data()) || !VerQueryValueW(buf.data(), L"\\", reinterpret_cast<void**>(&fi), &len) || !fi) return 0;
    return MAKE_EXE_VERSION(HIWORD(fi->dwFileVersionMS), LOWORD(fi->dwFileVersionMS), HIWORD(fi->dwFileVersionLS));
}

/** The same call sites SKSE 2.3.1 hooks for Skyrim 1.7.104 (Hooks_Threads.cpp, Hooks_UI.cpp). */
static bool InstallHooks() {
    const uintptr_t base = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
    g_stubs = NearPage(base);
    const uintptr_t t1 = base + 0x00658870 + 0xBAE, t2 = base + 0x0065A3C0 + 0x1C, u1 = base + 0x01169D20 + 0xAB5;
    // Check all three first: never patch half the game.
    for (uintptr_t a : { t1, t2, u1 }) if (*reinterpret_cast<uint8_t*>(a) != 0xE8) { Log("hook site %llx is not a call", (unsigned long long)(a - base)); return false; }
    const uintptr_t o1 = *reinterpret_cast<int32_t*>(t1 + 1) + t1 + 5, o2 = *reinterpret_cast<int32_t*>(t2 + 1) + t2 + 5;
    if (o1 != o2) { Log("task hook sites disagree"); return false; }
    g_origTasks = reinterpret_cast<_ThisFn>(HookCall(t1, reinterpret_cast<void*>(&HookTasks)));
    HookCall(t2, reinterpret_cast<void*>(&HookTasks));
    g_origUi = reinterpret_cast<_ThisFn>(HookCall(u1, reinterpret_cast<void*>(&HookUi)));
    return g_origTasks && g_origUi;
}

// ---------------------------------------------------------------- setup
static std::wstring DirOf(const std::wstring& p) {
    size_t i = p.find_last_of(L"\\/");
    return i == std::wstring::npos ? L"." : p.substr(0, i);
}
static std::wstring ModulePath(HMODULE m) {
    wchar_t buf[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(m, buf, MAX_PATH * 2);
    return std::wstring(buf, n);
}
static void MakeDirs(const std::wstring& d) {
    if (d.empty() || CreateDirectoryW(d.c_str(), nullptr) || GetLastError() == ERROR_ALREADY_EXISTS) return;
    MakeDirs(DirOf(d));
    CreateDirectoryW(d.c_str(), nullptr);
}

static void OpenLog() {
    PWSTR docs = nullptr;
    std::wstring dir;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Documents, 0, nullptr, &docs))) {
        dir = std::wstring(docs) + L"\\My Games\\Skyrim Special Edition\\SKSE";
        CoTaskMemFree(docs);
    } else dir = DirOf(ModulePath(g_module));
    MakeDirs(dir);
    g_log = _wfopen((dir + L"\\SkyrimAYN.log").c_str(), L"w");
}

static bool OpenLink() {
    std::wstring ini = DirOf(ModulePath(g_module)) + L"\\SkyrimAYN.ini";
    wchar_t buf[1024];
    GetPrivateProfileStringW(L"Link", L"SharedFile", L"", buf, 1024, ini.c_str());
    g_path = buf[0] ? buf : DirOf(ModulePath(nullptr)) + L"\\SkyrimAYN\\state.bin";
    UINT hz = GetPrivateProfileIntW(L"Link", L"TickHz", 30, ini.c_str());
    g_tickHz = hz < 5 ? 5 : (hz > 60 ? 60 : hz);

    MakeDirs(DirOf(g_path));
    g_file = CreateFileW(g_path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (g_file == INVALID_HANDLE_VALUE) { Log("CreateFileW failed: %lu", GetLastError()); return false; }
    LARGE_INTEGER end; end.QuadPart = L::kTotal;
    SetFilePointerEx(g_file, end, nullptr, FILE_BEGIN);
    SetEndOfFile(g_file);
    g_mapping = CreateFileMappingW(g_file, nullptr, PAGE_READWRITE, 0, L::kTotal, nullptr);
    if (g_mapping) g_view = static_cast<uint8_t*>(MapViewOfFile(g_mapping, FILE_MAP_ALL_ACCESS, 0, 0, L::kTotal));
    if (!g_view) { Log("mmap failed: %lu", GetLastError()); return false; }

    // Fresh header; keep page 1 (commands) as the app left it.
    memset(g_view, 0, L::kPage);
    memset(g_view + L::kLists, 0, L::kTotal - L::kLists);
    Put<uint32_t>(0, L::kMagic);
    Put<uint16_t>(4, L::kVersion);
    Put<uint16_t>(6, 64);
    Put<uint32_t>(8, L::kTotal);
    Put<uint32_t>(L::kPid, GetCurrentProcessId());
    Put<uint32_t>(L::kRuntime, g_runtime);
    Put<uint32_t>(L::kSkse, g_skse);
    Put<uint32_t>(L::kTickHz, g_tickHz);
    g_lastCmd = g_ackSeq = Get<uint32_t>(L::kCmd);  // don't replay an old command
    return true;
}

static void StartLink() {
    if (!OpenLink()) { Log("link unavailable, plugin idle"); return; }
    Log("link: %ls at %u Hz", g_path.c_str(), g_tickHz);
    InitializeCriticalSection(&g_page0Lock);
    g_lastRun = GetTickCount64();
    HANDLE t = CreateThread(nullptr, 0, Scheduler, nullptr, 0, nullptr);
    if (t) CloseHandle(t);
    if (!g_standalone) { HANDLE c = CreateThread(nullptr, 0, CompassPump, nullptr, 0, nullptr); if (c) CloseHandle(c); }
}

/** Called by the app's dinput8.dll when SKSE isn't running: the companion works without it. */
extern "C" __declspec(dllexport) void SkyrimAYN_Standalone() {
    if (g_started.exchange(true)) return;
    g_runtime = RUNTIME_VERSION_1_7_104;
    g_skse = 0;
    g_start = GetTickCount64();
    OpenLog();
    Log("SkyrimAYN 0.19 loading without SKSE");
    // Every address here is for 1.7.104: on any other version, stay out of the game entirely.
    const uint32_t ver = ExeVersion();
    if (ver != RUNTIME_VERSION_1_7_104) { Log("game version %08X is not 1.7.104: companion idle", ver); return; }
    InitializeCriticalSection(&g_qLock);
    g_standalone = true;
    g_frameCompass = new CompassTask();
    if (!InstallHooks()) { Log("hooks unavailable, plugin idle"); return; }
    StartLink();
}

extern "C" __declspec(dllexport) bool SKSEPlugin_Load(const SKSEInterface* skse) {
    if (g_started.exchange(true)) return true;   // already running standalone
    g_runtime = skse->runtimeVersion;
    g_skse = skse->skseVersion;
    g_handle = skse->GetPluginHandle();
    g_start = GetTickCount64();
    OpenLog();
    Log("SkyrimAYN 0.19 loading: runtime=%08X skse=%08X", g_runtime, g_skse);

    g_task = static_cast<SKSETaskInterface*>(skse->QueryInterface(kInterface_Task));
    if (!g_task) { Log("no task interface, plugin idle"); return true; }
    StartLink();
    return true;
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) { g_module = inst; DisableThreadLibraryCalls(inst); }
    return TRUE;
}
