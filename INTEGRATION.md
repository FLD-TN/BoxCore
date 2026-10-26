# BoxCore — Integration Guide (BoxCore 1.5.0, API 1.3.0)

This is the only document you need to integrate a plugin with BoxCore. Do not modify BoxCore; use the API below.

BoxCore 1.1.0 changed how the multiplier is computed on the server side, but the boost API did not change: it still returns one integer `>= 1`.

BoxCore 1.3.0 adds the **GEM** currency and API `com.dex:boxcore-api:1.1.0`, which only **adds** `GemService` and `GemResult` (§10). `BoostService` and `BoostTypes` are unchanged: plugins built against `boxcore-api-1.0.0` need no change and no rebuild. Use 1.1.0 only if you need gems.

BoxCore 1.4.0 (API `com.dex:boxcore-api:1.2.0`) allows **decimal multipliers** (one decimal, e.g. x1.5) for the types declared with `decimal: true` in BoxCore's `config.yml` (by default only `quest`; `mining` stays whole). The API only **adds** default methods (`getMultiplierTenths`, `getMultiplierExact`) and the helper class `Multipliers` (§11). `getMultiplier` keeps its signature and returns the **whole part** (x1.5 → 1), so a plugin built against an older API never gives more than the boost; it only misses the decimal part. A plugin whose type is whole (`mining`) needs no change.

BoxCore 1.5.0 (API `com.dex:boxcore-api:1.3.0`) adds **clan boosts** (§12). The API only **adds** types and default methods; a plugin that only reads multipliers needs no change, the clan boost is already in `getMultiplier`.

## 1. What BoxCore does

BoxCore is the core plugin of the BoxPvP server. Version 1 provides **boosts**: a multiplier per player and per *boost type* (`mining`, `quest`, …), a whole number or, for decimal types, a number with one decimal. A player's multiplier for a type comes from three sources: a rank permission, the player's active personal boost bottle, and a server-wide (global) boost started by an admin. Your plugin asks BoxCore for the multiplier at the moment it grants something and multiplies the amount.

> **Rule:** a player's multiplier is `max(rank + active personal bottle, global)` (a player has at most one active bottle per type), capped per type; absent sources count as 0 and the result is at least x1. It is a whole number, or has one decimal (x1.5) for a type declared with `decimal: true` (by default only `quest`); read it with `getMultiplierTenths` (§11).

x1 means "no boost". All combination rules, timing, expiry, display and storage are handled by BoxCore; your plugin only reads the final value.

## 2. Dependency

| Item | Value |
|---|---|
| API jar | `boxcore-api-1.2.0.jar` (whole boosts only: `1.0.0` still works) |
| Maven coordinates | `com.dex:boxcore-api:1.2.0` |
| Java | 21 |
| Repository | `mavenLocal()` (installed with `publishToMavenLocal` from the BoxCore repository) |

The API is **compile-only**. Never shade or relocate it into your jar: the BoxCore plugin jar provides these exact classes at runtime.

**Maven**

```xml
<dependency>
    <groupId>com.dex</groupId>
    <artifactId>boxcore-api</artifactId>
    <version>1.1.0</version>
    <scope>provided</scope>
</dependency>
```

Maven resolves `~/.m2/repository` automatically. If you only have the jar file, install it first:

```
mvn install:install-file -Dfile=boxcore-api-1.1.0.jar -DgroupId=com.dex -DartifactId=boxcore-api -Dversion=1.1.0 -Dpackaging=jar
```

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("com.dex:boxcore-api:1.1.0")
}
```

## 3. `plugin.yml`

```yaml
softdepend: [BoxCore]
```

`softdepend` (not `depend`): your plugin must keep working unchanged, with multiplier 1, when BoxCore is absent. It also guarantees BoxCore is enabled before your `onEnable` when it is present.

## 4. API source and guarantees

The boost API is the two classes below, in package `com.dex.boxcore.api`. The gem classes are in §10.

```java
package com.dex.boxcore.api;

/** Boost type ids shipped in BoxCore's default config. */
public final class BoostTypes {
    public static final String MINING = "mining";
    public static final String QUEST  = "quest";
    private BoostTypes() {}
}
```

```java
package com.dex.boxcore.api;

import java.util.UUID;

public interface BoostService {

    /**
     * Whole part of the effective multiplier (always >= 1) of an ONLINE player for a boost type: x1.5 -> 1.
     * Exact for types declared with decimal: false. Returns 1 if the player is offline or the type is not declared.
     * Thread-safe, O(1), allocation-free: reads a precomputed cache.
     */
    int getMultiplier(UUID playerId, String type);

