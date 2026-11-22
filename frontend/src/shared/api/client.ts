import axios from "axios"
import { keycloak, login, refreshAccessToken } from "../auth/keycloak"

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 15_000,
})

const MOCK = import.meta.env.DEV && import.meta.env.VITE_MOCK_API === "bf04"

apiClient.interceptors.request.use(async (config) => {
  // Dev-only mocked backend (src/mocks): no Keycloak, no Gateway.
  if (MOCK) return config
  // Never attach a bearer token to an arbitrary absolute URL passed by a caller.
  if (!import.meta.env.VITE_API_BASE_URL)
    throw new Error("Chưa cấu hình API Gateway.")
  const base = new URL(
    import.meta.env.VITE_API_BASE_URL,
    window.location.origin
  )
  if (config.baseURL !== import.meta.env.VITE_API_BASE_URL)
    throw new Error("Không được ghi đè API Gateway.")
  const target = new URL(config.url || "", base)
  if (target.origin !== base.origin)
    throw new Error("API chỉ được gọi qua Gateway đã cấu hình.")
  if (!keycloak.authenticated) {
    await login()
    throw new Error("Bạn cần đăng nhập.")
  }
  config.headers.set("Authorization", `Bearer ${await refreshAccessToken()}`)
  return config
})

// Success bodies are raw DTOs (D32); errors are ErrorEnvelope, read by parseApiError.
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      try {
        await login()
      } catch {
        /* Preserve the server error for the caller's UI. */
      }
    }
    // Do not replay writes. STEP_UP_REQUIRED is handled with the caller's intent.
    return Promise.reject(error)
  }
)

export const newIdempotencyKey = () => crypto.randomUUID()
