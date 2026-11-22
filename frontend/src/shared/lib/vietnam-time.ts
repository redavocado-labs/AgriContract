/** Server instants shown in Asia/Ho_Chi_Minh; date-only values stay as the signed date. */
export function vietnamTime(value?: string) {
  if (!value) return "Chưa có thông tin"
  if (/^\d{4}-\d{2}-\d{2}$/.test(value))
    return value.split("-").reverse().join("/")
  if (
    !/(Z|[+-]\d{2}:\d{2})$/.test(value) ||
    !Number.isFinite(Date.parse(value))
  )
    return "Thời gian chưa xác định"
  return (
    new Intl.DateTimeFormat("vi-VN", {
      dateStyle: "short",
      timeStyle: "short",
      timeZone: "Asia/Ho_Chi_Minh",
    }).format(new Date(value)) + " (giờ Việt Nam)"
  )
}
