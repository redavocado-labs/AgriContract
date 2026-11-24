import { useEffect, useRef, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { newIdempotencyKey } from "@/shared/api/client"
import { parseApiError } from "@/shared/api/errors"
import { useAuthStore } from "@/shared/auth/auth-store"
import type { FileUploadItem } from "@/shared/ui/file-upload"
import { contractError } from "@/features/contracts/types/presentation"
import {
  getBreachCase,
  getMilestoneDetail,
  uploadEvidence,
  type MilestoneDetail,
} from "../api/milestones"
import { sha256Hex, consentContent } from "../lib/milestone-consent"

export const milestoneKey = (
  userId: string | undefined,
  contractId: string,
  milestoneId: string
) => ["milestone", userId, contractId, milestoneId] as const

const SCAN_POLL_MILLISECONDS = 2_000
const SCAN_BUDGET_MILLISECONDS = 60_000

/** Poll at most one minute per batch of processing files. */
export function useMilestoneDetail(contractId: string, milestoneId: string) {
  const { authenticated, status, userId } = useAuthStore()
  const firstSeen = useRef(new Map<string, number>())
  return useQuery({
    queryKey: milestoneKey(userId, contractId, milestoneId),
    queryFn: ({ signal }) =>
      getMilestoneDetail(contractId, milestoneId, signal),
    enabled:
      authenticated && status === "ready" && !!contractId && !!milestoneId,
    retry: false,
    refetchOnWindowFocus: true,
    refetchInterval: (query) => {
      if (query.state.error) return false
      const now = Date.now()
      const files =
        query.state.data?.evidence.filter(
          (file) => file.status === "PROCESSING"
        ) ?? []
      for (const file of files)
        if (!firstSeen.current.has(file.fileId))
          firstSeen.current.set(file.fileId, now)
      return files.some(
        (file) =>
          now - firstSeen.current.get(file.fileId)! < SCAN_BUDGET_MILLISECONDS
      )
        ? SCAN_POLL_MILLISECONDS
        : false
    },
  })
}

export function useMilestoneBreachCase(detail: MilestoneDetail) {
  const userId = useAuthStore((state) => state.userId)
  return useQuery({
    queryKey: ["breach-case", detail.contractId, detail.breachCaseId, userId],
    queryFn: ({ signal }) =>
      getBreachCase(detail.contractId, detail.breachCaseId!, signal),
    enabled: !!userId && !!detail.breachCaseId,
    retry: false,
  })
}

/**
 * One logical write with its Idempotency-Key: kept for a manual retry after a
 * network/5xx failure, dropped after success or a definite 4xx rejection.
 */
export function useMilestoneCommand(detail: MilestoneDetail) {
  const userId = useAuthStore((state) => state.userId)
  const cache = useQueryClient()
  const lock = useRef(false)
  const key = useRef<{ fingerprint: string; key: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState("")
  async function run(
    fingerprint: string,
    action: (key: string) => Promise<void>,
    refresh = true
  ) {
    if (lock.current) return false
    lock.current = true
    if (key.current?.fingerprint !== fingerprint)
      key.current = { fingerprint, key: newIdempotencyKey() }
    setBusy(true)
    setError("")
    try {
      await action(key.current.key)
      key.current = null
      return true
    } catch (failure) {
      setError(contractError(failure))
      const { status } = parseApiError(failure)
      if (status >= 400 && status < 500) key.current = null
      return false
    } finally {
      if (refresh)
        await Promise.all([
          cache.invalidateQueries({
            queryKey: milestoneKey(
              userId,
              detail.contractId,
              detail.milestone.milestoneId
            ),
          }),
          cache.invalidateQueries({
            queryKey: ["contract", userId, detail.contractId],
          }),
          cache.invalidateQueries({
            queryKey: ["breach-case", detail.contractId],
          }),
          cache.invalidateQueries({ queryKey: ["contracts", userId] }),
        ])
      lock.current = false
      setBusy(false)
    }
  }
  return { run, busy, error, setError }
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
  const [local, setLocal] = useState<{
    name: string
    size: number
    fileId?: string
    file: File
    key: string
    error?: string
  } | null>(null)
  const [uploading, setUploading] = useState(false)
  const fileId = local ? local.fileId : (initialFileId ?? undefined)
  const server = detail.evidence.find((file) => file.fileId === fileId)
  async function upload(file: File, key: string) {
    setUploading(true)
    setLocal({ name: file.name, size: file.size, file, key })
    try {
      const accepted = await uploadEvidence(
        detail.contractId,
        detail.milestone.milestoneId,
        file,
        key
      )
      setLocal({
        name: file.name,
        size: file.size,
        file,
        key,
        fileId: accepted.fileId,
      })
      await cache.invalidateQueries({
        queryKey: milestoneKey(
          userId,
          detail.contractId,
          detail.milestone.milestoneId
        ),
      })
    } catch (failure) {
      setLocal({
        name: file.name,
        size: file.size,
        file,
        key,
        error: contractError(failure),
      })
    } finally {
      setUploading(false)
    }
  }
  const status = uploading
    ? "processing"
    : local?.error
      ? "failed"
      : server?.status === "READY"
        ? "ready"
        : server?.status === "FAILED"
          ? "failed"
          : fileId
            ? "processing"
            : null
  const item: FileUploadItem | null = status
    ? {
        key: fileId ?? local?.key,
        name: local?.name ?? "Ảnh đã gửi trước đó",
        sizeLabel: local ? sizeLabel(local.size) : undefined,
        status,
        failureReason:
          local?.error ??
          (server?.status === "FAILED"
            ? "ảnh không qua kiểm tra an toàn, hãy chọn ảnh khác"
            : undefined),
        onRetry: local?.error
          ? () => void upload(local.file, local.key)
          : undefined,
      }
    : null
  return {
    item,
    fileId: status === "ready" ? fileId : undefined,
    pending: status === "processing",
    select: (file: File) => void upload(file, newIdempotencyKey()),
    clear: () => setLocal(null),
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