    /** Effective multiplier in tenths (15 = x1.5), always >= 10. Same guarantees. @since 1.2.0 */
    default int getMultiplierTenths(UUID playerId, String type) {
        return getMultiplier(playerId, type) * Multipliers.ONE;
    }

    /** Effective multiplier as a double (1.5), always >= 1. @since 1.2.0 */
    default double getMultiplierExact(UUID playerId, String type) {
        return getMultiplierTenths(playerId, type) / 10.0;
    }

    /** True if the type is declared in BoxCore's config. */
    boolean isType(String type);

    /**
     * Convenience: floor(amount * multiplier) with the exact multiplier, computed in long,
     * saturating at Long.MAX_VALUE instead of overflowing. amount <= 0 is returned unchanged.
     */
    default long multiply(UUID playerId, String type, long amount) {
        return Multipliers.floor(amount, getMultiplierTenths(playerId, type));
    }
}
```

Guarantees:

- **Never throws**, for any input. A `null` player id or `null` type returns `1` (BoxCore logs this at most once per minute).
- **Thread-safe**: `getMultiplier` / `multiply` may be called from any thread, including async threads. Each call is a lookup in a precomputed concurrent map: O(1), no allocation. Calling it for every block break (~1 000 calls/s) is negligible.
- **Do not cache the value.** It changes when boosts start or expire and when rank permissions are refreshed. Call it at the moment you grant something.
- Offline player, unknown player or undeclared type → `1`.
- **Stable API.** The API is versioned with the plugin. Later versions may only add methods with default implementations; existing signatures never change.

Resolve the service through Bukkit's services manager:

```java
BoostService service = Bukkit.getServicesManager().load(BoostService.class);
```

BoxCore registers it in its `onEnable` (after loading config and data) and unregisters it in its `onDisable`.

## 5. Recommended hook pattern

Copy this class into your plugin. It is the **only** class that references `com.dex.boxcore.api`.

```java
// Only this class references com.dex.boxcore.api. Load it only if BoxCore is enabled.
public final class BoxCoreHook {
    private static BoostService service;

    /** Call once in onEnable (and after reload). */
    public static void init() {
        service = Bukkit.getPluginManager().isPluginEnabled("BoxCore")
                ? Bukkit.getServicesManager().load(BoostService.class)
                : null;
    }

    public static long multiply(UUID player, String type, long amount) {
        return service == null ? amount : service.multiply(player, type, amount);
    }

    public static int multiplier(UUID player, String type) {
        return service == null ? 1 : service.getMultiplier(player, type);
    }
}
```

**Guard class loading.** When BoxCore is not installed, the API classes do not exist at runtime, and loading `BoxCoreHook` would throw `NoClassDefFoundError`. Your plugin must never touch `BoxCoreHook` unless BoxCore is enabled. Keep a boolean in your own code (a class that does *not* reference the API) and route every call through it:

```java
// in your plugin class (does not import com.dex.boxcore.api)
private boolean boxCore;

@Override
public void onEnable() {
    boxCore = false;
    if (Bukkit.getPluginManager().isPluginEnabled("BoxCore")) {
        try {
            BoxCoreHook.init();
            boxCore = true;
        } catch (NoClassDefFoundError e) {
            getLogger().warning("BoxCore found but its API could not be loaded, boosts disabled");
        }
    }
}

