import axios from "axios"

/**
 * ErrorEnvelope of golden-flow-api.yaml: every service answers errors as
 * {timestamp, status, error, code, message, path, traceId}. Success bodies are
 * the raw DTO, with no wrapper (D32).
 */
export interface ErrorEnvelope {
  timestamp: string
  status: number
  error: string
  code: string
  message: string
  path: string
  traceId: string
}

export interface ApiError {
  status: number
  code: string
  message: string
  traceId?: string
}

const CODE = /^[A-Z][A-Z0-9_]*$/

export function isErrorEnvelope(value: unknown): value is ErrorEnvelope {
  return (
    typeof value === "object" &&
    value !== null &&
    "code" in value &&
    typeof value.code === "string" &&
    CODE.test(value.code) &&
    "status" in value &&
    typeof value.status === "number"
  )
}

export function parseApiError(error: unknown): ApiError {
  const response = axios.isAxiosError(error) ? error.response : undefined
  const body: unknown = response?.data
  const status = response?.status ?? 0
  if (isErrorEnvelope(body)) {
    return {
      status,
      code: body.code,
      message: typeof body.message === "string" ? body.message : "",
      traceId: typeof body.traceId === "string" ? body.traceId : undefined,
    }
  }
  // Gateway HTML, proxy errors and network failures carry no envelope.
  return {
    status,
    code: status ? `HTTP_${status}` : "NETWORK_ERROR",
    message: status
      ? "Yêu cầu không thành công. Vui lòng thử lại."
      : "Không kết nối được máy chủ. Vui lòng kiểm tra kết nối và thử lại.",
  }
}
