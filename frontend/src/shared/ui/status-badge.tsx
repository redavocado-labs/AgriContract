import type { ComponentProps } from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/shared/lib/utils"

const statusBadgeVariants = cva(
  "inline-flex items-center rounded-full px-2.5 py-1 text-[13px] font-medium leading-none whitespace-nowrap",
  {
    variants: {
      variant: {
        primary: "bg-primary-tint text-primary",
        success: "bg-success-tint text-success-strong",
        warning: "bg-warning-tint text-warning-strong",
        danger: "bg-danger-tint text-danger-strong",
        info: "bg-info-tint text-info-strong",
        neutral: "bg-neutral-tint text-text-secondary",
      },
    },
    defaultVariants: {
      variant: "neutral",
    },
  }
)

interface StatusBadgeProps
  extends ComponentProps<"span">,
    VariantProps<typeof statusBadgeVariants> {}

function StatusBadge({ className, variant, ...props }: StatusBadgeProps) {
  return (
    <span
      data-slot="status-badge"
      className={cn(statusBadgeVariants({ variant, className }))}
      {...props}
    />
  )
}

export { StatusBadge, statusBadgeVariants }
