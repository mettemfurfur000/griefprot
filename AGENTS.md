# AGENTS.md

Portable C backend for block reinforcement / shields, embedded by a Paper Java plugin through Java 25 FFM. No Minecraft types cross the C boundary.

## Setup requirements

- Build in an MSYS2 **MINGW64** shell (`scripts/pre_deps.sh` rejects anything else). The makefile uses MSYS paths.
- Requires a sibling checkout and build of **temlib** at `../temlib` (`../temlib/build/libtemlib.a`). Nothing links without it.
- Plugin needs a **Java 25 JDK**; Paper 26.1.2 is Java 25 and FFM is final there. Use Maven, not Gradle (Gradle cannot run on a Java 25 JVM). On this machine: Maven `C:\msys64\home\tem\maven\apache-maven-3.9.11\bin\mvn.cmd`, `JAVA_HOME=C:\Program Files\Java\jdk-25.0.2`.

## Commands

```
make                 # build/libgriefprot.a and build/test binary
make test            # BUILDS build/test only -- it does NOT run the suite
./build/test         # actually run the C suite (prints "all tests passed")
make abi             # build/griefprot_ffi.dll|.so (the FFM shim)
make abi-test        # builds and RUNS the shim tests
make abi-exports     # assert every gp_abi_* in the header is exported by name
make clean
```

- `make test` emits `Circular test <- test dependency dropped` from `test: test` in the makefile; harmless.
- Each file in `mains/` becomes its own executable target (`mains/test.c` -> `make test`).
- Plugin build (also stages the DLL): `./scripts/build_plugin.sh package` from an MINGW64 shell, or `make java-plugin`. `build_plugin.ps1` just forwards to the script. Both run `make abi` first.
- Single Java test: from `java/plugin`, `mvn -Dtest=AbiTest test` (surefire).
- **Java tests load the *staged* DLL, not `build/`.** `NativeLib` reads the packaged resource `/native/griefprot_windows_x64.dll`, so after touching any C file you must `make abi && cp build/griefprot_ffi.dll java/plugin/src/main/resources/native/griefprot_windows_x64.dll` or `mvn test` fails with `UnsatisfiedLinkError: missing export: <new gp_abi_*>`. This looks like a binding bug in `Abi.java` and is not one.

## Architecture

- `src/` + `include/` = the C library. `mains/` = one executable per file.
- `java/native/gp_abi.{h,c}` is the **entire** FFM boundary and the only supported entry points. Java never sees C structs by value; it passes `u32` handles (`0` invalid), scalars, callback addresses, and out-param pointers. Every function tolerates handle 0.
- `java/plugin/` = Paper plugin; `nativebridge/Abi.java` is the whole Java side of the boundary.
- Host callbacks flow through `gp_shield_sink`; the library is deliberately block-type agnostic (host answers `protectable`/`grant`).
- The compiled shim is staged to `java/plugin/src/main/resources/native/griefprot_windows_x64.dll`; FFM loads a real file from disk, so keep filtered resources (plugin.yml) separate from the binary.

### The reinforcement record pipeline

`GP_REC_FIELDS` (field list) -> `gp_block_reinf_t` (public struct) -> `gp_block_reinf_pack/unpack` (10 raw bytes) -> `gp_sparse` value array (stride `GP_REC_BYTES`) -> `GP_REINF_REC_SIZE` = `u32` index + record -> TKV array groups -> the save.

Only the field list knows the layout. Everything downstream consumes the struct, the derived macros (`GP_REC_BYTES`, `GP_REC_TOTAL_BITS`, `GP_REC_FIELD_COUNT`, `GP_REC_SPARE_BITS`), or `gp_block_reinf_faces`/`_empty`/`_slot`.

The packer never learns a field's C type. It lifts each field's whole member into a `u64` in **member address order**, then pulls `bits`-wide elements out at `stride * 8` intervals. That is what keeps a 12 bit value inside a `u16` host-independent; bit order is LSB-first within each byte, matching the hand-rolled little endian `put_u32` in `griefprot_serialize.c`. Bit position is never written by hand anywhere.

