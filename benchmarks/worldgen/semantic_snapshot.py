"""Snapshot and compare saved Minecraft Anvil chunks without third-party packages."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import sys

from nbt_region import NbtError, iter_region_chunks, semantic_chunk, semantic_store_chunk


SCHEMA = 2
CATEGORIES = ("blocks", "biomes", "heightmaps", "light", "structures",
              "block_entities", "entities", "poi", "section_metadata", "other_nbt")


def canonical_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def sha256_json(value: object) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def region_directory(world_path: Path) -> Path:
    if (world_path / "region").is_dir():
        return world_path / "region"
    matches = sorted(path for path in world_path.rglob("region") if path.is_dir())
    if len(matches) != 1:
        raise ValueError(f"expected one region directory under {world_path}; found {len(matches)}")
    return matches[0]


def chunk_file(directory: Path, chunk_x: int, chunk_z: int) -> Path:
    return directory / "chunks" / f"c.{chunk_x}.{chunk_z}.json.gz"


def store_file(directory: Path, store: str, chunk_x: int, chunk_z: int) -> Path:
    return directory / store / f"c.{chunk_x}.{chunk_z}.json.gz"


def snapshot_store(world: Path, output: Path, store: str, bounds: tuple[int, int, int, int]) -> dict[str, int]:
    source = world / store
    output_store = output / store
    output_store.mkdir()
    if not source.is_dir():
        return {"observed_chunks": 0}
    observed = 0
    for chunk_x, chunk_z, root, region_timestamp in iter_region_chunks(source, bounds):
        semantic = semantic_store_chunk(root, store, chunk_x, chunk_z)
        semantic["region_timestamp"] = region_timestamp
        encoded = canonical_bytes(semantic)
        with store_file(output, store, chunk_x, chunk_z).open("wb") as target:
            with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as stream:
                stream.write(encoded)
        observed += 1
    return {"observed_chunks": observed}


def snapshot(args: argparse.Namespace) -> dict:
    world = args.world.resolve()
    region = region_directory(world)
    probe_report = None
    min_y = max_y_exclusive = None
    if args.metadata:
        metadata_path = args.metadata.resolve()
        probe_report = json.loads(metadata_path.read_text(encoding="utf-8"))
        min_y, max_y_exclusive = probe_report.get("min_y"), probe_report.get("max_y_exclusive")
        if (not isinstance(min_y, int) or isinstance(min_y, bool)
                or not isinstance(max_y_exclusive, int) or isinstance(max_y_exclusive, bool)
                or max_y_exclusive <= min_y or min_y % 16 != 0 or max_y_exclusive % 16 != 0):
            raise ValueError("probe metadata must provide aligned min_y and max_y_exclusive dimension bounds")
    min_x, min_z, max_x, max_z = args.bounds
    if min_x > max_x or min_z > max_z:
        raise ValueError("bounds must be min-chunk-x min-chunk-z max-chunk-x max-chunk-z")
    bounds = (min_x, min_z, max_x, max_z)
    expected = {(x, z) for x in range(min_x, max_x + 1) for z in range(min_z, max_z + 1)}
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    chunks_dir = output / "chunks"
    chunks_dir.mkdir()

    observed: set[tuple[int, int]] = set()
    sector_timestamp_count = 0
    lighting_complete = 0
    structures = 0
    counts = {
        "block_states": 0, "biome_cells": 0, "heightmap_columns": 0,
        "block_light_arrays": 0, "sky_light_arrays": 0,
    }
    for chunk_x, chunk_z, root, region_timestamp in iter_region_chunks(region, bounds):
        key = (chunk_x, chunk_z)
        if key in observed:
            raise NbtError(f"duplicate chunk coordinate {key}")
        observed.add(key)
        sector_timestamp_count += 1
        semantic = semantic_chunk(root, chunk_x, chunk_z, min_y, max_y_exclusive)
        semantic["region_timestamp"] = region_timestamp
        lighting_complete += int(semantic["light_complete"])
        structures += int(semantic["structure_start_count"])
        counts["block_states"] += sum(len(values) for values in semantic["blocks"].values() if values is not None)
        counts["biome_cells"] += sum(len(values) for values in semantic["biomes"].values() if values is not None)
        counts["heightmap_columns"] += sum(len(values) for values in semantic["heightmaps"].values())
        for section in semantic["light"]["sections"].values():
            counts["block_light_arrays"] += int(section["block"] is not None)
            counts["sky_light_arrays"] += int(section["sky"] is not None)
        encoded = canonical_bytes(semantic)
        with chunk_file(output, *key).open("wb") as target:
            with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as stream:
                stream.write(encoded)

    entity_coverage = snapshot_store(world, output, "entities", bounds)
    poi_coverage = snapshot_store(world, output, "poi", bounds)

    missing = sorted(expected - observed)
    unexpected = sorted(observed - expected)
    if unexpected:
        raise NbtError(f"region reader returned out-of-bounds chunk(s): {unexpected[:5]}")
    manifest = {
        "schema": SCHEMA,
        "world_path": str(world),
        "region_path": str(region),
        "bounds_chunk_inclusive": list(bounds),
        "expected_chunks": len(expected),
        "observed_chunks": len(observed),
        "region_timestamp_records": sector_timestamp_count,
        "missing_chunks": [list(key) for key in missing],
        "missing_chunk_count": len(missing),
        "lighting_complete_chunks": lighting_complete,
        "structure_start_count": structures,
        "coverage": counts,
        "normalization": {
            "ignored_container_fields": ["Anvil sector offsets", "Anvil sector lengths", "Anvil sector timestamps", "compression bytes"],
            "compound_order": "sorted by key",
            "sections": "sorted by signed section Y",
            "block_states": "palettes expanded to 4096 semantic states per section; absent palettes are null only for sections wholly outside probe dimension bounds",
            "biomes": "palettes expanded to 64 semantic biome cells per section; absent palettes are null only for sections wholly outside probe dimension bounds",
            "heightmaps": "packed long arrays expanded to 256 values per named map",
            "block_entities": "sorted by x,y,z,id",
            "entity_store": "root Entities sorted by UUID/canonical NBT; all typed NBT fields retained",
            "poi_store": "each POI Records list sorted by canonical typed NBT; all typed NBT fields retained",
            "structure_references": "reference long arrays sorted as sets",
            "structure_children": "Children/Pieces lists preserved in stored order",
            "clock_tags": ["chunk-root LastUpdate", "chunk-root InhabitedTime"],
            "clock_tag_policy": "kept in clock_metadata and reported separately; excluded from generated-content comparison",
            "lighting_complete": "isLightOn tag present, Starlight light_version is 10, and Status is light/spawn/full; Paper/Starlight false isLightOn value is retained verbatim in other_nbt",
            "other_lists": "preserved in original order",
        },
        "entity_store": entity_coverage,
        "poi_store": poi_coverage,
    }
    if probe_report is not None:
        manifest["probe_report"] = probe_report
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest


def read_chunk(snapshot_path: Path, chunk_x: int, chunk_z: int) -> dict:
    path = chunk_file(snapshot_path, chunk_x, chunk_z)
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        return json.load(stream)


def sequence_diff(expected, actual, *, limit: int = 8) -> tuple[int, list[dict]]:
    differences = 0
    samples: list[dict] = []
    if isinstance(expected, dict) and isinstance(actual, dict):
        keys = sorted(set(expected) | set(actual))
        for key in keys:
            if key not in expected or key not in actual:
                differences += 1
                if len(samples) < limit:
                    samples.append({"path": str(key), "expected": expected.get(key, "<missing>"),
                                    "actual": actual.get(key, "<missing>")})
            else:
                count, nested = sequence_diff(expected[key], actual[key], limit=limit - len(samples))
                differences += count
                samples.extend({"path": f"{key}.{item['path']}", **{k: v for k, v in item.items() if k != "path"}}
                               for item in nested if len(samples) < limit)
        return differences, samples
    if isinstance(expected, list) and isinstance(actual, list):
        if len(expected) != len(actual):
            differences += abs(len(expected) - len(actual))
            if len(samples) < limit:
                samples.append({"path": "length", "expected": len(expected), "actual": len(actual)})
        for index, (left, right) in enumerate(zip(expected, actual)):
            count, nested = sequence_diff(left, right, limit=limit - len(samples))
            differences += count
            samples.extend({"path": f"[{index}].{item['path']}", **{k: v for k, v in item.items() if k != "path"}}
                           for item in nested if len(samples) < limit)
        return differences, samples
    if expected != actual:
        return 1, [{"path": "", "expected": expected, "actual": actual}][:limit]
    return 0, []


def compare(args: argparse.Namespace) -> dict:
    left_path, right_path = args.left.resolve(), args.right.resolve()
    left = json.loads((left_path / "manifest.json").read_text(encoding="utf-8"))
    right = json.loads((right_path / "manifest.json").read_text(encoding="utf-8"))
    if left.get("schema") != SCHEMA or right.get("schema") != SCHEMA:
        raise ValueError("unsupported snapshot schema")
    left_bounds = left["bounds_chunk_inclusive"]
    right_bounds = right["bounds_chunk_inclusive"]
    if left_bounds != right_bounds:
        raise ValueError(f"snapshot bounds differ: {left_bounds} vs {right_bounds}")
    min_x, min_z, max_x, max_z = left_bounds
    expected_coords = [(x, z) for x in range(min_x, max_x + 1) for z in range(min_z, max_z + 1)]
    missing_left = set(map(tuple, left["missing_chunks"]))
    missing_right = set(map(tuple, right["missing_chunks"]))
    category_counts = {name: 0 for name in CATEGORIES}
    samples: dict[str, list[dict]] = {name: [] for name in CATEGORIES}
    clock_differences = 0
    clock_samples = []
    region_timestamp_differences = 0
    region_timestamp_samples = []
    chunk_mismatches = []
    differing_data_versions = []
    incomplete_status = []
    incomplete_lighting = []
    structure_starts = {"left": 0, "right": 0}

    for chunk_x, chunk_z in expected_coords:
        coordinate = [chunk_x, chunk_z]
        if (chunk_x, chunk_z) in missing_left or (chunk_x, chunk_z) in missing_right:
            chunk_mismatches.append({
                "chunk": coordinate,
                "missing_left": (chunk_x, chunk_z) in missing_left,
                "missing_right": (chunk_x, chunk_z) in missing_right,
            })
            continue
        a = read_chunk(left_path, chunk_x, chunk_z)
        b = read_chunk(right_path, chunk_x, chunk_z)
        structure_starts["left"] += int(a["structure_start_count"])
        structure_starts["right"] += int(b["structure_start_count"])
        if not a["light_complete"] or not b["light_complete"]:
            incomplete_lighting.append({
                "chunk": coordinate, "left": a["light_complete"], "right": b["light_complete"],
            })
        if not a["status_full"] or not b["status_full"]:
            incomplete_status.append({"chunk": coordinate, "left": a["status_full"], "right": b["status_full"]})
        if a["data_version"] != b["data_version"]:
            differing_data_versions.append({"chunk": coordinate, "left": a["data_version"], "right": b["data_version"]})
        for category in CATEGORIES:
            if category in ("entities", "poi"):
                left_store = store_file(left_path, category, chunk_x, chunk_z)
                right_store = store_file(right_path, category, chunk_x, chunk_z)
                if left_store.is_file() and right_store.is_file():
                    with gzip.open(left_store, "rt", encoding="utf-8") as stream:
                        left_value = json.load(stream)
                    with gzip.open(right_store, "rt", encoding="utf-8") as stream:
                        right_value = json.load(stream)
                    for value in (left_value, right_value):
                        value.pop("region_timestamp", None)
                else:
                    left_value = None
                    right_value = None
                    if left_store.is_file() != right_store.is_file():
                        category_counts[category] += 1
                        if len(samples[category]) < 8:
                            samples[category].append({"chunk": coordinate, "differences": [{
                                "path": "store_chunk", "expected": "present" if left_store.is_file() else "absent",
                                "actual": "present" if right_store.is_file() else "absent",
                            }]})
                        continue
                count, examples = sequence_diff(left_value, right_value)
            else:
                count, examples = sequence_diff(a[category], b[category])
            category_counts[category] += count
            if examples and len(samples[category]) < 8:
                samples[category].append({"chunk": coordinate, "differences": examples})
        count, examples = sequence_diff(a["clock_metadata"], b["clock_metadata"])
        clock_differences += count
        if examples and len(clock_samples) < 8:
            clock_samples.append({"chunk": coordinate, "differences": examples})
        count, examples = sequence_diff(a["region_timestamp"], b["region_timestamp"])
        region_timestamp_differences += count
        if examples and len(region_timestamp_samples) < 8:
            region_timestamp_samples.append({"chunk": coordinate, "left": a["region_timestamp"],
                                             "right": b["region_timestamp"]})
        for store in ("entities", "poi"):
            left_store = store_file(left_path, store, chunk_x, chunk_z)
            right_store = store_file(right_path, store, chunk_x, chunk_z)
            if left_store.is_file() and right_store.is_file():
                with gzip.open(left_store, "rt", encoding="utf-8") as stream:
                    left_timestamp = json.load(stream).get("region_timestamp")
                with gzip.open(right_store, "rt", encoding="utf-8") as stream:
                    right_timestamp = json.load(stream).get("region_timestamp")
                count, _ = sequence_diff(left_timestamp, right_timestamp)
                region_timestamp_differences += count
                if count and len(region_timestamp_samples) < 8:
                    region_timestamp_samples.append({"store": store, "chunk": coordinate,
                                                     "left": left_timestamp, "right": right_timestamp})

    categories_equal = all(value == 0 for value in category_counts.values())
    chunks_complete = not left["missing_chunks"] and not right["missing_chunks"]
    light_complete = not incomplete_lighting
    structures_present = structure_starts["left"] > 0 and structure_starts["right"] > 0
    if args.require_structures and not structures_present:
        structures_pass = False
    else:
        structures_pass = category_counts["structures"] == 0
    passed = (chunks_complete and light_complete and not incomplete_status and not differing_data_versions
              and categories_equal and structures_pass)
    report = {
        "schema": SCHEMA,
        "left": str(left_path),
        "right": str(right_path),
        "bounds_chunk_inclusive": left_bounds,
        "expected_chunks": len(expected_coords),
        "chunks_equal": chunks_complete and not chunk_mismatches,
        "missing_chunk_count_left": left["missing_chunk_count"],
        "missing_chunk_count_right": right["missing_chunk_count"],
        "category_difference_counts": category_counts,
        "category_difference_samples": samples,
        "clock_metadata_difference_count": clock_differences,
        "clock_metadata_samples": clock_samples,
        "region_timestamp_difference_count": region_timestamp_differences,
        "region_timestamp_samples": region_timestamp_samples,
        "data_version_difference_chunks": differing_data_versions[:8],
        "incomplete_lighting_chunks": incomplete_lighting[:8],
        "incomplete_generation_status_chunks": incomplete_status[:8],
        "structure_start_counts": structure_starts,
        "structures_required": args.require_structures,
        "structures_present_both": structures_present,
        "pass": passed,
        "interpretation": (
            "PASS: decoded chunk content, blocks, biomes, heightmaps, light, structures and remaining NBT match. "
            "Anvil sector layout/timestamps and the two reported chunk clock fields are normalized. "
            "Entity and POI Anvil stores are compared as typed NBT."
            if passed else
            "FAIL: at least one required chunk, FULL generation status, light flag, data version, semantic category, or structure-coverage gate differs."
        ),
    }
    if args.report:
        args.report.resolve().parent.mkdir(parents=True, exist_ok=True)
        args.report.resolve().write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    snap = sub.add_parser("snapshot", help="decode a saved dimension into semantic per-chunk gzip JSON")
    snap.add_argument("--world", type=Path, required=True, help="dimension directory or parent containing one region directory")
    snap.add_argument("--output", type=Path, required=True, help="new, non-existing snapshot directory")
    snap.add_argument("--bounds", type=int, nargs=4, required=True, metavar=("MIN_CX", "MIN_CZ", "MAX_CX", "MAX_CZ"))
    snap.add_argument("--metadata", type=Path, help="optional GPurWorldgenProbe report JSON")
    cmp = sub.add_parser("compare", help="compare two semantic snapshots")
    cmp.add_argument("--left", type=Path, required=True, help="CPU baseline snapshot")
    cmp.add_argument("--right", type=Path, required=True, help="GPU snapshot")
    cmp.add_argument("--report", type=Path, help="write JSON report")
    cmp.add_argument("--require-structures", action="store_true", help="fail if both snapshots contain zero structure starts")
    args = parser.parse_args()
    try:
        report = snapshot(args) if args.command == "snapshot" else compare(args)
        print(json.dumps(report, indent=2, ensure_ascii=False))
        if args.command == "snapshot":
            if report["missing_chunk_count"]:
                return 1
            return 0
        return 0 if report["pass"] else 2
    except (OSError, ValueError, NbtError, json.JSONDecodeError) as error:
        print(f"worldgen snapshot error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

