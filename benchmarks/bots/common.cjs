'use strict'
const path = require('node:path')
const os = require('node:os')

function parseArgs(argv) {
  const config = { host: '127.0.0.1', port: 25565, count: 50, workers: 4, ramp: 10,
    action: 'idle', radius: 2, speed: 2, hz: 20, reportMs: 1000, duration: 0,
    prefix: 'GPurBot', version: '26.1', viewDistance: 4, startIndex: 0, walkingPercent: 100,
    dependencyDir: require('node:fs').existsSync(path.join(__dirname, 'node_modules/minecraft-protocol'))
      ? path.join(__dirname, 'node_modules') : path.resolve(__dirname, '../../validation/bots/node_modules') }
  const names = { 'report-ms': 'reportMs', 'view-distance': 'viewDistance',
    'start-index': 'startIndex', 'dependency-dir': 'dependencyDir', 'walking-percent': 'walkingPercent' }
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--help') return { help: true }
    if (!argv[i].startsWith('--')) throw new Error(`Unexpected argument ${argv[i]}`)
    const key = names[argv[i].slice(2)] || argv[i].slice(2)
    if (!(key in config)) throw new Error(`Unknown option ${argv[i]}`)
    const value = argv[++i]
    if (value === undefined) throw new Error(`Missing value for ${key}`)
    config[key] = typeof config[key] === 'number' ? Number(value) : value
  }
  if (config.host !== '127.0.0.1') throw new Error('Only --host 127.0.0.1 is permitted (offline authentication)')
  for (const key of ['count', 'workers', 'port', 'hz', 'reportMs', 'viewDistance']) {
    if (!Number.isInteger(config[key]) || config[key] <= 0) throw new Error(`${key} must be a positive integer`)
  }
  if (config.count > 300 || config.workers > 8 || config.port > 65535 || config.hz > 20) throw new Error('Limits: count<=300, workers<=8, port<=65535, hz<=20')
  for (const key of ['ramp', 'duration', 'radius', 'speed', 'startIndex']) {
    if (!Number.isFinite(config[key]) || config[key] < 0) throw new Error(`${key} must be nonnegative`)
  }
  if (config.radius > 2 || config.speed > 4.3) throw new Error('Pad walking requires radius<=2, speed<=4.3 blocks/s')
  if (!Number.isInteger(config.walkingPercent) || config.walkingPercent < 0 || config.walkingPercent > 100) throw new Error('walking-percent must be an integer0..100')
  if (!['idle', 'walk'].includes(config.action)) throw new Error('action must be idle or walk')
  if (config.version !== '26.1') throw new Error('Pinned validated client protocol is 26.1; install ViaVersion+ViaBackwards on26.2')
  if (!/^[A-Za-z0-9_]{1,12}$/.test(config.prefix)) throw new Error('prefix must be1..12 username-safe characters')
  if (!Number.isInteger(config.startIndex) || config.startIndex + config.count > 9999) throw new Error('Bot index must fit four digits')
  config.workers = Math.min(config.workers, config.count)
  config.hostLogicalProcessors = os.availableParallelism()
  return config
}

const sumFields = ['attempted', 'connected', 'loggedIn', 'play', 'spawned', 'live', 'survival',
  'everSpawned', 'ended', 'errors', 'kicks', 'unexpectedLosses', 'movementPackets', 'teleportConfirms',
  'keepalives', 'chunkBatches', 'receivedBytes', 'sentBytes', 'rssBytes', 'heapUsedBytes', 'cpuCorePercent', 'walking']
function walkingEnabled(index, percent) {
  return Math.floor((index + 1) * percent / 100) > Math.floor(index * percent / 100)
}
function aggregate(stats) {
  const result = Object.fromEntries(sumFields.map(key => [key, 0]))
  result.eventLoopP99Ms = 0
  result.eventLoopMaxMs = 0
  for (const stat of stats) {
    for (const key of sumFields) result[key] += stat[key] || 0
    result.eventLoopP99Ms = Math.max(result.eventLoopP99Ms, stat.eventLoopP99Ms || 0)
    result.eventLoopMaxMs = Math.max(result.eventLoopMaxMs, stat.eventLoopMaxMs || 0)
  }
  return result
}

function applyTeleport(previous, packet) {
  const result = {}
  for (const key of ['x', 'y', 'z', 'yaw', 'pitch']) {
    result[key] = packet[key] + (packet.flags?.[key] ? (previous[key] || 0) : 0)
  }
  return result
}

function nextStep(position, anchor, angle, radius, distance) {
  const x = anchor.x + radius * Math.cos(angle)
  const z = anchor.z + radius * Math.sin(angle)
  const dx = x - position.x, dz = z - position.z
  const length = Math.hypot(dx, dz)
  const scale = length > distance ? distance / length : 1
  return { x: position.x + dx * scale, y: anchor.y, z: position.z + dz * scale,
    yaw: -Math.atan2(dx, dz) * 180 / Math.PI, pitch: 0 }
}

function opaquePackets(data) {
  // These payloads are consumed from the real TCP connection but do not need world/NBT decoding.
  const skip = ['map_chunk', 'update_light', 'entity_metadata', 'entity_update_attributes',
    'world_particles', 'sound_effect', 'entity_sound_effect', 'declare_recipes', 'tags',
    'window_items', 'advancements', 'tile_entity_data', 'chunk_biomes', 'recipe_book_add']
  const types = {}
  for (const name of skip) {
    if (data.protocol.play.toClient.types[`packet_${name}`]) types[`packet_${name}`] = 'restBuffer'
  }
  return { [data.version.majorVersion]: { play: { toClient: { types } } } }
}
module.exports = { parseArgs, aggregate, applyTeleport, nextStep, opaquePackets, walkingEnabled }
