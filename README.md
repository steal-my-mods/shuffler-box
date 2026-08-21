# Shuffler Box

A shulker box that builds for you. Fill it with a palette of blocks, hold it in your **off hand**,
and it keeps feeding your other hand one random block at a time out of that palette, so a wall
comes out mottled the way a real one is without you cycling hotbar slots for an hour.

- **Minecraft** 1.21.1
- **Loader** NeoForge 21.1+
- **Dependencies** none

## Using it

**1. Craft one**

```
 B       B = Bone Block      S = Shulker Shell
SCS      C = Chest           I = Ink Sac
 I
```

**2. Fill it.** Place it and right-click, exactly like a shulker box. 27 slots, and it keeps what
is inside when you break it and pick it up.

**3. Set your odds by how many slots you use.** This is the whole idea:

```
[cobble] [cobble] [cobble] [stone] → 3 cobblestone for every 1 stone
```

Weighting is **by slot, not by stack size**. Three slots of cobblestone against one of stone is
3:1 whether those slots hold 64 each or one block each. Hover the box to see the odds it is
currently set to.

**4. Build.** Put the box in your off hand and right-click with an empty main hand. That places a
block and puts the next one in your hand; place it and the box hands you another. Keep clicking and
you keep building, a fresh random block every time.

To **place the box itself**, hold it in your *main* hand.

Any click your main hand has no use for falls through to the box, so holding a pickaxe or a sword
and right-clicking places a drawn block too -- the same way a torch in your off hand would. Only a
main hand with something to place of its own, or something that answers the click itself, keeps the
box out of it.

## Two things worth knowing

**The box feeds your hand; it never takes over your placement.** Every block is placed out of your
main hand, the ordinary way, and spent out of your main hand. The box's job is to hand you the next
one, one block at a time, whenever a placement leaves your hand empty. So if you go and pick up a
stack of sand to patch a hole in the floor, you place sand -- all of it, exactly where you point --
and the box picks up again once the sand runs out. Nothing you hold on purpose is ever swapped out
from under you.

**In creative it keeps itself going.** Nothing is ever spent in creative, so your hand never runs
dry on its own: there the box swaps in a fresh block after each placement instead. Right-click once
with an empty hand to start -- that places a block and arms your hand -- and from then on every
click hands you another. A stack you picked out yourself is still left alone.

**Stacks drain evenly, so your odds hold.** If the box drew from one cobblestone slot until it ran
dry, a palette set to 3:1 would quietly slide to 2:1 and then 1:1 while you built. Instead every
draw takes from the *fullest* slot of that block, so a slot only empties once every slot of its
kind is down to its last item. The ratio you laid out survives until the box is nearly empty.

## Compatibility

Modded blocks work because the box does not place them: you do. It puts a block in your hand and
the game places it out of your hand exactly as if you had picked it off your hotbar, so the block
arrives by its own code with its whole stack intact, orientations are chosen, land-protection mods
get their veto, and anything else watching placements sees an ordinary one. (The one placement the
box makes itself, when you click with an empty hand, goes through the same call your hand would.)
Copycat blocks get a mode of their own, below.

Two blocks of the same type carrying different data count as two different entries in the
palette, so a box of copycat panels in three materials shuffles between the three.

Blocks the box cannot place (a stack of diamonds someone parked in there) sit in their slot and
never take a turn. The tooltip says how many slots are stranded that way.

Opening the box uses the vanilla shulker box screen, so inventory-sorting mods treat it as one.

## Copycats: shape from your hand, paint from the box

A copycat block takes its material from whatever you are holding in your **off hand** as it is
placed -- the same hand the box wants. So copycats are the one thing the box does not replace.
Hold a stack of copycat walls in your main hand with the box in your off hand, and every wall you
place is filled with a material drawn from the box: the shape is yours, the paint is the box's,
and each block you place can come out a different material. Your hand pays for the copycat, the
box pays for the material. Copycats you place by the arrow -- the one that appears when you point
at a copycat you have already placed and offers to put the next one alongside it -- are painted
from the box too.

A copycat already standing in the world with nothing on it is painted from the box as well: point
at it and right-click, with an empty main hand or with whatever you happen to be holding, and it
takes a material drawn from the box. Copycats that already wear something are left alone -- Create
itself declines to repaint them -- so clicking one you have finished still places a block against
its face, and building alongside a copycat wall works the way it always did.

The box only offers materials a copycat can actually wear -- full blocks, nothing with a block
entity, no stairs, plus whatever `create:copycat_allow` and `create:copycat_deny` have to say --
and it draws between them by slot, the same weighting as everything else. Fill three slots with
andesite and one with brass and your copycats come out 3:1. A box holding nothing wearable (all
slabs, say) stands aside, and the copycat is placed plain, exactly as it would be with an empty
off hand.

Which blocks count as copycats is the `shufflerbox:fills_from_off_hand` block tag. Every copycat
in Create and Create: Copycats+ is in it; a datapack can add another mod's.

Copycats are the one thing the box paints rather than supplies, so the usual rule still applies
underneath: place the last copycat in your hand and the box tops that hand up with an ordinary
block, the same as it would after any other stack ran out.

## Releasing

Tag it and Actions does the rest:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The tag must match `mod_version` in `gradle.properties`, and `CHANGELOG.md` must have a section
naming it. The workflow fails on either. Rehearse with `./gradlew publishMods -PdryRun=true`.

Releases go to **CurseForge and GitHub**. Modrinth is not a destination for now, while its new
rules on generative AI in project images are an open question. The icon here is generated by
[`tools/generate_logo.py`](tools/generate_logo.py) from textures that are themselves generated.
See the Distribution notes in [CLAUDE.md](CLAUDE.md).

## Building it

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runGameTestServer  # the tests
```

JDK 21. See [CLAUDE.md](CLAUDE.md) for the repo layout.

## License

MIT. See [LICENSE](LICENSE).

Every texture, the block model and the icon are original and generated by the scripts in
[`tools/`](tools/): functional art rather than good art. No Mojang or third-party asset is used
or redistributed.
