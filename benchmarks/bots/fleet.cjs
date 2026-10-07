'use strict'
const { fork } = require('node:child_process')
const path = require('node:path')
const readline = require('node:readline')
const { performance } = require('node:perf_hooks')
const { parseArgs, aggregate } = require('./common.cjs')
let config
try { config = parseArgs(process.argv.slice(2)) } catch (error) { console.error(error.message); process.exit(2) }
if (config.help) {
  console.log('node fleet.cjs --count 50 --port 25565 --ramp 10 --workers 4 --action idle|walk --duration 0')
  console.log('Defaults: host127.0.0.1, version26.1, radius2, speed2, hz20.')
  console.log('stdin NDJSON: {"command":"action","action":"walk"}, {"command":"phase","phase":"measurement"}, {"command":"stop"}')
  process.exit(0)
}
const started = performance.now()
const children = new Map(), stats = new Map()
let stopping = false, phase = 'ramp', action = config.action, reportTimer, forceTimer
function output(value) { process.stdout.write(JSON.stringify({ timestamp: new Date().toISOString(), elapsedSeconds: (performance.now() - started) / 1000, ...value }) + '\n') }
output({ type: 'fleet_start', config, parentPid: process.pid, nodeVersion: process.version })
for (let workerId = 0; workerId < config.workers; workerId++) {
  const indices = Array.from({ length: config.count }, (_, i) => i).filter(i => i % config.workers === workerId)
  const child = fork(path.join(__dirname, 'worker.cjs'), [], { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] })
  children.set(workerId, child)
  child.stderr.on('data', data => output({ type: 'worker_stderr', workerId, message: String(data).slice(0, 2000) }))
  child.on('message', message => {
    if (message.type === 'worker_stats') stats.set(workerId, { ...message, lastReportAt: performance.now() })
    else output({ ...message, workerId })
  })
  child.on('exit', (code, signal) => {
    children.delete(workerId)
    output({ type: 'worker_exit', workerId, code, signal, expected: stopping })
    if (!stopping) { process.exitCode = 1; stop('worker_exit') }
    if (!children.size && stopping) finish()
  })
  child.send({ command: 'start', config, workerId, indices })
}
function report(type = 'fleet_stats') {
  const workers = [...stats.values()]
  const totals = aggregate(workers)
  output({ type, phase, action, requested: config.count, workerCount: children.size,
    staleWorkers: workers.filter(s => performance.now() - s.lastReportAt > config.reportMs * 3).map(s => s.workerId),
    ...totals, clientCpuHostPercent: totals.cpuCorePercent / config.hostLogicalProcessors,
    parentRssBytes: process.memoryUsage().rss,
    workers: workers.map(({ lastReportAt, type, ...stat }) => stat) })
}
reportTimer = setInterval(report, config.reportMs)
function stop(reason) {
  if (stopping) return
  stopping = true
  output({ type: 'fleet_stopping', reason })
  clearInterval(reportTimer)
  for (const child of children.values()) child.send({ command: 'stop' })
  forceTimer = setTimeout(() => { for (const child of children.values()) child.kill(); }, 5000)
}
function finish() {
  clearTimeout(forceTimer)
  report('fleet_final')
  input.close()
  process.stdin.destroy()
}
const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
input.on('line', line => {
  try {
    const message = JSON.parse(line)
    if (message.command === 'stop') stop('stdin')
    else if (message.command === 'phase') { phase = String(message.phase); output({ type: 'phase', phase }) }
    else if (message.command === 'status') report()
    else if (message.command === 'action' && ['idle', 'walk'].includes(message.action)) {
      action = message.action
      for (const child of children.values()) child.send(message)
      output({ type: 'action', action })
    } else throw new Error('Unknown command or invalid action')
  } catch (error) { output({ type: 'control_error', error: error.message }) }
})
process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))
if (config.duration) setTimeout(() => stop('duration'), config.duration * 1000).unref()
