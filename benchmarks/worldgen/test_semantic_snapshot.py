"""Fixture-only tests for Anvil decoding and semantic worldgen normalization."""

from __future__ import annotations

import argparse
import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from nbt_region import NbtError, SECTOR_BYTES
from semantic_snapshot import compare, read_chunk, sequence_diff, snapshot


def tag(tag_type: int, value):
    return tag_type, value


def _name(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _payload(tag_type: int, value) -> bytes:
    if tag_type == 1:
        return struct.pack(">b", value)
    if tag_type == 2:
        return struct.pack(">h", value)
    if tag_type == 3:
        return struct.pack(">i", value)
    if tag_type == 4:
        return struct.pack(">q", value)
    if tag_type == 7:
        return struct.pack(">i", len(value)) + value
    if tag_type == 8:
        return _name(value)
    if tag_type == 9:
        element_type, values = value
        return bytes((element_type,)) + struct.pack(">i", len(values)) + b"".join(
            _payload(element_type, item[1] if isinstance(item, tuple) and len(item) == 2 else item)
            for item in values
        )
    if tag_type == 10:
        return b"".join(
            bytes((child_type,)) + _name(child_name) + _payload(child_type, child_value)
            for child_name, (child_type, child_value) in value.items()
        ) + b"\0"
    if tag_type == 11:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">i", item) for item in value)
    if tag_type == 12:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">q", item) for item in value)
    raise AssertionError(f"fixture writer does not support tag type {tag_type}")


def nbt_root(fields: dict[str, tuple[int, object]]) -> bytes:
    return b"\x0a" + _name("") + _payload(10, fields)


def _palette_entry(name: str):
    return tag(10, {"Name": tag(8, name)})


