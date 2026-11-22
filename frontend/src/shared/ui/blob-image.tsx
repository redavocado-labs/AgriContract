import { useEffect, useState } from "react"

import { cn } from "@/shared/lib/utils"
import { Skeleton } from "@/shared/ui/skeleton"

interface BlobImageProps {
  /** Loads the bytes with the caller's auth; called again when `cacheKey` changes. */
  load: (signal: AbortSignal) => Promise<Blob>
  cacheKey: string
  alt: string
  className?: string
}

/**
 * An image whose bytes need an authenticated request (no public URL). Shown
 * through a blob URL that is revoked on unmount. PDFs show a file tile.
 */
function BlobImage({ load, cacheKey, alt, className }: BlobImageProps) {
  const [state, setState] = useState<
    | { kind: "loading" }
    | { kind: "error" }
    | { kind: "ready"; url: string; pdf: boolean }
  >({ kind: "loading" })
  useEffect(() => {
    const controller = new AbortController()
    let url: string | undefined
    setState({ kind: "loading" })
    load(controller.signal)
      .then((blob) => {
        url = URL.createObjectURL(blob)
        setState({ kind: "ready", url, pdf: blob.type === "application/pdf" })
      })
      .catch(() => {
        if (!controller.signal.aborted) setState({ kind: "error" })
      })
    return () => {
      controller.abort()
      if (url) URL.revokeObjectURL(url)
    }
    // load is recreated by callers each render; cacheKey identifies the file.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cacheKey])
  const frame = cn(
    "block overflow-hidden rounded-md border-[0.5px] border-border bg-surface-muted",
    className
  )
  if (state.kind === "loading")
    return (
      <Skeleton
        role="status"
        aria-label={`Đang tải ${alt}`}
        className={cn("rounded-md", className)}
      />
    )
  if (state.kind === "error")
    return (
      <div
        className={cn(
          frame,
          "flex flex-col items-center justify-center gap-1 p-3 text-center"
        )}
      >
        <span className="ms text-[22px] text-text-muted">broken_image</span>
        <span className="text-xs text-text-secondary">Chưa tải được ảnh</span>
      </div>
    )
  if (state.pdf)
    return (
      <a
        href={state.url}
        target="_blank"
        rel="noreferrer"
        className={cn(
          frame,
          "flex flex-col items-center justify-center gap-1 p-3"
        )}
      >
        <span className="ms text-[22px] text-text-secondary">description</span>
        <span className="text-xs text-primary underline">Mở PDF</span>
      </a>
    )
  return (
    <a href={state.url} target="_blank" rel="noreferrer" className={frame}>
      <img src={state.url} alt={alt} className="size-full object-cover" />
    </a>
  )
}

export { BlobImage }
