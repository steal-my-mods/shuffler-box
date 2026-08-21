# Shuffler Box: repo guide

A standalone mod for **Minecraft 1.21.1 / NeoForge 21.1+**, no other mods required. A shulker box
that hands out a weighted-random block every time you place one while it is in your off hand.

## Commands

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # automated in-world tests -- the real check
python3 tools/generate_textures.py        # redraw the block's six faces
python3 tools/generate_test_structure.py  # redraw the GameTest platform
python3 tools/generate_logo.py            # redraw the in-jar 256px icon
./gradlew publishMods        # upload to CurseForge and GitHub Releases
./gradlew publishMods -PdryRun=true   # rehearse it without uploading anything
```

JDK 21 required. `gradle/gradle-daemon-jvm.properties` pins the daemon to it, so the commands work
without setting `JAVA_HOME` even when the default `java` is newer. Don't delete that file. There
is no unit-test suite; correctness is covered by GameTests in
`com.shufflerbox.test.ShufflerBoxGameTests`. Run them after any change to the draw, the drain, or
the placement path.

## Distribution

Releases go out through `publishMods` (`me.modmuss50.mod-publish-plugin`), driven by
`.github/workflows/release.yml` on a `v*` tag. Things in there that are decisions, not accidents:

- **`minecraft_version_range` is `[1.21.1,1.21.2)`,** not the MDK's default `[1.21.1,1.22)`. The
  mod is built against 1.21.1's block-entity component and interaction APIs; the wider range would
  let it install on 1.21.4 and break there instead of refusing.
- **The changelog drives the release notes.** `publishMods` reads the `CHANGELOG.md` section whose
  heading names the current `mod_version` and fails if there isn't one, because a missing entry should
  stop a release rather than ship the previous version's notes under a new number. It is wired as
  a lazy provider so an ordinary `./gradlew build` never trips over it.
- **`archivesName` carries the Minecraft version** (`shufflerbox-1.21.1-0.1.0.jar`). If you change
  it, remember the sites will not let you rename a file after upload.
- **`curseforge_project_id` is blank** until the project exists; `publishMods` is the only thing
  that reads it. Rehearse a release with `./gradlew publishMods -PdryRun=true`, which resolves the
  jar, pulls the changelog section and checks every destination without uploading.

- **CurseForge and GitHub only; Modrinth is deliberately not a destination.** Modrinth's Content
  Rules gained a section 6 on generative AI in August 2026. Its disclosure requirement is no
  obstacle (tick "Contains AI-generated content" and move on), but **6.2 flatly bans project images
  "created or derived from generative AI output"** with no disclosure lane, and here the icon *is*
  generated, from textures that are themselves generated, by `tools/generate_logo.py`. CurseForge
  asks only that a *misleading* AI-modified showcase image carry a disclaimer, which a picture of
  the actual block is not. So the first release goes to CurseForge while that is an open question.
  To restore Modrinth: redraw the block textures by hand, reserve the project and uncomment
  `modrinth_project_id`, re-add the `modrinth` block to `publishMods` **and** `MODRINTH_TOKEN` to
  `release.yml`. An empty token fails at upload, not at configuration, which half-publishes a
  release after CurseForge has already accepted the jar.

- **`type = STABLE`, not `BETA`.** Both sites let players filter to stable versions only, and a
  first release labelled Beta is invisible to them. `displayName` is set too, or the version lists
  as "shuffler-box 0.1.0" after the Gradle project name rather than as "Shuffler Box 0.1.0".
- **The logo is the block itself**, projected isometrically from its own six face textures
  (`tools/generate_logo.py`), so the icon cannot drift away from what the mod looks like. It is
  size-parameterised (`--size`, a multiple of 256): 256 for the in-jar `logoFile`, 512 in
  `branding/` for the project pages. Multiples only, or the texel scale goes fractional and the
  box's pixels stop being square. It deliberately does **not** use Create: Workers' badge design:
  that badge reads as "addon for Create", and this mod is standalone.
- **No `maven-publish`,** unlike Create: Workers. Nothing compiles against this mod, so there is
  no artifact for anyone to resolve.
- **Commits use a repo-local identity** (`Steal-My-Mods`, the account noreply address) set in
  `.git/config`, deliberately not the global one. Don't "fix" it back.
- **`LICENSE` ships in the jar** under `META-INF/`. There is no `NOTICE.md`: no third-party code
  is used, and every texture and the icon are original.

## Architecture landmarks

| Path | Role |
|---|---|
| `shuffle/Palette` | The whole idea. A detached copy of a box's 27 slots, the weighted draw, and the even-drain rule. Pure logic, no world and no player |
| `shuffle/ShuffleHandler` | The `UseItemOnBlockEvent` listener that swaps a hand placement for a draw |
| `block/ShufflerBoxBlock` | The placed block. A plain full cube, deliberately not an animated shulker lid |
| `block/ShufflerBoxBlockEntity` | The 27 slots when placed. `BaseContainerBlockEntity` already moves them in and out of the item's `minecraft:container` component |
| `item/ShufflerBoxItem` | Tooltip showing the odds, and the no-nesting rule |
| `registry/SB*` | Blocks, block entities, items |
| `tools/generate_textures.py` | The block's six faces, drawn from code |

## Things that are decisions, not accidents

- **Weighting is by slot, not by stack size**, and `Palette#consume` drains the *fullest* slot of a
  block rather than the drawn one. Drawing from the drawn slot would empty one cobblestone slot
  early and slide a 3:1 palette to 2:1 and then 1:1 while the player built. This is the mod's whole
  reason to exist. `slotsDrainEvenlySoTheOddsHold` covers it, and asserts the spread between slots
  as well as the schedule, so shortening the drain cannot pass by accident.

