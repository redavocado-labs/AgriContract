import { Link, useLocation } from "react-router-dom"

import { cn } from "@/shared/lib/utils"
import { AccountMenu } from "@/features/organization"

const NAV_LINKS = [
  { label: "Sàn nguồn hàng", to: "/listing" },
  { label: "Bảng giá tham khảo", to: "/prices" },
  { label: "Cách hoạt động", to: "/how-it-works" },
  { label: "Về ký quỹ", to: "/escrow" },
]

function PublicNav() {
  const { pathname } = useLocation()

  return (
    <header className="sticky top-0 z-10 flex flex-wrap items-center gap-x-4 gap-y-2 border-b-[0.5px] border-border bg-surface px-4 py-2 md:h-16 md:flex-nowrap md:gap-8 md:px-10 md:py-0">
      <Link to="/" className="text-[17px] font-medium text-primary">
        AgriContract
      </Link>
      <nav aria-label="Điều hướng chính" className="flex w-full flex-wrap gap-x-4 gap-y-2 text-sm text-text-secondary md:w-auto md:gap-x-[26px]">
        {NAV_LINKS.map((link) => {
          const isActive =
            pathname === link.to || pathname.startsWith(link.to + "/")
          return (
            <Link
              key={link.to}
              to={link.to}
              className={cn(
                "hover:text-text-primary",
                isActive && "font-medium text-primary"
              )}
              aria-current={isActive ? "page" : undefined}
            >
              {link.label}
            </Link>
          )
        })}
      </nav>
      <div className="flex-1" />
      <AccountMenu />
    </header>
  )
}

export { PublicNav }