## Changing the record

Ordered checklist. Missing a step is a build failure in every case except the last, which is the dangerous one.

1. Add or remove the `X(name, declaration, bits, count)` line in `GP_REC_FIELDS`, `include/griefprot_record.h`. Order is storage order, so append to keep existing fields where they were.
2. If `bits * count` exceeds `GP_REC_SPARE_BITS` the record grows. That is free, but confirm it: `GP_REC_BYTES` feeds the sparse map's value size and `GP_REINF_RECORDS_PER_GROUP`.
3. Add a case to `field_write` in `mains/test.c`. Its `default` branch is `CHECK(false)`, so a field added without a case fails the C suite rather than going untested.
4. Decide whether a record holding *only* the new field is live. If yes, extend `gp_block_reinf_empty` in `griefprot_record.h`; five sites (`gp_chunk_reinf_add`, `gp_chunk_reinf_consume`, `gp_chunk_reinf_motion_block_set`, `gp_chunk_reinf_transfer`, `gp_chunk_reinf_visit`) and `reinf_entries` all key off it, so a new field that changes what "empty" means silently changes what the chunk thinks it is holding. `flags` is the worked example of saying no: both of its bits describe what the protection on a block means, so a record holding only a flag would outlive every face it was set alongside and never be cleaned up. `gp_chunk_reinf_flags_set` refuses an unreinforced block rather than creating one.
5. Decide what a merge does on collision. `merge_reinf` in `griefprot_chunk.c` or's `slot_flags`, `motion_block` and `flags`; a field you add will default to whatever that function does with it, which is often "dropped".
6. Bump `GP_FORMAT_VERSION`. **Even when the byte count is unchanged** -- v2 and v3 are both 10 bytes with different bits inside, and a v2 file read as v3 gives every block wrong faces, wrong durability, and constraints nobody set. v3 to v4 is the same trap with a sharper edge: the two new bits decode as a face lock and a piston waiver on every block in the world.

### The packer's hard limits

Enforced by `static_assert` in `griefprot_record.c`, so violations are build errors:

- `bits` is 1..64.
- The field's **whole member must fit in one `u64`**, i.e. 8 bytes. A 4 element `u32` array is 16 bytes and will not compile. Split it into two entries, or extend the packer to a multi-word accumulator.
- `count` must divide the member's size.
- `bits` must fit the declared C type. Wider values are truncated silently by the pack, which is why `gp_chunk_reinf_add` refuses amounts above `GP_REINF_DURABILITY_MAX` rather than clipping them.

## Landmines

