import assert from "node:assert/strict"
import type { Locator, Page, Response } from "@playwright/test"
import {
  buyerId,
  contractDto,
  contractId,
  contractPath,
  errorDto,
  evidenceDto,
  fileId,
  json,
  milestoneDto,
  milestoneId,
  milestonePath,
  now,
  otherContractId,
  secondFileId,
  test,
} from "./browser-fixture"

const contractUrl = `/contracts/${contractId}`
const milestoneUrl = `${contractUrl}/milestones/${milestoneId}`
const exactButton = (scope: Page | Locator, name: string) => scope.getByRole("button", { name, exact: true })
const waitForText = (page: Page, text: string) => page.getByText(text, { exact: true }).waitFor()
const activationPending = /Đã ký hai bên.*Hợp đồng chưa được kích hoạt\./
const active = /Đã kích hoạt theo trạng thái máy chủ\./
const review = "Xem lại trước khi gửi"

async function clockRequest(page: Page, milliseconds: number, match: (response: Response) => boolean) {
  await Promise.all([
    page.waitForResponse(match, { timeout: 4000 }),
    page.clock.fastForward(milliseconds),
  ])
}

function isGet(path: string) {
  return (response: Response) =>
    response.request().method() === "GET" && new URL(response.url()).pathname === path
}

test("FE-004 begins polling when SIGNED follows a late manual read", async ({ scenario, load }) => {
  let status = "OFFERED"
  let reads = 0
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath) {
      reads++
      return json(200, contractDto(status))
    }
  })
  await load(page, contractUrl)
  await waitForText(page, "Đã gửi đề nghị")
  await page.clock.fastForward(125_000)
  assert.equal(reads, 1)

  status = "SIGNED"
  await exactButton(page, "Tải lại trạng thái").click()
  await page.getByText(activationPending).waitFor()
  assert.equal(await page.getByText(active).count(), 0)
  assert.equal(reads, 2)

  status = "ACTIVE"
  await clockRequest(page, 5001, isGet(contractPath))
  await page.getByText(active).waitFor()
  await page.clock.fastForward(130_000)
  assert.equal(reads, 3)
})

test("FE-004 uses a bounded budget for initially SIGNED and permits manual recovery", async ({ scenario, load }) => {
  let reads = 0
  let status = "SIGNED"
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath) {
      reads++
      return json(200, contractDto(status))
    }
  })
  await load(page, contractUrl)
  await page.getByText(activationPending).waitFor()
  await clockRequest(page, 5001, isGet(contractPath))
  await page.clock.fastForward(120_000)
  await page.getByText(/hết thời gian kiểm tra tự động/i).waitFor()
  const atExpiry = reads
  await page.clock.fastForward(130_000)
  assert.equal(reads, atExpiry)
  assert.equal(await page.getByText(active).count(), 0)

  status = "ACTIVE"
  await exactButton(page, "Tải lại trạng thái").click()
  await page.getByText(active).waitFor()
  assert.equal(reads, atExpiry + 1)
})

test("FE-004 recovers from a failed activation read without declaring activation", async ({ scenario, load }) => {
  let readFailure = false
  let status = "SIGNED"
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return readFailure ? errorDto(503, "SERVICE_UNAVAILABLE") : json(200, contractDto(status))
  })
  await load(page, contractUrl)
  await page.getByText(activationPending).waitFor()
  readFailure = true
  await clockRequest(page, 5001, isGet(contractPath))
  assert.equal(await page.getByText(active).count(), 0)

  readFailure = false
  status = "ACTIVE"
  await page.getByRole("button", { name: /^(?:Tải lại trạng thái|Thử lại)$/ }).click()
  await page.getByText(active).waitFor()
})

test("FE-004 switching contract IDs gets a fresh activation budget and stops polling the old resource", async ({ scenario, load }) => {
  const otherPath = `/api/v1/contracts/${otherContractId}`
  let firstReads = 0
  let secondReads = 0
  const page = await scenario((request, path) => {
    if (request.method() !== "GET") return
    if (path === contractPath) {
      firstReads++
      return json(200, contractDto("SIGNED"))
    }
    if (path === otherPath) {
      secondReads++
      return json(200, contractDto(secondReads === 1 ? "SIGNED" : "ACTIVE", otherContractId))
    }
  })
  await load(page, contractUrl)
  await page.getByText(activationPending).waitFor()
  await page.clock.fastForward(125_000)
  const previousReads = firstReads
  // Navigate in the same SPA context without recreating providers or resetting the clock.
  await page.evaluate((id) => {
    history.pushState({}, "", "/contracts/" + id)
    dispatchEvent(new PopStateEvent("popstate"))
  }, otherContractId)
  await waitForText(page, otherContractId)
  await page.getByText(activationPending).waitFor()
  await clockRequest(page, 5001, isGet(otherPath))
  await page.getByText(active).waitFor()
  assert.equal(firstReads, previousReads)
  assert.equal(secondReads, 2)
})

