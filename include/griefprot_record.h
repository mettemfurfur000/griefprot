#ifndef GRIEFPROT_RECORD_H
#define GRIEFPROT_RECORD_H

#include "general.h"

#ifdef __cplusplus
extern "C" {
#endif

/* How many distinct faces one block may carry reinforcement on at once. */
#define GP_MAX_REINFORCED_FACES 4

/* Face indices.

   NONE is deliberately zero: reinforcement records are zero filled when
   allocated, so zero must mean "slot unused" rather than a real face. */
typedef enum
{
	GP_FACE_NONE = 0,
	GP_FACE_DOWN = 1,
	GP_FACE_UP = 2,
	GP_FACE_NORTH = 3,
	GP_FACE_SOUTH = 4,
	GP_FACE_WEST = 5,
	GP_FACE_EAST = 6,
	GP_FACE_COUNT = 7
} gp_face_t;

/*
	Per slot behaviour bits, stored as GP_SLOT_FLAG_*.

	Declared and round tripped, but nothing in the library reads them yet. They
	exist so a per face rule (internal rebar that only holds while an adjacent
	side still does, for instance) can be added as a bit without touching the
	layout at all.
*/
#define GP_SLOT_FLAG_ANCHORED 0x1u

/* Directions a piston may not move the block in, one bit per GP_FACE_*, indexed
   by the face the block travels towards.

   Zero means nothing is blocked, so an untouched record costs nothing on disk and
   a block that was never constrained behaves exactly as it did before. Like the
   slot flags this is plumbing: the library carries the mask but does not yet
   refuse a move on it. */
#define GP_MOTION_BLOCKED_NONE 0x00u
#define GP_MOTION_BLOCKED_ALL 0x3Fu

/*
	Per block behaviour, stored as GP_BLOCK_FLAG_*.

	Where GP_SLOT_FLAG_* describes one reinforced face, these describe the block as
	a whole, so there is one of them whatever the record's four slots hold. Unlike
	the slot flags and the motion mask these are read by the library: gp_chunk_reinf_add
	refuses to open a new face on a locked block, and gp_world_move_block is bypassed
	for a piston free one.

	Both are host decisions about the physical block rather than about its plates,
	so both travel with it: merge_reinf ors them, and a move carries them to the
	cell the block lands in.

	Neither keeps a record alive on its own. A block with no reinforced faces is
	not protected, so a lock on it has nothing to lock and piston freedom on it has
	nothing to waive; gp_block_reinf_empty therefore ignores both.
*/
#define GP_BLOCK_FLAG_NONE 0x0u

/* No further face may be added to this block. Upgrading a face it already
   carries is still allowed: the point is to stop the build growing new protected
   sides, not to stop the owner reinforcing the ones they built. */
#define GP_BLOCK_FLAG_LOCK_FACES 0x1u

/* A piston may push or pull this block without any of the rules the library
   otherwise applies, so a reinforced block can still be part of a moving
   contraption. Its protection travels with it as usual. */
#define GP_BLOCK_FLAG_PISTON_FREE 0x2u

#define GP_BLOCK_FLAG_ALL (GP_BLOCK_FLAG_LOCK_FACES | GP_BLOCK_FLAG_PISTON_FREE)

/*
	The block reinforcement record.

	GP_REC_FIELDS below is the only place a field is declared. The public struct,
	the stored byte size, packing and unpacking, and the record group sizing in
	the saver are all derived from it, so adding or removing a field is a one line
	edit here plus whatever reads the new value.

	Each entry is

	    X(name, declaration, bits per element, element count)

	  name        must match the declaration, since it is used with offsetof
	  declaration the member exactly as it appears inside the struct
	  bits        width of one element in the stored record, 1..64
	  count       elements in the member, 1 for a scalar

	Fields are laid out in list order, lowest bit first within each byte, so
	appending a field leaves every existing field exactly where it was.

	The rules the list has to keep, all of them checked by a static_assert in the
	packer rather than by a comment:

	  - bits must fit the C type the declaration uses
	  - a field's elements together must fit in one u64, which is how the generic
	    packer lifts them out of the struct without knowing their type
	  - the element count must divide the member, so an element's offset inside
	    it is a stride the packer can compute

	A value wider than its field is truncated on the way in. That is why
	GP_REINF_DURABILITY_MAX has to stay in step with the durability entry below;
	mains/test.c pins that by filling the field to its cap and reading it back.
*/

#define GP_REC_FIELDS(X)                                                                                       \
	/* Remaining break events a slot absorbs. 12 bits caps a face at 4095 hits,   \
	   which is 16x the default iron tier and keeps the record at 10 bytes. */      \
	X(durability, u16 durability[GP_MAX_REINFORCED_FACES], 12, GP_MAX_REINFORCED_FACES)                        \
	/* Which face each slot covers. GP_FACE_NONE means the slot is unused. */                                   \
	X(face, u8 face[GP_MAX_REINFORCED_FACES], 3, GP_MAX_REINFORCED_FACES)                                      \
	/* Per slot behaviour, see GP_SLOT_FLAG_*. */                                                              \
	X(slot_flags, u8 slot_flags[GP_MAX_REINFORCED_FACES], 2, GP_MAX_REINFORCED_FACES)                          \
	/* Physics constraint, see GP_MOTION_BLOCKED_*. */                                                         \
	X(motion_block, u8 motion_block, 6, 1)                                                                     \
	/* Per block behaviour, see GP_BLOCK_FLAG_*. */                                                            \
	X(flags, u8 flags, 2, 1)

/* Sum of every field's width. Adding a field moves this on its own. */
#define GP_REC_BITS_TERM(name, declaration, bits, count) + ((u32)(bits) * (u32)(count))
#define GP_REC_TOTAL_BITS (0u GP_REC_FIELDS(GP_REC_BITS_TERM))

/* How many fields the list declares. A host walking the layout, or a test
   covering it, loops over this rather than repeating the count. */
#define GP_REC_COUNT_TERM(name, declaration, bits, count) + 1u
#define GP_REC_FIELD_COUNT (0u GP_REC_FIELDS(GP_REC_COUNT_TERM))

/* Bytes one record occupies in the sparse map and in a save. */
#define GP_REC_BYTES ((GP_REC_TOTAL_BITS + 7u) / 8u)

/* Bits the declared fields do not use. Kept for documentation: appending a field
   spends them, and the record only grows once they run out. */
#define GP_REC_SPARE_BITS (GP_REC_BYTES * 8u - GP_REC_TOTAL_BITS)

/* Largest durability a face can hold, and therefore the largest amount
   gp_chunk_reinf_add will accept. This is the width of the durability entry in
   the list below, spelled out because callers need it as a constant; the test
   suite fills the field to this value and reads it back, so the two cannot drift
   apart unnoticed. */
#define GP_REINF_DURABILITY_MAX ((1u << 12u) - 1u)

/* Public, unpacked view of one block's reinforcement state.

   Deliberately a plain struct rather than a packed mirror of the stored bytes:
   the record format is an implementation detail the generic packer owns, and a
   host should not be able to depend on either the field order or the padding. */
typedef struct
{
#define GP_REC_MEMBER(name, declaration, bits, count) declaration;
	GP_REC_FIELDS(GP_REC_MEMBER)
#undef GP_REC_MEMBER
} gp_block_reinf_t;

/* How many slots carry a real face, 0..GP_MAX_REINFORCED_FACES.

   Derived from the face ids rather than stored. A separate counter would be one
   more thing every mutation has to keep in sync, and it could disagree with the
   faces it is supposed to be counting. */
static inline u32 gp_block_reinf_faces(const gp_block_reinf_t *r)
{
	u32 n = 0;
	for (u32 i = 0; i < GP_MAX_REINFORCED_FACES; i++)
		if (r->face[i] != GP_FACE_NONE)
			n++;
	return n;
}

/* Slot holding `face`, or -1 when the block does not carry it. */
static inline int gp_block_reinf_slot(const gp_block_reinf_t *r, gp_face_t face)
{
	for (u32 i = 0; i < GP_MAX_REINFORCED_FACES; i++)
		if (r->face[i] != GP_FACE_NONE && r->face[i] == (u8)face)
			return (int)i;

	return -1;
}

/* No further face may be opened on this block. */
static inline bool gp_block_reinf_faces_locked(const gp_block_reinf_t *r)
{
	return (r->flags & GP_BLOCK_FLAG_LOCK_FACES) != 0;
}

/* A piston moves this block without the library's push and pull rules. */
static inline bool gp_block_reinf_piston_free(const gp_block_reinf_t *r)
{
	return (r->flags & GP_BLOCK_FLAG_PISTON_FREE) != 0;
}

/* True when the record holds nothing worth keeping.

   A block can carry a motion constraint without any reinforced face, so emptiness
   cannot be decided on the face count alone.

   The per block flags are deliberately not part of this. Both of them describe
   what protection on the block means, so a block with no reinforced face has
   nothing for either to say, and a record holding only a flag is one the sparse
   map would be paying for indefinitely. */
static inline bool gp_block_reinf_empty(const gp_block_reinf_t *r)
{
	return gp_block_reinf_faces(r) == 0 && r->motion_block == GP_MOTION_BLOCKED_NONE;
}

/* Writes `in` as GP_REC_BYTES bytes at `out`. Padding past the last field is
   zeroed, so two equal records always produce equal bytes. */
void gp_block_reinf_pack(const gp_block_reinf_t *in, u8 *out);

/* Reads one record written by gp_block_reinf_pack. */
void gp_block_reinf_unpack(const u8 *in, gp_block_reinf_t *out);

#ifdef __cplusplus
}
#endif

#endif // GRIEFPROT_RECORD_H