- **Do not ray trace explosions.** `event.blockList()` is already the output of the server's own exposure test (`ServerExplosion.calculateExplodedPositions()`, handed straight to `CraftEventFactory.callEntityExplodeEvent`), which casts a fixed 1352-direction lattice from each candidate and occludes through collision shapes. `ExplosionListener` used to run its own centre-to-centre full-cube DDA on top, and it could never see the blocks the server had *excluded* -- so the reinforced walls it was supposed to charge were exactly the ones it never saw. There is no API hook into that ray: `ExplosionResult` is a post-hoc outcome enum, and `ServerExplosion.getSeenPercent` is the entity-damage path, not the block one. Leave the tracer out; if a wall must be charged for absorbing a blast, that is a new mechanic over the blocks the server *did* select, not a trace.
- **`gp_sparse` record pointers do not survive a `gp_sparse_remove`.** Removal compacts once `dead > count && dead > 32`, and compaction reallocates the value array. `gp_chunk_reinf_transfer` was written the obvious way -- get_or_create the destination, then remove the source -- and held a dangling pointer whenever `src == dst`. It now re-fetches by key after the removal. Anything that reserves a cell and then removes from the same table has the same bug.
- **`gp_chunk_reinf_get` returning true does not mean the block has a reinforced face.** It means a record exists. A block held in place by a motion constraint is a record with zero faces. Use `gp_block_reinf_faces` when that is what you mean.
- **The face lock is enforced *after* the upgrade search in `gp_chunk_reinf_add`, not before.** Put it at the top and a locked block refuses to reinforce the sides its owner already built, which is the opposite of what the flag is for. A locked block refuses new faces and takes upgrades; the C suite pins both halves.
- **A piston move must be walked front to back, and the chain must be complete before anything is refused.** `BlockPistonExtendEvent`/`BlockPistonRetractEvent#getBlocks` is not obliged to list the blocks a slime or honey block hauls along behind it, and it is not in an order that leaves each cell free. `PistonListener` therefore rebuilds the chain (`dragChain`, slime and honey only, the block directly behind, run to a fixed point) and sorts it so the block in front moves first. Two consequences: a record is only ever moved into a cell the block ahead already vacated, because `merge_reinf` **sums** durability on a shared face rather than refusing -- so a back-to-front walk welds one block's protection onto the block ahead of it, and pulls can stack points on the way out and back; and the sticky refusal is deliberately wider than "the block it hauls". A slime is the one material a piston moves without pressing on the face it is about to attack: it grips whatever is behind it and merely sits against everything else it touches, so a slime in the chain refuses the move on **all six** sides if any neighbour carries reinforcement on the face between them. Narrowing that to the hauled block, or to the hauled block's leading face, leaves both the extend case (pressed on its trailing face) and the merely-adjacent case open. Do not add a move path that resolves a chain in the order the event hands it over.
- **`reinf_entries` counts records, not faces.** It is decremented when a record is dropped, never when a single face is exhausted. It used to do the latter and drifted negative on any multi-face block.
- `gp_chunk_unpack` counts one entry per stored record without consulting the chunk's own counters, so a save containing an all-zero record resurrects a live entry. Nothing writes such a record, but a hand-edited or corrupted save could.
- **`gp_shield_tick` runs a whole regeneration window on every tick, because nothing reads `shield->window`.** `gp_shield_create` copies `cfg->shield_window` in and the serializer packs and unpacks it, but no code compares `elapsed` against it, so a running shield grants `shield_regen` (20) points *per tick* and `shield-window-seconds` (300) has no effect at all. This was invisible while a shield could only be made by an admin, and a survival shield generator is what makes it matter. The fix is a single gate in `gp_shield_tick` before `gp_shield_run_window`, and it needs a C test: a shield ticked once inside its window grants nothing, and one ticked past it grants exactly one window's worth.
- **A shield generator is a real furnace, and the cancelled `FurnaceBurnEvent` is the only thing stopping it from losing fuel.** A furnace takes the fuel item out of its slot the *moment it lights*, not when the gauge empties, so `ShieldGeneratorListener.onBurn` refuses the burn for a marked block: let it light and the coal is gone before the once-a-second poll ever sees the slot. That refusal is what lets the poll be the only thing that empties the slot, and it is *not* redundant with refusing the input slot -- the input slot is refused so the furnace has nothing to smelt, but the slot can still be filled by a hopper or a dispenser. The `lit > 0` branch in `ShieldGenerators.refill` exists because the invariant is about the server's behaviour and not about anything the library enforces: if a version ever ignores the refusal, the fuel is credited from the gauge the furnace left rather than lost.
- **A generator's shield is suspended on chunk unload rather than destroyed, and the suspension is load-bearing.** `gp_shield_tick` returns before touching the host at all for a shield that is not both powered and fuelled, so unpowering is exactly what stops a range shield answering `getBlockAt` across a cube of coordinates the server has released. Destroying it instead loses it outright, because the library packs live shields into the save it writes at shutdown -- a shield retired on unload was simply absent from the next file. The price is that `admin shield power off` on a shield whose chunk unloads is undone when the chunk loads again.
- **Paper exposes no way to ask how long an item burns as fuel.** There is no burn time on `Material`, `ItemStack` or `MaterialData`; `Material#isFuel()` is a boolean and nothing more. So `ShieldGenerators.burnSeconds` is a table of vanilla values, with the wood family reached through item tags and anything unnamed defaulted to a stick. The low default is a balance decision, not an oversight: an unfamiliar item from another pack being worth a coal is an economy hole, and being worth five seconds is only disappointing.
- **A generator refuses to be moved by a piston, and that is checked outside `PistonListener.refusal` on purpose.** A generator's shield is anchored at its block, so moving the block leaves a shield covering coordinates with nothing in them. It is not a rule about faces, which is all `refusal` knows about, and the piston-free flag must not reach it: slicking a block waives the rules about reinforcement, not about relocating a machine. `Protection.clearBlockAndPartner` takes the `ShieldGenerators` as a parameter rather than reaching for it, so a fourth listener that lets a block disappear cannot forget the shield anchored there.
- `tweak nothing`: `gp_config` tier values are `u16` in the public API but the record caps at 12 bits. `ConfigLoader` clamps `copper-tier-points` / `iron-tier-points` with a hardcoded 4095; `gp_abi_reinf_durability_max` is bound as `Abi.reinfDurabilityMax()` but not actually used there.