def _pack_padded(values: list[int], bits: int) -> tuple[int, ...]:
    per_word = 64 // bits
    mask = (1 << bits) - 1
    words = [0] * ((len(values) + per_word - 1) // per_word)
    for index, value in enumerate(values):
        if value & ~mask:
            raise AssertionError("value does not fit fixture bit width")
        words[index // per_word] |= value << ((index % per_word) * bits)
    return tuple(word if word < (1 << 63) else word - (1 << 64) for word in words)


def make_chunk(*, alternate_palette_order=False, changed_block=False, light_on=True,
               structures=True, clock=10, nested_clock=77, region_timestamp=1, reverse_compounds=False,
               reverse_structures=False, changed_biome=False, height_value=0, block_light_value=0x11,
               status="minecraft:full", section_y=0, omit_palettes=False, starlight_version=None):
    block_values = [0] * 4096
    block_values[123] = 1
    block_names = ["minecraft:stone", "minecraft:dirt"]
    if alternate_palette_order:
        block_values = [1 - value for value in block_values]
        block_names.reverse()
    if changed_block:
        block_values[123] = 0 if block_values[123] == 1 else 1
    block_palette = [_palette_entry(name) for name in block_names]
    blocks = {
        "palette": tag(9, (10, block_palette)),
        "data": tag(12, _pack_padded(block_values, 4)),
    }
    biome_values = [0] * 64
    biome_values[5] = 1
    if changed_biome:
        biome_values[6] = 1
    biome_names = ["minecraft:plains", "minecraft:forest"]
    if alternate_palette_order:
        biome_values = [1 - value for value in biome_values]
        biome_names.reverse()
    biome_palette = [_palette_entry(name) for name in biome_names]
    biomes = {
        "palette": tag(9, (10, biome_palette)),
        "data": tag(12, _pack_padded(biome_values, 1)),
    }
    section = {
        "Y": tag(1, section_y),
        "block_states": tag(10, blocks),
        "biomes": tag(10, biomes),
        "BlockLight": tag(7, bytes([block_light_value]) * 2048),
        "SkyLight": tag(7, bytes([0xFF]) * 2048),
    }
    if omit_palettes:
        del section["block_states"]
        del section["biomes"]
        del section["BlockLight"]
        del section["SkyLight"]
        section["starlight.skylight_state"] = tag(3, 1)
        section["starlight.blocklight_state"] = tag(3, 1)
    if reverse_compounds:
        section = dict(reversed(list(section.items())))
    heights = {name: tag(12, _pack_padded([height_value] * 256, 9))
               for name in ("MOTION_BLOCKING", "WORLD_SURFACE")}
    if reverse_compounds:
        heights = dict(reversed(list(heights.items())))
    starts = {}
    if structures:
        structure_children = [
            tag(10, {"id": tag(8, "minecraft:house"), "BB": tag(3, 1)}),
            tag(10, {"id": tag(8, "minecraft:well"), "BB": tag(3, 2)}),
        ]
        if reverse_structures:
            structure_children.reverse()
        starts["minecraft:village"] = tag(10, {
            "id": tag(8, "minecraft:village"),
            "ChunkX": tag(3, 0), "ChunkZ": tag(3, 0),
            "Children": tag(9, (10, structure_children)),
        })
    references = [3, 7]
    if reverse_structures:
        starts = dict(reversed(list(starts.items())))
        references.reverse()
    root_fields = {
        "DataVersion": tag(3, 4189),
        "xPos": tag(3, 0), "zPos": tag(3, 0),
        "Status": tag(8, status),
        "isLightOn": tag(1, int(light_on)),
        "LastUpdate": tag(4, clock), "InhabitedTime": tag(4, clock + 7),
        "NestedMetadata": tag(10, {"LastUpdate": tag(4, nested_clock)}),
        "sections": tag(9, (10, [tag(10, section)])),
        "Heightmaps": tag(10, heights),
        "structures": tag(10, {
            "starts": tag(10, starts),
            "References": tag(10, {"minecraft:village": tag(12, tuple(references))}),
        }),
        "block_entities": tag(9, (10, [])),
    }
    if starlight_version is None and light_on:
        starlight_version = 10
    if starlight_version is not None:
        root_fields["starlight.light_version"] = tag(3, starlight_version)
    if reverse_compounds:
        root_fields = dict(reversed(list(root_fields.items())))
    return nbt_root(root_fields), region_timestamp


def write_region(world: Path, *, options: dict) -> None:
    region_dir = world / "region"
    region_dir.mkdir(parents=True)
    nbt, timestamp = make_chunk(**options)
    compressed = zlib.compress(nbt)
    record = struct.pack(">I", len(compressed) + 1) + b"\x02" + compressed
    sector_count = (len(record) + SECTOR_BYTES - 1) // SECTOR_BYTES
    data = bytearray(SECTOR_BYTES * (2 + sector_count))
    data[0:4] = struct.pack(">I", (2 << 8) | sector_count)
    data[SECTOR_BYTES:SECTOR_BYTES + 4] = struct.pack(">I", timestamp)
    data[SECTOR_BYTES * 2:SECTOR_BYTES * 2 + len(record)] = record
    (region_dir / "r.0.0.mca").write_bytes(data)


def write_store(world: Path, store: str, root_fields: dict[str, tuple[int, object]]) -> None:
    region_dir = world / store
    region_dir.mkdir()
    nbt = nbt_root(root_fields)
    compressed = zlib.compress(nbt)
    record = struct.pack(">I", len(compressed) + 1) + b"\x02" + compressed
    sector_count = (len(record) + SECTOR_BYTES - 1) // SECTOR_BYTES
    data = bytearray(SECTOR_BYTES * (2 + sector_count))
    data[0:4] = struct.pack(">I", (2 << 8) | sector_count)
    data[SECTOR_BYTES:SECTOR_BYTES + 4] = struct.pack(">I", 17)
    data[SECTOR_BYTES * 2:SECTOR_BYTES * 2 + len(record)] = record
    (region_dir / "r.0.0.mca").write_bytes(data)


def make_entity_store(*, reverse=False, changed=False, changed_uuid=False):
    first_uuid = 9 if changed_uuid else 1
    entries = [
        tag(10, {"UUID": tag(11, (0, 0, 0, first_uuid)), "id": tag(8, "minecraft:pig")}),
        tag(10, {"UUID": tag(11, (0, 0, 0, 2)), "id": tag(8, "minecraft:cow" if not changed else "minecraft:goat")}),
    ]
    if reverse:
        entries.reverse()
    return {
        "DataVersion": tag(3, 4189),
        "Position": tag(11, (0, 0)),
        "Entities": tag(9, (10, entries)),
    }


def make_poi_store(*, reverse=False, changed=False):
    records = [
        tag(10, {"pos": tag(3, 4), "type": tag(8, "minecraft:home"), "free_tickets": tag(3, 1)}),
        tag(10, {"pos": tag(3, 8 if not changed else 9), "type": tag(8, "minecraft:job_site"), "free_tickets": tag(3, 0)}),
    ]
    if reverse:
        records.reverse()
    return {
        "DataVersion": tag(3, 4189),
        "Sections": tag(10, {"0": tag(10, {"Valid": tag(1, 1), "Records": tag(9, (10, records))})}),
    }


def make_snapshot(root: Path, name: str, **options) -> Path:
    world = root / f"world-{name}"
    output = root / f"snapshot-{name}"
    world_options = dict(options)
    entity_order = world_options.pop("entity_order", False)
    poi_order = world_options.pop("poi_order", False)
    entity_changed = world_options.pop("entity_changed", False)
    entity_uuid_changed = world_options.pop("entity_uuid_changed", False)
    poi_changed = world_options.pop("poi_changed", False)
    write_region(world, options=world_options)
    write_store(world, "entities", make_entity_store(reverse=entity_order, changed=entity_changed,
                                                       changed_uuid=entity_uuid_changed))
    write_store(world, "poi", make_poi_store(reverse=poi_order, changed=poi_changed))
    snapshot(argparse.Namespace(
        world=world, output=output, bounds=[0, 0, 0, 0], metadata=None,
    ))
    return output


class SemanticSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="gpur-worldgen-fixture-")
        self.root = Path(self.temporary.name)
        self.pair_index = 0

    def tearDown(self):
        self.temporary.cleanup()

    def compare_pair(self, left_options: dict, right_options: dict, *, require_structures=False):
        pair = self.pair_index
        self.pair_index += 1
        left = make_snapshot(self.root, f"left-{pair}", **left_options)
        right = make_snapshot(self.root, f"right-{pair}", **right_options)
        report = self.root / f"comparison-{pair}.json"
        result = compare(argparse.Namespace(
            left=left, right=right, report=report, require_structures=require_structures,
        ))
        return result

    def test_light_only_section_below_dimension_is_preserved_without_inventing_blocks(self):
        world = self.root / "world-light-only-boundary"
        output = self.root / "snapshot-light-only-boundary"
        metadata = self.root / "probe-light-only-boundary.json"
        write_region(world, options={"section_y": -5, "omit_palettes": True})
        metadata.write_text(json.dumps({"min_y": -64, "max_y_exclusive": 320}), encoding="utf-8")
        snapshot(argparse.Namespace(world=world, output=output, bounds=[0, 0, 0, 0], metadata=metadata))

        chunk = read_chunk(output, 0, 0)
        self.assertIsNone(chunk["blocks"]["-5"])
        self.assertIsNone(chunk["biomes"]["-5"])
        self.assertEqual(chunk["section_metadata"][0]["y"], -5)
        metadata_tags = chunk["section_metadata"][0]["tags"]
        self.assertIn("starlight.skylight_state", metadata_tags)
        self.assertIn("starlight.blocklight_state", metadata_tags)

    def test_missing_block_palette_inside_dimension_still_fails_closed(self):
        world = self.root / "world-missing-in-range-palette"
        output = self.root / "snapshot-missing-in-range-palette"
        metadata = self.root / "probe-in-range.json"
        write_region(world, options={"section_y": 0, "omit_palettes": True})
        metadata.write_text(json.dumps({"min_y": -64, "max_y_exclusive": 320}), encoding="utf-8")
        with self.assertRaisesRegex(NbtError, "inside dimension height lacks block_states"):
            snapshot(argparse.Namespace(world=world, output=output, bounds=[0, 0, 0, 0], metadata=metadata))

    def test_paper_starlight_false_light_marker_with_matching_version_is_lit(self):
        world = self.root / "world-paper-starlight-light"
        output = self.root / "snapshot-paper-starlight-light"
        metadata = self.root / "probe-paper-starlight-light.json"
        write_region(world, options={"light_on": False, "starlight_version": 10})
        metadata.write_text(json.dumps({"min_y": -64, "max_y_exclusive": 320}), encoding="utf-8")
        manifest = snapshot(argparse.Namespace(world=world, output=output, bounds=[0, 0, 0, 0], metadata=metadata))

        self.assertEqual(manifest["lighting_complete_chunks"], 1)
        chunk = read_chunk(output, 0, 0)
        other = chunk["other_nbt"]
        self.assertIn(["isLightOn", ["byte", 0]], other[1])
        self.assertIn(["starlight.light_version", ["int", 10]], other[1])

    def test_normalizes_palette_compound_and_clock_ordering(self):
        result = self.compare_pair(
            {"clock": 10, "region_timestamp": 101},
            {"clock": 200, "region_timestamp": 999, "alternate_palette_order": True,
             "reverse_compounds": True, "reverse_structures": True},
        )
        self.assertTrue(result["pass"], json.dumps(result, indent=2))
        self.assertGreater(result["clock_metadata_difference_count"], 0)
        self.assertGreater(result["region_timestamp_difference_count"], 0)
        self.assertEqual(result["category_difference_counts"]["blocks"], 0)
        self.assertEqual(result["category_difference_counts"]["biomes"], 0)

    def test_block_difference_fails_semantic_comparison(self):
        result = self.compare_pair({}, {"changed_block": True})
        self.assertFalse(result["pass"])
        self.assertGreater(result["category_difference_counts"]["blocks"], 0)

    def test_biome_heightmap_and_light_values_are_compared(self):
        for options, category in (({"changed_biome": True}, "biomes"),
                                  ({"height_value": 1}, "heightmaps"),
                                  ({"block_light_value": 0x22}, "light")):
            with self.subTest(category=category):
                result = self.compare_pair({}, options)
                self.assertFalse(result["pass"])
                self.assertGreater(result["category_difference_counts"][category], 0)

    def test_unlit_chunk_fails_even_if_other_nbt_matches(self):
        result = self.compare_pair({}, {"light_on": False})
        self.assertFalse(result["pass"])
        self.assertTrue(result["incomplete_lighting_chunks"])
        self.assertGreater(result["category_difference_counts"]["light"], 0)

    def test_partial_generation_status_fails_even_if_both_sides_match(self):
        result = self.compare_pair({"status": "minecraft:noise"}, {"status": "minecraft:noise"})
        self.assertFalse(result["pass"])
        self.assertTrue(result["incomplete_generation_status_chunks"])

    def test_only_chunk_root_clock_tags_are_normalized(self):
        result = self.compare_pair({}, {"nested_clock": 78})
        self.assertFalse(result["pass"])
        self.assertGreater(result["category_difference_counts"]["other_nbt"], 0)

    def test_structure_coverage_can_be_required(self):
        result = self.compare_pair(
            {"structures": False}, {"structures": False}, require_structures=True,
        )
        self.assertFalse(result["pass"])
        self.assertFalse(result["structures_present_both"])

    def test_valid_structure_starts_are_counted(self):
        result = self.compare_pair({}, {}, require_structures=True)
        self.assertTrue(result["pass"], json.dumps(result, indent=2))
        self.assertTrue(result["structures_present_both"])
        self.assertEqual(result["structure_start_counts"], {"left": 1, "right": 1})

    def test_difference_count_scans_all_values_but_caps_examples(self):
        count, examples = sequence_diff([0] * 50, [1] * 50, limit=8)
        self.assertEqual(count, 50)
        self.assertEqual(len(examples), 8)

    def test_entity_and_poi_order_is_normalized_but_nbt_content_is_not(self):
        result = self.compare_pair(
            {}, {"entity_order": True, "poi_order": True},
        )
        self.assertTrue(result["pass"], json.dumps(result, indent=2))
        self.assertEqual(result["category_difference_counts"]["entities"], 0)
        self.assertEqual(result["category_difference_counts"]["poi"], 0)
        changed = self.compare_pair({}, {"entity_changed": True, "poi_changed": True})
        self.assertFalse(changed["pass"])
        self.assertGreater(changed["category_difference_counts"]["entities"], 0)
        self.assertGreater(changed["category_difference_counts"]["poi"], 0)

    def test_entity_uuid_values_are_retained_in_semantic_equality(self):
        result = self.compare_pair({}, {"entity_uuid_changed": True})
        self.assertFalse(result["pass"])
        self.assertGreater(result["category_difference_counts"]["entities"], 0)


if __name__ == "__main__":
    unittest.main()
