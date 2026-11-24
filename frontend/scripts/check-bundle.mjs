import { readFileSync } from "node:fs"
import { gzipSync } from "node:zlib"

const dist = new URL("../dist/", import.meta.url)
const manifest = JSON.parse(readFileSync(new URL(".vite/manifest.json", dist), "utf8"))
const entries = Object.keys(manifest).filter((key) => manifest[key].isEntry)
if (entries.length === 0) throw new Error("No production bundle entries found.")

const initialKeys = new Set()
function visit(key) {
  if (initialKeys.has(key)) return
  const chunk = manifest[key]
  if (!chunk) throw new Error(`Unknown manifest import: ${key}`)
  initialKeys.add(key)
  for (const dependency of chunk.imports ?? []) visit(dependency)
}
for (const entry of entries) visit(entry)

const paths = (chunks) => new Set(chunks.map(({ file }) => file).filter((file) => file.endsWith(".js")))
const initialFiles = paths([...initialKeys].map((key) => manifest[key]))
const files = [...paths(Object.values(manifest))]
const sizes = files.map((file) => {
  const content = readFileSync(new URL(file, dist))
  return { file, bytes: content.length, gzip: gzipSync(content).length }
})
const initial = sizes.filter(({ file }) => initialFiles.has(file))
const deferred = sizes.filter(({ file }) => !initialFiles.has(file))
const sum = (chunks, unit) => chunks.reduce((total, chunk) => total + chunk[unit], 0)

const summary = {
  initial: { bytes: sum(initial, "bytes"), gzip: sum(initial, "gzip") },
  deferred: { bytes: sum(deferred, "bytes"), gzip: sum(deferred, "gzip") },
  largestDeferredGzip: Math.max(0, ...deferred.map(({ gzip }) => gzip)),
}
console.log(JSON.stringify(summary, null, 2))

// Limits are measured against the first split production bundle.
const initialLimit = 205_000
const deferredLimit = 30_000
const totalLimit = 350_000
if (
  summary.initial.gzip > initialLimit ||
  summary.largestDeferredGzip > deferredLimit ||
  summary.initial.gzip + summary.deferred.gzip > totalLimit
) {
  console.error(`JavaScript exceeded the gzip budget (${initialLimit} initial, ${deferredLimit} per deferred chunk, ${totalLimit} total).`)
  process.exitCode = 1
}
