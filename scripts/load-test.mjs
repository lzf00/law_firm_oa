import { writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'

const baseUrl = process.env.OA_BASE_URL ?? 'http://localhost:8081/api'
const virtualUsers = Number(process.env.OA_LOAD_USERS ?? 100)
const iterations = Number(process.env.OA_LOAD_ITERATIONS ?? 12)
const concurrency = Number(process.env.OA_LOAD_CONCURRENCY ?? 20)
const evidencePath = process.env.OA_LOAD_EVIDENCE
const users = ['admin', 'zhanglawyer', 'liassistant']
const paths = ['/me', '/matters', '/deadlines', '/notifications?page=1&size=30', '/offices']

const latencies = []
const failures = []
let cursor = 0

async function request(path, user) {
  const started = performance.now()
  const response = await fetch(`${baseUrl}${path}`, {
    headers: { 'X-Dev-User': user, Accept: 'application/json' },
  })
  const latency = performance.now() - started
  latencies.push(latency)
  if (!response.ok) {
    failures.push({ path, user, status: response.status })
  }
  await response.arrayBuffer()
}

async function worker() {
  while (true) {
    const current = cursor
    cursor += 1
    if (current >= virtualUsers * iterations) return
    const virtualUser = current % virtualUsers
    const iteration = Math.floor(current / virtualUsers)
    const user = users[virtualUser % users.length]
    const path = paths[(virtualUser + iteration) % paths.length]
    await request(path, user)
  }
}

const suiteStarted = performance.now()
await Promise.all(Array.from({ length: concurrency }, () => worker()))

const boundaryResponse = await fetch(`${baseUrl}/finance/time-entries?page=1&size=101`, {
  headers: { 'X-Dev-User': 'admin', Accept: 'application/json' },
})
if (boundaryResponse.status !== 400) {
  failures.push({ path: '/finance/time-entries?size=101', status: boundaryResponse.status })
}

const uploadRequests = Array.from({ length: 20 }, (_, index) =>
  fetch(`${baseUrl}/documents/uploads`, {
    method: 'POST',
    headers: { 'X-Dev-User': 'admin', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      matterId: '00000000-0000-0000-0006-000000000001',
      logicalName: `Load boundary ${Date.now()}-${index}`,
      documentType: 'CASE_FILE',
      originalFilename: `load-${index}.txt`,
      contentType: 'text/plain',
      sizeBytes: 1,
      sha256: 'a'.repeat(64),
    }),
  }).then(async (response) => {
    if (!response.ok) {
      failures.push({ path: '/documents/uploads', status: response.status })
    }
    await response.arrayBuffer()
  }),
)
await Promise.all(uploadRequests)

latencies.sort((a, b) => a - b)
const percentile = (value) => latencies[Math.min(
  latencies.length - 1,
  Math.max(0, Math.ceil(latencies.length * value) - 1),
)]
const durationMs = performance.now() - suiteStarted
const report = {
  generatedAt: new Date().toISOString(),
  virtualUsers,
  iterations,
  concurrency,
  requestCount: latencies.length,
  uploadInitiationConcurrency: uploadRequests.length,
  durationMs: Math.round(durationMs),
  throughputPerSecond: Number((latencies.length / (durationMs / 1000)).toFixed(2)),
  latencyMs: {
    p50: Number(percentile(0.5).toFixed(2)),
    p95: Number(percentile(0.95).toFixed(2)),
    p99: Number(percentile(0.99).toFixed(2)),
    max: Number(latencies.at(-1).toFixed(2)),
  },
  failures,
}

if (evidencePath) {
  await writeFile(evidencePath, `${JSON.stringify(report, null, 2)}\n`)
}
console.log(JSON.stringify(report, null, 2))

if (failures.length > 0) {
  throw new Error(`Representative load suite had ${failures.length} failures`)
}
if (report.latencyMs.p95 > Number(process.env.OA_LOAD_P95_LIMIT_MS ?? 1500)) {
  throw new Error(`p95 ${report.latencyMs.p95}ms exceeded the acceptance limit`)
}
