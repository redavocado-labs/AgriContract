import type {
  AdminAuditRecord,
  AuditChainVerification,
  AuditSubjectType,
} from "@/features/audit"
import type { AppRole } from "@/shared/auth/auth-store"
import {
  BUYER_ID,
  CONTRACT_2_ID,
  CONTRACT_ID,
  FINISHED_CONTRACT_ID,
  SELLER_ID,
} from "./bf04-fixtures"

const h = (digit: string, tag = "") => {
  const clean = tag.replace(/[^a-f0-9]/g, "")
  return (clean + digit.repeat(64)).slice(0, 64)
}

const uuid = (n: number) =>
  `a0000000-0000-4000-8000-${String(n).padStart(12, "0")}`

const t = (offsetMinutes: number) =>
  new Date(Date.UTC(2026, 10, 24, 3, 0 + offsetMinutes, 0)).toISOString()

const initialGlobalRecords: AdminAuditRecord[] = [
  {
    recordId: uuid(1),
    globalSeq: 1,
    subjectType: "SYSTEM",
    subjectId: "SYSTEM",
    sourceType: "EXTERNAL_VERIFIER_KEY_REGISTERED",
    sourceEventType: "bank.verifier_key_registered",
    sourceEventId: uuid(101),
    content: { publicKeyFingerprint: "fp-vicofa-es256-2026-01" },
    recordHash: h("1", "a101"),
    prevHashSubject: null,
    prevHashGlobal: null,
    createdAt: t(0),
  },
  {
    recordId: uuid(2),
    globalSeq: 2,
    subjectType: "CONTRACT",
    subjectId: CONTRACT_ID,
    sourceType: "CONTRACT_SIGNED",
    sourceEventType: "contract.signed",
    sourceEventId: uuid(102),
    content: {
      contractId: CONTRACT_ID,
      buyerId: BUYER_ID,
      sellerId: SELLER_ID,
      signedContentHash: h("a", "c0de"),
    },
    recordHash: h("2", "c0de"),
    prevHashSubject: null,
    prevHashGlobal: h("1", "a101"),
    createdAt: t(15),
  },
  {
    recordId: uuid(3),
    globalSeq: 3,
    subjectType: "CONTRACT",
    subjectId: CONTRACT_ID,
    sourceType: "CONTRACT_DOCUMENT_GENERATED",
    sourceEventType: "contract.document_generated",
    sourceEventId: uuid(103),
    content: {
      contractId: CONTRACT_ID,
      documentType: "FINAL_CONTRACT",
      templateCode: "VN_COFFEE_B2B",
      templateVersion: "1.0",
    },
    recordHash: h("3", "d0c1"),
    prevHashSubject: h("2", "c0de"),
    prevHashGlobal: h("2", "c0de"),
    createdAt: t(17),
  },
  {
    recordId: uuid(4),
    globalSeq: 4,
    subjectType: "USER_PAIR",
    subjectId: `${BUYER_ID}:${SELLER_ID}`,
    sourceType: "AML_RISK_CLEARED",
    sourceEventType: "reputation.elevated_risk_cleared",
    sourceEventId: uuid(104),
    content: {
      buyerId: BUYER_ID,
      sellerId: SELLER_ID,
      reason: "Đã đối soát hợp đồng xuất khẩu và hoá đơn hợp lệ",
    },
    recordHash: h("4", "a310"),
    prevHashSubject: null,
    prevHashGlobal: h("3", "d0c1"),
    createdAt: t(30),
  },
  {
    recordId: uuid(5),
    globalSeq: 5,
    subjectType: "CONTRACT",
    subjectId: CONTRACT_ID,
    sourceType: "MILESTONE_EVENT",
    sourceEventType: "milestone.seller_weighed",
    sourceEventId: uuid(105),
    content: {
      contractId: CONTRACT_ID,
      milestoneId: uuid(501),
      declarationNumber: 1,
      sellerDeclaredWeight: 10000,
    },
    recordHash: h("5", "b501"),
    prevHashSubject: h("3", "d0c1"),
    prevHashGlobal: h("4", "a310"),
    createdAt: t(60),
  },
  {
    recordId: uuid(6),
    globalSeq: 6,
    subjectType: "CONTRACT",
    subjectId: CONTRACT_ID,
    sourceType: "MILESTONE_EVENT",
    sourceEventType: "milestone.buyer_received",
    sourceEventId: uuid(106),
    content: {
      contractId: CONTRACT_ID,
      milestoneId: uuid(501),
      buyerReceivedWeight: 9980,
    },
    recordHash: h("6", "b602"),
    prevHashSubject: h("5", "b501"),
    prevHashGlobal: h("5", "b501"),
    createdAt: t(90),
  },
  {
    recordId: uuid(7),
    globalSeq: 7,
    subjectType: "CONTRACT",
    subjectId: CONTRACT_ID,
    sourceType: "MILESTONE_EVENT",
    sourceEventType: "milestone.settled",
    sourceEventId: uuid(107),
    content: {
      contractId: CONTRACT_ID,
      milestoneId: uuid(501),
      actualAmount: "948100000.00",
    },
    recordHash: h("7", "7f3a"),
    prevHashSubject: h("6", "b602"),
    prevHashGlobal: h("6", "b602"),
    createdAt: t(120),
  },
  {
    recordId: uuid(8),
    globalSeq: 8,
    subjectType: "CONTRACT",
    subjectId: CONTRACT_2_ID,
    sourceType: "CONTRACT_SIGNED",
    sourceEventType: "contract.signed",
    sourceEventId: uuid(108),
    content: {
      contractId: CONTRACT_2_ID,
      buyerId: BUYER_ID,
      sellerId: SELLER_ID,
      signedContentHash: h("8", "c200"),
    },
    recordHash: h("8", "c200"),
    prevHashSubject: null,
    prevHashGlobal: h("7", "7f3a"),
    createdAt: t(130),
  },
  {
    recordId: uuid(9),
    globalSeq: 9,
    subjectType: "CONTRACT",
    subjectId: CONTRACT_2_ID,
    sourceType: "MILESTONE_EVENT",
    sourceEventType: "milestone.seller_weighed",
    sourceEventId: uuid(109),
    content: {
      contractId: CONTRACT_2_ID,
      milestoneId: uuid(502),
      declarationNumber: 1,
      sellerDeclaredWeight: 8500,
    },
    recordHash: h("9", "dead"),
    prevHashSubject: h("8", "c200"),
    prevHashGlobal: h("8", "c200"),
    createdAt: t(140),
  },
  {
    recordId: uuid(10),
    globalSeq: 10,
    subjectType: "CONTRACT",
    subjectId: FINISHED_CONTRACT_ID,
    sourceType: "CONTRACT_SETTLED",
    sourceEventType: "contract.settled",
    sourceEventId: uuid(110),
    content: {
      contractId: FINISHED_CONTRACT_ID,
      buyerId: BUYER_ID,
      sellerId: SELLER_ID,
    },
    recordHash: h("a", "f999"),
    prevHashSubject: null,
    prevHashGlobal: h("9", "dead"),
    createdAt: t(160),
  },
]

