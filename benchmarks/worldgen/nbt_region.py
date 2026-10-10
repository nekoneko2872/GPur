"""Small, dependency-free Anvil/NBT reader for semantic worldgen comparisons.

This deliberately handles the common gzip/zlib/raw Anvil compression formats.
Unsupported region compression fails closed rather than silently skipping data.
"""

from __future__ import annotations

from dataclasses import dataclass
import gzip
import math
from pathlib import Path
import re
import struct
import zlib


REGION_NAME = re.compile(r"^r\.(-?\d+)\.(-?\d+)\.mca$")
SECTOR_BYTES = 4096
HEADER_BYTES = SECTOR_BYTES * 2
MAX_NBT_ARRAY = 128 * 1024 * 1024
MAX_NBT_DEPTH = 512
STARLIGHT_LIGHT_VERSION = 10


@dataclass(frozen=True)
class TagList:
    element_type: int
    values: tuple["Tag", ...]


@dataclass(frozen=True)
class Tag:
    tag_type: int
    value: object


class NbtError(ValueError):
    pass


class Reader:
    def __init__(self, data: bytes):
        self.data = memoryview(data)
        self.offset = 0

    def take(self, count: int) -> memoryview:
        if count < 0 or count > MAX_NBT_ARRAY or self.offset + count > len(self.data):
            raise NbtError(f"invalid/truncated NBT read: offset={self.offset} count={count} size={len(self.data)}")
        start = self.offset
        self.offset += count
        return self.data[start:self.offset]

    def unpack(self, fmt: str):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def string(self) -> str:
        length = self.unpack(">H")
        try:
            return bytes(self.take(length)).decode("utf-8")
        except UnicodeDecodeError as error:
            raise NbtError(f"invalid UTF-8 NBT string at offset {self.offset - length}") from error

    def payload(self, tag_type: int, depth: int = 0) -> Tag:
        if depth > MAX_NBT_DEPTH:
            raise NbtError("NBT nesting exceeds safety limit")
        if tag_type == 1:
            value = self.unpack(">b")
        elif tag_type == 2:
            value = self.unpack(">h")
        elif tag_type == 3:
            value = self.unpack(">i")
        elif tag_type == 4:
            value = self.unpack(">q")
        elif tag_type == 5:
            value = bytes(self.take(4))
        elif tag_type == 6:
            value = bytes(self.take(8))
        elif tag_type == 7:
            length = self.unpack(">i")
            if length < 0:
                raise NbtError(f"negative byte-array length {length}")
            value = bytes(self.take(length))
        elif tag_type == 8:
            value = self.string()
        elif tag_type == 9:
            element_type = self.unpack(">B")
            length = self.unpack(">i")
            if length < 0 or length > MAX_NBT_ARRAY:
                raise NbtError(f"invalid list length {length}")
            if element_type == 0 and length:
                raise NbtError("non-empty list has TAG_End element type")
            value = TagList(element_type, tuple(self.payload(element_type, depth + 1) for _ in range(length)))
        elif tag_type == 10:
            entries: dict[str, Tag] = {}
            while True:
                child_type = self.unpack(">B")
                if child_type == 0:
                    break
                if child_type > 12:
                    raise NbtError(f"unknown NBT tag type {child_type}")
                name = self.string()
                if name in entries:
                    raise NbtError(f"duplicate NBT compound key {name!r}")
                entries[name] = self.payload(child_type, depth + 1)
            value = entries
        elif tag_type == 11:
            length = self.unpack(">i")
            if length < 0 or length * 4 > MAX_NBT_ARRAY:
                raise NbtError(f"invalid int-array length {length}")
            value = tuple(self.unpack(">i") for _ in range(length))
        elif tag_type == 12:
            length = self.unpack(">i")
            if length < 0 or length * 8 > MAX_NBT_ARRAY:
                raise NbtError(f"invalid long-array length {length}")
            value = tuple(self.unpack(">q") for _ in range(length))
        else:
            raise NbtError(f"unknown NBT tag type {tag_type}")
        return Tag(tag_type, value)


