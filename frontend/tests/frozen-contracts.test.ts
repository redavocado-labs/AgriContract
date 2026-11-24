import { readFileSync } from "node:fs"
import { load } from "js-yaml"
import { describe, expect, it, vi } from "vitest"
import { z } from "zod"
import { milestoneDetailSchema } from "@/features/milestones/api/milestones"

// Compatibility exercises the actual runtime schema without initializing a browser session.
vi.mock("@/shared/api/client", () => ({ apiClient: {} }))

const api = load(readFileSync(
  new URL("../../contracts/openapi/golden-flow-api.yaml", import.meta.url),
  "utf8",
))

function resolve(reference: string) {
  const prefix = "#/components/schemas/"
  if (!reference?.startsWith(prefix)) throw new Error(`Unexpected schema reference: ${reference}`)
  const schema = api.components.schemas[reference.slice(prefix.length)]
  if (!schema) throw new Error(`Unknown schema reference: ${reference}`)
  return schema
}

type Shape = {
  required?: string[]
  properties: Record<string, {
    type?: string | string[]
    enum?: unknown[]
    anyOf?: Array<{ type?: string }>
    oneOf?: Array<{ type?: string; $ref?: string }>
  }>
}

function checkShape(frozen: Shape, runtime: Shape) {
  expect([...frozen.required ?? []].sort()).toEqual([...runtime.required ?? []].sort())
  for (const [key, field] of Object.entries(runtime.properties)) {
    const expected = frozen.properties[key]
    expect(expected, key).toBeDefined()
    if (expected.enum || field.enum) {
      expect(field.enum, key).toEqual(expected.enum)
      continue
    }
    const variants = expected.oneOf?.map((part) => part.$ref ? resolve(part.$ref).type : part.type)
      ?? [expected.type].flat()
    const accepted = field.anyOf?.map((part) => part.type) ?? [field.type].flat()
    expect(accepted.sort(), key).toEqual(variants.sort())
  }
}

const endpoint = api.paths["/api/v1/contracts/{contractId}/milestones/{milestoneId}"].get
const response = resolve(endpoint.responses["200"].content["application/json"].schema.$ref)
const evidence = resolve(response.properties.evidence.items.$ref)
const milestone = resolve(response.properties.milestone.$ref)
const runtime = z.toJSONSchema(milestoneDetailSchema, { io: "input" })

describe("frozen milestone read model compatibility", () => {
  it("agrees with the milestone summary and evidence DTOs that gate delivery actions", () => {
    checkShape(milestone, runtime.properties.milestone)
    checkShape(evidence, runtime.properties.evidence.items)
  })

  it("detects new required fields, new statuses and changed wire types", () => {
    const schema = runtime.properties.evidence.items
    const variants = [
      { ...evidence, required: [...evidence.required, "reviewer"] },
      { ...evidence, properties: { ...evidence.properties, status: { enum: ["PROCESSING", "READY", "FAILED", "REVIEW"] } } },
      { ...evidence, properties: { ...evidence.properties, fileId: { type: "integer" } } },
    ]
    for (const variant of variants) expect(() => checkShape(variant, schema)).toThrow()
  })

  it("accepts an additional optional field that the frontend does not consume", () => {
    checkShape({ ...evidence, properties: { ...evidence.properties, optionalNote: { type: "string" } } }, runtime.properties.evidence.items)
  })
})
