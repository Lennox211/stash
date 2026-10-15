# Stash ESP (Fabric, Minecraft 26.2, Java 25)

Client-Mod: zeigt durch Wände (gelbe Umrandung + gelbe Linie vom Fadenkreuz) bis 32 Chunks:
Kisten, Trapped Chests, Enderkisten, Fässer, Shulker, Trichter, Spender/Werfer,
Rüstungsständer, Loren mit Kiste/Trichter, alle Schienen, Redstone-Teile (Kabel, Repeater,
Comparator, Observer, Kolben, Lampen, TNT, ...).
Tasten (änderbar unter Steuerung > "Stash ESP"): **J** = ESP an/aus, **V** = Freecam an/aus (WASD, Leertaste hoch, Shift runter, Sprint = schneller). Oben links zeigt eine Anzeige, was aktiv ist. Spawner werden rot markiert, alles andere gelb.

## Bauen
1. JDK 25 installieren.
2. Am einfachsten: Fabric-Template für 26.2 generieren (https://fabricmc.net/develop/template),
   damit du den Gradle-Wrapper hast, und dann `src/` + `build.gradle` + `gradle.properties`
   + `settings.gradle` durch die aus diesem Ordner ersetzen.
3. `./gradlew build` -> `build/libs/stash-esp-1.0.0.jar` in den `mods`-Ordner (+ Fabric API).

## Anpassen
- Ziele: `TargetScanner` (BLOCK_ENTITY_TYPES, ENTITY_TYPES, isInterestingState)
- Farbe/Limits/Linienstärke: `EspRenderer` (Konstanten oben)
- Reichweite: `StashEspClient.RANGE_CHUNKS`