const store = [...initialGlobalRecords]

function appendAdminView(
  adminUserId: string,
  scope: "CONTRACT" | "GLOBAL",
  contractId?: string,
  subjectTypeFilter?: string | null,
  page = 0
) {
  const prev = store[store.length - 1]
  const nextSeq = (prev?.globalSeq ?? 0) + 1
  store.push({
    recordId: uuid(900 + nextSeq),
    globalSeq: nextSeq,
    subjectType: "SYSTEM",
    subjectId: "SYSTEM",
    sourceType: "AUDIT_VIEWED",
    sourceEventType: "audit.viewed",
    sourceEventId: uuid(1000 + nextSeq),
    content: {
      adminUserId,
      scope,
      ...(contractId ? { contractId } : {}),
      ...(scope === "GLOBAL"
        ? { subjectTypeFilter: subjectTypeFilter ?? null, page: String(page) }
        : {}),
    },
    recordHash: h("e", `e${nextSeq}`),
    prevHashSubject:
      [...store].reverse().find((r) => r.subjectType === "SYSTEM")?.recordHash ??
      null,
    prevHashGlobal: prev?.recordHash ?? null,
    createdAt: new Date().toISOString(),
  })
}

export function handleAuditMock(
  method: string,
  path: string,
  viewer: AppRole,
  viewerId: string,
  mode: string,
  params?: Record<string, unknown>
): { status: number; data?: unknown } | null {
  if (method !== "get") return null

  const partyMatch = path.match(
    /^\/api\/v1\/audit\/contracts\/([^/]+)\/(records|verification)$/
  )
  if (partyMatch) {
    if (viewer !== "BUYER" && viewer !== "SELLER") {
      return { status: 403, data: { status: 403, code: "FORBIDDEN", path } }
    }
    const contractId = decodeURIComponent(partyMatch[1]!)
    const kind = partyMatch[2]!
    const rows = store.filter(
      (r) => r.subjectType === "CONTRACT" && r.subjectId === contractId
    )
    if (!rows.length) {
      return {
        status: 404,
        data: { status: 404, code: "AUDIT_TRAIL_NOT_FOUND", path },
      }
    }
    const newest = rows[rows.length - 1]!
    if (kind === "records") {
      return {
        status: 200,
        data: {
          contractId,
          currentRecordHash: newest.recordHash,
          anchorStatus: "NOT_ANCHORED",
          records: rows.map((r) => ({
            recordId: r.recordId,
            sourceType: r.sourceType ?? "",
            sourceEventType: r.sourceEventType ?? "",
            content: r.content ?? {},
            recordHash: r.recordHash,
            createdAt: r.createdAt ?? new Date().toISOString(),
          })),
        },
      }
    }
    const broken = mode === "broken" || contractId === CONTRACT_2_ID
    const verification: AuditChainVerification = broken
      ? {
          scope: "CONTRACT",
          contractId,
          status: "BROKEN",
          checkedRecords: rows.length,
          brokenRecordId: rows[Math.min(1, rows.length - 1)]!.recordId,
          brokenReason: "RECORD_HASH_MISMATCH",
          checkedAt: new Date().toISOString(),
          anchorStatus: "NOT_ANCHORED",
        }
      : {
          scope: "CONTRACT",
          contractId,
          status: "INTACT",
          checkedRecords: rows.length,
          brokenRecordId: null,
          brokenReason: null,
          checkedAt: new Date().toISOString(),
          anchorStatus: "NOT_ANCHORED",
        }
    return { status: 200, data: verification }
  }

  const adminContractMatch = path.match(
    /^\/api\/v1\/admin\/audit\/contracts\/([^/]+)\/(records|verification)$/
  )
  if (adminContractMatch) {
    if (viewer !== "ADMIN") {
      return { status: 403, data: { status: 403, code: "FORBIDDEN", path } }
    }
    const contractId = decodeURIComponent(adminContractMatch[1]!)
    const kind = adminContractMatch[2]!
    const rows = store.filter(
      (r) => r.subjectType === "CONTRACT" && r.subjectId === contractId
    )
    if (!rows.length) {
      return {
        status: 404,
        data: { status: 404, code: "AUDIT_TRAIL_NOT_FOUND", path },
      }
    }
    const newest = rows[rows.length - 1]!
    if (kind === "records") {
      appendAdminView(viewerId, "CONTRACT", contractId)
      return {
        status: 200,
        data: {
          contractId,
          currentRecordHash: newest.recordHash,
          anchorStatus: "NOT_ANCHORED",
          records: rows,
        },
      }
    }
    const broken = mode === "broken" || contractId === CONTRACT_2_ID
    return {
      status: 200,
      data: broken
        ? {
            scope: "CONTRACT",
            contractId,
            status: "BROKEN",
            checkedRecords: rows.length,
            brokenRecordId: rows[Math.min(1, rows.length - 1)]!.recordId,
            brokenReason: "SUBJECT_LINK_BROKEN",
            checkedAt: new Date().toISOString(),
            anchorStatus: "NOT_ANCHORED",
          }
        : {
            scope: "CONTRACT",
            contractId,
            status: "INTACT",
            checkedRecords: rows.length,
            brokenRecordId: null,
            brokenReason: null,
            checkedAt: new Date().toISOString(),
            anchorStatus: "NOT_ANCHORED",
          },
    }
  }

  if (path === "/api/v1/admin/audit/records") {
    if (viewer !== "ADMIN") {
      return { status: 403, data: { status: 403, code: "FORBIDDEN", path } }
    }
    const subjectType =
      typeof params?.subjectType === "string" && params.subjectType
        ? (params.subjectType as AuditSubjectType)
        : null
    const page = Number(params?.page ?? 0)
    const size = Number(params?.size ?? 20)
    appendAdminView(viewerId, "GLOBAL", undefined, subjectType, page)
    const filtered = [...store]
      .reverse()
      .filter((r) => !subjectType || r.subjectType === subjectType)
    const content = filtered.slice(page * size, (page + 1) * size)
    if (mode === "broken" && page === 0 && !subjectType) {
      content.splice(1, 0, {
        recordId: uuid(998),
        globalSeq: 99,
        subjectType: null,
        subjectId: null,
        sourceType: null,
        sourceEventType: null,
        sourceEventId: null,
        content: null,
        recordHash: h("f", "bad0"),
        prevHashSubject: null,
        prevHashGlobal: h("e", "bad1"),
        createdAt: null,
      })
    }
    return {
      status: 200,
      data: {
        content,
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / size),
      },
    }
  }

  if (path === "/api/v1/admin/audit/verification") {
    if (viewer !== "ADMIN") {
      return { status: 403, data: { status: 403, code: "FORBIDDEN", path } }
    }
    const broken = mode === "broken"
    return {
      status: 200,
      data: broken
        ? {
            scope: "GLOBAL",
            contractId: null,
            status: "BROKEN",
            checkedRecords: store.length,
            brokenRecordId: uuid(9),
            brokenReason: "GLOBAL_LINK_BROKEN",
            checkedAt: new Date().toISOString(),
            anchorStatus: "NOT_ANCHORED",
          }
        : {
            scope: "GLOBAL",
            contractId: null,
            status: "INTACT",
            checkedRecords: store.length,
            brokenRecordId: null,
            brokenReason: null,
            checkedAt: new Date().toISOString(),
            anchorStatus: "NOT_ANCHORED",
          },
    }
  }

  return null
}
