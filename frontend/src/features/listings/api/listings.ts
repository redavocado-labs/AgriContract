import { z } from "zod"
import { apiClient } from "@/shared/api/client"
import { readPublicCatalog } from "./public-catalog"

const qualitySpec = z.object({
  commodity: z.literal("COFFEE"),
  type: z.enum(["ARABICA", "ROBUSTA"]),
  moisturePercent: z.number(),
  foreignMatterPercent: z.number(),
  blackBrokenBeansPercent: z.number(),
})
const quantity = z
  .union([z.number(), z.string().regex(/^\d+(?:\.\d+)?$/)])
  .transform(Number)
  .pipe(z.number().nonnegative())

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
  packagingDeclaration: z.string().nullish(),
  cropYear: z.number().int().nullish(),
  harvestSeason: z.string().nullish(),
  certifications: z.array(z.string()).nullish(),
  registryEntryIds: z.array(z.string()).optional(),
  traceabilityEvidencePackageId: z.string().nullish(),
})
export type Listing = z.infer<typeof listingSchema>
export type QualitySpec = z.infer<typeof qualitySpec>

const listingPageSchema = z.object({
  content: z.array(listingSchema),
  page: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
  totalElements: z.number().int().nonnegative(),
})

const marketplacePageSchema = listingPageSchema.extend({
  content: z.array(listingSchema.extend({
    sellerReputation: z.object({
      score: z.number().int().min(0).max(100).nullable(),
      locked: z.boolean(),
    }).nullable(),
  })),
  reputationStatus: z.enum(["AVAILABLE", "UNAVAILABLE", "SIGN_IN_REQUIRED"]),
})

export type MarketplaceListing = z.infer<typeof marketplacePageSchema>["content"][number]

function parseListing(data: unknown, id: string) {
  const listing = listingSchema.parse(data)
  if (listing.listingId.toLowerCase() !== id.toLowerCase())
    throw new Error("Nguồn hàng trả về không khớp.")
  return listing
}

export async function getListing(id: string, signal?: AbortSignal) {
  const { data } = await apiClient.get<unknown>(
    `/api/v1/listings/${encodeURIComponent(id)}`,
    { signal }
  )
  return parseListing(data, id)
}

export async function getPublicListing(id: string, signal?: AbortSignal) {
  const data = await readPublicCatalog(`/api/v1/listings/${encodeURIComponent(id)}`, signal)
  return parseListing(data, id)
}

export async function searchListings(page: number, signal?: AbortSignal) {
  const { data } = await apiClient.get<unknown>("/api/v1/listings", {
    params: { page, size: 12 },
    signal,
  })
  return listingPageSchema.parse(data)
}

export async function searchMarketplace(
  page: number,
  viewerId: string | undefined,
  minSellerScore: number | undefined,
  signal?: AbortSignal
) {
  const path = "/api/v1/listings"
  const params = { page, size: 12, minSellerScore: viewerId ? minSellerScore : undefined }
  const data = viewerId
    ? (await apiClient.get<unknown>(path, { params, signal })).data
    : await readPublicCatalog(path, signal, params)
  return marketplacePageSchema.parse(data)
}
