import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { newIdempotencyKey } from "@/shared/api/client"
import { parseApiError } from "@/shared/api/errors"
import { useRecoverableWrite } from "@/shared/api/operation-recovery"
import { useAuthStore } from "@/shared/auth/auth-store"
import type { FileUploadItem } from "@/shared/ui/file-upload"
import { contractError } from "@/features/contracts"
import {
  getBreachCase,
  getMilestoneDetail,
  uploadEvidence,
  type MilestoneDetail,
} from "../api/milestones"
import { canReadMilestoneCase } from "../lib/presentation"
import { sha256Hex, consentContent } from "../lib/milestone-consent"

export const milestoneKey = (
  userId: string | undefined,
  contractId: string,
  milestoneId: string
) => ["milestone", userId, contractId, milestoneId] as const

const SCAN_BUDGET = 60_000
const SCAN_INTERVAL = 2_000

interface EvidenceScanChecks {
  track: (fileId: string) => () => void
  timedOut: (fileId: string) => boolean
  recheck: () => void
  checking: boolean
}

export const EvidenceScanContext = createContext<EvidenceScanChecks | null>(null)

/** Detail read model, with a separate budget for each observed or just-uploaded file. */
export function useMilestoneDetail(contractId: string, milestoneId: string) {
  const { authenticated, status, userId } = useAuthStore()
  const scope = authenticated && status === "ready" && userId && contractId && milestoneId
    ? JSON.stringify([userId, contractId, milestoneId])
    : null
  const [tracked, setTracked] = useState<{
    scope: string
    files: Map<string, number>
  } | null>(null)
  const [budget, setBudget] = useState<{
    scope: string
    deadlines: Map<string, number>
  } | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const deadlines = scope && budget?.scope === scope ? budget.deadlines : undefined
  const trackedFiles = scope && tracked?.scope === scope ? tracked.files : undefined
  const hasScanBudgetAfter = (time: number) =>
    deadlines && Array.from(deadlines.values()).some((deadline) => deadline > time)
  const canRefreshMilestone = () => !deadlines?.size || !!hasScanBudgetAfter(Date.now())
  const query = useQuery({
    queryKey: milestoneKey(userId, contractId, milestoneId),
    queryFn: ({ signal }) =>
      getMilestoneDetail(contractId, milestoneId, signal),
    enabled: !!scope,
    retry: false,
    refetchOnWindowFocus: canRefreshMilestone,
    refetchOnReconnect: canRefreshMilestone,
    refetchInterval: (query) => {
      if ([401, 403, 404].includes(parseApiError(query.state.error).status))
        return false
      return hasScanBudgetAfter(Date.now() + SCAN_INTERVAL)
        ? SCAN_INTERVAL
        : false
    },
  })
  const evidence = query.data?.evidence
  useEffect(() => {
    if (!scope) {
      setBudget(null)
      setElapsed(0)
      return
    }
    const waiting = new Set(trackedFiles?.keys())
    for (const file of evidence ?? []) {
      if (file.status === "PROCESSING") waiting.add(file.fileId)
      else waiting.delete(file.fileId)
    }
    const newDeadline = Date.now() + SCAN_BUDGET
    setBudget((previous) => ({
      scope,
      deadlines: new Map(
        Array.from(waiting, (id) => [
          id,
          (previous?.scope === scope ? previous.deadlines.get(id) : undefined) ??
            newDeadline,
        ])
      ),
    }))
  }, [scope, evidence, trackedFiles])
  const nextDeadline = deadlines && Math.min(
    ...Array.from(deadlines.values()).filter((deadline) => deadline > elapsed)
  )
  useEffect(() => {
    if (nextDeadline === undefined || !Number.isFinite(nextDeadline)) return
    const timeout = setTimeout(
      () => setElapsed(nextDeadline),
      Math.max(0, nextDeadline - Date.now())
    )
    return () => clearTimeout(timeout)
  }, [nextDeadline])
  const track = useCallback((fileId: string) => {
    if (!scope) return () => {}
    setTracked((previous) => {
      const files = new Map(previous?.scope === scope ? previous.files : undefined)
      files.set(fileId, (files.get(fileId) ?? 0) + 1)
      return { scope, files }
    })
    return () => setTracked((previous) => {
      if (previous?.scope !== scope) return previous
      const files = new Map(previous.files)
      const count = files.get(fileId) ?? 0
      if (count <= 1) files.delete(fileId)
      else files.set(fileId, count - 1)
      return { scope, files }
    })
  }, [scope])
  const evidenceScan: EvidenceScanChecks = {
    track,
    timedOut: (id) => (deadlines?.get(id) ?? Infinity) <= elapsed,
    recheck: () => void query.refetch({ cancelRefetch: false }),
    checking: query.isFetching,
  }
  return { ...query, evidenceScan }
}

