import { lazy, Suspense } from "react"
import { BrowserRouter, Link, Route, Routes, useLocation } from "react-router-dom"
import { RenderErrorBoundary } from "@/shared/ui/render-error-boundary"

import { AuthPage } from "@/routes/auth-page"
import { HomePage } from "@/routes/home-page"
import { ToastProvider } from "@/shared/ui/toast"
import {
  RequireAuth,
  RequireRole,
} from "@/features/auth/components/auth-guards"
import { APP_ROLES } from "@/shared/auth/auth-store"
import { RequireOrganizationProfile } from "@/features/organization/components/organization-guards"

const RemedyRetryPage = lazy(() =>
  import("@/routes/remedy-retry-page").then((module) => ({ default: module.RemedyRetryPage }))
)

const EscrowPage = lazy(() =>
  import("@/routes/escrow-page").then((module) => ({ default: module.EscrowPage }))
)
const HowItWorksPage = lazy(() =>
  import("@/routes/how-it-works-page").then((module) => ({ default: module.HowItWorksPage }))
)
const ListingDetailPage = lazy(() =>
  import("@/routes/listing-detail-page").then((module) => ({ default: module.ListingDetailPage }))
)
const MarketplacePage = lazy(() =>
  import("@/routes/marketplace-page").then((module) => ({ default: module.MarketplacePage }))
)
const PricesPage = lazy(() =>
  import("@/routes/prices-page").then((module) => ({ default: module.PricesPage }))
)
const PublicReputationPage = lazy(() =>
  import("@/routes/public-reputation-page").then((module) => ({ default: module.PublicReputationPage }))
)
const AccountPage = lazy(() =>
  import("@/routes/account-page").then((module) => ({ default: module.AccountPage }))
)
const OrganizationOnboardingPage = lazy(() =>
  import("@/routes/organization-onboarding-page").then((module) => ({ default: module.OrganizationOnboardingPage }))
)
const ContractsPage = lazy(() =>
  import("@/routes/contracts-page").then((module) => ({ default: module.ContractsPage }))
)
const ContractDetailPage = lazy(() =>
  import("@/routes/contract-detail-page").then((module) => ({ default: module.ContractDetailPage }))
)
const CreateContractPage = lazy(() =>
  import("@/routes/create-contract-page").then((module) => ({ default: module.CreateContractPage }))
)
const MilestonePage = lazy(() =>
  import("@/routes/milestone-page").then((module) => ({ default: module.MilestonePage }))
)
const DisputesPage = lazy(() =>
  import("@/routes/disputes-page").then((module) => ({ default: module.DisputesPage }))
)
const AdminAuditPage = lazy(() =>
  import("@/routes/admin-audit-page").then((module) => ({ default: module.AdminAuditPage }))
)

const ComponentShowcase = import.meta.env.DEV
  ? lazy(() => import("@/routes/component-showcase").then((module) => ({ default: module.ComponentShowcase })))
  : undefined
const MobileComponentShowcase = import.meta.env.DEV
  ? lazy(() => import("@/routes/mobile-component-showcase").then((module) => ({ default: module.MobileComponentShowcase })))
  : undefined

function NotFound() {
  return (
    <main className="mx-auto max-w-xl space-y-4 px-6 py-16 text-text-primary">
      <title>Không tìm thấy trang — AgriContract</title>
      <h1 className="text-2xl font-semibold">Không tìm thấy trang</h1>
      <Link className="text-primary underline" to="/">Về trang chủ</Link>
    </main>
  )
}

function RouteContent() {
  const location = useLocation()
  return (
    <RenderErrorBoundary scope="route" resetKey={location.key}>
      <Suspense fallback={<p className="px-6 py-12 text-text-secondary" role="status">Đang tải trang…</p>}>
        <Routes>
          <Route element={<RequireAuth />}>
            <Route
              path="/onboarding"
              element={<OrganizationOnboardingPage />}
            />
            <Route
              element={
                <RequireRole
                  roles={["BUYER", "SELLER", "ARBITRATOR", "OPERATOR"]}
                />
              }
            >
              <Route
                path="/contracts/:id/milestones/:milestoneId"
                element={<MilestonePage />}
              />
            </Route>
            <Route element={<RequireRole roles={["OPERATOR"]} />}>
              <Route path="/operations/remedy" element={<RemedyRetryPage />} />
            </Route>
            <Route element={<RequireRole roles={["ADMIN"]} />}>
              <Route path="/admin/audit" element={<AdminAuditPage />} />
            </Route>
            <Route element={<RequireRole roles={["ARBITRATOR"]} />}>
              <Route path="/disputes" element={<DisputesPage />} />
              <Route
                path="/disputes/:breachCaseId"
                element={<DisputesPage />}
              />
            </Route>
            <Route element={<RequireOrganizationProfile />}>
              <Route element={<RequireRole roles={["BUYER", "SELLER"]} />}>
                <Route path="/contracts" element={<ContractsPage />} />
                <Route path="/contracts/new" element={<CreateContractPage />} />
                <Route path="/contracts/:id" element={<ContractDetailPage />} />
              </Route>
              <Route path="/account" element={<AccountPage />} />
              <Route element={<RequireRole roles={APP_ROLES} />}>
                <Route path="/workspace" element={<AccountPage />} />
              </Route>
            </Route>
          </Route>
          <Route path="/" element={<HomePage />} />
          <Route path="/listing" element={<MarketplacePage />} />
          <Route path="/listings/:id" element={<ListingDetailPage />} />
          <Route path="/prices" element={<PricesPage />} />
          <Route
            path="/reputation/:userId"
            element={<PublicReputationPage />}
          />
          <Route path="/how-it-works" element={<HowItWorksPage />} />
          <Route path="/escrow" element={<EscrowPage />} />
          <Route path="/login" element={<AuthPage />} />
          <Route
            path="/register"
            element={<AuthPage defaultView="register" />}
          />
          <Route
            path="/forgot-password"
            element={<AuthPage defaultView="forgot_password" />}
          />
          {ComponentShowcase && MobileComponentShowcase && (
            <>
              <Route path="/components" element={<ComponentShowcase />} />
              <Route path="/components/mobile" element={<MobileComponentShowcase />} />
            </>
          )}
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Suspense>
    </RenderErrorBoundary>
  )
}

export default function App() {
  return (
    <RenderErrorBoundary scope="app">
      <ToastProvider>
        <BrowserRouter>
          <RouteContent />
        </BrowserRouter>
      </ToastProvider>
    </RenderErrorBoundary>
  )
}
