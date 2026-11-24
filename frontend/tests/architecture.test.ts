import { spawnSync } from "node:child_process"
import {
  closeSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import { describe, expect, it } from "vitest"

const checker = fileURLToPath(new URL("../scripts/check-architecture.mjs", import.meta.url))

function check(files: Record<string, string>) {
  const root = mkdtempSync(join(tmpdir(), "agricontract-architecture-"))
  try {
    writeFileSync(join(root, "tsconfig.app.json"), JSON.stringify({
      compilerOptions: {
        module: "esnext",
        moduleResolution: "bundler",
        paths: { "@/*": ["./src/*"] },
      },
      include: ["src"],
    }))
    for (const [name, content] of Object.entries(files)) {
      const file = join(root, name)
      mkdirSync(dirname(file), { recursive: true })
      writeFileSync(file, content)
    }
    const outputFile = join(root, "output.log")
    const outputFd = openSync(outputFile, "w")
    try {
      const { error, status } = spawnSync(process.execPath, [checker], {
        cwd: root,
        stdio: ["ignore", outputFd, outputFd],
      })
      if (error) throw error
      return { status, output: readFileSync(outputFile, "utf8") }
    } finally {
      closeSync(outputFd)
    }
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

describe("frontend architecture checker", () => {
  it("resolves aliases, declared feature APIs and white-box test imports", () => {
    const result = check({
      "src/features/contracts/index.ts": 'export { label } from "./label"',
      "src/features/contracts/label.ts": 'export const label = "Hợp đồng"',
      "src/features/milestones/panel.ts": 'import { label } from "@/features/contracts"',
      "src/routes/contracts-page.ts": 'import { label } from "../features/contracts/label"',
      "tests/contracts.test.ts": 'import { label } from "@/features/contracts/label"',
    })

    expect(result.status).toBe(0)
    expect(result.output).toContain("passed for 5 modules")
  })

  it("rejects shared-to-feature imports, including erased type references", () => {
    const result = check({
      "src/features/auth/index.ts": "export type Session = { active: boolean }",
      "src/shared/view.ts": 'export type View = import("@/features/auth").Session',
    })

    expect(result.status).toBe(1)
    expect(result.output).toContain("Shared modules can only depend on other shared modules")
    expect(result.output).toContain("src/shared/view.ts -> src/features/auth/index.ts")
  })

  it("rejects relative private imports and undeclared public feature imports", () => {
    const result = check({
      "src/features/contracts/index.ts": 'export { label } from "./label"',
      "src/features/contracts/label.ts": 'export const label = "Hợp đồng"',
      "src/features/auth/session.ts": 'export { label } from "@/features/contracts"',
      "src/features/milestones/panel.ts": 'export { label } from "../contracts/label.js"',
    })

    expect(result.status).toBe(1)
    expect(result.output).toContain("Import contracts through its public index.ts")
    expect(result.output).toContain("Feature auth has no declared dependency on contracts")
  })

  it("rejects cycles across static and lazy imports", () => {
    const result = check({
      "src/shared/first.ts": 'export const load = () => import("@/shared/second")',
      "src/shared/second.ts": 'export { load } from "./first"',
    })

    expect(result.status).toBe(1)
    expect(result.output).toContain(
      "Circular dependency: src/shared/first.ts -> src/shared/second.ts -> src/shared/first.ts"
    )
  })
})
