import React from "react"
import ReactDOM from "react-dom/client"
import App from "./App"
import "./index.css"
import { QueryClientProvider } from "@tanstack/react-query"
import { queryClient } from "./shared/api/query-client"
import { reportClientFailure } from "./shared/api/telemetry"
import { initializeAuth } from "./shared/auth/keycloak"

// Finish processing the OIDC callback before BrowserRouter reads the URL.
const root = document.getElementById("root")
if (!root) throw new Error("Missing root element")
root.textContent = "Đang kiểm tra phiên đăng nhập…"
// Dev-only BF-04 screens on mocked data (src/mocks); never in a production build.
if (import.meta.env.DEV && import.meta.env.VITE_MOCK_API === "bf04") {
  const { installBf04Mock } = await import("./mocks/bf04-mock-api")
  installBf04Mock()
} else {
  await initializeAuth()
}
const rootOptions = import.meta.env.PROD ? {
  // Boundaries report caught errors with their scope. Keep React's raw error
  // diagnostics in development, where the stack is needed for debugging.
  onCaughtError: () => undefined,
  onUncaughtError: () => reportClientFailure({ category: "render", scope: "app" }),
  onRecoverableError: () => reportClientFailure({ category: "render", scope: "app" }),
} : undefined
ReactDOM.createRoot(root, rootOptions).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>
)
