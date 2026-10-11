#include "griefprot_record.h"

#include <stddef.h>
#include <string.h>

/*
	Bit level packing for the block reinforcement record.

	Nothing here knows what a field means. The field list in griefprot_record.h
	is expanded into the struct, into the checks below, and into the two walks
	that copy fields between the struct and the stored bytes, so a new field only
	has to be described once.

	Bit order is least significant bit first within each byte, and bytes go out in
	address order of the struct member. Neither depends on the host's endianness,
	which matters because a record is memcpy'd verbatim into a TKV array and read
	back the same way on whatever machine loads the save.
*/

static u64 rec_mask(u32 bytes)
{
	return bytes >= sizeof(u64) ? ~(u64)0 : (((u64)1 << (bytes * 8u)) - 1u);
}

/* Writes `width` low bits of `value` at bit `*at`, leaving the rest of the
   buffer alone. The caller zeroes the record first. */
static void rec_bits_put(u8 *out, u32 *at, u32 width, u64 value)
{
	for (u32 i = 0; i < width; i++)
		if ((value >> i) & 1u)
			out[(*at + i) / 8u] |= (u8)(1u << ((*at + i) % 8u));

	*at += width;
}

/* Reads `width` bits at bit `*at`, zero extended. */
static u64 rec_bits_get(const u8 *in, u32 *at, u32 width)
{
	u64 value = 0;

	for (u32 i = 0; i < width; i++)
		if (in[(*at + i) / 8u] & (1u << ((*at + i) % 8u)))
			value |= (u64)1 << i;

	*at += width;
	return value;
}

/*
	A field is lifted out of the struct by byte range rather than by type, since
	the field list carries no type information to the packer.

	  total  bytes the whole member occupies in the struct
	  stride bytes between consecutive elements
	  bytes  bytes one element's value can span, ceil(bits / 8)

	Assembling the member into a u64 in address order is what keeps a 12 bit value
	inside a u16 from depending on the host's byte order: element e simply starts
	at bit e * stride * 8 in the assembled value.
*/
#define GP_REC_CHECK(name, declaration, bits, count)                                                        \
	static_assert((bits) >= 1u && (bits) <= 64u, "a record field must be 1..64 bits wide");                   \
	static_assert(sizeof(((const gp_block_reinf_t *)0)->name) <= sizeof(u64),                                \
	              "a record field's elements must fit in one u64");                                          \
	static_assert(sizeof(((const gp_block_reinf_t *)0)->name) % (count) == 0u,                               \
	              "a record field's element count does not divide its member");                                \
	static_assert((u64)((u32)(bits) + 7u) / 8u <= (u64)sizeof(((const gp_block_reinf_t *)0)->name) / (count),\
	              "a record field is wider than the C type it is declared with");

#define GP_REC_PACK_FIELD(name, declaration, bits, count)                                                    \
	do                                                                                                       \
	{                                                                                                        \
		GP_REC_CHECK(name, declaration, bits, count);                                                        \
		const u32 gp_stride = (u32)(sizeof(((const gp_block_reinf_t *)0)->name) / (count));                    \
		const u32 gp_bytes = ((u32)(bits) + 7u) / 8u;                                                        \
		u64 gp_raw = 0;                                                                                       \
		for (u32 gp_b = 0; gp_b < sizeof(((const gp_block_reinf_t *)0)->name); gp_b++)                        \
			gp_raw |= (u64)((const u8 *)in)[offsetof(gp_block_reinf_t, name) + gp_b] << (8u * gp_b);           \
		for (u32 gp_e = 0; gp_e < (u32)(count); gp_e++)                                                       \
			rec_bits_put(out, &at, (u32)(bits),                                                               \
			             (gp_raw >> (gp_e * gp_stride * 8u)) & rec_mask(gp_bytes));                           \
	} while (0);

#define GP_REC_UNPACK_FIELD(name, declaration, bits, count)                                                  \
	do                                                                                                       \
	{                                                                                                        \
		GP_REC_CHECK(name, declaration, bits, count);                                                        \
		const u32 gp_stride = (u32)(sizeof(((const gp_block_reinf_t *)0)->name) / (count));                    \
		const u32 gp_bytes = ((u32)(bits) + 7u) / 8u;                                                        \
		u64 gp_raw = 0;                                                                                       \
		for (u32 gp_e = 0; gp_e < (u32)(count); gp_e++)                                                       \
			gp_raw |= (rec_bits_get(in, &at, (u32)(bits)) & rec_mask(gp_bytes)) << (gp_e * gp_stride * 8u);   \
		memcpy((u8 *)out + offsetof(gp_block_reinf_t, name), &gp_raw,                                       \
		       sizeof(((const gp_block_reinf_t *)0)->name));                                                  \
	} while (0);

void gp_block_reinf_pack(const gp_block_reinf_t *in, u8 *out)
{
	if (!in || !out)
		return;

	/* Zeroed first so the spare bits past the last field are a function of the
	   record rather than of whatever was in the buffer. */
	memset(out, 0, GP_REC_BYTES);

	u32 at = 0;
	GP_REC_FIELDS(GP_REC_PACK_FIELD)
}

void gp_block_reinf_unpack(const u8 *in, gp_block_reinf_t *out)
{
	if (!in || !out)
		return;

	memset(out, 0, sizeof(*out));

	u32 at = 0;
	GP_REC_FIELDS(GP_REC_UNPACK_FIELD)
}

#undef GP_REC_UNPACK_FIELD
#undef GP_REC_PACK_FIELD
#undef GP_REC_CHECK