function fakePhoto(name: string) {
  return { name, mimeType: "image/png", buffer: Buffer.from("qa fixture bytes") }
}

test("FE-015 blocks evidence while the read model omits it, then enables review only after READY", async ({ scenario, load }) => {
  let evidence: ReturnType<typeof evidenceDto>[] = []
  let uploads = 0
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("ACTIVE"))
    if (request.method() === "GET" && path === milestonePath)
      return json(200, milestoneDto(evidence))
    if (request.method() === "POST" && path === `${milestonePath}/evidence`) {
      assert.equal(request.headers()["content-type"], "image/png")
      assert.match(request.headers()["idempotency-key"], /^[0-9a-f-]{36}$/)
      assert.deepEqual(request.postDataBuffer(), fakePhoto("photo.png").buffer)
      uploads++
      return json(202, { fileId, status: "PROCESSING" })
    }
  })
  await load(page, milestoneUrl)
  await page.getByLabel("Khối lượng cân được").fill("2000")
  await page.getByLabel("Ảnh cân hàng").setInputFiles(fakePhoto("photo.png"))
  await waitForText(page, "photo.png")
  assert.equal(await exactButton(page, review).isDisabled(), true)
  await page.clock.fastForward(61_000)
  const slot = page.locator("[data-slot=evidence-uploader]").filter({ hasText: "Ảnh cân hàng" })
  await slot.getByText(/chưa xác định|chưa có kết quả|quá thời gian/i).first().waitFor()
  assert.equal(await exactButton(page, review).isDisabled(), true)

  evidence = [evidenceDto(fileId, "READY")]
  const recheck = page.getByRole("button", { name: /kiểm tra lại|tải lại trạng thái/i }).first()
  await recheck.click()
  await slot.getByText(/Sẵn sàng/).waitFor()
  assert.equal(await exactButton(page, review).isEnabled(), true)
  assert.equal(uploads, 1)
})

test("FE-015 a rejected scan offers replacement and leaves an independent file usable", async ({ scenario, load }) => {
  let evidence: ReturnType<typeof evidenceDto>[] = []
  let uploads = 0
  let reads = 0
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("ACTIVE"))
    if (request.method() === "GET" && path === milestonePath) {
      reads++
      return json(200, milestoneDto(evidence))
    }
    if (request.method() === "POST" && path === `${milestonePath}/evidence`) {
      const id = uploads++ === 0 ? fileId : secondFileId
      evidence = [...evidence, evidenceDto(id, "PROCESSING")]
      return json(202, { fileId: id, status: "PROCESSING" })
    }
  })
  await load(page, milestoneUrl)
  await page.getByLabel("Khối lượng cân được").fill("2000")
  const photo = page.getByLabel("Ảnh cân hàng")
  const certificate = page.getByLabel("Phiếu giao hàng (không bắt buộc)")
  await photo.setInputFiles(fakePhoto("scale.png"))
  await waitForText(page, "scale.png")
  await certificate.setInputFiles(fakePhoto("certificate.png"))
  await waitForText(page, "certificate.png")
  assert.equal(await exactButton(page, review).isDisabled(), true)

  evidence = [evidenceDto(fileId, "FAILED"), evidenceDto(secondFileId, "READY")]
  await clockRequest(page, 2001, isGet(milestonePath))
  const slot = page.locator("[data-slot=evidence-uploader]").filter({ hasText: "Ảnh cân hàng" })
  const certificateSlot = page.locator("[data-slot=evidence-uploader]").filter({ hasText: "Phiếu giao hàng" })
  await certificateSlot.getByText(/Sẵn sàng/).waitFor()
  await slot.getByText(/không qua|bị từ chối/i).first().waitFor()
  assert.equal(await slot.getByRole("button", { name: "Thử lại", exact: true }).count(), 0)
  assert.equal(await slot.getByRole("button", { name: "Chọn ảnh khác" }).isEnabled(), true)
  assert.equal(await exactButton(page, review).isDisabled(), true)
  const finalReads = reads
  await page.clock.fastForward(65_000)
  assert.equal(reads, finalReads)
})