function milestoneRevision(detail: MilestoneDetail) {
  return [
    detail.milestone.status,
    detail.milestone.fundingStatus,
    detail.sellerDeclaration?.declarationNumber,
  ].join(":")
}

export function useMilestoneCommand(
  detail: MilestoneDetail,
  action:
    | "seller-weigh"
    | "buyer-receipt"
    | "buyer-decision"
    | "seller-response"
    | "funding"
    | "breach-contest"
    | "dispute-exit"
    | "dispute-otp"
) {
  const userId = useAuthStore((state) => state.userId)
  const cache = useQueryClient()
  const { contractId, milestone: { milestoneId } } = detail
  const operation = useRecoverableWrite(action, JSON.stringify([contractId, milestoneId]))
  const queryKey = milestoneKey(userId, contractId, milestoneId)
  const [error, setError] = useState("")
  async function run(
    fingerprint: string | null,
    action: (key: string) => Promise<void>
  ) {
    if (!userId) return false
    setError("")
    try {
      await operation.run({
        binding: fingerprint,
        revision: milestoneRevision(detail),
        reconcile: async ({ revision }) => {
          const current = await cache.fetchQuery({
            queryKey,
            queryFn: ({ signal }) => getMilestoneDetail(contractId, milestoneId, signal),
            retry: false,
            staleTime: 0,
          })
          return revision === milestoneRevision(current) ? "replay" : "changed"
        },
        send: action,
      })
      return useAuthStore.getState().userId === userId
    } catch (failure) {
      setError(contractError(failure))
      return false
    } finally {
      void Promise.allSettled([
        cache.invalidateQueries({ queryKey }),
        cache.invalidateQueries({ queryKey: ["contract", userId, contractId] }),
        cache.invalidateQueries({ queryKey: ["breach-case", contractId] }),
        cache.invalidateQueries({ queryKey: ["contracts", userId] }),
      ])
    }
  }
  return {
    run,
    busy: operation.busy,
    error: error || (operation.pending && !operation.busy
      ? "Kết quả thao tác trước chưa được xác nhận. Hệ thống sẽ kiểm tra trạng thái đợt giao trước khi tiếp tục."
      : ""),
    setError,
  }
}

