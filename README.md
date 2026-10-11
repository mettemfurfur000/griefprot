# griefprot

Portable C backend for block reinforcement and shield protection, meant to be
embedded by a Minecraft plugin or mod (and other games later) rather than run on
its own. No Minecraft types cross this boundary: the library only knows about
positions and numbers, and the host supplies the callbacks.

## What it does

**Side reinforcement.** A consumable applied to one face of a block sets that
face's break allowance. Copper nugget 32, iron 256, both configurable. A block
holds at most four reinforced faces out of six, so a solid wall can be fully
covered while a protruding block always keeps a weak spot. Reinforcement does
not decay.

The allowance is a tier value, not an increment, so nothing stacks: applying a
second plate to a reinforced face either upgrades it in place, when the new plate
is strictly stronger, or is refused. Two copper plates never become 64, and a
copper plate never weakens an iron one. A refusal leaves the block untouched and
returns false, which is a host's signal to keep the item it was about to spend.

Note that `gp_chunk_reinf_clear` clears a whole block rather than a single face,
so upgrade-in-place is the only way to move a face between tiers without losing
the block's other faces.

**The face tools.** Three items act on a block face, and none of them acts on the
click. The player right-clicks, keeps the tool against the block for
`brush-hold-ticks`, and watches an action bar fill; only then does anything
happen. There is no API that exposes the mouse button, so releasing it does not
abort a hold -- walking the crosshair off the block, swapping the tool or
sneaking does.

| Tool | Effect |
| --- | --- |
| Brush + nugget | Reinforces the aimed face. The nugget in the *other* hand picks the tier and is spent; the brush may be in either hand. A nugget on its own does nothing. |
| Flint & steel | Sets `GP_BLOCK_FLAG_LOCK_FACES`: the block refuses any face it does not already carry, and still takes upgrades to the ones it has. Needs the block to be protected already. |
| Slime ball | Sets `GP_BLOCK_FLAG_PISTON_FREE`: a piston pushes or pulls the block with none of the library's rules applying to it. Its protection travels with it. Needs the block to be protected already, and has to be used from a side carrying no reinforcement, so the decision is made on a face the owner can see is bare. |

Both flags are one way. No item clears them; `PistonListener` and the library
still apply the stacking guard and the destination-face check to an exempt block,
because those are about the block being pushed *into* rather than the one moving.

**Breaking through.** A break that protection stops is charged to the attacker's
tool, because Paper never damages it on a cancelled break. An item that is not a
tool cannot be charged at all, so it does not get to spend the block's protection
either: `BreakListener.refuseUnarmed` cancels the break before it reaches the
store. Unbreakable is not treated as "not a tool" -- a player who earned a
netherite pickaxe should not be told their pickaxe cannot break stone.

**Reaching a face you cannot see.** A plate normally lands on the face the
player clicked. Holding shift sends it somewhere the crosshair is not pointing,
which is the only way to reach a face that cannot be aimed at. `OppositeSide`
decides where:

- `SAME_BLOCK` (default) puts the plate on the far side of the block that was
  clicked. Stand on a lone block, shift-click its top face, and the plate lands
  on the underside, which no click can aim at.
- `THROUGH` sends it past the clicked face onto the block behind, on the face
  that shares the plane. This is the only way to plate the interior of a wall
  more than one block thick, since the faces between two buried blocks cannot be
  aimed at by anyone. It refuses when the block behind is air or unprotectable,
  which is most of the time in the open.

Neither is a convenience. Without either, reinforcement can only ever land on a
face the builder can already see, so it tracks how exposed a block is, which is
the inverse of what protection wants. A raider digs one block of dirt and swings
at a wall's underside, and the only answer to that has been to plate a block in
isolation and shove it into place with a piston.

Three consequences a host should know:

