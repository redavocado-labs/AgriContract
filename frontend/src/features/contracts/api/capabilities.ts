import type { Consent, Contract, ParticipantRole } from "../types/contract"

/**
 * What the running backend serves today. The UI unlocks an action only when its
 * capability is on; an off capability renders its reason instead of a dead
 * button. Flip a flag when the backend ships the endpoint, never by guessing.
 */
export const capabilities = {
  /** POST /api/v1/contracts (#232). */
  create: { enabled: true },
  /** PATCH /{id}/terms before the first signature (#93). */
  negotiate: { enabled: true },
  /** POST /{id}/withdraw (#233). */
  withdraw: { enabled: true },
  /** POST /{id}/sign/initiate + /sign/verify. */
  sign: { enabled: true },
  /** document-preview / documents: no controller yet. */
  documents: {
    enabled: false,
    reason:
      "Tài liệu PDF chưa sẵn sàng: máy chủ chưa cung cấp bản xem trước và hợp đồng PDF đã ký.",
  },
} as const

/**
 * Active SIGN_CONTRACT statements (contract-service consent-statement/*-1.0.txt,
 * consentStatementVersion "1.0"). Shown word for word; the server rejects any
 * other version with CONSENT_STATEMENT_OUTDATED, and the page then reloads.
 */
const SIGN_STATEMENTS: Record<
  ParticipantRole,
  { version: string; text: string }
> = {
  BUYER: {
    version: "1.0",
    text: "Tôi xác nhận đã đọc toàn bộ hợp đồng có mã vân tay hiển thị bên dưới, hiểu rằng việc ký có hiệu lực ràng buộc tôi với tư cách bên mua, và đồng ý ký bằng mã OTP gửi tới email của tôi.",
  },
  SELLER: {
    version: "1.0",
    text: "Tôi xác nhận đã đọc toàn bộ hợp đồng có mã vân tay hiển thị bên dưới, hiểu rằng việc ký có hiệu lực ràng buộc tôi với tư cách bên bán, và đồng ý ký bằng mã OTP gửi tới email của tôi.",
  },
}

/** The consent bound to this contract's current hash, or null when it cannot be signed. */
export function getSigningConsent(
  contract: Pick<Contract, "id" | "hash">,
  role: ParticipantRole | null
): Consent | null {
  if (!role || !contract.hash) return null
  const statement = SIGN_STATEMENTS[role]
  return {
    action: `SIGN_CONTRACT_AS_${role}`,
    version: statement.version,
    text: statement.text,
    contractId: contract.id,
    contentHash: contract.hash,
  }
}
