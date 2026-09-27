# BoxCore — Integration Guide (BoxCore 1.1.0, API 1.0.0)

This is the only document you need to integrate a plugin with BoxCore. Do not modify BoxCore; use the API below.

BoxCore 1.1.0 changed how the multiplier is computed on the server side, but the API did not change: it is still `com.dex:boxcore-api:1.0.0` and it still returns one integer `>= 1`. Plugins built against `boxcore-api-1.0.0` need no change.

## 1. What BoxCore does

BoxCore is the core plugin of the BoxPvP server. Version 1 provides **boosts**: an integer multiplier per player and per *boost type* (`mining`, `quest`, …). A player's multiplier for a type comes from three sources: a rank permission, the player's active personal boost bottle, and a server-wide (global) boost started by an admin. Your plugin asks BoxCore for the multiplier at the moment it grants something and multiplies the amount.

> **Rule:** a player's multiplier is `max(rank + active personal bottle, global)` (a player has at most one active bottle per type), capped per type; absent sources count as 0 and the result is at least 1. It is always an integer.

`1` means "no boost". All combination rules, timing, expiry, display and storage are handled by BoxCore; your plugin only reads the final integer.

## 2. Dependency

| Item | Value |
|---|---|
| API jar | `boxcore-api-1.0.0.jar` |
| Maven coordinates | `com.dex:boxcore-api:1.0.0` |
| Java | 21 |
| Repository | `mavenLocal()` (installed with `publishToMavenLocal` from the BoxCore repository) |

The API is **compile-only**. Never shade or relocate it into your jar: the BoxCore plugin jar provides these exact classes at runtime.

**Maven**

```xml
<dependency>
    <groupId>com.dex</groupId>
    <artifactId>boxcore-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

Maven resolves `~/.m2/repository` automatically. If you only have the jar file, install it first:

```
mvn install:install-file -Dfile=boxcore-api-1.0.0.jar -DgroupId=com.dex -DartifactId=boxcore-api -Dversion=1.0.0 -Dpackaging=jar
```

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("com.dex:boxcore-api:1.0.0")
}
```

## 3. `plugin.yml`

```yaml
softdepend: [BoxCore]
```

`softdepend` (not `depend`): your plugin must keep working unchanged, with multiplier 1, when BoxCore is absent. It also guarantees BoxCore is enabled before your `onEnable` when it is present.

## 4. API source and guarantees

The API contains exactly two classes in package `com.dex.boxcore.api`.

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
     * Effective multiplier (always >= 1) of an ONLINE player for a boost type.
     * Returns 1 if the player is offline or the type is not declared.
     * Thread-safe, O(1), allocation-free: reads a precomputed cache.
     */
    int getMultiplier(UUID playerId, String type);

    /** True if the type is declared in BoxCore's config. */
    boolean isType(String type);

    /**
     * Convenience: amount * getMultiplier(playerId, type), computed in long,
     * saturating at Long.MAX_VALUE instead of overflowing. amount <= 0 is returned unchanged.
     */
    default long multiply(UUID playerId, String type, long amount) {
        if (amount <= 0) return amount;
        int m = getMultiplier(playerId, type);
        return m <= 1 ? amount : (amount > Long.MAX_VALUE / m ? Long.MAX_VALUE : amount * m);
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

If your amounts are decimals (`double`), convert to a `long` in the smallest unit you use (for example cents) before calling `multiply`, or multiply by `getMultiplier(...)` yourself — the multiplier is always an integer.

## 8. Testing the integration

All commands work from the server console. `<duration>` accepts `30s`, `10m`, `1h30m`, `2d` (up to the type's configured maximum, 24h by default); `<multiplier>` is an integer from 2 to the type's maximum (100 by default).

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

`/boxcore check <player>` prints, per type, the rank value, the active bottle with its remaining time, the global boost with its remaining time, the rank + bottle sum and the effective value.

Every value `/boxcore check` shows as effective is exactly what `getMultiplier` returns. With PlaceholderAPI installed, `%boxcore_multiple_mining%` and `%boxcore_multiple_quest%` show the same values.

## 9. Type ids per consumer

| Consumer | Type id | Multiplies |
|---|---|---|
| CataMines (`CataMines`) | `mining` | items obtained from mining: loot-table drops and vanilla drops of blocks broken inside a mine |
| BoxQuest (`BoxQuest`) | `quest` | the money reward when a quest completes |

Use only your own type id. Types are declared in BoxCore's `config.yml`; `isType(type)` tells you whether it is declared on this server (an undeclared type simply yields multiplier 1).
