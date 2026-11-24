import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react"
import { useAuthStore } from "@/shared/auth/auth-store"
import { newIdempotencyKey } from "./client"
import { parseApiError } from "./errors"

const RETRY_WINDOW = 24 * 60 * 60 * 1_000
const CHANGE_EVENT = "agricontract:operation-change"
const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
const activeScopes = new Set<string>()
const uncertain =
  "Chưa xác nhận được kết quả thao tác. Hãy tải lại trạng thái từ máy chủ trước khi tiếp tục."
const changed =
  "Trạng thái trên máy chủ đã thay đổi. Hãy xem lại dữ liệu trước khi thao tác."
const unresolved =
  "Chưa thể đối chiếu thao tác trước đó với dữ liệu máy chủ. Hãy kiểm tra trạng thái hoặc liên hệ bộ phận hỗ trợ."
const expired =
  "Đã hết thời gian khôi phục thao tác này. Hãy kiểm tra trạng thái hoặc liên hệ bộ phận hỗ trợ."

type Pending =
  | { key: string; startedAt: number; revision?: string }
  | { expired: true; revision?: string }

interface Attempt<T> {
  // null is reserved for commands with no variable request body.
  binding: string | null
  revision?: string
  reconcile: (pending: Pending) => Promise<"replay" | "changed" | "unresolved">
  send: (key: string) => Promise<T>
}

const definiteConflicts = new Set([
  "CONTRACT_NOT_NEGOTIABLE",
  "CONTRACT_NOT_WITHDRAWABLE",
  "CONTRACT_TERMS_FROZEN",
  "CONSENT_CONTENT_MISMATCH",
  "COUNTERPARTY_NOT_ELIGIBLE",
  "EVIDENCE_FILE_REUSED",
  "EVIDENCE_NOT_READY",
  "EVIDENCE_NOT_UPLOADED",
  "EVIDENCE_REJECTED",
  "INSUFFICIENT_LISTING_QUANTITY",
  "LISTING_VERSION_MISMATCH",
  "MILESTONE_ACTION_NOT_ALLOWED",
  "BREACH_CASE_ACTION_NOT_ALLOWED",
  "PRESENTED_CONTENT_HASH_MISMATCH",
  "WEIGH_CORRECTION_CLOSED",
  "OTP_TERMS_CHANGED",
  "OTP_CHALLENGE_NOT_ACTIVE",
  "OTP_INVALID",
  "OTP_EXPIRED",
  "OTP_ATTEMPTS_EXHAUSTED",
  "CONTRACT_NOT_SIGNABLE",
  "CONTRACT_ALREADY_SIGNED_BY_ACTOR",
])

function definitelyRejected(failure: unknown) {
  const { status, code } = parseApiError(failure)
  return (
    [400, 401, 403, 404, 413, 415, 422].includes(status) ||
    (status === 409 && definiteConflicts.has(code))
  )
}

function subscribe(notify: () => void) {
  window.addEventListener("storage", notify)
  window.addEventListener(CHANGE_EVENT, notify)
  return () => {
    window.removeEventListener("storage", notify)
    window.removeEventListener(CHANGE_EVENT, notify)
  }
}

function read(key: string): Pending | null {
  const raw = window.localStorage.getItem(key)
  if (raw === null) return null
  const record: unknown = JSON.parse(raw)
  if (typeof record === "object" && record !== null) {
    const revision = "revision" in record && typeof record.revision === "string"
      ? record.revision
      : undefined
    if ("expired" in record && record.expired === true) return { expired: true, revision }
    if (
      "key" in record && typeof record.key === "string" && UUID.test(record.key) &&
      "startedAt" in record && typeof record.startedAt === "number" &&
      Number.isFinite(record.startedAt)
    ) {
      return { key: record.key, startedAt: record.startedAt, revision }
    }
  }
  throw new Error(unresolved)
}

function store(scope: string, pending: Pending | null) {
  if (pending) window.localStorage.setItem(scope, JSON.stringify(pending))
  else window.localStorage.removeItem(scope)
  window.dispatchEvent(new Event(CHANGE_EVENT))
}

function expire(scope: string, pending: Pending | null) {
  if (
    pending && "key" in pending &&
    (Date.now() - pending.startedAt >= RETRY_WINDOW || Date.now() < pending.startedAt)
  ) {
    const marker: Pending = { expired: true, revision: pending.revision }
    store(scope, marker)
    return marker
  }
  return pending
}

