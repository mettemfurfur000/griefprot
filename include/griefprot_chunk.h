#ifndef GRIEFPROT_CHUNK_H
#define GRIEFPROT_CHUNK_H

#include "general.h"
#include "griefprot_record.h"
#include "griefprot_sparse.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Bytes one per block reinforcement record occupies in the sparse map and in a
   save is GP_REC_BYTES, from the field list in griefprot_record.h. */

/*
	Per-chunk sparse reinforcement storage.

	Blocks with no reinforcement cost nothing: the backing table only grows to
	hold the blocks that actually carry protection. The serialized form is the
	table itself (dense block index + one packed record per entry), which is what
	gets written into chunk NBT by the platform adapters.
*/
typedef struct
{
	gp_sparse reinf; // per face reinforcement records
	gp_sparse shield; // per block shield points
	u32 chunk_height;
	u32 reinf_entries; // live reinforcement records
	u32 shield_entries;

	/* Decay scheduling. Shield points bleed once no powered shield covers them,
	   so instead of walking every block in the chunk we keep a per chunk due
	   time and only sweep the shield map when it arrives. */
	u64 next_decay;
} gp_chunk_reinf;

void gp_chunk_reinf_init(gp_chunk_reinf *chunk, u32 chunk_height);
void gp_chunk_reinf_free(gp_chunk_reinf *chunk);

/*
 * Reinforces `face` with durability `amount`, which is a tier value rather than
 * an increment: the face ends up able to absorb exactly `amount` more breaks.
 *
 * If the face already carries a plate, a strictly stronger one upgrades it in
 * place, and an equal or weaker one is refused. Nothing stacks, so durability
 * can never exceed the best tier available, and a weak plate can never undo a
 * strong one.
 *
 * A block carrying GP_BLOCK_FLAG_LOCK_FACES still takes upgrades but opens no new
 * face, which is what the flag is for.
 *
 * `amount` must be greater than zero and no more than
 * GP_REINF_DURABILITY_MAX, which is the most a face can hold in the record.
 * Returns false when the face is already at or above `amount`, when the block is
 * locked and the face is not one it already carries, when all four slots are
 * taken and this face is not one of them, when `amount` is out of range, or when
 * the position is invalid. A false return means the block was not modified, so a
 * host can keep the item it was about to spend.
 */
bool gp_chunk_reinf_add(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, gp_face_t face, u16 amount);

/* True when the given face carries any reinforcement. */
bool gp_chunk_reinf_face_active(const gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, gp_face_t face);

/* Reads the block record. Returns false when the block carries no record at all,
   which is not the same as carrying a record with no reinforced face: a block
   held in place by a motion constraint has no face and still has a record. Use
   gp_block_reinf_faces on the result to ask how many faces are protected. */
bool gp_chunk_reinf_get(const gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, gp_block_reinf_t *out);

/*
	Per slot flags, one of GP_SLOT_FLAG_*.

	Stored and carried by merges, moves and saves, but no rule reads them yet.
	They are reachable now so the layout and the round trip are settled before
	anything depends on them.
*/
u8 gp_chunk_reinf_slot_flags(const gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, gp_face_t face);

/* Replaces the flags on `face`. Pass 0 to clear them. False when the face is not
   reinforced or the position is invalid, in which case nothing changed. */
bool gp_chunk_reinf_slot_flags_set(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, gp_face_t face, u8 flags);

/* Directions a piston may not move this block in, a bit per GP_FACE_*.

	Stored and carried by merges, moves and saves, but no move consults it yet.
	Because the mask defaults to GP_MOTION_BLOCKED_NONE, a block nobody has
	constrained reads back as zero, so adding the constraint costs nothing on the
	blocks that do not have one. */
u8 gp_chunk_reinf_motion_block(const gp_chunk_reinf *chunk, u32 x, u32 y, u32 z);

/* Replaces the mask, masking `blocked` down to the six real directions. Creates a
   record on a block that has none, since a constraint is meaningful without any
   reinforced face. False only when the position is invalid. */
bool gp_chunk_reinf_motion_block_set(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, u8 blocked);

/* Per block flags, a bit per GP_BLOCK_FLAG_*, or GP_BLOCK_FLAG_NONE when the block
   carries no record at all. */
u8 gp_chunk_reinf_flags(const gp_chunk_reinf *chunk, u32 x, u32 y, u32 z);

/*
	Writes `flags & mask` into the block's flags, leaving every other bit alone, so
	a host can set one flag without having to know the current value of the other.

	Unlike the motion mask this refuses a block with no reinforced face rather than
	creating a record for it. Both flags describe what the protection on a block
	means, so on an unprotected block they mean nothing, and a record holding only
	one would outlive the block: gp_block_reinf_empty ignores the flags, so the
	record would sit in the sparse map until the block itself was cleared. False
	when the position is invalid or the block carries no reinforced face, in which
	case nothing changed.
*/
bool gp_chunk_reinf_flags_set(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, u8 flags, u8 mask);

/* Consumes one break event on `face`. Returns true while the face still has
   durability left, false once the face is exhausted and the block breaks. */
bool gp_chunk_reinf_consume(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, gp_face_t face);

/* Removes all protection from a block, e.g. once the block itself is gone. */
void gp_chunk_reinf_clear(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z);

/* Moves every protection record from one position to another, keeping slot and
   face assignment intact. Used for pistons, droppers and any other block move. */
