import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useAuthStore } from "@/shared/auth/auth-store"
import {
  confirmEmailVerification,
  getOrganizationProfile,
  requestEmailVerification,
  registerOrganization,
  type OrganizationFormValues,
} from "../api/profile"

export const organizationProfileKey = (userId?: string) =>
  ["my-profile", userId] as const

export function useOrganizationProfile() {
  const { userId, authenticated, status } = useAuthStore()
  return useQuery({
    queryKey: organizationProfileKey(userId),
    queryFn: ({ signal }) => getOrganizationProfile(userId!, signal),
    enabled: status === "ready" && authenticated && Boolean(userId),
    retry: false,
  })
}

export function useRegisterOrganization() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (values: OrganizationFormValues) => {
      const { userId, roles, authenticated, status } = useAuthStore.getState()
      if (!authenticated || status !== "ready" || !userId)
        throw new Error("Bạn cần đăng nhập lại trước khi gửi hồ sơ.")
      return registerOrganization(values, userId, roles)
    },
    onSuccess: async ({ profile }) => {
      if (useAuthStore.getState().userId !== profile.userId) return
      // Only cache the actual server response; then re-read authoritative GET /me.
      client.setQueryData(organizationProfileKey(profile.userId), profile)
      await client.invalidateQueries({
        queryKey: organizationProfileKey(profile.userId),
        exact: true,
      })
    },
    retry: false,
  })
}

export function useRequestEmailVerification() {
  return useMutation({ mutationFn: requestEmailVerification, retry: false })
}

export function useConfirmEmailVerification() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => {
      const { userId } = useAuthStore.getState()
      if (!userId) throw new Error("Bạn cần đăng nhập lại.")
      return confirmEmailVerification(code, userId)
    },
    onSuccess: (profile) => {
      client.setQueryData(organizationProfileKey(profile.userId), profile)
    },
    retry: false,
  })
}
