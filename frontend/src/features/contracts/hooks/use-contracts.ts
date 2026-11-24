import { useEffect, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { useAuthStore } from "@/shared/auth/auth-store"
import { getContract, listContracts } from "../api/contracts"
import { pollInterval } from "../types/presentation"
import type { ParticipantRole } from "../types/contract"

export function useContracts(
  role: ParticipantRole,
  status: string,
  page: number
) {
  const { authenticated, status: authStatus, userId, roles } = useAuthStore()
  return useQuery({
    queryKey: ["contracts", userId, role, status, page],
    queryFn: ({ signal }) => listContracts(userId!, role, status, page, signal),
    enabled:
      authenticated &&
      authStatus === "ready" &&
      !!userId &&
      roles.includes(role),
    retry: false,
  })
}
export function useContract(id: string) {
  const { authenticated, status, userId } = useAuthStore()
  const scope = authenticated && status === "ready" && userId && id
    ? JSON.stringify([userId, id])
    : null
  const [activation, setActivation] = useState<{
    scope: string
    startedAt: number
    expired: boolean
  } | null>(null)
  const budget = scope && activation?.scope === scope ? activation : null
  const query = useQuery({
    queryKey: ["contract", userId, id],
    queryFn: ({ signal }) => getContract(id, userId!, signal),
    enabled: !!scope,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: (query) => query.state.data?.status !== "SIGNED",
    refetchIntervalInBackground: false,
    refetchInterval: (query) => {
      if (!budget || budget.expired) return false
      const now = Date.now()
      const interval = pollInterval(
        query.state.data?.status,
        budget.startedAt,
        now,
        !!query.state.error
      )
      return interval && now + interval < budget.startedAt + 120_000
        ? interval
        : false
    },
  })
  const awaitingActivation = !!scope && query.data?.status === "SIGNED"
  useEffect(() => {
    if (!scope || !awaitingActivation) {
      setActivation(null)
      return
    }
    const startedAt = Date.now()
    setActivation({ scope, startedAt, expired: false })
    const timeout = setTimeout(() => {
      setActivation({ scope, startedAt, expired: true })
    }, 120_000)
    return () => clearTimeout(timeout)
  }, [scope, awaitingActivation])

  const activationCheck = !awaitingActivation
    ? null
    : budget?.expired
      ? "timed-out"
      : query.isError
        ? "interrupted"
        : "pending"
  return { ...query, activationCheck }
}