test("FE-015 a projected PROCESSING file stops automatic reads after its 60-second budget", async ({ scenario, load }) => {
  let uploaded = false
  let reads = 0
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("ACTIVE"))
    if (request.method() === "GET" && path === milestonePath) {
      reads++
      return json(200, milestoneDto(uploaded ? [evidenceDto(fileId, "PROCESSING")] : []))
    }
    if (request.method() === "POST" && path === `${milestonePath}/evidence`) {
      uploaded = true
      return json(202, { fileId, status: "PROCESSING" })
    }
  })
  await load(page, milestoneUrl)
  await Promise.all([
    page.waitForResponse(isGet(milestonePath)),
    page.getByLabel("Ảnh cân hàng").setInputFiles(fakePhoto("slow-scan.png")),
  ])
  await clockRequest(page, 2001, isGet(milestonePath))
  await page.clock.fastForward(61_000)
  await page.getByText(/chưa có kết quả kiểm tra an toàn/i).waitFor()
  const atTimeout = reads
  await page.clock.fastForward(65_000)
  assert.equal(reads, atTimeout)
  assert.equal(await exactButton(page, review).isDisabled(), true)
})

test("FE-003 a lost withdraw response reconciles across a second tab and reload without replay", async ({ scenario, load }) => {
  let status = "OFFERED"
  const writes: (string | undefined)[] = []
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto(status))
    if (request.method() === "POST" && path === `${contractPath}/withdraw`) {
      writes.push(request.headers()["idempotency-key"])
      status = "WITHDRAWN"
      return "abort"
    }
  })
  await load(page, contractUrl)
  await exactButton(page, "Rút đề nghị").click()
  await page.getByRole("dialog").getByRole("button", { name: "Rút đề nghị", exact: true }).click()
  await waitForText(page, "Đã rút đề nghị")
  assert.equal(await page.getByText("Đã rút đề nghị hợp đồng", { exact: true }).count(), 0)

  const secondTab = await page.context().newPage()
  await load(secondTab, contractUrl)
  await waitForText(secondTab, "Đã rút đề nghị")
  assert.equal(await exactButton(secondTab, "Rút đề nghị").count(), 0)
  await page.reload({ waitUntil: "domcontentloaded" })
  await waitForText(page, "Đã rút đề nghị")
  assert.equal(await exactButton(page, "Rút đề nghị").count(), 0)
  assert.equal(writes.length, 1)
  assert.match(writes[0] ?? "", /^[0-9a-f-]{36}$/)
})

test("FE-003 an uncertain withdrawal survives reload and reuses its key after the authoritative read", async ({ scenario, load }) => {
  const events: string[] = []
  const keys: (string | undefined)[] = []
  let status = "OFFERED"
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath) {
      events.push("read")
      return json(200, contractDto(status))
    }
    if (request.method() === "POST" && path === `${contractPath}/withdraw`) {
      events.push("write")
      keys.push(request.headers()["idempotency-key"])
      if (keys.length === 1) return errorDto(429, "RATE_LIMIT_EXCEEDED")
      status = "WITHDRAWN"
      return { status: 204 }
    }
  })
  const openWithdrawal = () => exactButton(page, "Rút đề nghị").click()
  const confirm = page.getByRole("dialog").getByRole("button", { name: "Rút đề nghị", exact: true })
  await load(page, contractUrl)
  await openWithdrawal()
  await confirm.click()
  await page.getByRole("dialog").getByRole("alert").waitFor()
  assert.equal(keys.length, 1)
  assert.equal(await page.getByText("Đã rút đề nghị hợp đồng", { exact: true }).count(), 0)

  await page.reload({ waitUntil: "domcontentloaded" })
  await openWithdrawal()
  assert.equal(keys.length, 1)
  events.length = 0
  await confirm.click()
  await page.getByText("Đã rút đề nghị hợp đồng", { exact: true }).waitFor()
  assert.deepEqual(events.slice(0, 2), ["read", "write"])
  assert.equal(keys.length, 2)
  assert.match(keys[0] ?? "", /^[0-9a-f-]{36}$/)
  assert.equal(keys[0], keys[1])
})

