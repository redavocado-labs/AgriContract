// Operator, Arbitrator, Admin screens & role isolation (no role fallback) through the real web client.
// Tests run on any day because they inspect role portals, audit chain integrity, and RBAC boundaries. Each case
// signs in and prepares what it needs, so one failure never stops the others.
import { randomUUID } from "node:crypto"
import { expect, test } from "@playwright/test"
import { api, coffeeListing, poll } from "./api"
import { FUNDING_LEAD, businessDaysAfter, session, todayIct } from "./fixtures"

async function createSampleOffer(): Promise<string> {
  const { listingId } = await coffeeListing()
  const listing = (await api("buyer1", "GET", `/api/v1/listings/${listingId}`, undefined, 200)).data
  const delivery = businessDaysAfter(todayIct(), FUNDING_LEAD + 5)
  const res = await api("buyer1", "POST", "/api/v1/contracts", {
    listingId,
    listingVersionToken: listing.listingVersionToken,
    terms: {
      agreedPrice: listing.askingUnitPrice,
      currency: "VND",
      milestoneSchedule: [{ milestoneIndex: 1, committedQuantity: 500, expectedDeliveryDate: delivery, graceDays: 2 }],
      qualityTerms: {
        commodity: "COFFEE",
        committedSpec: { commodity: "COFFEE", type: "ROBUSTA", moisturePercent: 12.5, foreignMatterPercent: 0.5, blackBrokenBeansPercent: 5 },
        deviationPolicy: {
          type: { rejectOnMismatch: true },
          moisturePercent: { direction: "MAXIMUM", tolerance: 0.5, penalty: 1, reject: 2, discountRate: 0.02 },
          foreignMatterPercent: { direction: "MAXIMUM", tolerance: 0.5, penalty: 1, reject: 2, discountRate: 0.02 },
          blackBrokenBeansPercent: { direction: "MAXIMUM", tolerance: 0.5, penalty: 1, reject: 2, discountRate: 0.02 },
        },
      },
      deliveryTerms: {
        deliveryResponsibility: "SELLER_DELIVERS", riskTransferPoint: "BUYER_RECEIPT",
        titleTransferPoint: "FULL_PAYMENT", transportCostBearer: "SELLER", unloadingCostBearer: "BUYER",
      },
      inspectionTerms: {
        requirement: "NONE_UNLESS_DISPUTED", requiredTier: "LEVEL_1_5",
        reportDueBusinessDays: 5, inspectionCostResponsibility: "LOSER_PAYS",
      },
      priceBand: { minimumUnitPrice: "86400.00", maximumUnitPrice: "105600.00" },
      priceAdjustmentRule: { acceptanceMode: "BOTH_ACCEPT", cutoff: "BEFORE_FUNDING_REQUEST", maximumAcceptedAdjustmentsPerMilestone: 1 },
      toleranceRate: 0.05, shortfallPenaltyThreshold: 0.05,
      buyerPenaltyRate: 0.08, sellerPenaltyRate: 0.06,
      forceMajeureReportWindowDays: 7, buyerConfirmWindowDays: 3, fundingLeadBusinessDays: FUNDING_LEAD,
      buyerDepositRate: 0.05, disputeFloorReleaseRate: 0.5, sellerDepositRate: 0.03,
      level2InspectorOrg: null,
      legalProfile: { governingLaw: "VN_COMMERCIAL_LAW", contractType: "COFFEE_SALE", maxContractualPenaltyRate: 0.08, damagesPolicy: "COMMERCIAL_CUMULATIVE_IF_PROVEN" },
    },
  }, 201)
  const contractId = (res.data.contractId ?? res.data.id) as string
  // Withdraw immediately so audit-service records contract.terminated and builds a contract trail
  await api("buyer1", "POST", `/api/v1/contracts/${contractId}/withdraw`, undefined, 204)
  await poll(
    () => api("admin1", "GET", `/api/v1/admin/audit/contracts/${contractId}/records`),
    ({ status, data }) => status === 200 && Array.isArray(data?.records) && data.records.length > 0,
    "audit record for withdrawn contract",
  )
  return contractId
}

