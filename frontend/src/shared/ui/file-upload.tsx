import { cn } from "@/shared/lib/utils"

interface FileUploadItem {
  key?: string
  name: string
  sizeLabel?: string
  status:
    | "uploading"
    | "processing"
    | "scan-timeout"
    | "ready"
    | "rejected"
    | "upload-failed"
    | "failed"
    | "local"
  failureReason?: string
  onRetry?: () => void
  onRecheck?: () => void
  checking?: boolean
}

interface FileUploadProps {
  files: FileUploadItem[]
  className?: string
}

function FileUpload({ files, className }: FileUploadProps) {
  return (
    <div
      data-slot="file-upload"
      className={cn("flex flex-col gap-2", className)}
    >
      {files.map((file) => {
        const failed = ["failed", "rejected", "upload-failed"].includes(file.status)
        const pending = file.status === "uploading" || file.status === "processing"
        return (
          <div
            key={file.key ?? file.name}
            className="flex items-center gap-3 rounded-sm border-[0.5px] border-border px-3.5 py-2.5"
          >
            <div
              className={cn(
                "size-9 shrink-0 rounded-md",
                failed ? "bg-danger-tint" : "bg-surface-muted"
              )}
          />
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm text-text-primary">
              {file.name}
            </div>
            {failed ? (
              <div role="status" className="text-[11px] text-danger">
                {file.status === "upload-failed"
                  ? "Chưa tải tệp lên thành công."
                  : file.status === "rejected"
                    ? "Tệp bị từ chối."
                    : "Không xử lý được."}{" "}
                {file.failureReason}
              </div>
            ) : file.status === "scan-timeout" ? (
              <div role="status" className="text-[11px] text-warning">
                Đã hết thời gian kiểm tra tự động. Chưa có kết quả kiểm tra an toàn.
              </div>
            ) : file.sizeLabel ? (
              <div className="text-[11px] text-text-muted">
                {file.sizeLabel}
              </div>
            ) : null}
          </div>
            {pending ? (
              <span role="status" className="flex shrink-0 items-center gap-1.5 text-[13px] text-warning">
                <span aria-hidden className="size-3.5 animate-spin rounded-full border-2 border-warning-tint border-t-warning" />
                {file.status === "uploading" ? "Đang tải lên" : "Đang kiểm tra"}
              </span>
            ) : file.status === "ready" ? (
              <span role="status" className="flex shrink-0 items-center gap-1 text-[13px] text-success">
                <span aria-hidden className="ms text-[15px]">check_circle</span>
                Sẵn sàng
              </span>
            ) : file.status === "local" ? (
              <span className="shrink-0 text-xs text-text-secondary">
                Chỉ trên thiết bị
              </span>
            ) : file.onRecheck ? (
              <button
                type="button"
                onClick={file.onRecheck}
                disabled={file.checking}
                className="shrink-0 text-[13px] text-primary underline disabled:text-text-muted"
              >
                {file.checking ? "Đang kiểm tra…" : "Kiểm tra lại"}
              </button>
            ) : file.onRetry ? (
              <button
                type="button"
                onClick={file.onRetry}
                className="shrink-0 text-[13px] text-danger underline"
              >
                Thử lại
              </button>
            ) : null}
          </div>
        )
      })}
    </div>
  )
}

export { FileUpload }
export type { FileUploadItem }