function assertIdentity(userId: string | undefined) {
  const auth = useAuthStore.getState()
  if (!userId || !auth.authenticated || auth.status !== "ready" || auth.userId !== userId)
    throw new Error("Phiên đăng nhập cần được xác thực lại.")
}

/** Browser coordination only. Contract-service remains responsible for atomic replay. */
export function useRecoverableWrite(
  operation: string,
  resource: string,
  obsolete = false
) {
  const userId = useAuthStore((state) => state.userId)
  // The API's organization profile is keyed to the authenticated subject.
  // The scope contains no form values, credential material or signing data.
  const scope = JSON.stringify([
    "agricontract-operation-v1",
    import.meta.env.VITE_API_BASE_URL,
    userId,
    operation,
    resource,
  ])
  const initialBinding = useRef<{
    key: string
    binding: string
    scope: string
  } | null>(null)
  const getSnapshot = useCallback(() => {
    try {
      return userId ? window.localStorage.getItem(scope) : null
    } catch {
      return null
    }
  }, [scope, userId])
  const record = useSyncExternalStore(subscribe, getSnapshot)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!record || !navigator.locks) return undefined
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const current = read(scope)
      const cleanup = () => {
        void navigator.locks.request(scope, { ifAvailable: true }, (lock) => {
          if (!lock || window.localStorage.getItem(scope) !== record) return
          if (obsolete) {
            store(scope, null)
            if (initialBinding.current?.scope === scope) initialBinding.current = null
          } else {
            const retained = expire(scope, read(scope))
            if (retained && "expired" in retained && initialBinding.current?.scope === scope)
              initialBinding.current = null
          }
        }).catch(() => {})
      }
      if (obsolete) cleanup()
      else if (current && "key" in current) {
        const delay = Math.max(0, current.startedAt + RETRY_WINDOW - Date.now())
        timer = setTimeout(cleanup, Math.min(delay, RETRY_WINDOW))
      }
    } catch {
      // The write path handles unavailable or unreadable recovery storage.
    }
    return () => clearTimeout(timer)
  }, [obsolete, record, scope])

  async function run<T>(attempt: Attempt<T>) {
    if (activeScopes.has(scope)) throw new Error("Thao tác này đang được xử lý.")
    if (!navigator.locks)
      throw new Error("Trình duyệt chưa hỗ trợ bảo vệ thao tác giữa các thẻ. Hãy mở trang bằng trình duyệt được hỗ trợ.")
    activeScopes.add(scope)
    setBusy(true)
    try {
      return await navigator.locks.request(scope, { ifAvailable: true }, async (lock) => {
        if (!lock) throw new Error("Thao tác này đang được xử lý trong thẻ khác.")
        assertIdentity(userId)
        let existing: Pending | null
        try {
          existing = expire(scope, read(scope))
        } catch {
          throw new Error(unresolved)
        }

        if (existing) {
          const disposition = await attempt.reconcile(existing).catch(() => "unresolved")
          assertIdentity(userId)
          if (disposition === "changed") {
            store(scope, null)
            initialBinding.current = null
            throw new Error(changed)
          }
          if (!("key" in existing)) {
            initialBinding.current = null
            throw new Error(expired)
          }
          if (
            disposition !== "replay" ||
            (attempt.binding !== null &&
              (initialBinding.current?.key !== existing.key ||
                initialBinding.current.scope !== scope ||
                initialBinding.current.binding !== attempt.binding))
          ) {
            throw new Error(unresolved)
          }
        }
        const key = existing && "key" in existing ? existing.key : newIdempotencyKey()
        if (!existing) {
          try {
            store(scope, { key, startedAt: Date.now(), revision: attempt.revision })
          } catch {
            throw new Error("Không thể lưu thông tin phục hồi cho thao tác. Hãy kiểm tra cài đặt trình duyệt.")
          }
          if (attempt.binding !== null)
            initialBinding.current = { key, binding: attempt.binding, scope }
        }

        let result: T
        try {
          result = await attempt.send(key)
        } catch (failure) {
          if (definitelyRejected(failure)) {
            // An authentication or validation failure during recovery does
            // not resolve the prior attempt.
            if (!existing) {
              try { store(scope, null) } catch { /* Retain a conservative marker. */ }
              initialBinding.current = null
            }
            throw failure
          }
          throw new Error(uncertain, { cause: failure })
        }
        // The HTTP result is authoritative even if local cleanup fails.
        initialBinding.current = null
        try { store(scope, null) } catch { /* Retain a conservative marker. */ }
        return result
      })
    } finally {
      activeScopes.delete(scope)
      setBusy(false)
    }
  }

  return { run, pending: record !== null, busy }
}