public long boosted(UUID player, String type, long amount) {
    return boxCore ? BoxCoreHook.multiply(player, type, amount) : amount;
}
```

Also:

- Use the type id as a **string literal** (`"mining"`, `"quest"`) or your own constant outside the hook class. Referencing `BoostTypes.MINING` from other classes is fine at compile time (it is a compile-time constant and gets inlined), but keeping all API references inside `BoxCoreHook` is the simplest rule to follow.
- If your plugin has a reload command, call `BoxCoreHook.init()` again there.
- If BoxCore is disabled while your plugin runs, the stored service simply returns `1` from then on (BoxCore clears its data on disable). Call `init()` again after BoxCore is enabled again.

## 6. Applying a multiplier to items

Multiply the **total amount** once, in `long`, then split it into stacks that respect the item's max stack size. Never set an `ItemStack` amount above `getMaxStackSize()`.

```java
long total = boosted(player.getUniqueId(), "mining", drop.getAmount());
int max = drop.getMaxStackSize();
while (total > 0) {
    int n = (int) Math.min(total, max);
    ItemStack stack = drop.clone();
    stack.setAmount(n);
    // give or drop 'stack' (overflowing inventories should drop at the player's feet)
    total -= n;
}
```

For very large totals, cap what you actually spawn with your own safety limit; `multiply` itself saturates at `Long.MAX_VALUE` rather than overflowing.

## 7. Applying a multiplier to money

Multiply the base amount **once per reward**, then use that single multiplied value everywhere: the payment, any totals, logs and the messages shown to the player. Do not call BoxCore again for the message — the value could change between the two calls.

```java
long base = 1000; // base reward in whole units
long reward = boosted(player.getUniqueId(), "quest", base);
economy.depositPlayer(player, reward);
player.sendMessage("Reward: " + reward);
```

If your amounts are decimals (`double`), convert to a `long` in the smallest unit you use (for example cents) before calling `multiply`, or multiply by `getMultiplier(...)` yourself — the multiplier of a whole type is always an integer. For a decimal type see §11.

## 8. Testing the integration

All commands work from the server console. `<duration>` accepts `30s`, `10m`, `1h30m`, `2d` (up to the type's configured maximum, 24h by default); `<multiplier>` is an integer from 2 to the type's maximum (100 by default), or for a decimal type a number from 1.1 with at most one decimal (`1.5`, `1,5`).

Run the steps in order. The rank permission `boxcore.boost.mining.3` is kept during all steps after it is granted.

| Step | Command | Expected `mining` |
|---|---|---|
| Baseline | `/boxcore check <player>` | 1 |
| Rank source | permission `boxcore.boost.mining.3` (e.g. LuckPerms), wait up to 30 s | 3 |
| Global boost | `/boxcore global start mining 5 10m` | 5 (global 5 > rank 3) |
| Personal boost | `/boxcore give <player> mining 10 5m`, right-click the bottle | 13 (rank 3 + bottle 10 > global 5) |
| Stop | `/boxcore global stop mining` | 13 (unchanged, rank + bottle) |
| Bottle expires | wait 5 min | 3 (rank only) |
| Without BoxCore | remove `BoxCore.jar`, restart | your plugin enables normally, multipliers are 1, no errors |

Decimal type (`quest`): rank permission `boxcore.boost.quest.1_5` + `/boxcore give <player> quest 2 5m` (bottle used) → `getMultiplierTenths` = 35, `getMultiplierExact` = 3.5, `getMultiplier` = 3.

`/boxcore check <player>` prints, per type, the rank value, the active bottle with its remaining time, the global boost with its remaining time, the rank + bottle sum and the effective value.

Every value `/boxcore check` shows as effective is exactly what `getMultiplier` returns. With PlaceholderAPI installed, `%boxcore_multiple_mining%` and `%boxcore_multiple_quest%` show the same values (`1.5` for a decimal value, with the separator of `multiplier-decimal-separator`); `%boxcore_multiple_<type>_raw%` always uses a dot.

## 9. Type ids per consumer

| Consumer | Type id | Multiplies |
|---|---|---|
| CataMines (`CataMines`) | `mining` | items obtained from mining: loot-table drops and vanilla drops of blocks broken inside a mine |
| BoxQuest (`BoxQuest`) | `quest` (decimal) | the money reward and the `{boost:N}` amounts of reward commands when a quest completes |

Use only your own type id. Types are declared in BoxCore's `config.yml`; `isType(type)` tells you whether it is declared on this server (an undeclared type simply yields multiplier 1).

## 10. Gems (API 1.1.0)

BoxCore 1.3.0 owns a server currency called **GEM**: integer balances per player, stored by BoxCore (SQLite). It is **not** a Vault economy; Vault stays with the server's main money plugin. Players earn gems through admin/console commands (`boxcore gem give <player> <amount> [reason] [-s]`, e.g. from quest rewards) and your plugin can spend or grant them through `GemService`.

```java
package com.dex.boxcore.api;

import java.util.UUID;

public interface GemService {
    /** Balance of an online, loaded player, or -1 if not loaded (offline, still loading, or storage disabled). Any thread. */
    long balance(UUID playerId);
    /** True if balance() returns a real value for this player. Any thread. */
    boolean isLoaded(UUID playerId);
    /** Adds amount (>= 1). Main thread only (throws IllegalStateException otherwise). */
    GemResult deposit(UUID playerId, long amount, String reason);
    /** Removes amount (>= 1) only if the balance is sufficient; all or nothing. Main thread only. */
    GemResult withdraw(UUID playerId, long amount, String reason);
    /** Configured display name, e.g. "Gem". */
    String displayName();
    /** Formats an amount the way BoxCore displays it, e.g. "1.250". */
    String format(long amount);
}