## Deliberately unfinished

`slot_flags` (`GP_SLOT_FLAG_ANCHORED`) and `motion_block` are stored, merged, moved and saved, and **no rule reads them**. They are not on the ABI at all, so Java cannot reach them. That is intentional: the gameplay was still being designed. When the semantics land they are a check in `gp_chunk_reinf_consume` and one in `gp_world_move_block` -- plus ABI functions, `Abi.java` bindings and `abi-exports` entries -- with no format change.

`flags` is the exception and *is* read: `GP_BLOCK_FLAG_LOCK_FACES` is enforced in `gp_chunk_reinf_add`, and `GP_BLOCK_FLAG_PISTON_FREE` is honoured by `PistonListener.refusal` in Java. Note that the library does **not** let a piston push into a reinforced destination face, even when the source is piston free: that is the destination's protection, not the mover's. Likewise the "would stack two records" guard in `PistonListener.handle` still applies to an exempt block, because `merge_reinf` would sum two blocks' durability into one.

## Known gaps

- **TODO: blocks that die on their own leave their record behind.** The cleanup in `Protection` runs for player breaks (`BreakListener.onBreakApplied`), explosions (`ExplosionListener.onEntitiesDestroyed` / `onBlocksDestroyed`) and placements (`InteractListener.onPlace`). Nothing watches a block that is destroyed by the world itself: `BlockBurnEvent` (fire), `BlockFadeEvent` and `LeavesDecayEvent`, `BlockFromToEvent` (liquid spreading), `EntityChangeBlockEvent` (falling sand or gravel landing), `BlockDispenseEvent` (a dispenser emptying onto a protected block), and `StructureGrowEvent`. Each needs a listener that clears through `Protection.clearBlockAndPartner` once the removal is final, the same way the three existing sites do -- MONITOR, `ignoreCancelled`, so a plugin that cancels the event leaves the block protected.

## Conventions / gotchas

