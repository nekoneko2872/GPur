'use strict'
const path = require('node:path')
const { performance, monitorEventLoopDelay } = require('node:perf_hooks')
const { applyTeleport, nextStep, opaquePackets, walkingEnabled } = require('./common.cjs')
let config, workerId, protocol, action, telemetryTimer, movementTimer, stopping = false
let attempted = 0, errors = 0, kicks = 0, unexpectedLosses = 0, ended = 0, everSpawned = 0
let movementPackets = 0, teleportConfirms = 0, keepalives = 0, chunkBatches = 0
let retiredReceivedBytes = 0, retiredSentBytes = 0
let lastCpu = process.cpuUsage(), lastReport = performance.now(), lastMove = performance.now()
const clients = new Set(), rampTimers = new Set()
const lag = monitorEventLoopDelay({ resolution: 10 })
lag.enable()
function send(message) { if (process.connected) process.send(message) }
function errorRecord(error, record) {
  errors++
  if (errors <= 12) send({ type: 'client_error', username: record?.name, error: String(error.message || error).slice(0, 1000) })
}
function write(record, name, params) {
  if (record.client.ended || stopping) return false
  try { record.client.write(name, params); return true } catch (error) { errorRecord(error, record); record.client.end('write_error'); return false }
}

function connect(index) {
  if (stopping) return
  attempted++
  const name = config.prefix + String(config.startIndex + index).padStart(4, '0')
  const record = { name, walking: walkingEnabled(config.startIndex + index, config.walkingPercent),
    connected: false, loggedIn: false, play: false, spawned: false,
    survival: false, position: { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 }, anchor: null,
    angle: (index * 2.399963229728653) % (2 * Math.PI), lastHeartbeat: performance.now() }
  try {
    record.client = protocol.createClient({ host: config.host, port: config.port, username: name,
      auth: 'offline', version: config.version, hideErrors: true, keepAlive: true,
      checkTimeoutInterval: 120000, customPackets: config.customPackets,
      clientSettings: { viewDistance: config.viewDistance, enableServerListing: false } })
  } catch (error) { errorRecord(error, record); ended++; unexpectedLosses++; return }
  clients.add(record)
  const client = record.client
  client.on('connect', () => { record.connected = true })
  client.on('success', () => { record.loggedIn = true })
  client.on('login', packet => {
    record.play = true
    record.entityId = packet.entityId
    record.survival = packet.worldState.gamemode === 'survival'
    send({ type: 'client_login', username: name, uuid: client.uuid, entityId: packet.entityId,
      version: client.version, gamemode: packet.worldState.gamemode })
  })
  client.on('respawn', packet => {
    record.spawned = false
    record.anchor = null
    record.survival = packet.worldState.gamemode === 'survival'
  })
  client.on('game_state_change', packet => {
    if (packet.reason === 3) record.survival = packet.gameMode === 0
  })
  client.on('position', packet => {
    record.position = applyTeleport(record.position, packet)
    record.anchor = { ...record.position }
    if (write(record, 'teleport_confirm', { teleportId: packet.teleportId })) teleportConfirms++
    write(record, 'position_look', { ...record.position, flags: { onGround: true, hasHorizontalCollision: false } })
    if (!record.spawned) {
      record.spawned = true
      if (!record.everSpawned) {
        record.everSpawned = true
        everSpawned++
        send({ type: 'client_spawn', username: name, uuid: client.uuid, entityId: record.entityId,
          x: record.position.x, y: record.position.y, z: record.position.z })
      }
      write(record, 'player_loaded', {})
    }
  })
  client.on('keep_alive', () => { keepalives++ })
  client.on('ping', packet => { write(record, 'pong', { id: packet.id }) })
  client.on('chunk_batch_finished', () => {
    if (write(record, 'chunk_batch_received', { chunksPerTick: 20 })) chunkBatches++
  })
  client.on('update_health', packet => {
    if (packet.health <= 0) write(record, 'client_command', { actionId: 0 })
  })
  client.on('kick_disconnect', packet => {
    kicks++
    send({ type: 'client_kick', username: name, reason: JSON.stringify(packet).slice(0, 1200) })
  })
  client.on('error', error => { errorRecord(error, record); client.end('client_error') })
  client.on('end', reason => {
    ended++
    if (!stopping) unexpectedLosses++
    retiredReceivedBytes += client.socket?.bytesRead || 0
    retiredSentBytes += client.socket?.bytesWritten || 0
    clients.delete(record)
    if (!stopping) send({ type: 'client_end', username: name, reason: String(reason).slice(0, 500), spawned: record.spawned })
  })
}

