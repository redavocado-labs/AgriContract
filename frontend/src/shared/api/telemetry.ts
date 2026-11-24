import { matchPath } from "react-router-dom"

const routeTemplates = [
  "/", "/listing", "/listings/:id", "/reputation/:userId", "/prices",
  "/escrow", "/how-it-works", "/login", "/register", "/forgot-password",
  "/onboarding", "/workspace", "/account", "/contracts", "/contracts/new",
  "/contracts/:id", "/contracts/:id/milestones/:milestoneId", "/disputes",
  "/disputes/:breachCaseId",
]
const traceIdPattern = /^(?:[0-9a-f]{16}|[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/i
const releasePattern = /^(?:[a-f0-9]{7,40}|\d+\.\d+\.\d+)$/i

export type ClientFailure =
  | { category: "render"; scope: "app" | "route" }
  | { category: "http" | "network"; operation: "read" | "write"; status: number; traceId?: string }

export function reportClientFailure(failure: ClientFailure) {
  try {
    const version = import.meta.env.VITE_APP_VERSION
    const route = routeTemplates.find((template) => matchPath(template, window.location.pathname))
    const header = {
      event: "frontend.failure",
      version: typeof version === "string" && releasePattern.test(version) ? version : "unversioned",
      route: route ?? "unknown",
      category: failure.category,
    }
    if (failure.category === "render") {
      console.warn("AgriContract", { ...header, scope: failure.scope })
      return
    }
    console.warn("AgriContract", {
      ...header,
      operation: failure.operation,
      ...(failure.status >= 100 && failure.status <= 599 ? { status: failure.status } : {}),
      ...(failure.traceId && traceIdPattern.test(failure.traceId) ? { traceId: failure.traceId } : {}),
    })
  } catch {
    // Diagnostics must never interrupt rendering or a request's error handling.
  }
}