test("FE-003 concurrent tabs submit at most one withdrawal while the first response is pending", async ({ scenario, load }) => {
  let status = "OFFERED"
  let writes = 0
  const pendingResponse = Promise.withResolvers<void>()
  const page = await scenario(async (request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto(status))
    if (request.method() === "POST" && path === `${contractPath}/withdraw`) {
      writes++
      await pendingResponse.promise
      status = "WITHDRAWN"
      return { status: 204 }
    }
  })
  const secondTab = await page.context().newPage()
  await Promise.all([load(page, contractUrl), load(secondTab, contractUrl)])
  await exactButton(page, "Rút đề nghị").click()
  await exactButton(secondTab, "Rút đề nghị").click()
  const confirm = (tab: Page) =>
    tab.getByRole("dialog").getByRole("button", { name: "Rút đề nghị", exact: true })
  try {
    await Promise.all([
      page.waitForRequest((request) => request.method() === "POST"),
      confirm(page).click(),
    ])
    const competingWrite = secondTab
      .waitForRequest((request) => request.method() === "POST", { timeout: 4500 })
      .then(() => "dispatched")
    const blocked = secondTab.getByRole("alert")
      .filter({ hasText: /đang được xử lý/i })
      .waitFor({ timeout: 4000 })
      .then(() => "blocked")
    const [outcome] = await Promise.all([Promise.race([competingWrite, blocked]), confirm(secondTab).click()])
    assert.equal(outcome, "blocked")
    assert.equal(writes, 1)
  } finally {
    pendingResponse.resolve()
  }
  await waitForText(page, "Đã rút đề nghị")
})

test("FE-003 an expired unresolved withdrawal is never sent under a fresh key", async ({ scenario, load }) => {
  let writes = 0
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("OFFERED"))
    if (request.method() === "POST" && path === `${contractPath}/withdraw`) {
      writes++
      return errorDto(503, "SERVICE_UNAVAILABLE")
    }
  })
  await load(page, contractUrl)
  await exactButton(page, "Rút đề nghị").click()
  const dialog = page.getByRole("dialog")
  const confirm = exactButton(dialog, "Rút đề nghị")
  await confirm.click()
  await dialog.getByRole("alert").waitFor()
  assert.equal(writes, 1)
  await page.clock.fastForward(24 * 60 * 60 * 1000 + 1000)
  await confirm.click()
  await dialog.getByRole("alert")
    .filter({ hasText: "Đã hết thời gian khôi phục thao tác này" })
    .waitFor()
  assert.equal(writes, 1)
})

test("FE-003 a failed reconciliation read leaves an uncertain withdrawal unresolved", async ({ scenario, load }) => {
  let writes = 0
  let failedReads = 0
  let failReconciliation = false
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath) {
      if (!failReconciliation) return json(200, contractDto("OFFERED"))
      failedReads++
      return errorDto(503, "SERVICE_UNAVAILABLE")
    }
    if (request.method() === "POST" && path === `${contractPath}/withdraw`) {
      writes++
      return errorDto(503, "SERVICE_UNAVAILABLE")
    }
  })
  await load(page, contractUrl)
  await exactButton(page, "Rút đề nghị").click()
  const dialog = page.getByRole("dialog")
  const confirm = exactButton(dialog, "Rút đề nghị")
  await Promise.all([page.waitForResponse(isGet(contractPath)), confirm.click()])
  await dialog.getByRole("alert").filter({ hasText: /chưa xác nhận được kết quả/i }).waitFor()
  failReconciliation = true
  await Promise.all([
    page.waitForResponse((response) => isGet(contractPath)(response) && response.status() === 503),
    confirm.click(),
  ])
  await page.getByText(/chưa thể tải hợp đồng|chưa thể đối chiếu thao tác/i).first().waitFor()
  assert(failedReads > 0)
  assert.equal(writes, 1)
  assert.equal(await page.getByText("Đã rút đề nghị hợp đồng", { exact: true }).count(), 0)
})

test("FE-011 a Buyer cannot weigh as Seller, and an HTTP 403 hides milestone data", async ({ scenario, load }) => {
  let denied = false
  const page = await scenario((request, path) => {
    assert.equal(request.headers()["authorization"], "Bearer qa-BUYER")
    if (request.method() === "GET" && path === contractPath) {
      const dto = contractDto("ACTIVE")
      assert.equal(dto.buyerId, buyerId)
      return json(200, dto)
    }
    if (request.method() === "GET" && path === milestonePath)
      return denied ? errorDto(403, "FORBIDDEN") : json(200, milestoneDto())
  }, { actor: "BUYER" })
  await load(page, milestoneUrl)
  await page.getByText("Thông tin đợt", { exact: true }).waitFor()
  assert.equal(await page.getByLabel("Khối lượng cân được").count(), 0)
  assert.equal(await exactButton(page, "Xác nhận đã cân").count(), 0)
  denied = true
  await page.reload({ waitUntil: "domcontentloaded" })
  await waitForText(page, "Bạn không có quyền xem đợt giao này")
  assert.equal(await page.getByText("Thông tin đợt", { exact: true }).count(), 0)
})