- **The held block is a stencil and is never consumed.** The box pays for every placement, so one
  block placed is always one block gone from the box and there is no way to dupe through it.

- **The box is reached through two separate turns, because the game gives the hands two.** A block
  in the main hand takes the first turn and would place itself, so `isRequestToShuffle` steps in
  there. An empty main hand takes *no* turn (`ServerPlayerGameMode#useItemOn` skips `useOn`
  entirely for an empty stack, so no event fires for that hand at all) and the click falls
  through to the off hand, where the box is. Handling only the main hand is what made an
  empty-handed click place the box instead of a block; `anEmptyMainHandStillDrawsFromTheBox`
  covers it. The off-hand turn is only ever reached once nothing else has claimed the click, which
  is exactly the condition we want, so it does not need to test what the main hand holds.
  Consequence: the only way to *place* a box is to hold it in the main hand
  (`aBoxInTheMainHandStillPlacesItself`).

- **`ShuffleHandler` cancels on the client too, and places nothing there.** `Minecraft#startUseItem`
  walks the hands in order and stops at the first that consumes the click: if the client let the
  main hand pass, it would go on to try the off hand and place the box itself. Cancelling without
  a client-side prediction also means the drawn block appears once, when the server's update
  lands, instead of the held block flashing first and being corrected.

- **Placement goes through `ItemStack#useOn`, not `BlockItem#place`.** That call is where the
  adventure-mode check, the block-place event that land-protection mods veto with, and every other
  mod's listener live. Reaching past it into `place` would place the block and skip all of them.
  The cost is that the nested call fires `UseItemOnBlockEvent` again, which is what the `DRAWING`
  thread-local exists to absorb; `buildingFromTheBoxSpendsTheBoxAndNotTheHand` would recurse to a
  stack overflow without it.

- **Both sides decide to intercept from the same synced state** (`Palette#hasPlaceable` over the
  item's `minecraft:container` component). Anything that made the client and server disagree about
  whether the box takes the click would desync the placement.

- **The block is a full cube, not a shulker box shape.** A shulker box's lid is an animated entity
  model with its own renderer and collision. This box's job happens while it is in your hand, so
  the placed form is a die and the whole `client/` package it would otherwise need does not exist.

- **The recipe is built from shulker shells, not from a shulker box.** Vanilla ingredients match on
  item alone, so a recipe taking a shulker box would happily eat a full one and delete what was
  inside it. Building from shells sidesteps that without a custom recipe class.

- **Recipe ingredients are objects (`{"item": "..."}`), not bare strings.** The bare-string form
  arrived in 1.21.2; on 1.21.1 it fails to parse, and a recipe that fails to parse is dropped with
  one line in the log: the game starts, the mod works, and the item is quietly uncraftable. This
  recipe shipped broken until `theRecipeLoadsAndYieldsABox` existed.

- **The GameTest platform is generated** (`tools/generate_test_structure.py`) rather than saved
  from a structure block, so the fixture is readable and can be changed without launching the
  game. Note that GameTest lays a structure one block *above* its structure block, so the
  template's floor is at helper-relative y=1, not y=0.

- **There is no item sprite; the item renders the block model.** A flat 2D sprite read as a purple
  rectangle in a hotbar of 3D blocks, which is not what block items, or vanilla shulker boxes,
  look like. `models/item/shuffler_box.json` is a one-line parent of the block model, so the
  item and the placed block can never drift apart.

- **Textures are generated** by `tools/generate_textures.py`. Side faces are banded like a shulker
  box, lighter lid over darker base; the seam sits at row 6 because that is the gap between the
  first and second rows of pips, and anywhere else would cut a pip in half. Faces are numbered so
  opposite faces sum to seven, and within that constraint so that the three an inventory slot
  shows (top, north, east at `[30, 225]`) come out as 1, 2 and 3. Rerun the script after editing;
  don't hand-edit the PNGs, they will be overwritten.

## Conventions

Four-space indentation. Registry classes are `SB*` under `registry/`. Nothing is committed without
explicit instruction.