public enum GemResult { SUCCESS, INSUFFICIENT_FUNDS, BALANCE_LIMIT, NOT_LOADED, INVALID_AMOUNT }
```

Guarantees:

- Amounts are `long` integers. A balance is never negative and never above the server's maximum (at most 10^12).
- **Only online players whose data is loaded** can be read or changed. Data loads before the player joins; a player can still be "not loaded" for a moment if the database was slow. Treat `NOT_LOADED` as "try again later", never as a zero balance.
- `withdraw` checks and subtracts in **one call**. Do not call `balance()` and then decide: call `withdraw` and read the result.
- A `deposit` of an amount you just withdrew successfully in the same tick never fails with `BALANCE_LIMIT` or `INSUFFICIENT_FUNDS`: use it to roll back.
- `SUCCESS` is final for you: BoxCore applied the change in memory and queued it to disk.
- `deposit` / `withdraw` must run on the **main thread**; `balance` / `isLoaded` may run on any thread.
- After BoxCore disables (server stop, plugman), a `GemService` reference you kept answers `-1` / `NOT_LOADED` to everything; it never changes a balance again. Call `init()` again on `PluginEnableEvent` of BoxCore to get the new service.

**Reason**: a short machine-readable origin written to BoxCore's transaction log, e.g. `shopkeeper:tier_shop/t1`. Allowed characters `[a-z0-9_:./-]`, at most 64; anything else is lower-cased, replaced by `_` and truncated. Use `<your plugin>:<detail>`. A null or blank reason returns `INVALID_AMOUNT`.

**Hook pattern**: the same as §5. Keep every `GemService` reference inside your hook class and load it only when BoxCore is enabled:

```java
// Only this class references com.dex.boxcore.api. Load it only if BoxCore is enabled.
public final class BoxCoreGems {
    private static GemService gems;

    public static void init() {
        gems = Bukkit.getPluginManager().isPluginEnabled("BoxCore")
                ? Bukkit.getServicesManager().load(GemService.class)   // null on BoxCore < 1.3.0
                : null;
    }

    public static boolean ready(UUID player) {
        return gems != null && gems.isLoaded(player);
    }

    /** Charges a player; false if not loaded or not enough gems (nothing changed). Main thread. */
    public static boolean charge(UUID player, long amount, String reason) {
        return gems != null && gems.withdraw(player, amount, reason) == GemResult.SUCCESS;
    }