bool gp_chunk_reinf_move(gp_chunk_reinf *chunk, u32 from_x, u32 from_y, u32 from_z, u32 to_x, u32 to_y,
                         u32 to_z);

/* Chunk to chunk protection transfer. Each coordinate is validated against the
   chunk it belongs to, so a cross chunk caller cannot address a local cell in
   the source chunk by mistake. `src` and `dst` may be the same chunk. Refuses a
   reinforcement merge that would need more distinct faces than the record can
   hold, rather than dropping one.

   Flags on a face shared by both blocks are or'd, and so are the per block
   flags and the motion mask, so a constraint held at either end of a push
   survives the merge. */
bool gp_chunk_reinf_transfer(gp_chunk_reinf *src, u32 from_x, u32 from_y, u32 from_z,
                             gp_chunk_reinf *dst, u32 to_x, u32 to_y, u32 to_z);

/* Moves a block's shield points, summing with the destination's. */
bool gp_chunk_shield_transfer(gp_chunk_reinf *src, u32 from_x, u32 from_y, u32 from_z,
                              gp_chunk_reinf *dst, u32 to_x, u32 to_y, u32 to_z);

/* Callback used when walking or dumping records, e.g. to write NBT. */
typedef void (*gp_reinf_visit_fn)(void *user_data, u32 x, u32 y, u32 z, const gp_block_reinf_t *reinf);
void gp_chunk_reinf_visit(const gp_chunk_reinf *chunk, gp_reinf_visit_fn fn, void *user_data);

/* Rough heap footprint of the chunk's protection data, in bytes. */
u64 gp_chunk_reinf_memory(const gp_chunk_reinf *chunk);

/*
	Shield points.

	Deliberately a separate number from the per-face reinforcement above: a
	shield covers the whole block while reinforcement only covers the faces it
	was applied to, and the two are consumed by different events.

	These values live in the chunk that owns the block, not in the shield's
	block entity. That keeps a chunk from having to load its shielding shield's
	neighbourhood, at the cost of a block losing its shield points while the
	shield sits in an unloaded chunk.
*/
u32 gp_chunk_shield_get(const gp_chunk_reinf *chunk, u32 x, u32 y, u32 z);

/* Adds `amount` points, saturating at `cap` (pass 0 for uncapped). Returns the
   number of points actually added. */
u32 gp_chunk_shield_add(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, u32 amount, u32 cap);

/* Removes up to `amount` points. Returns the number actually removed. */
u32 gp_chunk_shield_take(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, u32 amount);

/* Records that a live shield covers this block until `expiry` on the host's
   clock. Decay skips covered blocks, so a block that is being held up never dips
   between regeneration windows.

   The claim is a deadline rather than a reference count on purpose: shields
   unload, get broken, run out of fuel, or have their block removed, and none of
   those notify the blocks they were covering. A claim that has to be released
   explicitly would outlive its shield and freeze decay forever. With a deadline,
   a shield that stops refreshing simply lets its coverage lapse one interval
   later.

   `expiry` is saturating, so UINT64_MAX means "covered indefinitely". */
void gp_chunk_shield_cover(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, u64 expiry);

/* Whether decay is allowed to touch this block. True when the block holds no
   shield points at all, or its coverage claim has lapsed. */
bool gp_chunk_shield_uncovered(const gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, u64 now);

/* How many blocks in this chunk decay currently skips because a live shield
   covers them. */
u32 gp_chunk_shield_covered_count(const gp_chunk_reinf *chunk, u64 now);

typedef void (*gp_shield_visit_fn)(void *user_data, u32 x, u32 y, u32 z, u32 points);
void gp_chunk_shield_visit(const gp_chunk_reinf *chunk, gp_shield_visit_fn fn, void *user_data);

/* Runs one decay sweep if `now` is at or past the chunk's due time, then
   schedules the next one. Only blocks whose coverage has lapsed lose `amount`
points; a block a live shield still covers is skipped, and that is counted in
`blocks_skipped` when non-NULL. All other blocks lose `amount` points; blocks
that reach zero are dropped from the sparse map. Returns the number of points
   removed, and sets `blocks_touched` when it is not NULL. */
u32 gp_chunk_shield_decay(gp_chunk_reinf *chunk, u64 now, u32 interval, u32 amount, u32 *blocks_touched, u32 *blocks_skipped);

/* True when the chunk is due for a decay sweep. next_decay == 0 means never
   scheduled yet, so freshly placed shield points get a full interval of grace. */
static inline bool gp_chunk_decay_due(const gp_chunk_reinf *chunk, u64 now)
{
	return chunk->shield_entries > 0 && chunk->next_decay != 0 && now >= chunk->next_decay;
}

/* What a break event found on a block. Reinforcement and shield points are
   tracked separately, so a block can have either, both, or neither. */
typedef struct
{
	bool reinf_survived;  // a reinforced face absorbed the break
	u32 shield_absorbed;  // shield points spent on this break
	u32 shield_left;      // shield points remaining afterwards
	bool block_broke;     // nothing left to absorb it
} gp_break_result;

/* Resolves one break event against a block.

   Reinforcement is checked first, per face: a reinforced face absorbs the break
   and the block survives. Only an unprotected face falls through to shield
   points. Returns false and leaves everything untouched when the position is out
   of the chunk's range. */
bool gp_chunk_resolve_break(gp_chunk_reinf *chunk, u32 x, u32 y, u32 z, gp_face_t face, gp_break_result *out);

#ifdef __cplusplus
}
#endif

#endif // GP_GRIEFPROT_CHUNK_H
