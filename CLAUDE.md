# Shuffler Box: repo guide

A standalone mod for **Minecraft 1.21.1 / NeoForge 21.1+**, no other mods required. A shulker box
that keeps feeding your main hand one weighted-random block at a time while it sits in your off
hand.

## Commands

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client -- installs Create + Copycats+ + JEI into run/mods first
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # automated in-world tests -- the real check
python3 tools/generate_textures.py        # redraw the block's six faces
python3 tools/generate_test_structure.py  # redraw the GameTest platform
python3 tools/generate_logo.py            # redraw the in-jar 256px icon
python3 tools/fetch_dev_mods.py           # put Create + Copycats+ in run/mods, then runClient
python3 tools/fetch_dev_mods.py --clean   # take them out again
python3 tools/generate_copycat_tag.py     # redraw the copycat tag from those jars
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
- **`publishCurseforge` refuses to run on a blank `curseforge_project_id`.** The plugin itself
  does not mind one: it stages the upload and only fails when the API rejects it, by which point
  `publishGithub` may already have created the release, since the two run in no guaranteed order.
  Rehearse with `./gradlew publishMods -PdryRun=true`, which resolves the jar, pulls the changelog
  section and trips that guard without uploading anything.
- **`projectSlug` only builds the download link**, not the upload, which is keyed on the project
  id. A wrong slug is a dead link in the release announcement rather than a failed publish, so it
  is worth an eye rather than a guard. It is confirmed as `shuffler-box`.
- **A project pending CurseForge approval still accepts uploads.** Its public page 404s until a
  moderator clears it, which looks like the project not existing; it does not stop `publishMods`.
  Confirmed on Create: Workers on 2026-08-21, so a first release does not have to wait for
  approval.

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
| `shuffle/ShuffleHandler` | Every way the box reaches into a click: topping the hand up after a placement, answering an empty-handed click itself, and lending a copycat its material |
| `block/ShufflerBoxBlock` | The placed block. A plain full cube, deliberately not an animated shulker lid |
| `block/ShufflerBoxBlockEntity` | The 27 slots when placed. `BaseContainerBlockEntity` already moves them in and out of the item's `minecraft:container` component |
| `item/ShufflerBoxItem` | Tooltip showing the odds, and the no-nesting rule |
| `registry/SB*` | Blocks, block entities, items |
| `tools/generate_textures.py` | The block's six faces, drawn from code |
| `compat/Copycats` | The copycat rules, asked through tags and vanilla types so nothing compiles against Create: which blocks fill from the off hand, and what may be a material |
| `tools/fetch_dev_mods.py` | Pinned Create + Copycats+ release jars into a run directory, for compatibility testing. Not a dependency of anything |
| `tools/generate_copycat_tag.py` | The `fills_from_off_hand` block tag, read out of those jars |

## Things that are decisions, not accidents

- **Weighting is by slot, not by stack size**, and `Palette#consume` drains the *fullest* slot of a
  block rather than the drawn one. Drawing from the drawn slot would empty one cobblestone slot
  early and slide a 3:1 palette to 2:1 and then 1:1 while the player built. This is the mod's whole
  reason to exist. `slotsDrainEvenlySoTheOddsHold` covers it, and asserts the spread between slots
  as well as the schedule, so shortening the drain cannot pass by accident.

- **The box feeds the hand; it does not take placements over.** Every block is placed out of the
  main hand by the game's own path and spent out of the main hand. All the box does is hand over one
  more block, drawn from the palette, when a placement leaves that hand empty (`ShuffleHandler#topUp`).
  One block at a time, so every block placed is its own draw -- that is what makes a wall mottled,
  and what keeps the odds on each block instead of on the first of a stack of sixty-four.

  It used to be the other way round: anything in the main hand was a stencil and the box placed a
  drawn block in its stead. That reads well until you break some sand in your floor, pick a sand
  block up to patch it, and find you cannot place it while the box is in your off hand -- there was
  no way to place anything deliberately short of moving the box out of the hand. Hence the flip;
  `aBlockHeldOnPurposeIsPlacedOnPurpose` is the case it exists for, and the no-dupe property is
  unchanged, because the box hands over an item and you place that item.

