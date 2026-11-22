import { beforeEach, describe, expect, it, vi } from "vitest"
const http = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock("@/shared/api/client", () => ({ apiClient: http }))
import {
  createContract,
  inputKeys,
} from "../src/features/contracts/api/contracts"
import {
  offerDefaults,
  offerSchema,
  offerTerms,
} from "../src/features/contracts/schemas/offer"

// Synthetic fixture only.
const coffee = {
  contractId: "10000000-0000-4000-8000-000000000001",
  listingId: "listing-test",
  buyerId: "buyer-test",
  sellerId: "seller-test",
  status: "OFFERED",
  signedContentHash: "a".repeat(64),
  reservedQuantity: 1000,
  milestones: [],
  terms: { agreedPrice: "95000.00" },
}
beforeEach(() => vi.clearAllMocks())

describe("create offer", () => {
  it("creates an offer with only ContractTermsInput fields", async () => {
    const listing = { askingUnitPrice: "95000.00", minOrderQuantity: 500 }
    const values = offerSchema.parse({
      ...offerDefaults(listing),
      milestones: [
        {
          committedQuantity: "1000",
          expectedDeliveryDate: "2026-12-15",
          graceDays: "2",
        },
      ],
    })
    const terms = offerTerms(values)
    expect(
      Object.keys(terms).every((k) =>
        (inputKeys as readonly string[]).includes(k)
      )
    ).toBe(true)
    expect(terms.buyerDepositRate).toBe(0.05)
    http.post.mockResolvedValueOnce({ data: coffee })
    await createContract(
      { listingId: "listing-test", listingVersionToken: "v1", terms },
      "key",
      "buyer-test"
    )
    expect(http.post.mock.calls[0][0]).toBe("/api/v1/contracts")
    expect(http.post.mock.calls[0][2]).toEqual({
      headers: { "Idempotency-Key": "key" },
    })
    await expect(
      createContract(
        { listingId: "l", listingVersionToken: "v", terms: { goodsTerms: {} } },
        "k",
        "buyer-test"
      )
    ).rejects.toThrow()
    expect(http.post).toHaveBeenCalledTimes(1)
  })
  it("rejects an offer whose price is outside its band", () => {
    const base = offerDefaults({ askingUnitPrice: "95000.00" })
    expect(
      offerSchema.safeParse({
        ...base,
        agreedPrice: "200000.00",
        milestones: [
          {
            committedQuantity: 1,
            expectedDeliveryDate: "2026-12-15",
            graceDays: 0,
          },
        ],
      }).success
    ).toBe(false)
  })
})
