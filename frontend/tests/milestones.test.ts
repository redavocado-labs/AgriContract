import { describe, expect, it, vi } from "vitest"
vi.mock("@/shared/api/client", () => ({ apiClient: {} }))
import {
  consentContent,
  kg3,
  milestoneConsent,
  milestoneStatement,
  sha256Hex,
} from "../src/features/milestones/lib/milestone-consent"
import {
  breachCaseSchema,
  milestoneDetailSchema,
} from "../src/features/milestones/api/milestones"
import {
  daysLeft,
  milestoneTimeline,
} from "../src/features/milestones/lib/presentation"
import { initialBreachCases, initialDetails } from "../src/mocks/bf04-fixtures"

const milestoneId = "5F0C9A1E-3B2D-4C8E-9F10-2A3B4C5D6E7F"

describe("milestone consent recipe (design §3.2.3, D84)", () => {
  it("formats kg with exactly 3 decimals without float drift", () => {
    expect(kg3("9700")).toBe("9700.000")
    expect(kg3("9700.5")).toBe("9700.500")
    expect(kg3("0009700.25")).toBe("9700.250")
    expect(kg3(9650.25)).toBe("9650.250")
    expect(() => kg3("9700.1234")).toThrow()
    expect(() => kg3("abc")).toThrow()
  })
  it("builds ACTION|field strings with lowercase uuid and null as empty", () => {
    expect(
      consentContent({
        action: "DECLARE_SELLER_WEIGHT",
        milestoneId,
        sellerDeclaredWeight: "9700",
        sellerEvidenceFileId: "8A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D",
        deliveryCertificateFileId: null,
      })
    ).toBe(
      "DECLARE_SELLER_WEIGHT|5f0c9a1e-3b2d-4c8e-9f10-2a3b4c5d6e7f|9700.000|8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d|"
    )
    expect(
      consentContent({
        action: "CONTEST_MILESTONE_FLAG",
        milestoneId,
        sellerDeclaredWeight: 9700.5,
        buyerReceivedWeight: 9650.25,
        issueType: "QUANTITY",
      })
    ).toBe(
      "CONTEST_MILESTONE_FLAG|5f0c9a1e-3b2d-4c8e-9f10-2a3b4c5d6e7f|9700.500|9650.250|QUANTITY"
    )
  })
  it("matches known SHA-256 vectors (computed independently with hashlib)", async () => {
    const declare = await milestoneConsent({
      action: "DECLARE_SELLER_WEIGHT",
      milestoneId,
      sellerDeclaredWeight: "9700",
      sellerEvidenceFileId: "8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
      deliveryCertificateFileId: null,
    })
    expect(declare).toEqual({
      statementVersion: "1.0",
      presentedContentHash:
        "e94551169fabb7ba7371d99801e90680c541a65581986ce93f1cde79415a2653",
    })
    const flag = await milestoneConsent({
      action: "FLAG_MILESTONE_ISSUE",
      milestoneId,
      declarationNumber: 2,
      sellerDeclaredWeight: 9700.5,
      buyerReceivedWeight: 9650.25,
      issueType: "QUALITY",
    })
    expect(flag.presentedContentHash).toBe(
      "f5ee4f84874e6cc2427c80285d34b9c780f2d0cd3efc8b55f10a968e69b2a16e"
    )
    expect(await sha256Hex("")).toBe(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
  })
  it("fills statement placeholders and keeps the rest word for word", () => {
    expect(milestoneStatement("DECLARE_SELLER_WEIGHT", { x: "9700.5" })).toBe(
      "Tôi xác nhận lô hàng của đợt giao này đã được cân với khối lượng 9.700,5 kg như tôi nhập, ảnh đính kèm là ảnh chụp thật của lần cân đó, và tôi chịu trách nhiệm về số liệu đã khai."
    )
    expect(
      milestoneStatement("FLAG_MILESTONE_ISSUE", { issueType: "QUALITY" })
    ).toContain("có vấn đề về chất lượng.")
    expect(
      milestoneStatement("FLAG_MILESTONE_ISSUE", { issueType: "QUANTITY" })
    ).toContain("có vấn đề về khối lượng.")
    expect(
      milestoneStatement("CONFIRM_CLEAN_RECEIPT", { s: 9680, b: 9550 })
    ).toContain("bên bán khai 9.680 kg và bên mua cân 9.550 kg")
  })
})

describe("BF-04 read model", () => {
  it("dev fixtures follow MilestoneDetailResponse and BreachCase (#552)", () => {
    for (const detail of initialDetails())
      expect(() => milestoneDetailSchema.parse(detail)).not.toThrow()
    for (const breach of initialBreachCases())
      expect(() => breachCaseSchema.parse(breach)).not.toThrow()
  })
  it("rejects money as a number in computedResolution", () => {
    const [breach] = initialBreachCases()
    expect(() =>
      breachCaseSchema.parse({
        ...breach,
        computedResolution: {
          ...(breach.computedResolution as object),
          sellerEntitlementAmount: 0,
        },
      })
    ).toThrow()
  })
  it("adds the dispute branch to the timeline only once flagged", () => {
    const details = initialDetails()
    const clean = milestoneTimeline(
      milestoneDetailSchema.parse(
        details.find((d) => d.milestone.milestoneIndex === 5)
      )
    )
    expect(clean.map((n) => n.key)).toEqual([
      "CREATED",
      "IN_PROGRESS",
      "SELLER_WEIGHED",
      "BUYER_RECEIVED",
      "SETTLED",
    ])
    expect(clean.find((n) => n.key === "BUYER_RECEIVED")?.status).toBe("active")
    const disputed = milestoneTimeline(
      milestoneDetailSchema.parse(
        details.find((d) => d.milestone.milestoneIndex === 3)
      )
    )
    expect(disputed.find((n) => n.key === "CONTESTED")?.status).toBe("active")
  })

  it("counts whole days left from a server deadline without moving it", () => {
    const now = Date.parse("2026-11-22T00:00:00Z")
    expect(daysLeft("2026-11-24T05:00:00Z", now)).toBe(2)
    expect(daysLeft("2026-11-22T05:00:00Z", now)).toBe(0)
  })
})