test("FE-011 switching user identity does not display the previous actor's cached milestone", async ({ scenario, load }) => {
  const buyerRead = Promise.withResolvers<void>()
  const page = await scenario(async (request, path) => {
    if (request.method() !== "GET") return
    const buyer = request.headers()["authorization"] === "Bearer qa-BUYER"
    if (path === contractPath) {
      return buyer
        ? errorDto(403, "FORBIDDEN")
        : json(200, { ...contractDto("ACTIVE"), buyerId: "00000000-0000-4000-8000-000000000009" })
    }
    if (path === milestonePath) {
      if (!buyer) return json(200, milestoneDto())
      await buyerRead.promise
      return errorDto(403, "FORBIDDEN")
    }
  })
  await load(page, milestoneUrl)
  await waitForText(page, "Thông tin đợt")
  try {
    await Promise.all([
      page.waitForRequest((request) =>
        new URL(request.url()).pathname === milestonePath &&
        request.headers()["authorization"] === "Bearer qa-BUYER"
      ),
      page.evaluate(async ({ nextUser, storeModule }) => {
        localStorage.setItem("qa-actor", "BUYER")
        const { useAuthStore } = await import(storeModule)
        useAuthStore.setState({ userId: nextUser, roles: ["BUYER"] })
      }, { nextUser: buyerId, storeModule: "/src/shared/auth/auth-store.ts" }),
    ])
    assert.equal(await page.getByText("Thông tin đợt", { exact: true }).count(), 0)
    assert.equal(await page.getByLabel("Khối lượng cân được").count(), 0)
  } finally {
    buyerRead.resolve()
  }
  await waitForText(page, "Bạn không có quyền xem đợt giao này")
})

test("FE-010 counts down the server's seller correction deadline and removes the edit action", async ({ scenario, load }) => {
  const detail = milestoneDto()
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("ACTIVE"))
    if (request.method() === "GET" && path === milestonePath) {
      return json(200, {
        ...detail,
        milestone: { ...detail.milestone, status: "SELLER_WEIGHED" },
        sellerDeclaration: {
          declarationNumber: 1,
          sellerDeclaredWeight: 2000,
          sellerEvidenceFileId: fileId,
          deliveryCertificateFileId: null,
          declaredAt: new Date(now.getTime() - 3_580_000).toISOString(),
          correctionWindowClosesAt: new Date(now.getTime() + 20_000).toISOString(),
        },
      })
    }
  })
  await load(page, milestoneUrl)
  await exactButton(page, "Sửa số cân").waitFor()
  await page.clock.runFor(20_001)
  await page.getByText(/Đã hết thời gian sửa/).first().waitFor()
  assert.equal(await exactButton(page, "Sửa số cân").count(), 0)
})

test("FE-015 an explicit retry after evidence upload 503 keeps the original key and bytes", async ({ scenario, load }) => {
  const file = fakePhoto("retry.png")
  const keys: (string | undefined)[] = []
  let evidence: ReturnType<typeof evidenceDto>[] = []
  const page = await scenario((request, path) => {
    if (request.method() === "GET" && path === contractPath)
      return json(200, contractDto("ACTIVE"))
    if (request.method() === "GET" && path === milestonePath)
      return json(200, milestoneDto(evidence))
    if (request.method() === "POST" && path === `${milestonePath}/evidence`) {
      keys.push(request.headers()["idempotency-key"])
      assert.deepEqual(request.postDataBuffer(), file.buffer)
      if (keys.length === 1) return errorDto(503, "SERVICE_UNAVAILABLE")
      evidence = [evidenceDto(fileId, "READY")]
      return json(202, { fileId, status: "READY" })
    }
  })
  await load(page, milestoneUrl)
  await page.getByLabel("Khối lượng cân được").fill("2000")
  await page.getByLabel("Ảnh cân hàng").setInputFiles(file)
  const retry = page.getByRole("button", { name: /thử lại/i })
  await retry.waitFor()
  assert.equal(keys.length, 1)
  assert.equal(await exactButton(page, review).isDisabled(), true)
  await retry.click()
  await page.getByText(/Sẵn sàng/).waitFor()
  assert.equal(await exactButton(page, review).isEnabled(), true)
  assert.equal(keys.length, 2)
  assert.match(keys[0] ?? "", /^[0-9a-f-]{36}$/)
  assert.equal(keys[0], keys[1])
})
