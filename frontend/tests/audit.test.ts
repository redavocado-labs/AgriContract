// @vitest-environment jsdom
import { describe, expect, it } from "vitest"
import {
  auditError,
  brokenReasonMessage,
  formatAuditEventLabel,
  formatSubjectLabel,
  isUnreadableAuditRecord,
  newestFirst,
} from "@/features/audit"

describe("audit event labels and formatting", () => {
  it("maps known audit events to Vietnamese labels with content figures", () => {
    expect(
      formatAuditEventLabel({
        sourceType: "CONTRACT_SIGNED",
        sourceEventType: "contract.signed",
        content: { signedContentHash: "a".repeat(64) },
      })
    ).toBe("Hai bên ký hợp đồng")

    expect(
      formatAuditEventLabel({
        sourceType: "MILESTONE_EVENT",
        sourceEventType: "milestone.seller_weighed",
        content: { sellerDeclaredWeight: 10000, declarationNumber: 2 },
      })
    ).toBe("Bên bán cân 10.000 kg · lần cân 2")

    expect(
      formatAuditEventLabel({
        sourceType: "MILESTONE_EVENT",
        sourceEventType: "milestone.settled",
        content: { actualAmount: "948100000.00" },
      })
    ).toBe("Tất toán đợt giao — giải ngân 948.100.000 ₫")

    expect(
      formatAuditEventLabel({
        sourceType: "INSPECTION_REPORT",
        sourceEventType: "inspection.report_confirmed",
        content: { tier: "LEVEL_1_5", normalizedResult: "CONFORMING" },
      })
    ).toBe("Kết quả giám định nội bộ: Đạt chất lượng")

    expect(
      formatAuditEventLabel({
        sourceType: "EXTERNAL_INSPECTION_REPORT",
        sourceEventType: "inspection.report_confirmed",
        content: { tier: "LEVEL_2", normalizedResult: "NON_CONFORMING" },
      })
    ).toBe("Kết quả giám định độc lập: Không đạt chất lượng")

    expect(
      formatAuditEventLabel({
        sourceType: "AUDIT_VIEWED",
        sourceEventType: "audit.viewed",
        content: {
          adminUserId: "ad000000-0000-4000-8000-000000000001",
          scope: "CONTRACT",
          contractId: "11111111-2222-4333-8444-555555555555",
        },
      })
    ).toBe("ADMIN ad000000…0001 xem nhật ký hợp đồng 11111111…5555")
  })

  it("falls back to raw sourceEventType when no mapping exists", () => {
    expect(
      formatAuditEventLabel({
        sourceType: "FUTURE_EVENT",
        sourceEventType: "custom.future_event",
        content: {},
      })
    ).toBe("custom.future_event")
  })

  it("reverses records so newest appears first without mutating input", () => {
    const input = [1, 2, 3]
    expect(newestFirst(input)).toEqual([3, 2, 1])
    expect(input).toEqual([1, 2, 3])
  })

  it("maps broken chain reasons and subject labels", () => {
    expect(brokenReasonMessage("RECORD_HASH_MISMATCH")).toBe(
      "Nội dung bản ghi này đã bị sửa"
    )
    expect(brokenReasonMessage("SUBJECT_LINK_BROKEN")).toBe(
      "Mắt xích trước bản ghi này bị đứt (có bản ghi bị xoá hoặc chèn)"
    )
    expect(brokenReasonMessage("GLOBAL_LINK_BROKEN")).toBe(
      "Mắt xích toàn hệ thống bị đứt"
    )
    expect(formatSubjectLabel("SYSTEM", "SYSTEM")).toBe("Hệ thống")
    expect(formatSubjectLabel("USER_PAIR", "u1:u2")).toBe("Cặp người dùng u1:u2")
  })

  it("detects unreadable admin records with null payload fields", () => {
    expect(
      isUnreadableAuditRecord({
        sourceType: null,
        sourceEventType: null,
        content: null,
        createdAt: null,
      })
    ).toBe(true)
    expect(
      isUnreadableAuditRecord({
        sourceType: "CONTRACT_SIGNED",
        sourceEventType: "contract.signed",
        content: {},
        createdAt: "2026-11-24T10:00:00Z",
      })
    ).toBe(false)
  })

  it("maps AUDIT_TRAIL_NOT_FOUND to Vietnamese message", () => {
    expect(
      auditError({
        isAxiosError: true,
        response: { status: 404, data: { status: 404, code: "AUDIT_TRAIL_NOT_FOUND" } },
      })
    ).toBe("Hợp đồng này chưa có bản ghi bằng chứng.")
  })
})