def parse_nbt(data: bytes) -> tuple[str, Tag]:
    reader = Reader(data)
    tag_type = reader.unpack(">B")
    if tag_type != 10:
        raise NbtError(f"chunk root must be a compound, found tag type {tag_type}")
    name = reader.string()
    root = reader.payload(tag_type)
    if reader.offset != len(reader.data):
        trailing = len(reader.data) - reader.offset
        raise NbtError(f"{trailing} trailing bytes after root NBT compound")
    return name, root


def _decompress(payload: bytes, compression: int) -> bytes:
    if compression == 1:
        return gzip.decompress(payload)
    if compression == 2:
        return zlib.decompress(payload)
    if compression == 3:
        return payload
    raise NbtError(f"unsupported Anvil compression type {compression}")


def iter_region_chunks(region_directory: Path, bounds: tuple[int, int, int, int] | None = None):
    """Yield (chunk_x, chunk_z, root compound, region timestamp) in coordinate order."""
    found = []
    for path in sorted(region_directory.glob("r.*.*.mca")):
        match = REGION_NAME.match(path.name)
        if not match:
            continue
        region_x, region_z = map(int, match.groups())
        data = path.read_bytes()
        if len(data) < HEADER_BYTES:
            raise NbtError(f"truncated region header: {path}")
        if len(data) % SECTOR_BYTES:
            raise NbtError(f"region size is not sector aligned: {path}")
        locations = struct.unpack(">1024I", data[:SECTOR_BYTES])
        timestamps = struct.unpack(">1024I", data[SECTOR_BYTES:HEADER_BYTES])
        sectors = len(data) // SECTOR_BYTES
        for slot, packed in enumerate(locations):
            sector_offset, sector_count = packed >> 8, packed & 0xFF
            if sector_offset == 0 and sector_count == 0:
                continue
            if sector_offset < 2 or sector_count == 0 or sector_offset + sector_count > sectors:
                raise NbtError(f"invalid sector location in {path.name}, slot={slot}, offset={sector_offset}, count={sector_count}")
            local_x, local_z = slot % 32, slot // 32
            chunk_x = region_x * 32 + local_x
            chunk_z = region_z * 32 + local_z
            if bounds is not None:
                min_x, min_z, max_x, max_z = bounds
                if not (min_x <= chunk_x <= max_x and min_z <= chunk_z <= max_z):
                    continue
            start = sector_offset * SECTOR_BYTES
            length = struct.unpack(">I", data[start:start + 4])[0]
            if length < 1 or length > sector_count * SECTOR_BYTES - 4:
                raise NbtError(f"invalid chunk payload length in {path.name}, slot={slot}: {length}")
            compression_flags = data[start + 4]
            external = bool(compression_flags & 0x80)
            compression = compression_flags & 0x7F
            compressed = data[start + 5:start + 4 + length]
            if external:
                external_path = path.parent.parent / "c" / f"c.{chunk_x}.{chunk_z}.mcc"
                # Some Anvil implementations store external chunks beside regions.
                if not external_path.is_file():
                    external_path = path.parent / f"c.{chunk_x}.{chunk_z}.mcc"
                if not external_path.is_file():
                    raise NbtError(f"missing external chunk payload for {chunk_x},{chunk_z}")
                compressed = external_path.read_bytes()
            raw = _decompress(compressed, compression)
            _name, root = parse_nbt(raw)
            found.append((chunk_x, chunk_z, root, timestamps[slot]))
    yield from sorted(found, key=lambda item: (item[0], item[1]))


def compound(tag: Tag | None, label: str) -> dict[str, Tag]:
    if tag is None or tag.tag_type != 10:
        raise NbtError(f"{label} must be a compound")
    return tag.value  # type: ignore[return-value]


def list_tag(tag: Tag | None, label: str) -> TagList:
    if tag is None or tag.tag_type != 9:
        raise NbtError(f"{label} must be a list")
    return tag.value  # type: ignore[return-value]


def text(tag: Tag | None, label: str) -> str:
    if tag is None or tag.tag_type != 8:
        raise NbtError(f"{label} must be a string")
    return tag.value  # type: ignore[return-value]