    /** Gives back a charge that must be undone (e.g. the rest of a transaction failed). Main thread. */
    public static void refund(UUID player, long amount, String reason) {
        if (gems == null) return;
        GemResult result = gems.deposit(player, amount, reason + ":rollback");
        if (result != GemResult.SUCCESS) {
            Bukkit.getLogger().severe("Gem refund failed (" + result + "): " + player + " " + amount + ", compensate manually");
        }
    }
}
```

Withdraw and roll back:

```java
if (!BoxCoreGems.charge(player.getUniqueId(), 20, "shopkeeper:tier_shop/t1")) {
    // not enough gems (or not loaded): refuse, nothing was taken
    return;
}
if (!giveItems(player)) {
    BoxCoreGems.refund(player.getUniqueId(), 20, "shopkeeper:tier_shop/t1");
}
```

Testing: `/boxcore gem give|take|set <player> <amount>` and `/boxcore gem check <player>` (balance and the last 5 transactions with their reasons). With PlaceholderAPI: `%boxcore_gem%`, `%boxcore_gem_formatted%`.

## 11. Decimal multipliers (API 1.2.0)

A type declared with `decimal: true` (by default only `quest`) may have one decimal: x1.1, x1.5, x12.5. Read it in **tenths** (`15` = x1.5) so no floating point is involved:

```java
int tenths = service.getMultiplierTenths(player, "quest");   // 15
```

`com.dex.boxcore.api.Multipliers` (static, never throws, `amount <= 0` unchanged, saturates at `Long.MAX_VALUE`):

| Method | Example |
|---|---|
| `parse(String)` | `"1.5"` / `"1,5"` → 15, `"2"` → 20, invalid → -1 |
| `format(int)` / `format(int, char)` | 15 → `"1.5"`, 20 → `"2"`, `format(15, ',')` → `"1,5"` |
| `floor(long amount, int tenths)` | `floor(3, 15)` → 4 (4.5 rounded down) |
| `halfUp(long amount, int tenths)` | `halfUp(3, 15)` → 5, `halfUp(5, 15)` → 8 (7.5) |

Choose the rounding that is fair for your reward: a single item per action rounded down would make x1.5 useless (`floor(1, 15)` = 1).

**Runtime compatibility.** The default methods and `Multipliers` exist only when the server runs BoxCore 1.4.0 or later. If your plugin may run with an older BoxCore, call `getMultiplierTenths` inside your hook class, fall back to `getMultiplier(...) * 10` on `NoSuchMethodError`, and do not use `Multipliers` outside the hook:

```java
public static int multiplierTenths(UUID player, String type) {
    if (service == null) return 10;
    if (tenthsAvailable) {
        try {
            return service.getMultiplierTenths(player, type);
        } catch (NoSuchMethodError oldBoxCore) {
            tenthsAvailable = false;   // BoxCore < 1.4.0: whole multipliers only
        }
    }
    return service.getMultiplier(player, type) * 10;
}
```

Rank permission of a decimal type: `boxcore.boost.<type>.<whole>_<digit>` (`boxcore.boost.quest.1_5` = x1.5); a dot cannot be used because it separates the permission node.

## 12. Clan boosts (API 1.3.0, BoxCore 1.5.0)

A clan boost applies to every member of a clan: `effective = min(max(rank + bottle, global, clan), cap)`. It never adds
up with anything, it counts down in real time (members online or not), and `getMultiplier` already includes it, so a
plugin that only **reads** multipliers (CataMines, BoxQuest) needs no change.

Only the clan plugin (BoxClans) talks to this part of the API. Every new method is a `default` method: implementations
older than 1.3.0 answer "unavailable".

```java
BoostService boosts = Bukkit.getServicesManager().load(BoostService.class);
boosts.registerClanProvider(plugin, new ClanProvider() {        // once, on enable; removed when plugin is disabled
    public UUID clanOf(UUID player) { ... }                     // null = no clan
    public boolean canUseClanBoost(UUID player) { ... }         // Minh Chủ / Phó Bang
    public String clanName(UUID clan) { ... }                   // legacy § text
    public Collection<UUID> onlineMembers(UUID clan) { ... }
});
boosts.refreshClanOf(player);   // after join / leave / kick: the multiplier changes at once (any thread)
boosts.clearClan(clan);         // clan disbanded: its boosts are deleted (any thread)
```

Starting a boost (main thread only; no permission check, the caller decides):

```java
ClanBoostResult r = boosts.startClanBoost(clan, "mining", 20, 3600, false, player.getName());
switch (r.status()) {
    case STARTED, EXTENDED, REPLACED -> { /* done, the online members were told; consume the bottle */ }
    case CONFIRM_REPLACE, CONFIRM_CAPPED -> { /* ask the player, then call again with confirmed = true */ }
    case LOWER, FULL, INVALID, UNAVAILABLE -> { /* refused, keep the bottle */ }
}
```

| Running boost of the type | New boost | Result |
|---|---|---|
| none (or expired) | any | `STARTED` |
| same multiplier | any duration | `EXTENDED`: the duration is added, up to `max-duration`. If some would be cut: `CONFIRM_CAPPED` first (`lostSeconds`); already at `max-duration`: `FULL` |
| lower multiplier | higher | `CONFIRM_REPLACE` (`lostSeconds` = remaining time of the old one), then `REPLACED` |
| higher multiplier | lower | `LOWER` |

Other calls: `stopClanBoost(clan, type)`, `clanBoost(clan, type)` → `Optional<ActiveBoost>`, and
`readBottle(item)` → `Optional<BottleInfo>` (type, tenths, duration, `clan()` true for a clan bottle) to recognise
BoxCore bottles, e.g. in a clan storage.

Every change of a clan boost fires `com.dex.boxcore.api.event.ClanBoostEvent` on the main thread after it happened
(`action()`: `STARTED`, `EXTENDED`, `REPLACED`, `STOPPED`, `EXPIRED`; `clan()`, `type()`, `tenths()`, `oldTenths()`,
`expiresAt()`, `addedSeconds()`, `actor()` = player name or `null`), whatever caused it: a bottle in hand, the API, the tick.
