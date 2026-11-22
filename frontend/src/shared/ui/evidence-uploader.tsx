import { useId, useRef, useState, type ReactNode } from "react"

import { cn } from "@/shared/lib/utils"
import { FileUpload, type FileUploadItem } from "@/shared/ui/file-upload"

interface EvidenceUploaderProps {
  id?: string
  label: ReactNode
  required?: boolean
  hint?: string
  /** MIME types accepted, vd "image/jpeg,image/png,application/pdf". */
  accept?: string
  /** Byte limit checked before upload; the server checks again. */
  maxBytes?: number
  disabled?: boolean
  /** Current file and its server status; null before a file is picked. */
  value: FileUploadItem | null
  onSelect: (file: File) => void
  error?: string
  className?: string
}

const DEFAULT_ACCEPT = "image/jpeg,image/png,application/pdf"
const MB = 1024 * 1024

/**
 * One evidence slot: pick or take a photo, then show its scan status with the
 * existing FileUpload row. Upload itself is the caller's job, so the component
 * fits any "file -> PROCESSING -> READY/FAILED" flow.
 */
function EvidenceUploader({
  id,
  label,
  required,
  hint,
  accept = DEFAULT_ACCEPT,
  maxBytes = 10 * MB,
  disabled,
  value,
  onSelect,
  error,
  className,
}: EvidenceUploaderProps) {
  const fallbackId = useId()
  const inputId = id ?? fallbackId
  const input = useRef<HTMLInputElement>(null)
  const [localError, setLocalError] = useState("")
  const pick = (file?: File) => {
    if (!file) return
    if (!accept.split(",").includes(file.type)) {
      setLocalError("Chỉ nhận ảnh JPG, PNG hoặc tệp PDF.")
      return
    }
    if (file.size > maxBytes) {
      setLocalError(`Tệp vượt quá ${Math.round(maxBytes / MB)} MB.`)
      return
    }
    setLocalError("")
    onSelect(file)
  }
  const message = localError || error
  return (
    <div
      data-slot="evidence-uploader"
      className={cn("flex flex-col gap-2", className)}
    >
      <label htmlFor={inputId} className="text-[13px] text-text-primary">
        {label} {required ? <span className="text-danger">*</span> : null}
      </label>
      <input
        ref={input}
        id={inputId}
        type="file"
        accept={accept}
        capture="environment"
        disabled={disabled}
        className="sr-only"
        onChange={(event) => {
          pick(event.target.files?.[0])
          event.target.value = ""
        }}
      />
      {value ? (
        <div className="flex flex-col gap-2">
          <FileUpload files={[value]} />
          <button
            type="button"
            disabled={disabled}
            onClick={() => input.current?.click()}
            className="self-start text-[13px] text-primary underline disabled:text-text-muted disabled:no-underline"
          >
            Chọn ảnh khác
          </button>
        </div>
      ) : (
        <button
          type="button"
          disabled={disabled}
          onClick={() => input.current?.click()}
          className={cn(
            "flex flex-col items-center justify-center gap-1 rounded-md border-[0.5px] border-dashed bg-page-bg px-4 py-6 text-center",
            message ? "border-danger" : "border-border-strong",
            disabled
              ? "cursor-not-allowed opacity-60"
              : "cursor-pointer hover:bg-surface-muted"
          )}
        >
          <span className="ms text-[28px] text-text-secondary">
            add_a_photo
          </span>
          <span className="text-sm text-text-primary">Chụp hoặc chọn ảnh</span>
          <span className="text-xs text-text-muted">
            JPG, PNG hoặc PDF, tối đa {Math.round(maxBytes / MB)} MB
          </span>
        </button>
      )}
      {message ? (
        <p role="alert" className="text-xs text-danger">
          {message}
        </p>
      ) : hint ? (
        <p className="text-xs text-text-secondary">{hint}</p>
      ) : null}
    </div>
  )
}

export { EvidenceUploader }