const sizeLabel = (bytes: number) =>
  bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toLocaleString("vi-VN", { maximumFractionDigits: 1 })} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`

/**
 * One evidence slot: uploads the picked file, then reads its scan status from
 * the detail's evidence[] (the server is the only source of READY/FAILED).
 */
export function useEvidenceSlot(
  detail: MilestoneDetail,
  initialFileId?: string | null
) {
  const userId = useAuthStore((state) => state.userId)
  const cache = useQueryClient()
  const scan = useContext(EvidenceScanContext)
  const [local, setLocal] = useState<
    | { file: File; key: string; fileId?: string; error?: string; retryable?: boolean }
    | "removed"
    | null
  >(null)
  const [uploading, setUploading] = useState(false)
  const uploadLock = useRef(false)
  const selected = local === "removed" ? null : local
  const fileId = local ? selected?.fileId : (initialFileId ?? undefined)
  const server = detail.evidence.find((file) => file.fileId === fileId)
  const track = scan?.track
  useEffect(() => {
    return fileId && track ? track(fileId) : undefined
  }, [fileId, track])

  const queryKey = milestoneKey(userId, detail.contractId, detail.milestone.milestoneId)
  async function upload(file: File, key: string, retry = false) {
    if (uploadLock.current) return
    uploadLock.current = true
    setUploading(true)
    setLocal({ file, key })
    try {
      if (retry) {
        const current = await cache.fetchQuery({
          queryKey,
          queryFn: ({ signal }) =>
            getMilestoneDetail(detail.contractId, detail.milestone.milestoneId, signal),
          retry: false,
          staleTime: 0,
        })
        if (current.milestone.status !== detail.milestone.status) {
          setLocal({
            file,
            key,
            error: "Đợt giao đã chuyển sang bước khác. Hãy kiểm tra trạng thái mới.",
          })
          return
        }
      }
      const auth = useAuthStore.getState()
      if (!userId || auth.userId !== userId || !auth.authenticated || auth.status !== "ready")
        throw new Error("Phiên đăng nhập cần được xác thực lại.")
      const accepted = await uploadEvidence(
        detail.contractId,
        detail.milestone.milestoneId,
        file,
        key
      )
      setLocal({ file, key, fileId: accepted.fileId })
      void cache.invalidateQueries({ queryKey }).catch(() => {})
    } catch (failure) {
      const { status, code } = parseApiError(failure)
      setLocal({
        file,
        key,
        error: contractError(failure),
        retryable:
          code !== "IDEMPOTENCY_KEY_REUSED" &&
          (!status || status === 408 || status === 429 || status >= 500),
      })
    } finally {
      uploadLock.current = false
      setUploading(false)
    }
  }
  let status: FileUploadItem["status"] | null = null
  if (uploading) status = "uploading"
  else if (selected?.error) status = "upload-failed"
  else if (server?.status === "READY") status = "ready"
  else if (server?.status === "FAILED") status = "rejected"
  else if (fileId) status = scan?.timedOut(fileId) ? "scan-timeout" : "processing"

  const item: FileUploadItem | null = status
    ? {
        key: fileId ?? selected?.key,
        name: selected?.file.name ?? "Ảnh đã gửi trước đó",
        sizeLabel: selected ? sizeLabel(selected.file.size) : undefined,
        status,
        failureReason:
          selected?.error ??
          (server?.status === "FAILED"
            ? "ảnh không qua kiểm tra an toàn, hãy chọn ảnh khác"
            : undefined),
        onRetry: selected?.retryable
          ? () => void upload(selected.file, selected.key, true)
          : undefined,
        onRecheck: status === "scan-timeout" ? scan?.recheck : undefined,
        checking: scan?.checking,
      }
    : null
  return {
    item,
    fileId: status === "ready" ? fileId : undefined,
    uploading,
    pending: status === "uploading" || status === "processing",
    select: (file: File) => void upload(file, newIdempotencyKey()),
    clear: () => {
      if (!uploadLock.current) setLocal("removed")
    },
  }
}

/** presentedContentHash of the content the viewer is looking at; null until computable. */
export function useConsentHash(
  parts: Parameters<typeof consentContent>[0] | null
) {
  const content = (() => {
    try {
      return parts ? consentContent(parts) : null
    } catch {
      return null
    }
  })()
  const [hash, setHash] = useState<{ content: string; hash: string } | null>(
    null
  )
  useEffect(() => {
    if (!content) return
    let live = true
    void sha256Hex(content).then(
      (value) => live && setHash({ content, hash: value })
    )
    return () => {
      live = false
    }
  }, [content])
  return content && hash?.content === content ? hash.hash : null
}

export function useMilestoneBreachCase(detail: MilestoneDetail) {
  const { userId, roles } = useAuthStore()
  return useQuery({
    queryKey: ["breach-case", detail.contractId, detail.breachCaseId, userId],
    queryFn: ({ signal }) =>
      getBreachCase(detail.contractId, detail.breachCaseId!, signal),
    enabled: !!userId && !!detail.breachCaseId && canReadMilestoneCase(roles),
    retry: false,
  })
}
