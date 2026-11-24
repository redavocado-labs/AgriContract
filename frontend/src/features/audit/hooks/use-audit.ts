import { useMutation, useQuery } from "@tanstack/react-query"
import { useAuthStore } from "@/shared/auth/auth-store"
import {
  getAdminContractAuditTrail,
  getPartyContractAuditTrail,
  listAdminAuditRecords,
  verifyAdminContractAuditTrail,
  verifyGlobalAuditChain,
  verifyPartyContractAuditTrail,
  type AuditSubjectType,
} from "../api/audit"

export function usePartyContractAuditTrail(contractId: string, enabled: boolean) {
  const { authenticated, status, userId } = useAuthStore()
  return useQuery({
    queryKey: ["audit-party-trail", userId, contractId],
    queryFn: ({ signal }) => getPartyContractAuditTrail(contractId, signal),
    enabled: authenticated && status === "ready" && Boolean(userId) && Boolean(contractId) && enabled,
    retry: false,
  })
}

export function usePartyContractVerification(contractId: string, enabled: boolean) {
  const { authenticated, status, userId } = useAuthStore()
  return useQuery({
    queryKey: ["audit-party-verify", userId, contractId],
    queryFn: ({ signal }) => verifyPartyContractAuditTrail(contractId, signal),
    enabled: authenticated && status === "ready" && Boolean(userId) && Boolean(contractId) && enabled,
    retry: false,
  })
}

export function useAdminContractAuditTrail(contractId: string) {
  const { authenticated, status, userId, roles } = useAuthStore()
  return useQuery({
    queryKey: ["audit-admin-trail", userId, contractId],
    queryFn: ({ signal }) => getAdminContractAuditTrail(contractId, signal),
    enabled:
      authenticated &&
      status === "ready" &&
      Boolean(userId) &&
      roles.includes("ADMIN") &&
      Boolean(contractId.trim()),
    retry: false,
  })
}

export function useAdminContractVerification(contractId: string, enabled: boolean) {
  const { authenticated, status, userId, roles } = useAuthStore()
  return useQuery({
    queryKey: ["audit-admin-verify", userId, contractId],
    queryFn: ({ signal }) => verifyAdminContractAuditTrail(contractId, signal),
    enabled:
      authenticated &&
      status === "ready" &&
      Boolean(userId) &&
      roles.includes("ADMIN") &&
      Boolean(contractId.trim()) &&
      enabled,
    retry: false,
  })
}

export function useAdminGlobalAuditRecords(
  subjectType: AuditSubjectType | "",
  page: number,
  size = 20
) {
  const { authenticated, status, userId, roles } = useAuthStore()
  return useQuery({
    queryKey: ["audit-admin-global", userId, subjectType, page, size],
    queryFn: ({ signal }) =>
      listAdminAuditRecords(
        {
          subjectType: subjectType || undefined,
          page,
          size,
        },
        signal
      ),
    enabled:
      authenticated &&
      status === "ready" &&
      Boolean(userId) &&
      roles.includes("ADMIN"),
    retry: false,
  })
}

export function useVerifyGlobalAuditChain() {
  return useMutation({
    mutationFn: () => verifyGlobalAuditChain(),
  })
}
