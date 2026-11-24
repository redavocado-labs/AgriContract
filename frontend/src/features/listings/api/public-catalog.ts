import axios from "axios"
import { z } from "zod"

// The Gateway serves these catalog reads to guests. This client has no auth interceptors.
const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 15_000,
  allowAbsoluteUrls: false,
})

export async function readPublicCatalog(
  path: "/api/v1/listings" | `/api/v1/listings/${string}` | `/api/v1/prices/COFFEE/${string}`,
  signal?: AbortSignal,
  params?: Record<string, string | number | undefined>
): Promise<unknown> {
  const { data } = await client.get<unknown>(path, { signal, params })
  return data
}

const slug = z.string().regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/)
const itemsSchema = z.object({
  commodity: z.literal("COFFEE"),
  province: slug,
  items: z.array(z.object({ itemName: z.string().min(1), itemSlug: slug })),
})
const quoteSchema = z.object({
  commodity: z.literal("COFFEE"),
  itemName: z.string().min(1),
  itemSlug: slug,
  province: z.string().min(1),
  provinceSlug: slug,
  price: z.string().regex(/^\d+\.\d{2}$/),
  source: z.enum(["VNSAT_SCRAPE", "OPERATOR_MANUAL"]),
  capturedAt: z.iso.date(),
  ingestedAt: z.iso.datetime({ offset: true }),
  referenceSource: z.string().nullable(),
  stale: z.boolean(),
})

export async function getPriceItems(province: string, signal?: AbortSignal) {
  const result = itemsSchema.parse(
    await readPublicCatalog(`/api/v1/prices/COFFEE/${encodeURIComponent(province)}/items`, signal)
  )
  if (result.province !== province) throw new Error("Tỉnh của bảng giá không khớp.")
  return result.items
}

export async function getPriceQuote(province: string, item: string, signal?: AbortSignal) {
  const quote = quoteSchema.parse(
    await readPublicCatalog(`/api/v1/prices/COFFEE/${encodeURIComponent(province)}`, signal, { item })
  )
  if (quote.provinceSlug !== province || quote.itemSlug !== item)
    throw new Error("Mặt hàng hoặc tỉnh của giá tham khảo không khớp.")
  return quote
}