- **Only two things reach into a click at all.** An empty main hand takes *no* turn
  (`ServerPlayerGameMode#useItemOn` skips `useOn` for an empty stack, so no event fires for that
  hand), so the click falls through to the off hand where the box is, and the box answers it
  directly: it places a drawn block and the top-up then arms the hand, which is how the loop starts
  (`anEmptyMainHandDrawsFromTheBoxAndArmsTheHand`). And a copycat in the main hand is lent a
  material rather than fed a block. Everything else is left alone, which is why the only way to
  place a box is still to hold it in the main hand (`aBoxInTheMainHandStillPlacesItself`) -- now
  simply because nothing intervenes.

- **The top-up is hung off `BlockEvent.EntityPlaceEvent` and runs at the tail of the tick.** The
  event is the trigger because it means *a block was placed*, whichever route placed it -- the
  player's own hand, the box's own turn, a copycat's placement helper -- and the check waits for the
  end of the tick because whether the hand is empty is only settled once the placement has taken
  what it needs out of it. Hanging it off the click instead would fire on clicks that placed
  nothing, which in creative (see below) would rummage through the hand every time the player
  right-clicked a chest.

- **Creative is read from the other side, and still only ever after a placement.** Creative spends
  nothing, so a hand that empties never fills again on its own and the box would sit inert in the
  mode most of the building happens in. So there it **replaces the stack it handed over** once a
  placement has been made with it, tracked by keeping hold of that stack and comparing by identity;
  anything else in the hand is the player's own and is left alone. An empty-handed click starts the
  loop, the same as in survival. A data component on the drawn item would be the obvious way to
  mark it and is worse: a component changes an item's identity, so the box's cobblestone would
  refuse to stack with the player's. `creativeGetsAFreshBlockAfterEveryPlacement` asserts the stack
  was *replaced* rather than that it still holds andesite, which would pass without a redraw
  happening at all.

- **Filling empty hands on sight was tried, and taken back out.** It was meant to save creative the
  one gesture that starts the loop -- put the box in the off hand, select an empty slot, build --
  and it made the box unusable: "the hand" is whichever hotbar slot is selected, so scrolling along
  the bar selected each empty slot in turn and the box dutifully loaded every one of them with a
  random block. **Anything that reaches for a hand without a placement to justify it has this
  failure mode.** The same mistake in miniature is topping up the wrong slot, so a placement now
  records which slot made it and the top-up is skipped if the player scrolled off that slot before
  the end of the tick (`toppingUpFollowsTheSlotThatPlaced`).

- **`ShuffleHandler` cancels the off-hand turn on the client too, and places nothing there.**
  `Minecraft#startUseItem` walks the hands in order and stops at the first that consumes the click,
  so a client that let the off-hand turn pass would place the box itself. Cancelling without a
  client-side prediction also means the drawn block appears once, when the server's update lands.

- **The box's own placement goes through `ItemStack#useOn`, not `BlockItem#place`.** That call is
  where the adventure-mode check, the block-place event that land-protection mods veto with, and
  every other mod's listener live. Reaching past it into `place` would place the block and skip all
  of them. The cost is that the nested call fires `UseItemOnBlockEvent` again, which is what the
  `DRAWING` thread-local exists to absorb -- without it the empty-hand path recurses to a stack
  overflow. Since the feed-the-hand flip this is the only placement the mod makes itself.

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

- **Mods to test against are release jars in a run directory's `mods` folder, not dependencies on
  a run classpath** (`tools/fetch_dev_mods.py`). Create carries Flywheel, Ponder and Registrate as
  nested jars in `META-INF/jarjar/`, and FML only unpacks those for a jar it loaded *from* a mods
  folder; the same file added to a run's classpath loads as one flat jar, `flywheel` and `ponder`
  never register, and Create -- which requires both -- dies during startup. A dev run does read
  `<gameDirectory>/mods`, so the mods folder is both the simpler route and the honest one: a
  compatibility test should load the artifact a player downloads. Nothing is wired into
  `build`, so an ordinary build never reaches the network and the mod keeps its zero dependencies.
  Run directories are gitignored, which makes the install per-clone -- and forgetting it means a
  client that comes up quietly *unable* to test the copycat behaviour, which happened twice before
  `runClient` was wired to `installDevMods`. That task declares `run/mods` as its output, so it runs
  once per clone or worktree and is up to date from then on, and a machine with no network gets a
  warning and a client without the mods rather than no client at all. It is on `runClient` alone:
  `runGameTestServer` has to be able to run both with these mods and without, which is the whole
  point of the tests skipping themselves when Copycats+ is absent.

