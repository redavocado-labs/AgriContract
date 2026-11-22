import { z } from "zod"
import { apiClient } from "@/shared/api/client"

const qualitySpec = z.object({
  commodity: z.literal("COFFEE"),
  type: z.enum(["ARABICA", "ROBUSTA"]),
  moisturePercent: z.number(),
  foreignMatterPercent: z.number(),
  blackBrokenBeansPercent: z.number(),
})
const quantity = z.union([z.number(), z.string()]).transform(Number)

/** ListingResponse (product-service), the fields an offer needs. */
const listingSchema = z.object({
  listingId: z.string().min(1),
  sellerId: z.string().min(1),
  productName: z.string().nullish(),
  unitOfMeasure: z.string().nullish(),
  offeredQuantity: quantity.nullish(),
  quantityAvailable: quantity.nullish(),
  minOrderQuantity: quantity.nullish(),
  askingUnitPrice: z.string().regex(/^\d+\.\d{2}$/),
  status: z.string(),
  listingVersionToken: z.string().min(1),
  commodity: z.string().nullish(),
  regionText: z.string().nullish(),
  declaredQualitySpec: qualitySpec.nullish(),
  warehouseLocation: z.string().nullish(),
  estimatedAvailabilityDate: z.string().nullish(),
})
export type Listing = z.infer<typeof listingSchema>
export type QualitySpec = z.infer<typeof qualitySpec>

export async function getListing(id: string, signal?: AbortSignal) {
  const { data } = await apiClient.get(
    `/api/v1/listings/${encodeURIComponent(id)}`,
    { signal }
  )
  return listingSchema.parse(data)
}

export async function searchListings(page: number, signal?: AbortSignal) {
  const { data } = await apiClient.get("/api/v1/listings", {
    params: { page, size: 12 },
    signal,
  })
  return z
    .object({
      content: z.array(listingSchema.passthrough()),
      page: z.number().int(),
      totalPages: z.number().int(),
      totalElements: z.number().int(),
    })
    .parse(data)
}
