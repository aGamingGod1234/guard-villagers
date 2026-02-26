# Guard Villagers

Fabric mod for Minecraft `1.21.11` that adds recruitable and naturally spawned village guards.

## Requirements

- Java `21`
- Minecraft `1.21.11`
- Fabric Loader `>= 0.18.2`
- Fabric API `0.139.4+1.21.11`

## Developer Commands

Windows (PowerShell/cmd):

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat build
```

macOS/Linux:

```bash
./gradlew runClient
./gradlew runServer
./gradlew build
```

Built jars are written to `build/libs/`.

## In-Game Commands

All command roots are registered as:

- `/guards shop` - Open the Guard Shop UI.
- `/guards tactics` - Open the Guard Tactics UI.
- `/guards stay` - Order all owned guards to hold position.
- `/guards follow` - Order all owned guards to follow again.
- `/guards behavior perimeter`
- `/guards behavior bodyguard`
- `/guards behavior crowd_control`
- `/guards behavior offensive`
- `/guards behavior defensive`
- `/guards zone <radius>` - Assign current location as home zone for nearby owned guards (`8` to `128`).
- `/guards hierarchy add_role` - Add a hierarchy row.
- `/guards hierarchy rename <row> <name>` - Rename a hierarchy row with a custom label.
- `/guards count` - Show how many guards you currently own.

## How It Works

### 1) Village guard lifecycle

- Natural guards are maintained in villages on server ticks.
- Spawn caps are calculated from village density, doors, beds, and prior village state.
- Natural guards are assigned village home zones and defensive defaults.

### 2) Hiring and ownership

- Open `/guards shop` and buy a guard with emerald blocks.
- You can also claim an unowned guard by right-clicking it with an emerald (if your reputation is trusted).
- Owned guards store owner/squad/home data and persist across world saves.

### 3) Guard control and tactics

- Behavior modes: perimeter, bodyguard, crowd control, offensive, defensive.
- Follow/stay stances for owner command control.
- Home zones: guards can be anchored to patrol within a configurable radius.
- Tactics UI now centers on zones + hierarchy: top-down chunk painting, role rows, row columns mapped to zone colors, and drag-style guard reassignment.
- In hierarchy mode, use `Shift + Right-click` on a row header to type a custom role name directly in the tactics UI.
- Owned guards now show a live hierarchy badge above their head (`R# C# role Lv#`) for quick control and debugging.

### 4) Whistle controls

- Right-click in air: open tactics screen.
- Sneak + right-click in air: rally nearby squad leaders.
- Right-click an owned guard: assign/update home zone.
- Sneak + right-click an owned guard: increase patrol radius (clamped).

### 5) Reputation and economy

- Reputation changes from trade, villager harm, guard harm, and raid/hostile combat outcomes.
- Reputation modifies guard purchase prices.
- Low trust blocks hiring; very low trust can make guards hostile to their owner.
- Shop upgrades improve armor odds, weapon quality, and passive healing.

## License

Licensed under `CC0-1.0` (see `LICENSE`).
