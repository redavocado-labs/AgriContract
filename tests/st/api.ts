// Set-up through the API for the steps the web client has no screen for yet (category approval, product, plot and
// listing of the seller, KYC decision of the Operator). The system tests themselves act through the UI; these calls
// only prepare their pre-conditions, as tests/api/e2e/bf02-plot-listing does.
import { randomUUID } from "node:crypto"

export const GATEWAY = process.env.ST_GATEWAY_URL ?? "http://localhost:8080"
export const KEYCLOAK = process.env.ST_KEYCLOAK_URL ?? "http://localhost:8180"
export const MAILHOG = process.env.ST_MAILHOG_URL ?? "http://localhost:8025"
export const PASSWORD = "pass123"

export async function token(username: string, password = PASSWORD): Promise<string> {
  const res = await fetch(`${KEYCLOAK}/realms/agricontract/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "password", client_id: "agricontract-frontend", username, password }),
  })
  if (res.status !== 200) throw new Error(`token ${username}: ${res.status}`)
  return (await res.json()).access_token
}

export async function api(user: string, method: string, path: string, body?: unknown, expected?: number) {
  const headers: Record<string, string> = { authorization: `Bearer ${await token(user)}` }
  if (body !== undefined) headers["content-type"] = "application/json"
  if (method !== "GET") headers["idempotency-key"] = randomUUID()
  const res = await fetch(GATEWAY + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (expected !== undefined && res.status !== expected) {
    throw new Error(`${method} ${path}: expected ${expected}, got ${res.status} ${text.slice(0, 300)}`)
  }
  return { status: res.status, data }
}

export async function poll<T>(read: () => Promise<T>, accept: (v: T) => boolean, label: string, attempts = 45) {
  for (let i = 0; i < attempts; i++) {
    const v = await read()
    if (accept(v)) return v
    await new Promise((r) => setTimeout(r, 2000))
  }
  throw new Error(`timed out waiting for ${label}`)
}

/** A COFFEE listing of seller1 that a buyer can offer on, built as BF-02 builds it. */
export async function coffeeListing(suffix = Date.now().toString(36)) {
  const cats = (await api("seller1", "GET", "/api/v1/categories", undefined, 200)).data as { categoryId: string; commodity?: string }[]
  let category = cats.find((c) => c.commodity === "COFFEE")?.categoryId
  if (!category) {
    const proposed = (await api("seller1", "POST", "/api/v1/categories", { name: `Coffee ST ${suffix}` }, 201)).data
    await api("operator1", "PUT", `/api/v1/admin/categories/${proposed.categoryId}/approve`, { commodity: "COFFEE" }, 200)
    category = proposed.categoryId
  }
  const name = `Robusta ST ${suffix}`
  const product = (await api("seller1", "POST", "/api/v1/products", {
    name, unit: "kg", categoryId: category, imageUrls: ["https://example.test/st-robusta.jpg"],
    cropYear: 2026, harvestSeason: "2026/27", certifications: [],
  }, 201)).data
  const plot = (await api("seller1", "POST", "/api/v1/plots", {
    householdLabel: `ST ${suffix}`, latitude: 12.6642, longitude: 108.0543, areaHectares: 1.5, accuracyMeters: 8.5,
    declaredProvince: "DAK_LAK",
  }, 201)).data
  await poll(() => api("seller1", "GET", "/api/v1/plots?size=100"),
    ({ data }) => data?.content?.some((p: { registryEntryId: string; geoVerificationStatus: string }) =>
      p.registryEntryId === plot.registryEntryId && ["VERIFIED", "UNVERIFIED"].includes(p.geoVerificationStatus)),
    "plot geo check")
  const listing = (await api("seller1", "POST", "/api/v1/listings", {
    productId: product.productId, productName: name, unitOfMeasure: "KG", offeredQuantity: 5000, minOrderQuantity: 100,
    askingUnitPrice: "96000.00", registryEntryIds: [plot.registryEntryId], regionText: "Buon Ma Thuot, Dak Lak",
    declaredQualitySpec: { commodity: "COFFEE", type: "ROBUSTA", moisturePercent: 12.5, foreignMatterPercent: 0.5,
      blackBrokenBeansPercent: 5 },
    packagingDeclaration: "Bao day 60 kg",
  }, 201)).data
  return { listingId: listing.listingId as string, productName: name }
}

/** Latest mail to a recipient whose subject matches, from MailHog. */
export async function mail(to: string, subject: RegExp, since: Date) {
  return poll(async () => {
    const res = await fetch(`${MAILHOG}/api/v2/search?kind=to&query=${encodeURIComponent(to)}&limit=20`)
    const items = (await res.json()).items as { Created: string; Content: { Headers: { Subject: string[] }; Body: string } }[]
    return items.find((m) => new Date(m.Created) >= since && subject.test(m.Content.Headers.Subject?.[0] ?? ""))
  }, (m) => m !== undefined, `mail to ${to}`, 30)
}

export function decodeMail(body: string) {
  // quoted-printable over UTF-8: =XX are bytes, so rebuild the bytes before decoding
  const raw = body.replace(/=\r?\n/g, "").replace(/=([0-9A-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
  return Buffer.from(raw, "latin1").toString("utf8")
}
