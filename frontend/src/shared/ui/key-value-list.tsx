import type { ComponentProps, ReactNode } from "react"

import { cn } from "@/shared/lib/utils"

interface KeyValueItem {
  key?: string
  label: string
  value: ReactNode
  stackOnNarrow?: boolean
}

interface KeyValueListProps extends Omit<ComponentProps<"div">, "children"> {
  items: KeyValueItem[]
}

function KeyValueList({ items, className, ...props }: KeyValueListProps) {
  return (
    <div
      data-slot="key-value-list"
      className={cn(
        "rounded-md border-[0.5px] border-border bg-surface px-4 py-3 sm:px-5 sm:py-4",
        items.some((item) => item.stackOnNarrow) && "@container",
        className
      )}
      {...props}
    >
      {items.map((item, index) => (
        <div
          key={item.key ?? item.label}
          className={cn(
            "flex justify-between gap-x-3 py-2.5 text-sm",
            item.stackOnNarrow
              ? "flex-col items-start gap-y-1 @sm:flex-row @sm:items-baseline"
              : "items-baseline",
            index < items.length - 1 && "border-b-[0.5px] border-border"
          )}
        >
          <span className="min-w-0 text-text-secondary">{item.label}</span>
          <span
            className={cn(
              "min-w-0 text-text-primary [overflow-wrap:anywhere]",
              item.stackOnNarrow ? "@sm:text-right" : "text-right"
            )}
          >
            {item.value}
          </span>
        </div>
      ))}
    </div>
  )
}

export { KeyValueList }
export type { KeyValueItem }