- C style: `.clang-format` (Microsoft base) with **tab indentation** and Allman braces; 4-wide. No make target runs it.
- Changing the ABI means updating `java/native/gp_abi.h` (and its tests); `make abi-exports` fails the build on any header symbol Java cannot look up by name. Bind it in `Abi.java` too, or the function is dead weight on both sides.
- The block reinforcement record is declared **once**, as the `GP_REC_FIELDS` X-macro list in `include/griefprot_record.h`. That list derives the public struct, the stored byte size (`GP_REC_BYTES`), the packer, and the serializer's group sizing. Adding or removing a field is a one line edit there plus whatever reads the new value -- do not hand-roll a bit offset anywhere else. `mains/test.c` has `field_write`, which fails the build's test run if a field is added without a case.
- `GP_REC_SPARE_BITS` is real room: append a narrow field there and the record does not grow. Widen past it and `GP_REC_BYTES`, the sparse map's value size and `GP_REINF_RECORDS_PER_GROUP` all move on their own.
- Durability is 12 bits. `gp_chunk_reinf_add` refuses a wider tier rather than truncating it, and `gp_abi_reinf_durability_max` exists so Java never hardcodes the ceiling.
- `gp_block_reinf_t` has no slot counter; `gp_block_reinf_faces` derives it. Nothing may reintroduce a stored count of anything the record can already be read back out of.
- `GP_REINF_RECORDS_PER_GROUP` / `GP_SHIELD_RECORDS_PER_GROUP` are derived from the record sizes, not constants. A fixed group count would overflow temlib's 8192 byte field buffer the moment a record grew.
- Record tests compare through `gp_block_reinf_pack` (`records_equal` in `mains/test.c`) rather than `memcmp` on the struct, because the struct has tail padding.
- Serialization is versioned by `GP_FORMAT_VERSION` (currently 4). Version 1, 2 and 3 saves are **rejected, not migrated** -- a host upgrading must discard old saves. Any record layout change needs a bump, even when the byte count is unchanged: v2 and v3 are both 10 bytes with different bits inside, and so are v3 and v4.
- `text`/overlay, decay, and coverage semantics are documented at length in `README.md`; read it before touching shield/decay/explosion logic.
- **A cancelled `BlockBreakEvent` costs the attacker no tool durability.** Paper's `destroyBlock` fires the event, then `return false`s before it ever reaches `itemStack.mineBlock(...)`. Anything that cancels a break to keep a block standing has to charge the tool itself or the swing is free forever; `ToolDamage` does this and `BreakListener` calls it wherever it cancels. Do not add a second cancel site without the charge. The one exception is `refuseUnarmed`, which cancels for a break that had nothing chargeable in hand.
- **Something that cannot be charged must not spend the block's protection either.** `ToolDamage.isTool` is deliberately *not* `ToolDamage.perBlock > 0`: an unbreakable tool charges nothing but is still a tool, and a player who earned one must not be told their pickaxe cannot break stone. The bare hand, the stick and the spare block are the free raid `ToolDamage` exists to close, so `BreakListener.refuseUnarmed` cancels those without ever consulting the store. Do not "simplify" `isTool` into `perBlock > 0`.
- **A face tool has to be held, and holding is not the same as pressing.** `InteractListener.tick` runs off the scheduler, not off the click stream, so the hold does not depend on how often the server repeats interact events. `FaceHold.arm` treats a repeat click for the same block, face, tool and sneaking state as a continuation and keeps the clock, because a hold that restarted on every repeat could never fill. The hand is deliberately *not* part of that identity -- a click fires once per hand, so two brushes would otherwise restart the clock forever. What aborts a hold is the tool leaving the hand, the player dying, sneaking, or the crosshair leaving the block. Letting go is **not** in that list and cannot be: no Bukkit API exposes the mouse button. Keep it that way rather than reaching for a cache that guesses.
- Tool damage is the **item's own `damage_per_block`** via `ItemStack#getData`, not a hardness/speed formula and not `ItemMeta#getTool`: 1.21.5 moved mining data into the `minecraft:tool` component, which lives on the item definition rather than the stack, so `ItemMeta#hasTool()` answers no for every unmodified vanilla tool. **`damage_per_block` is a flat 1 for every vanilla tool** -- `ToolMaterial#applyToolProperties` builds the component with a literal 1, and the real per-tier speed lives in its rules -- so the multiplier, not the item, is what sets a raid's cost. `ToolDamage.scaled` rounds **up**, since truncating turns a sub-1.0 `tool-damage-multiplier` into no charge at all. Only `NaN` charges nothing; an off-the-scale multiplier saturates to max, because clamping it to 0 would refund the raid.
- **Charge with `LivingEntity#damageItemStack(EquipmentSlot, int)`, never `ItemStack#damage(int, LivingEntity)`.** The stack overload resolves to `damageItemStack(stack, amount, null)` and vanilla's break callback is `if (slot != null) entity.onEquippedItemBroken(item, slot)` -- so with no slot the tool still empties but silently loses the break sound, the break particles, the `ITEM_BROKEN` statistic and the Mending roll. The slot overload also damages the held item in place, so there is no inventory read-back to fall out of step.