def long_array(tag: Tag | None, label: str) -> tuple[int, ...]:
    if tag is None or tag.tag_type != 12:
        raise NbtError(f"{label} must be a long array")
    return tag.value  # type: ignore[return-value]


def _pack_width(length: int, words: tuple[int, ...], minimum_bits: int, palette_size: int) -> int:
    if not words:
        raise NbtError("non-singleton palette has no packed data")
    minimum_bits = max(minimum_bits, max(1, math.ceil(math.log2(max(2, palette_size)))))
    candidates = []
    for bits in range(minimum_bits, 33):
        per_word = 64 // bits
        if per_word and (length + per_word - 1) // per_word == len(words):
            candidates.append(bits)
    if not candidates:
        raise NbtError(f"cannot infer padded palette width: values={length}, words={len(words)}, palette={palette_size}")
    return candidates[0]


def unpack_padded(values: tuple[int, ...], length: int, bits: int) -> list[int]:
    per_word = 64 // bits
    mask = (1 << bits) - 1
    output = []
    for index in range(length):
        word = values[index // per_word] & 0xFFFFFFFFFFFFFFFF
        shift = (index % per_word) * bits
        output.append((word >> shift) & mask)
    return output


def decode_palette(container: Tag, length: int, min_bits: int, label: str) -> list[object]:
    tags = compound(container, label)
    palette_tag = list_tag(tags.get("palette"), label + ".palette")
    palette = palette_tag.values
    if not palette:
        raise NbtError(f"{label}.palette is empty")
    data_tag = tags.get("data")
    if len(palette) == 1 and data_tag is None:
        indices = [0] * length
    else:
        words = long_array(data_tag, label + ".data")
        bits = _pack_width(length, words, min_bits, len(palette))
        indices = unpack_padded(words, length, bits)
    if any(index >= len(palette) for index in indices):
        raise NbtError(f"{label} packed value indexes beyond its palette")
    decoded = [palette_entry(entry, label) for entry in palette]
    return [decoded[index] for index in indices]


def palette_entry(tag: Tag, label: str) -> object:
    if tag.tag_type == 8:
        return tag.value
    fields = compound(tag, label + " palette entry")
    name_tag = fields.get("Name") or fields.get("name")
    name = text(name_tag, label + " palette name")
    props_tag = fields.get("Properties") or fields.get("properties")
    properties = {}
    if props_tag is not None:
        for key, value in compound(props_tag, label + " palette properties").items():
            properties[key] = text(value, label + " property")
    return {"name": name, "properties": dict(sorted(properties.items()))}


def _tag_obj(tag: Tag) -> object:
    typ = tag.tag_type
    if typ == 1:
        return ["byte", tag.value]
    if typ == 2:
        return ["short", tag.value]
    if typ == 3:
        return ["int", tag.value]
    if typ == 4:
        return ["long", tag.value]
    if typ == 5:
        return ["float_bits", tag.value.hex()]  # preserve NaNs and signed zero
    if typ == 6:
        return ["double_bits", tag.value.hex()]
    if typ == 7:
        return ["byte_array", tag.value.hex()]
    if typ == 8:
        return ["string", tag.value]
    if typ == 9:
        values: TagList = tag.value  # type: ignore[assignment]
        return ["list", values.element_type, [_tag_obj(item) for item in values.values]]
    if typ == 10:
        values: dict[str, Tag] = tag.value  # type: ignore[assignment]
        return ["compound", [[key, _tag_obj(values[key])] for key in sorted(values)]]
    if typ == 11:
        return ["int_array", list(tag.value)]  # type: ignore[arg-type]
    if typ == 12:
        return ["long_array", list(tag.value)]  # type: ignore[arg-type]
    if typ == 0:
        return ["end"]
    raise NbtError(f"cannot encode tag type {typ}")


def _children_sorted(tag: Tag) -> Tag:
    if tag.tag_type == 10:
        fields: dict[str, Tag] = tag.value  # type: ignore[assignment]
        result = {}
        for key, value in fields.items():
            if key.lower() in ("children", "pieces") and value.tag_type == 9:
                children: TagList = value.value  # type: ignore[assignment]
                ordered = sorted(children.values, key=lambda entry: repr(_tag_obj(entry)))
                result[key] = Tag(9, TagList(children.element_type, tuple(ordered)))
            else:
                result[key] = _children_sorted(value)
        return Tag(10, result)
    if tag.tag_type == 9:
        values: TagList = tag.value  # type: ignore[assignment]
        return Tag(9, TagList(values.element_type, tuple(_children_sorted(item) for item in values.values)))
    return tag


def _sorted_reference_arrays(tag: Tag) -> Tag:
    if tag.tag_type == 10:
        fields: dict[str, Tag] = tag.value  # type: ignore[assignment]
        result = {}
        for key, value in fields.items():
            if key.lower() == "references" and value.tag_type == 10:
                refs = compound(value, "structure references")
                result[key] = Tag(10, {
                    ref_key: Tag(12, tuple(sorted(long_array(ref_value, f"structure reference {ref_key}"))))
                    for ref_key, ref_value in refs.items()
                })
            else:
                result[key] = _sorted_reference_arrays(value)
        return Tag(10, result)
    if tag.tag_type == 9:
        values: TagList = tag.value  # type: ignore[assignment]
        return Tag(9, TagList(values.element_type, tuple(_sorted_reference_arrays(item) for item in values.values)))
    return tag


def _clock_sentinel(tag: Tag) -> Tag:
    """Normalize only the two clock tags at the chunk root.

    Names such as ``LastUpdate`` nested inside another NBT payload are content,
    not chunk clocks, so recursion intentionally never replaces them.
    """
    if tag.tag_type == 10:
        fields: dict[str, Tag] = tag.value  # type: ignore[assignment]
        return Tag(10, {
            key: Tag(value.tag_type, "__normalized_chunk_clock__")
            if key in ("LastUpdate", "InhabitedTime") and value.tag_type in (3, 4)
            else value
            for key, value in fields.items()
        })
    return tag


def _root_fields(root: Tag) -> dict[str, Tag]:
    fields = compound(root, "chunk root")
    # Anvil region files before the modern format use a Level compound.
    if "Level" in fields:
        level_fields = compound(fields["Level"], "chunk Level")
        # Preserve root-level metadata alongside the legacy Level payload. In
        # the event of a duplicate name, the chunk's Level value is authoritative.
        return {**{key: value for key, value in fields.items() if key != "Level"}, **level_fields}
    return fields


def _section_key(section: Tag) -> int:
    fields = compound(section, "section")
    y = fields.get("Y") or fields.get("y")
    if y is None or y.tag_type != 1:
        raise NbtError("chunk section has no byte Y")
    return int(y.value)


def _decode_heightmap(tag: Tag, label: str) -> list[int]:
    words = long_array(tag, label)
    bits = _pack_width(256, words, 1, 1)
    return unpack_padded(words, 256, bits)


def _generic_list_sorted(tag: Tag | None, key_function, label: str) -> Tag | None:
    if tag is None or tag.tag_type != 9:
        return tag
    values: TagList = tag.value  # type: ignore[assignment]
    return Tag(9, TagList(values.element_type, tuple(sorted(values.values, key=key_function))))


def semantic_store_chunk(root: Tag, store: str, expected_x: int, expected_z: int) -> dict[str, object]:
    """Return canonical NBT for the entity or POI Anvil store.

    These stores have different payloads from terrain chunks, so they are kept
    intact as typed NBT. Only order-insensitive entity/POI collections are sorted.
    """
    fields = compound(root, f"{store} chunk root")
    if store == "entities":
        position = fields.get("Position") or fields.get("position")
        if position is not None:
            if position.tag_type != 11 or tuple(position.value) != (expected_x, expected_z):
                raise NbtError(f"entity region table says {expected_x},{expected_z}, Position disagrees")
        else:
            x_tag, z_tag = fields.get("xPos"), fields.get("zPos")
            if x_tag is not None or z_tag is not None:
                if (x_tag is None or z_tag is None or x_tag.tag_type != 3 or z_tag.tag_type != 3
                        or (int(x_tag.value), int(z_tag.value)) != (expected_x, expected_z)):
                    raise NbtError(f"entity region table says {expected_x},{expected_z}, xPos/zPos disagree")
        entities = fields.get("Entities") or fields.get("entities")
        if entities is not None:
            fields = dict(fields)
            key = "Entities" if "Entities" in fields else "entities"
            fields[key] = _generic_list_sorted(
                entities,
                lambda item: (
                    repr(_tag_obj(compound(item, "entity").get("UUID"))),
                    repr(_tag_obj(item)),
                ),
                "entities",
            )
    elif store == "poi":
        # POI record list order is not semantic; coordinates/type/tickets are.
        sections = fields.get("Sections") or fields.get("sections")
        if sections is not None and sections.tag_type == 10:
            section_fields = compound(sections, "POI sections")
            normalized_sections = {}
            for section_y, section in section_fields.items():
                sf = compound(section, f"POI section {section_y}")
                records_key = "Records" if "Records" in sf else "records" if "records" in sf else None
                if records_key is not None:
                    sf = dict(sf)
                    sf[records_key] = _generic_list_sorted(
                        sf[records_key], lambda item: repr(_tag_obj(item)), "POI records",
                    )
                normalized_sections[section_y] = Tag(10, sf)
            fields = dict(fields)
            key = "Sections" if "Sections" in fields else "sections"
            fields[key] = Tag(10, normalized_sections)
    else:
        raise ValueError(f"unsupported Anvil semantic store {store!r}")
    return {"coords": [expected_x, expected_z], "nbt": _tag_obj(Tag(10, fields))}


def semantic_chunk(root: Tag, expected_x: int, expected_z: int,
                   min_y: int | None = None, max_y_exclusive: int | None = None) -> dict[str, object]:
    fields = _root_fields(root)
    x_tag, z_tag = fields.get("xPos"), fields.get("zPos")
    if x_tag is None or z_tag is None or x_tag.tag_type != 3 or z_tag.tag_type != 3:
        raise NbtError(f"chunk {expected_x},{expected_z} lacks modern xPos/zPos int tags")
    actual_x, actual_z = int(x_tag.value), int(z_tag.value)
    if (actual_x, actual_z) != (expected_x, expected_z):
        raise NbtError(f"region table says {expected_x},{expected_z}, chunk NBT says {actual_x},{actual_z}")

    sections_tag = list_tag(fields.get("sections"), "chunk sections")
    sections = sorted(sections_tag.values, key=_section_key)
    block_sections: dict[str, object] = {}
    biome_sections: dict[str, object] = {}
    light_sections: dict[str, object] = {}
    section_metadata = []
    for section in sections:
        sf = compound(section, "chunk section")
        y = _section_key(section)
        y_key = str(y)
        section_min_y = y * 16
        section_max_y = section_min_y + 16
        outside_dimension = (min_y is not None and max_y_exclusive is not None
                             and (section_max_y <= min_y or section_min_y >= max_y_exclusive))
        blocks = sf.get("block_states") or sf.get("BlockStates")
        if blocks is None:
            if not outside_dimension:
                raise NbtError(f"chunk section Y={y} inside dimension height lacks block_states")
            # Preserve absence as null. Do not equate it with an explicit all-air
            # palette: this section is wholly outside the dimension's Y range.
            block_sections[y_key] = None
        else:
            block_sections[y_key] = decode_palette(blocks, 4096, 4, f"blocks[{y}]")
        biomes = sf.get("biomes") or sf.get("Biomes")
        if biomes is None:
            if not outside_dimension:
                raise NbtError(f"chunk section Y={y} inside dimension height lacks biomes palette")
            biome_sections[y_key] = None
        else:
            biome_sections[y_key] = decode_palette(biomes, 64, 1, f"biomes[{y}]")
        light_sections[y_key] = {
            "block": sf["BlockLight"].value.hex() if sf.get("BlockLight") is not None and sf["BlockLight"].tag_type == 7 else None,
            "sky": sf["SkyLight"].value.hex() if sf.get("SkyLight") is not None and sf["SkyLight"].tag_type == 7 else None,
        }
        section_other = {
            key: _tag_obj(value) for key, value in sorted(sf.items())
            if key not in ("block_states", "BlockStates", "biomes", "Biomes", "BlockLight", "SkyLight")
        }
        section_metadata.append({"y": y, "tags": section_other})

    heights_tag = compound(fields.get("Heightmaps"), "chunk Heightmaps")
    heightmaps = {
        key: _decode_heightmap(value, f"Heightmaps.{key}")
        for key, value in sorted(heights_tag.items())
    }
    if not heightmaps:
        raise NbtError("chunk has no heightmaps")

    block_entities = fields.get("block_entities") or fields.get("TileEntities")
    if block_entities is not None:
        block_entities = _generic_list_sorted(
            block_entities,
            lambda item: (
                int(compound(item, "block entity").get("x", Tag(3, 0)).value),
                int(compound(item, "block entity").get("y", Tag(3, 0)).value),
                int(compound(item, "block entity").get("z", Tag(3, 0)).value),
                repr(_tag_obj(compound(item, "block entity").get("id", Tag(8, "")))),
            ),
            "block entities",
        )

    structures = fields.get("structures")
    if structures is not None:
        structures = _sorted_reference_arrays(_children_sorted(structures))

    clock = {
        key: _tag_obj(value)
        for key, value in sorted(fields.items())
        if key in ("LastUpdate", "InhabitedTime")
    }
    remaining = dict(fields)
    for key in ("sections", "Heightmaps", "block_entities", "TileEntities", "structures"):
        remaining.pop(key, None)
    # Normalize map/list storage order while retaining every semantic NBT field.
    remaining_tag = _clock_sentinel(Tag(10, remaining))

    # Paper/Starlight deliberately serializes a false `isLightOn` value to make
    # vanilla relight the chunk, while preserving native light in its own format.
    # Presence plus the matching Starlight version and a status >= LIGHT are the
    # serialized completeness contract; retain the original tags in other_nbt.
    light_marker = fields.get("isLightOn")
    light_version = fields.get("starlight.light_version")
    starts_count = 0
    if structures is not None and structures.tag_type == 10:
        structure_fields = compound(structures, "chunk structures")
        starts = structure_fields.get("starts") or structure_fields.get("Starts")
        if starts is not None and starts.tag_type == 10:
            starts_count = sum(
                1 for start in compound(starts, "structure starts").values()
                if start.tag_type == 10
                and (compound(start, "structure start").get("id") is not None)
                and text(compound(start, "structure start")["id"], "structure start id") != "INVALID"
            )

    status_tag = fields.get("Status")
    status_text = status_tag.value if status_tag is not None and status_tag.tag_type == 8 else None
    status_after_light = status_text in ("minecraft:light", "minecraft:spawn", "minecraft:full")
    light_complete = (light_marker is not None and light_marker.tag_type == 1
                      and light_version is not None and light_version.tag_type == 3
                      and light_version.value == STARLIGHT_LIGHT_VERSION and status_after_light)
    return {
        "coords": [actual_x, actual_z],
        "blocks": block_sections,
        "biomes": biome_sections,
        "heightmaps": heightmaps,
        "light": {
            "isLightOn": _tag_obj(light_marker) if light_marker is not None else None,
            "sections": light_sections,
        },
        "structures": _tag_obj(structures) if structures is not None else None,
        "block_entities": _tag_obj(block_entities) if block_entities is not None else None,
        "section_metadata": section_metadata,
        "other_nbt": _tag_obj(remaining_tag),
        "clock_metadata": clock,
        "light_complete": light_complete,
        "structure_start_count": starts_count,
        "data_version": _tag_obj(fields.get("DataVersion")) if fields.get("DataVersion") is not None else None,
        "status": _tag_obj(status_tag) if status_tag is not None else None,
        "status_full": status_text is not None and status_text.split(":")[-1] == "full",
    }

