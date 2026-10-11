#ifndef GRIEFPROT_SERIALIZE_H
#define GRIEFPROT_SERIALIZE_H

#include "general.h"
#include "griefprot.h"
#include "griefprot_chunk.h"
#include "griefprot_shield.h"
#include "griefprot_world.h"
#include "arena.h"
#include "data_io.h"
#include "tkv.h"

#ifdef __cplusplus
extern "C" {
#endif

/* On-disk format version. Bumped whenever a record layout changes so old data
   is detected instead of misread.
 *
 * Version 4: the per block reinforcement record gained a two bit flags field,
 * holding the face lock and the piston freedom a host sets on a block. The record
 * is still 10 bytes, so this is the case the bump exists for: v3 files read as v4
 * would decode the two highest bits of the record as flags nobody ever set, and
 * every one of those blocks would come back both face locked and piston free.
 * Version 3 files are rejected rather than migrated, same as v1 and v2.
 *
 * Version 3: the per block reinforcement record is now derived from the field
 * list in griefprot_record.h. Durability dropped from 16 to 12 bits, the
 * redundant slot counter is gone, and per slot flags plus a physics constraint
 * mask took its place. Version 2 files are rejected rather than migrated: the
 * record is the same 10 bytes but the bits inside it are in different places, so
 * a v2 file read as v3 would give every block the wrong faces, the wrong
 * durability, and constraints that were never set.
 *
 * Version 2 grew the per block shield record from 8 to 12 bytes to carry a
 * coverage expiry. Version 1 predates coverage claims entirely and is rejected
 * for the same reason: a claim cannot be reconstructed from data written before
 * coverage existed. */
#define GP_FORMAT_VERSION 4

/* Bytes one serialized reinforcement record takes: a u32 block index followed by
   one packed block record. Derived, so widening a field cannot silently push the
   group below without anything noticing. */
#define GP_REINF_REC_SIZE (sizeof(u32) + GP_REC_BYTES)

/* temlib's tkv_object_add_field writes values through an 8192 byte stack buffer,
   so a single array field can never exceed that. Records are therefore split
   across numbered groups, and the number per group is derived from the record
   size rather than fixed: a format change that widens a record would otherwise
   overflow that buffer rather than just spilling into another group. */
#define GP_TKV_FIELD_BYTES 8192u
#define GP_TKV_FIELD_SLACK 128u

#define GP_REINF_RECORDS_PER_GROUP ((GP_TKV_FIELD_BYTES - GP_TKV_FIELD_SLACK) / GP_REINF_REC_SIZE)

/* Largest block index representable, given a 16 x H x 16 chunk with a sane H. */
#define GP_MAX_BLOCK_INDEX 0xFFFFFFFBu

/* --- per chunk -------------------------------------------------------------- */

/* Builds a TKV object describing `chunk`. `out` owns the result, which is small
   enough (well under 8 KB per field) to write straight to a stream.
   Returns NULL on allocation failure or if the chunk holds an impossible
   number of records. */
tkv_object gp_chunk_pack(const gp_chunk_reinf *chunk, arena *out);

/* Restores a chunk from an object produced by gp_chunk_pack. Data already held
   by `chunk` is released first. Returns false on malformed input or a version
   mismatch. */
bool gp_chunk_unpack(tkv_object obj, gp_chunk_reinf *chunk);

/* Writes one self delimiting chunk blob: u32 byte length, then that many bytes.
   Reading one back is `gp_chunk_read_stream`. */
u8 gp_chunk_write_stream(const gp_chunk_reinf *chunk, stream_t *s);
bool gp_chunk_read_stream(stream_t *s, gp_chunk_reinf *chunk, arena *scratch, arena *out);

/* --- shield ----------------------------------------------------------------- */

/* Shield state including the whole target list, stored as flat i32 triples. */
tkv_object gp_shield_pack(const gp_shield *shield, arena *out);

/* Restores into `shield`, which must have room for at least
   `shield->target_capacity` targets. Returns false on malformed input. */
bool gp_shield_unpack(tkv_object obj, gp_shield *shield);

/* --- whole world ------------------------------------------------------------ */

/*
   The world is written as a small metadata TKV object followed by length
   prefixed per chunk and per shield blobs. Nesting every chunk inside one TKV
   object would be quadratic in temlib, and one object cannot exceed 8 KB per
   value, so each chunk gets its own tree instead.

   On a gzip stream the whole container compresses as one unit, which suits
   protection data that is mostly small repeated records.

   Shields are restored into caller supplied storage: `shield_pool` holds up to
   `shield_pool_size` slots, each created with room for
   `shield_target_capacity` targets. They are registered with the world but not
   owned by it; release them with gp_shield_destroy. Pass NULL/0 to load chunk
   data only. Returns the number of shields restored, or -1 on failure.
*/
i32 gp_world_unpack(tkv_object header, stream_t *s, gp_world *world, gp_shield **shield_pool,
                    u32 shield_pool_size, u32 shield_target_capacity, arena *scratch);

u8 gp_world_write_stream(const gp_world *world, stream_t *s);

/* Builds just the world metadata object. Exposed so hosts can inspect a save
   before committing to loading it. */
tkv_object gp_world_pack_header(const gp_world *world, arena *out);

/* --- helpers ---------------------------------------------------------------- */

/* Serialized size of a chunk in bytes, including its length prefix. Measuring a
   real object is exact; this is the cheap upper bound. */
u64 gp_chunk_packed_size(const gp_chunk_reinf *chunk);

#ifdef __cplusplus
}
#endif

#endif // GP_GRIEFPROT_SERIALIZE_H