- **A copycat in the main hand is filled, not replaced -- because both mods want the same hand.**
  `CopycatBlock#setPlacedBy` reads `getItemInHand(OFF_HAND)` unconditionally and takes whatever it
  finds there as the copycat's material, which is exactly where the box lives. Two features, one
  hand. Rather than one of them losing, the box changes job for copycats: the shape comes from the
  hand and the box supplies the material, drawn by the same slot-weighted draw
  (`Palette#draw` with a predicate). The hand pays for the copycat, the box pays for the material,
  so the rule that the box pays for what the box decides still holds. Without this, a copycat in
  hand was simply replaced by a drawn block, which is what makes the two mods look incompatible
  even though nothing is broken.

- **The material is lent, not injected, and it is lent in the first phase of the click.** A click
  walks three phases inside `ServerPlayerGameMode#useItemOn`: `stack.onItemUseFirst`
  (`ITEM_BEFORE_BLOCK`), then the clicked block's own `useItemOn` (`BLOCK`), then `stack.useOn`
  (`ITEM_AFTER_BLOCK`). A copycat's **placement helper** -- the arrow offering the far edge of the
  block you are looking at -- places the next copycat from the *clicked block's* turn, in the
  BLOCK phase, and consumes the click, so the item phase never runs at all. A box that only
  watched the item phase therefore lent nothing on exactly those clicks, and the copycat arrived
  with no material: a copycat with no material draws nothing, so it was an invisible block until
  something nearby forced a redraw, and then an untextured one. So `ShuffleHandler#lendMaterial`
  puts the drawn material in the off hand at the first phase, cancels nothing and places nothing,
  and whichever path the game was already taking places the copycat and reads the material as it
  lands -- including paths this mod has never heard of. `anArrowPlacementIsPaintedFromTheBoxToo`
  covers it, and it has to drive the block phase itself, because posting only the item-phase event
  is what let this ship broken.

- **The box goes back at the tail of the server tick** (`ServerTickEvent.Post`, which fires after
  packet handling and before the next tick's inventory sync, so the client is never told the box
  left that hand). Deliberately **not** `MinecraftServer#execute`, which is the obvious thing to
  reach for and is wrong: `scheduleExecutables()` is `!isSameThread()`, so a task submitted from
  the server thread runs *immediately* instead of being queued. That put the box back before the
  copycat was placed, and the bug looked exactly as it had before the fix.

- **The lent stack is guarded while it is out.** It is sitting in the off hand, so the game's own
  off-hand turn -- which happens whenever the main hand's placement did not consume the click --
  would place it as a block: one the player never asked for, paid for out of the box. Any click
  that would use the lent stack is cancelled instead. The box is charged only if the lent stack
  came back empty, which is what makes creative right for free (Create returns before it shrinks
  anything) and what makes a declined material free too. `setConsumedItem` copies the stack, so
  breaking the copycat still hands the material back.

- **Which blocks are copycats is a tag** (`shufflerbox:fills_from_off_hand`, generated by
  `tools/generate_copycat_tag.py` from the installed jars, every entry `required: false`), not a
  check on Create's class names. It survives Create moving a class, it is the extension point for
  any other mod's copycat-like block, and a copycat missing from it degrades to the old behaviour
  instead of crashing. Rerun the script after a Copycats+ update. **What may be a *material* is a
  mirror of `CopycatBlock#getAcceptedBlockState`** (`compat/Copycats`): the `create:copycat_allow`
  and `create:copycat_deny` tags, then no block entities, no stairs, and a full cube with real
  collision. Deliberately the conservative half of it -- an individual copycat can widen what it
  accepts through `isAcceptedRegardless`, so Create may take a material this mirror skips, and
  that costs nothing because the box is only ever charged for a material Create actually took.
  The block-entity clause is also why a Shuffler Box can never become a copycat's material.

- **A copycat with no material reports `create:copycat_base`, not an absent key.** Create stands
  that block in for null, so `aCopycatDrawnFromTheBoxIsNotMadeOfTheBox` asserts against the
  sentinel; reading it as "no material" is a test that passes for the wrong reason. The copycat
  tests skip themselves when `copycats:copycat_wall` is not registered, so a plain checkout runs
  green -- 12 tests pass with Create 6.0.10 and Copycats+ 3.0.6 in `run-gametest/mods`, and
  12 pass again with them removed.

## Conventions

Four-space indentation. Registry classes are `SB*` under `registry/`. Nothing is committed without
explicit instruction.