test("ST-ADM-010 Admin checks global audit log and verifies chain integrity", async ({ browser }) => {
  const admin = await session(browser, "admin1")
  await admin.goto("/admin/audit")
  await expect(admin.getByRole("heading", { name: "Nhật ký bằng chứng", level: 1 })).toBeVisible()
  await expect(admin.getByText("Mỗi lần bạn mở nhật ký, hệ thống ghi lại một bản ghi không xoá được.")).toBeVisible()

  await admin.getByRole("button", { name: "Kiểm tra toàn chuỗi" }).click()
  await expect(admin.getByText(/Toàn chuỗi nguyên vẹn/)).toBeVisible({ timeout: 30_000 })
})

test("ST-ADM-020 Admin inspects a contract audit trail and creates an audit.viewed trace", async ({ browser }) => {
  const sampleContractId = await createSampleOffer()
  const admin = await session(browser, "admin1")
  await admin.goto("/admin/audit")
  await admin.getByRole("tab", { name: "Theo hợp đồng" }).click()
  await admin.getByLabel("Mã hợp đồng (UUID)").fill(sampleContractId)
  await admin.getByRole("button", { name: "Tra cứu" }).click()

  await expect(admin.getByText("Mã bằng chứng hiện tại")).toBeVisible({ timeout: 30_000 })
  await expect(admin.getByText("Nguyên vẹn")).toBeVisible({ timeout: 30_000 })

  // Reading the contract trail appends an audit.viewed record to the immutable global trail
  await expect(async () => {
    const res = (await api("admin1", "GET", `/api/v1/admin/audit/records?subjectType=SYSTEM&size=20`, undefined, 200)).data
    const hasViewed = res.content.some(
      (r: { sourceEventType?: string; content?: { contractId?: string; scope?: string } }) =>
        r.sourceEventType === "audit.viewed" && r.content?.contractId === sampleContractId,
    )
    expect(hasViewed).toBe(true)
  }).toPass({ timeout: 20_000, intervals: [2000] })
})

test("ST-OPR-010 Operator opens remedy retry portal, validates UUID, and submits safely", async ({ browser }) => {
  const operator = await session(browser, "operator1")
  await operator.goto("/operations/remedy")
  await expect(operator.getByRole("heading", { name: "Gửi lại lệnh tiền", level: 1 })).toBeVisible()

  const input = operator.getByLabel("Mã hợp đồng")
  await input.fill("not-a-uuid")
  await expect(operator.getByText("Nhập mã hợp đồng đầy đủ.")).toBeVisible()
  await expect(operator.getByRole("button", { name: "Gửi lại lệnh tiền lỗi" })).toBeDisabled()

  await input.fill(randomUUID())
  await operator.getByRole("button", { name: "Gửi lại lệnh tiền lỗi" }).click()
  await expect(operator.getByRole("alert")).toBeVisible({ timeout: 20_000 })
})

test("ST-ARB-010 Arbitrator opens dispute queue from real backend", async ({ browser }) => {
  const arbitrator = await session(browser, "arbitrator1")
  await arbitrator.goto("/disputes")
  await expect(arbitrator.getByRole("heading", { name: "Tranh chấp chờ duyệt", level: 1 })).toBeVisible()
  // Queue shows either pending cases or the clean empty state
  await expect(
    arbitrator.getByText(/Không có vụ nào chờ duyệt|Chọn một vụ để xem/).first(),
  ).toBeVisible({ timeout: 20_000 })
})

test("ST-SEC-010 Role isolation (no fallback): Admin cannot access Arbitrator/Operator routes; Buyer cannot access Admin/Operator/Arbitrator routes", async ({ browser }) => {
  const admin = await session(browser, "admin1")
  const buyer = await session(browser, "buyer1")
  const denied = "Tài khoản chưa có vai trò phù hợp để truy cập trang này."
  for (const path of ["/disputes", "/operations/remedy"]) {
    await admin.goto(path)
    await expect(admin.getByText(denied)).toBeVisible()
  }
  for (const path of ["/admin/audit", "/disputes", "/operations/remedy"]) {
    await buyer.goto(path)
    await expect(buyer.getByText(denied)).toBeVisible()
  }
})
