import { Fragment, type ReactNode } from "react"
import { Link, useLocation } from "react-router-dom"
import { AppSidebar, type SidebarItem } from "@/shared/ui/app-sidebar"
import { TopBar } from "@/shared/ui/top-bar"
import { useAuthStore } from "@/shared/auth/auth-store"
import { AccountMenu } from "@/features/auth/components/account-menu"
import { useOrganizationProfile } from "@/features/organization/hooks/use-organization-profile"

export interface Crumb {
  label: string
  to?: string
  mono?: boolean
}

export function ContractsLayout({
  title,
  crumbs = [{ label: "Hợp đồng", to: "/contracts" }],
  aside,
  children,
}: {
  title: ReactNode
  /** In-app breadcrumb (client-side links, no reload). */
  crumbs?: Crumb[]
  /** Right side of the title row, vd a status badge. */
  aside?: ReactNode
  children: ReactNode
}) {
  const profile = useOrganizationProfile()
  const roles = useAuthStore((state) => state.roles)
  const { pathname } = useLocation()
  const party = roles.includes("BUYER") || roles.includes("SELLER")
  const items: SidebarItem[] = [
    ...(party
      ? [
          {
            label: "Hợp đồng của tôi",
            icon: "description",
            href: "/contracts",
            active: pathname.startsWith("/contracts"),
          },
          { label: "Sàn nguồn hàng", icon: "storefront", href: "/listing" },
        ]
      : []),
    ...(roles.includes("ARBITRATOR")
      ? [
          {
            label: "Tranh chấp chờ duyệt",
            icon: "gavel",
            href: "/disputes",
            active: pathname.startsWith("/disputes"),
          },
        ]
      : []),
    { label: "Hồ sơ doanh nghiệp", icon: "domain", href: "/account" },
  ]
  return (
    <div className="mx-auto flex min-h-screen max-w-[1440px] flex-col gap-4 p-3 md:flex-row md:gap-6 md:p-6">
      <AppSidebar
        className="w-full md:w-60"
        organizationName={profile.data?.organizationName ?? undefined}
        items={items}
      />
      <div className="min-w-0 flex-1 space-y-5">
        <TopBar
          avatar={<AccountMenu />}
          searchPlaceholder={
            profile.data?.organizationName ?? "Hợp đồng của bạn"
          }
          className="h-auto min-h-16 flex-wrap py-3"
        />
        <main className="min-w-0 space-y-5">
          <nav
            aria-label="Breadcrumb"
            className="flex flex-wrap items-center text-sm text-text-secondary"
          >
            {crumbs.map((crumb, index) => (
              <Fragment key={`${crumb.label}-${index}`}>
                {index > 0 && (
                  <span className="mx-1.5 text-border-strong">/</span>
                )}
                {crumb.to ? (
                  <Link
                    to={crumb.to}
                    className={
                      crumb.mono
                        ? "font-mono text-text-primary hover:text-primary"
                        : "text-primary hover:underline"
                    }
                  >
                    {crumb.label}
                  </Link>
                ) : (
                  <span
                    className={
                      crumb.mono
                        ? "font-mono font-medium text-text-primary"
                        : "font-medium text-text-primary"
                    }
                  >
                    {crumb.label}
                  </span>
                )}
              </Fragment>
            ))}
          </nav>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl">{title}</h1>
            {aside}
          </div>
          {children}
        </main>
      </div>
    </div>
  )
}