function move() {
  const now = performance.now(), dt = Math.min((now - lastMove) / 1000, 0.1)
  lastMove = now
  for (const record of clients) {
    if (!record.spawned || record.client.state !== 'play') continue
    if (action === 'walk' && record.walking && record.anchor) {
      record.angle += config.radius ? config.speed / config.radius * dt : 0
      record.position = nextStep(record.position, record.anchor, record.angle, config.radius, config.speed * dt)
      if (write(record, 'position_look', { ...record.position, flags: { onGround: true, hasHorizontalCollision: false } })) movementPackets++
    } else if (now - record.lastHeartbeat >= 1000) {
      if (write(record, 'flying', { flags: { onGround: true, hasHorizontalCollision: false } })) movementPackets++
      record.lastHeartbeat = now
    }
  }
}

function report() {
  const now = performance.now(), currentCpu = process.cpuUsage()
  const cpuCorePercent = ((currentCpu.user - lastCpu.user) + (currentCpu.system - lastCpu.system)) / ((now - lastReport) * 1000) * 100
  lastCpu = currentCpu; lastReport = now
  const counts = { connected: 0, loggedIn: 0, play: 0, spawned: 0, survival: 0 }
  let receivedBytes = retiredReceivedBytes, sentBytes = retiredSentBytes, walking = 0
  for (const record of clients) {
    for (const key of Object.keys(counts)) if (record[key]) counts[key]++
    receivedBytes += record.client.socket?.bytesRead || 0
    sentBytes += record.client.socket?.bytesWritten || 0
    if (action === 'walk' && record.walking && record.spawned) walking++
  }
  const mem = process.memoryUsage()
  send({ type: 'worker_stats', workerId, pid: process.pid, action, attempted, ...counts,
    live: clients.size, walking, everSpawned, ended, errors, kicks, unexpectedLosses, movementPackets,
    teleportConfirms, keepalives, chunkBatches, receivedBytes, sentBytes,
    rssBytes: mem.rss, heapUsedBytes: mem.heapUsed, cpuCorePercent,
    eventLoopP99Ms: lag.percentile(99) / 1e6, eventLoopMaxMs: lag.max / 1e6 })
  lag.reset()
}

function stop() {
  if (stopping) return
  stopping = true
  for (const timer of rampTimers) clearTimeout(timer)
  clearInterval(movementTimer); clearInterval(telemetryTimer)
  for (const record of clients) {
    record.client.end('fleet_stop')
    record.client.socket?.destroy()
  }
  setTimeout(() => { report(); lag.disable(); process.disconnect(); }, 200)
}
process.on('message', message => {
  if (message.command === 'start') {
    config = message.config; workerId = message.workerId; action = config.action
    protocol = require(path.join(config.dependencyDir, 'minecraft-protocol'))
    const data = require(path.join(config.dependencyDir, 'minecraft-data'))(config.version)
    config.customPackets = opaquePackets(data)
    for (const index of message.indices) {
      const timer = setTimeout(() => { rampTimers.delete(timer); connect(index) }, config.ramp ? index / config.ramp * 1000 : 0)
      rampTimers.add(timer)
    }
    movementTimer = setInterval(move, 1000 / config.hz)
    telemetryTimer = setInterval(report, config.reportMs)
  } else if (message.command === 'action') action = message.action
  else if (message.command === 'stop') stop()
})
process.on('disconnect', () => { if (!stopping) stop() })
process.on('SIGTERM', stop)
