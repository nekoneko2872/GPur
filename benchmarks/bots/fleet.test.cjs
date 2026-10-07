'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { parseArgs, aggregate, applyTeleport, nextStep, opaquePackets, walkingEnabled } = require('./common.cjs')
test('selected movement load is distributed across fleets without changing total online population', () => {
  assert.throws(() => parseArgs(['--walking-percent', '101']), /walking-percent/)
  for (const percent of [0, 20, 100]) {
    const ids = Array.from({ length: 300 }, (_, i) => i)
    assert.equal(ids.filter(i => walkingEnabled(i, percent)).length, percent * 3)
    assert.equal(ids.slice(50, 150).filter(i => walkingEnabled(i, percent)).length, percent)
  }
})
test('loopback restriction and bounded fleet reject unsafe or invalid CLI values', () => {
  assert.throws(() => parseArgs(['--host', 'example.com']), /127.0.0.1/)
  assert.throws(() => parseArgs(['--count', '301']), /count<=300/)
  assert.throws(() => parseArgs(['--workers', 'NaN']), /positive integer/)
  assert.throws(() => parseArgs(['--ramp', '-1']), /nonnegative/)
  assert.throws(() => parseArgs(['--prefix', 'bad name']), /username-safe/)
  assert.equal(parseArgs(['--count', '150', '--workers', '4', '--action', 'walk']).count, 150)
})
test('aggregation sums real live counters and CPU but takes worst eventloop latency', () => {
  const result = aggregate([{ live: 75, spawned: 70, cpuCorePercent: 40, eventLoopP99Ms: 12 },
    { live: 75, spawned: 74, cpuCorePercent: 60, eventLoopP99Ms: 18 }])
  assert.equal(result.live, 150); assert.equal(result.spawned, 144)
  assert.equal(result.cpuCorePercent, 100); assert.equal(result.eventLoopP99Ms, 18)
})
test('relative server teleports preserve unchanged axes and absolute replacements', () => {
  assert.deepEqual(applyTeleport({ x: 10, y: 64, z: -5, yaw: 90, pitch: 10 },
    { x: 2, y: 80, z: 3, yaw: 10, pitch: 0, flags: { x: true, yaw: true } }),
  { x: 12, y: 80, z: 3, yaw: 100, pitch: 0 })
})
test('walking never exceeds speed step or radius on an established pad', () => {
  const anchor = { x: 10, y: 64, z: 20 }, radius = 2, step = 0.1
  let current = { ...anchor }
  for (let i = 0; i < 1000; i++) {
    const next = nextStep(current, anchor, i * 0.05, radius, step)
    assert.ok(Math.hypot(next.x - current.x, next.z - current.z) <= step + 1e-10)
    assert.ok(Math.hypot(next.x - anchor.x, next.z - anchor.z) <= radius + 1e-10)
    assert.equal(next.y, 64)
    current = next
  }
})
test('opaque heavy packet schemas still consume complete payload and preserve control packets', () => {
  const dependencies = parseArgs([]).dependencyDir
  const data = require(path.join(dependencies, 'minecraft-data'))('26.1')
  const overrides = opaquePackets(data)
  const types = overrides['26.1'].play.toClient.types
  assert.equal(types.packet_map_chunk, 'restBuffer')
  assert.equal(types.packet_position, undefined)
  const { createDeserializer } = require(path.join(dependencies, 'minecraft-protocol/src/transforms/serializer'))
  const parser = createDeserializer({ version: '26.1', state: 'play', customPackets: overrides })
  const mappings = data.protocol.play.toClient.types.packet[1][0].type[1].mappings
  const packetId = Number(Object.entries(mappings).find(([, name]) => name === 'map_chunk')[0])
  return new Promise((resolve, reject) => {
    parser.on('error', reject)
    parser.on('data', packet => {
      assert.equal(packet.data.name, 'map_chunk')
      assert.deepEqual(packet.data.params, Buffer.from([1, 2, 3, 255]))
      resolve()
    })
    parser.end(Buffer.from([packetId, 1, 2, 3, 255]))
  })
})