- `SAME_BLOCK` reaches a face only from a face you can aim at, so it covers the
  underside of any block with nothing on top of it and nothing under it. A wall
  several blocks tall does **not** qualify: the brick above the bottom row
  covers that row's top face, so the only aimable faces lead to the other three
  sides, and the bottom row's underside is unreachable by any click at all. That
  is geometry, not a missing setting, and it is what `GP_SLOT_FLAG_ANCHORED` is
  for: a face held by the block it rests on. See [Storage](#storage).
- Shift is a blunt instrument. It is also how a player sneaks to avoid falling
  off a ledge, so the message always says where the plate actually went.
- In `THROUGH`, `gp_abi_reinf_add` creates a chunk rather than refusing an
  unloaded one, so the host must check residency before spending an item aimed at
  a block the server has not loaded. `InteractListener` does; a new host has to.
  `SAME_BLOCK` needs no such check, since the block is the one already clicked.

**Shield points.** A separate number from reinforcement, covering the whole
block rather than individual faces. A shield distributes its pool evenly across
its targets, regenerating one point at a time in a round robin so the budget is
shared fairly rather than drained into whichever target comes first. With no
targets selected it covers a cube of `(2r+1)^3` blocks instead. Shield points
decay once no live shield is covering them. A powered shield must stay fuelled
through `shield-window-seconds` before it grants that window's points. With the
defaults, one coal lasts 80 game seconds and a window is 300 game seconds, so it
needs at least four coal to reach the first regeneration window. Set
`shield-window-seconds` to change the game-time interval; `shield-regen-points-per-window`
is the point budget, not a duration. The old `shield-regen-seconds` key remains
supported for existing configs but logs a warning. These durations use game time
(20 server ticks per second), not wall time, so low TPS slows fuel use,
regeneration and decay in real time.

**The shield generator.** A shield is reachable in game. Place the block named by
`shield-core-material` (a blast furnace by default), right-click it with the
`shield-core-activation-item` (a nether star by default), and it becomes a
generator anchored at its own coordinates. The activation item is spent and is not
returned: breaking the generator later drops what the block itself drops and
nothing more, so the item is the standing price of having one. A furnace with
anything in its input or output slots is refused, so converting it can never
destroy what was in there.

A generator is a real furnace with two slots changed. The **fuel** slot is a
deposit: supported fuel is taken out and turned into shield fuel, and the shield
runs until it is spent. Unsupported or unfamiliar fuels are left in the slot and
refused. The **input** slot takes nothing -- a shield generator does not smelt --
although taking something back *out* of it still works, so a hopper cannot turn
the slot into a place items are lost. A lava bucket leaves an empty bucket in the
slot, as it would. Smoke appears above the generator while its shield is running.

An item is worth the number of furnace seconds it would have burned for, and the
shield drains `shield-fuel-per-second` per game second, so a coal is 80 units and
runs a generator for 80 game seconds while a blaze rod runs it for 120. Paper
exposes no way to ask the server how long an item burns, so the named tiers are a table
(`ShieldGenerators.burnSeconds`); the wood family is reached through item tags.
Anything else is refused until it is explicitly assigned a value. A generator
has no ceiling on its fuel; `shield-fuel-max` is what an admin-created shield
starts with.

The block is the source of truth, not the shield. The marker is a persistent data
key on the block entity, so it survives a restart, and it is what a chunk load
reads to rebuild a shield the store has lost. That is why the generator's shield is
suspended rather than destroyed when its chunk unloads: `gp_shield_tick` makes no
host call at all for a shield that is not both powered and fuelled, so unpowering
stops it reaching into coordinates the server no longer considers real without
losing it. A shield destroyed on unload was simply absent from the next save. A
generator also refuses to be moved by a piston, because a shield anchored at a
block that has gone somewhere else is a shield covering coordinates with nothing in
them, and the piston-free flag does not reach it -- slicking a block waives the
rules about reinforcement, not about relocating a machine.

## Coverage and decay

Decay only touches blocks no live shield is holding up. A running shield marks
the blocks it is responsible for once per regeneration window, and the chunk's
sweep skips anything still marked. Without this a covered block would lose points
every sweep and be handed them back on the next window, which shows up as a
visible flicker in durability.

The mark is a deadline, not a reference count. Shields unload, get broken, run
out of fuel, and disappear, and none of those events tell the blocks they were
covering, so a claim that had to be released explicitly would outlive its shield
and freeze decay forever. Instead each claim expires one decay interval after the
last window that refreshed it: a shield that stops running simply lets its
coverage lapse, and the blocks start bleeding again. A claim never shortens a
longer one, so a slow shield cannot hand a block back to decay while a fast one
still holds it.

Marking only ever protects points that already exist. Covering a block with no
points allocates nothing, which matters because a range shield considers its
entire cube every window.

Coverage is claimed over the whole eligible set rather than alongside the grants,
because the grant pass stops as soon as the window's budget is spent and does
nothing at all once every target is topped up. Tying the two together would leave
fully protected blocks unmarked and let decay bleed them.

Two limits worth knowing:

- A block whose coverage has lapsed can still be saved by a grant landing in the
  same tick as the sweep, depending on the host's ordering of the tick and the
  chunk sweep. The library does not sequence them for you.
- A block's coverage is only as fresh as the last window to claim it. A shield
  with more targets than its decay interval can leave the tail of its own list
  briefly uncovered. Raise the interval rather than the target count if that
  shows.

Breaking a block resolves in a fixed order: a reinforced face absorbs the break
first, and only an unprotected face falls through to shield points. So a block
can have reinforcement, shield points, both, or neither.

A break that protection stops also costs the attacker tool durability. Paper
does not damage the tool when `BlockBreakEvent` is cancelled, so stopping a
break would otherwise refund the swing and let a raid run indefinitely for
free. The base is the held tool's own `damage_per_block`, which is a flat 1 for
every vanilla tool at this version: the per-tier mining speed lives in the tool
component's rules and never reaches the wear. That makes `tool-damage-multiplier`
the thing that sets the real cost, defaulting to 20 so a diamond pickaxe gives
out after about 78 absorbed hits; the key in `config.yml` carries the table. It
applies to any break that was stopped, whether a reinforced face held it or
shield points paid for it, and 0 turns tool wear off entirely. Unbreaking and
Mending still apply, since the charge goes through the server's own item damage
rather than writing to the it directly. Explosions have no tool to charge. A tool that reaches zero breaks
the vanilla way: the charge goes through the server's own item-damage path with
the equipment slot supplied, so the break sound, the break particles, the
`ITEM_BROKEN` statistic and the Mending roll all happen as they would if the
break had gone through.

A block that is destroyed drops both layers, whatever killed it: a player, an
explosion, or a placement over it. The resolver only spends the face that was
struck, so a block broken from an unprotected side would otherwise keep its other
faces on air, and shield points are coordinates too. Blocks that share a slot,
the two halves of a stair or a bed, are cleared together, and the wipe happens
once the event is final rather than while it is being resolved: a plugin that
cancels a break afterwards leaves the standing block protected. Nothing is left
behind at the coordinates of a block that no longer stands, so a block placed
there later starts unprotected rather than inheriting what used to be there.

Piston pushes and sticky-piston pulls move reinforcement and shield points with
the block, including protection on the face nearest the piston. Protection is
not left behind at the block's old coordinates. If the protection store refuses
a move, for example because the destination would exceed its reinforced-face
limit, the piston event is cancelled rather than moving the block without its
protection; any earlier protection transfers in the same piston event are rolled
back. A move is also refused when it would enter another block's reinforced face,
preventing pistons from forcing blocks past a protected wall. Source-face checks
are event-specific: extensions guard the face opposite travel, while retractions
guard the face matching travel. Air blocks are not valid reinforcement targets.

A record's `motion_block` mask is carried along by a push and or'd into whatever
the destination already held, so a block constrained at either end stays
constrained. No code consults the mask yet; see [Storage](#storage).

Explosions use that same resolution for each affected block. The face nearest
the explosion origin is checked first; reinforcement absorbs the hit before
shield points. A protected block is removed from the explosion's affected list,
so the explosion can still damage other blocks normally.

Which blocks a blast reaches is the server's decision, not this plugin's. The
list an explosion event carries is the output of the server's own exposure
test, which casts a fixed lattice of unit directions from each candidate
toward the origin and counts the ones that arrive, occluding through block
shapes rather than full cubes. Nothing is traced here, deliberately: a second
tracer would be a second and coarser opinion about a ray the server has already
resolved, and it would be blind to the blocks the server excluded -- which is
exactly the set whose intervening walls it would want to charge. A block the
server judged out of reach is spared by the server, and no reinforcement or
shield points are spent on the wall in front of it.

Which reinforced face pays is the other half, and the server does not say. The
blast is taken to arrive on the face nearest the origin. If that face carries no
reinforcement, the block's own weakest reinforced face pays instead, the same
rule a player break uses when the struck side is unknown. A wall spared by the
server therefore costs nothing, but a block the server did select survives on
whatever reinforcement it has anywhere, which is what makes a diagonal blast
past a reinforced neighbour work: the block behind it keeps standing and loses
one point of durability instead of dropping to shield points and breaking.

Direct hits follow the break resolver's durability rule: a face protects the
target while durability remains after the hit. Spending its final point allows
the target block to break unless shield points absorb that break. Because a
blast may be charged to a face that was not facing it, a fully reinforced
block absorbs from every direction, and its total durability is the sum over
its faces rather than the single exposed side.

Explosion diagnostics are currently written to the server log under
`[explosion-audit:<id>]`. Each entry records the target coordinates, the face the
blast struck, the durability it spent, and the resolver decision. A next-tick
entry reports whether each candidate block actually broke and its face/shield
values before and after; surviving faces that drop below 31 are also called out
with the note that explains them.

## Debug overlays

Operators can inspect the block under their crosshair (up to 8 blocks away) with
`/griefprot overlay reinforcement`. Every face of each block position within the
configured spherical radius is drawn as a particle plane; the color moves from
red at zero durability through the copper tier to green at the iron tier. Faces
are filled rather than outlined because an outline shares all four of its edges
with the face opposite it, so a reinforced block and a bare neighbour light up the
same line. Set `debug-overlay-radius` in `config.yml` from 0 to 32 (default 2);
unloaded chunks are skipped. Larger radii can be expensive. Use
`/griefprot overlay shield` for a blue wireframe around every block with shield
points in that same spherical radius, or `/griefprot overlay off` to stop.
Use `/griefprot overlay details` to show the targeted block's coordinates,
durability on all six faces, and shield points in the action bar. This makes it
possible to compare the values before and after an explosion. Only one overlay
can be active per player at a time.

Separately from all of these, any player holding a **brush** sees the single face
they are aiming at, in the same colours: red where there is nothing, through the
tiers to green. It needs no command and no permission, because finding out after
the fact that the face was already plated costs them a nugget and tells them
nothing they could have acted on. The face is worked out from the eye ray rather
than cached from the last click, so it keeps up with the crosshair; see
`AimedFace`. A hold in progress takes the action bar, so `details` yields to it
while the player is brushing.

## Admin tools

Players and the console with `griefprot.admin` can inspect and manage tracked
protection data:

- `/griefprot admin stats [world]` reports native memory use, loaded/reserved
  chunks, and shield totals for tracked worlds.
- `/griefprot admin lookup [world] <x> <y> <z>` reports the block's face
  durability, shield points and block flags. A player may omit coordinates to
  inspect the block they are looking at; a world name is optional for player
  coordinates.
- `/griefprot admin protection clear [world] <x> <y> <z>` removes reinforcement
  and shield points. `protection add [world] <x> <y> <z> <face> <durability>`
  adds or upgrades one face; `face` is down, up, north, south, west, or east.
  `protection unlock [world] <x> <y> <z>` clears the face lock and the piston
  waiver without touching the reinforcement -- the only way back from the two
  flags, since neither has an item that undoes it.
- `/griefprot admin shield list [world]`, `create`, `remove`, `power`, `fuel`,
  `targets-clear`, and `target <add|remove>` manage shield state and targets.
  These commands use shield anchor coordinates; target edits also require the
  target coordinates. Players may omit coordinates for commands on one shield
  by looking at its anchor block. `remove` on a generator clears its block marker
  too, since otherwise the chunk load would build the shield again and the
  removal would only look like it worked.

All mutation commands require loaded GriefProt chunks. Global reinforcement
record counts are not currently available from the native API; per-block values
are available through `lookup`.

## Storage

Reinforcement is sparse per chunk. Chunks are `16 x chunk_height x 16` (384 in
the overworld, 128 in the nether), and only blocks that actually carry
protection occupy a slot, so a chunk costs what its reinforced blocks cost and
not what its volume costs.

A reinforcement record is 10 bytes. Its layout is not written out anywhere: it is
derived from a single field list in `include/griefprot_record.h`, which also
produces the public `gp_block_reinf_t` struct, the stored byte size, and the
packer. Adding or removing a field is a one line edit to that list.

```
X(durability,    u16 durability[4],  12, 4)   // 48 bits, break events per slot
X(face,          u8  face[4],         3, 4)   // 12 bits, which face each slot covers
X(slot_flags,    u8  slot_flags[4],   2, 4)   //  8 bits, per face behaviour
X(motion_block,  u8  motion_block,    6, 1)   //  6 bits, piston constraint
                                                // 74 bits used, 6 spare in the 80
```

`gp_block_reinf_faces` counts the reinforced faces rather than storing a count, so
there is no counter that can drift out of step with the faces it describes. A
record with no faces but a non zero `motion_block` is still a live record: a
constraint is meaningful on a block nobody reinforced, and it travels with the
block through a piston push.

`slot_flags` and `motion_block` are plumbing. The library stores, merges, moves
and saves them, but no rule consults them yet; the host cannot read or write them
either. They are in place so the per face and per block mechanics that would use
them do not have to change the format first.

Durability is 12 bits, which caps a face at `GP_REINF_DURABILITY_MAX` (4095)
break events. `gp_chunk_reinf_add` refuses a wider tier outright rather than
truncating it, `gp_abi_reinf_durability_max` reports the ceiling to hosts, and a
merge that would overflow it saturates.

```
config: copper=32 iron=256 pool=4096 regen=20 range=4 decay=1/300s
  100 reinforced blocks: 3776 bytes sparse vs 983040 dense (260.3x)
all tests passed
```

Shield points live in the chunk that owns the block, not in the shield's block
entity. A block therefore never has to load its shielding shield's chunk to read
its own protection; the cost is that it bleeds shield points while that shield
sits in an unloaded chunk.

## Build

```
make             # build/libgriefprot.a and build/test.exe
make test        # same, then run the suite
make abi         # build/griefprot_ffi.dll, the FFM shim for the Java host
make abi-test    # build and run the shim's own tests
make abi-exports # assert every header function is reachable by name
make clean
```

Uses `temlib` from the sibling directory, following its layout: `src/`,
`include/`, `mains/`, one static lib plus one target per file in `mains/`.

## Plugin

The Paper plugin lives in `java/plugin` and talks to `gp_abi.h` through Java 25
FFM. Build it from a MSYS2 MINGW64 shell with:

```
./scripts/build_plugin.sh              # or `make java-plugin`; same as `package`
./scripts/build_plugin.sh test         # any Maven goals you like
```

That runs `make abi`, stages the shim as a jar resource under the name
`NativeLib` looks for, and runs Maven's `clean` before the requested goals, so a
packaged plugin never carries stale compiled classes. It finds a Java 25 JDK and
a Maven launcher itself, preferring `JAVA_HOME` and `MAVEN_HOME` when they are
usable. `build_plugin.ps1` forwards to it for the same reason: the build needs
the MSYS2 toolchain and an MSYS makefile, so PowerShell has nothing useful to do
here.

Maven rather than Gradle, deliberately. Gradle 8.13 cannot run on a Java 25 JVM
at all: its embedded Kotlin `JavaVersion.parse` throws on `25.0.2` before the
build starts. A Java 25 toolchain declaration needs Gradle 9.1+, and javac from an
older JDK cannot target 25 either, so no Gradle version available here can compile
it.

The shared library is shipped inside the jar as a resource and unpacked at
startup, because FFM can only load a library from a real file on disk. It is
loaded into `Arena.global()`, so it is never unloaded and the unpacked copy is
left in place; on Windows a loaded DLL cannot be deleted anyway.

`api-version` is `26.1.2`, matching the server's `currentApiVersion`.

## Layout

| file | role |
|---|---|
| `include/griefprot.h` | positions, tunables, reinforcement tiers |
| `include/griefprot_sparse.h` | open-addressing u32 -> fixed-record map, the storage primitive |
| `include/griefprot_chunk.h` | per-chunk reinforcement, shield points, break resolution, decay |
| `include/griefprot_shield.h` | shield entity, target list, round robin regeneration |
| `include/griefprot_world.h` | chunk registry keyed by chunk coords, shield registry |
| `include/griefprot_serialize.h` | zlib/gzip TKV pack and unpack, per chunk and whole world |
| `mains/test.c` | test suite, 32 groups |
| `java/native/gp_abi.h` | the flat FFM-facing surface, and its only supported entry points |
| `java/native/gp_abi.c` | shim implementation: handles, coordinate mapping, callback bridge |
| `java/native/gp_abi_test.c` | shim contract tests, 184 checks |
| `java/plugin/src/main/java/com/tem/griefprot/nativebridge/Abi.java` | every downcall, typed; the whole Java side of the C boundary |
| `java/plugin/src/main/java/com/tem/griefprot/HostSink.java` | the three host callbacks as FFM upcall stubs |
| `java/plugin/src/main/java/com/tem/griefprot/world/GriefWorld.java` | one native world per dimension, chunk residency, persistence |
| `java/plugin/src/test/java/com/tem/griefprot/nativebridge/AbiTest.java` | 29 checks driving the real compiled library through FFM |

## Java host boundary

A JVM cannot call C structs by value without pinning down their layout, and a
layout that is wrong on one compiler is silently wrong rather than a compile
error. So Java never sees `gp_pos`, `gp_config`, `gp_break_result` or
`gp_shield_sink`. It sees only:

- `gp_h_world` and `gp_h_shield`, which are `uint32_t` handles, 0 meaning invalid
- scalars, by value
- callback addresses, which Java turns into upcall stubs
- out-parameters, which are pointers to Java-allocated `MemorySegment`

The shim owns every translation: world position to chunk plus local
coordinates, handle to object, and the sink callbacks from the library's
`gp_pos`-by-value form to the host's flat form. Chunk level calls take local
coordinates, so anything that forgets the conversion is wrong outside chunk 0
rather than crashing, which is why the shim resolves the chunk and its locals
together in one place.

`gp_abi_check()` verifies the assumptions the Java side hardcodes (pointer
width, `u32`/`u64` width, `gp_pos` field order, the face enum values) and returns
a bitmask, so a toolchain mismatch fails at startup instead of corrupting state
later. Call it before anything else.

Two conventions the host depends on:

- Every entry point tolerates handle 0 and returns a defined value rather than
  faulting, and always writes its out-parameters, so a stale handle in Java
  cannot take the server down or make a decision from uninitialised memory.
- `gp_abi_resolve_break` on an unloaded chunk reports "nothing protected this"
  and does not load the chunk. Resolving a break never allocates.

## Persistence

Serialization writes TKV trees through a `stream_t`, so passing a compressed
stream to `stream_open_write` gets gzip for free and a plain stream gets raw
bytes.

Per chunk:

```c
stream_t out;
stream_open_write("region/0.0.gz", 1, &out);
gp_chunk_write_stream(chunk, &out);
stream_close(&out);
```

and back with `gp_chunk_read_stream`.

Whole world:

```c
gp_world_write_stream(world, &out);
```

which writes a small metadata object followed by length prefixed per chunk and
per shield trees. Reading needs the metadata object first, then the stream:

```c
tkv_object header = tkv_read_from_stream(&in, scratch, meta);
i32 n = gp_world_unpack(header, &in, world, shield_pool, pool_size, target_cap, scratch);
```

Three constraints shape the format:

- `tkv_object_add_field` serializes through an 8192 byte stack buffer, so no
  single value can exceed that. Chunk records are therefore split across
  numbered array groups (`r0`, `r1`, ...). The number of records per group is
  derived from the record size rather than fixed, so widening a record spills
  into another group instead of overflowing that buffer.
- `add_field` copies the object and returns a new pointer, so packing code must
  rebind the object on every field.
- TKV converts endianness for scalars via `stream_read`, but array payloads are
  copied verbatim. Every multi byte value inside a record is encoded little
  endian by hand so a save is portable between hosts. The same rule governs the
  block record itself: bits are packed lowest first within each byte, and a
  field's elements are laid out in member address order.

Record layouts are versioned by `GP_FORMAT_VERSION`; a mismatch is refused
rather than misread, because silently loading an empty chunk would wipe
protection data.

The current version is 4. Version 4 added a two bit per-block `flags` field to the
record: the face lock and the piston waiver. The record is still 10 bytes, which is
precisely why the bump matters -- a v3 file read as v4 would decode the two
highest bits as flags nobody had ever set and come back with every block in the
world both locked and piston free.

Version 3 replaced the hand written bit layout with the derived field list:
durability dropped from 16 to 12 bits, the stored slot counter was removed, and
per slot flags plus a piston constraint mask took the space. Version 2 files are
rejected rather than migrated, for the same reason as v3: the record is the same
10 bytes but the bits inside it moved, so a v2 file read as v3 would give every
block the wrong faces, the wrong durability, and constraints nobody had set.

Version 2 grew the per-block shield record from 8 to 12 bytes to carry a
coverage expiry. Version 1 predates coverage claims entirely and is rejected
for the same reason: a claim cannot be reconstructed from data written before
coverage existed, and treating those blocks as uncovered would bleed every shield
the first time decay ran. A host upgrading from an earlier version should discard
its saves rather than expect them to load.

## Block movement

`gp_world_move_block(world, from, to, motion)` handles piston and dropper moves,
including across a chunk border. `motion` is the direction the block travels; the
side it was pushed or pulled from is the opposite face either way, since a block
shoved east came off its west side and one dragged west came off its east side.

It returns false, changing nothing, when:

- `motion` is not a real face, or either position lies outside the world
- the side being moved off currently carries reinforcement
- the destination's faces plus the incoming ones would exceed
  `cfg.max_faces_per_block`

On success reinforcement keeps its world space direction, since a piston
translates a block without rotating it, durability on a shared face is summed,
and shield points travel with the block. A refused move leaves both positions
untouched, so the host can cancel the piston action instead of silently losing
protection.

`gp_chunk_reinf_move` and `gp_chunk_reinf_transfer` / `gp_chunk_shield_transfer`
are the chunk-level primitives. Each transfer validates coordinates against the
chunk they belong to, so a cross chunk caller cannot address a local cell in the
source chunk by mistake.

### The piston waiver

`GP_BLOCK_FLAG_PISTON_FREE` does not change anything in `gp_world_move_block`. The
destination's protection is the destination's, and a block that is not supposed to
resist pistons should not be able to do so by proxy -- so "the destination's
entered face is reinforced" is still a refusal.

What the flag waives is the host's own veto, which is where the rules the library
cannot see live: the block may not move away from a reinforced face, may not move
into one, and a slime or honey block sitting against a reinforced face refuses the
whole move. `PistonListener.refusal` takes the flag as a predicate and skips all
three for the block carrying it, and for the protection it presents to a sticky
neighbour beside it. It is per block, not per cell: slicking one brick of a wall
does not unlock the next one.

So a host that wants the same rule needs both halves. `gp_abi_reinf_piston_free`
tells it which blocks are exempt; the library still refuses to push into a
reinforced destination face and still refuses a merge that would sum two blocks'
durability together.

## Host responsibilities

The library is deliberately ignorant of block types. The host decides what may
be protected or reinforced by answering two questions through `gp_shield_sink`:
whether a position is protectable, and granting points up to a cap. That covers
"instabreakable" blocks like torches and grass, and excluding other shields from
coverage, without the library carrying a block table.

A host also needs to route block move events through `gp_world_move_block`, and
to route `gp_world` chunks to its own chunk load and unload lifecycle.

Wiring decay up means setting one field on the sink:

```c
gp_shield_sink sink = {0};
sink.protectable = host_protectable;
sink.grant       = host_grant;
sink.cover       = gp_world_shield_cover;  // user = world
```

Zero the struct: the optional callbacks must be NULL when unused, and filling the
required fields individually leaves the rest as stack garbage.

### Shield clocks

`gp_shield_tick(shield, now, ...)` takes a host monotonic clock in whatever unit
the host likes; only differences matter. A new shield anchors `window_start` on
its first tick and grants nothing until a full window has passed. Without that
anchor a shield would treat the whole span between the epoch and its first tick
as elapsed and burn its entire fuel reserve at once, which is exactly what a host
passing wall-clock milliseconds would trigger.

A shield restored from disk is also unanchored, so it re-anchors rather than
charging fuel for the time it spent unloaded.

## Still to build

- the Paper plugin: FFM bindings, per dimension worlds, break, interaction and
  explosion listeners, chunk and piston routing
- a mod adapter once the plugin side is proven
