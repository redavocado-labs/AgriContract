import { formatDecimalVnd } from "@/shared/lib/decimal-display"
import type { ParticipantRole } from "@/features/contracts"

export function settlementStatement(amount: string) {
  return `Tôi đồng ý bên bán nhận ${formatDecimalVnd(amount)} đồng cho đợt giao này, phần còn lại hoàn bên mua. Thoả thuận chỉ có hiệu lực khi bên kia xác nhận đúng con số này; khi đó tranh chấp kết thúc không bên nào có lỗi. Nếu không thoả thuận được, giám định tiếp tục.`
}

export function concessionStatement(
  role: ParticipantRole,
  sellerAmount: string,
  buyerRefund: string
) {
  const seller = formatDecimalVnd(sellerAmount)
  const buyer = formatDecimalVnd(buyerRefund)
  return role === "BUYER"
    ? `Tôi rút báo lỗi / chấp nhận vị thế của bên bán cho đợt giao này: bên bán nhận ${seller} đồng, tôi được hoàn ${buyer} đồng. Tôi hiểu việc này không làm lại được, giám định đang chờ (nếu có) bị huỷ, và lần báo lỗi này được tính là báo lỗi không thành.`
    : `Tôi chấp nhận vị thế của bên mua cho đợt giao này: tôi nhận ${seller} đồng, bên mua được hoàn ${buyer} đồng. Tôi hiểu việc này không làm lại được, giám định đang chờ (nếu có) bị huỷ, và nếu vị thế này có lỗi thì tôi chịu phạt và khoá uy tín theo chính sách.`
}
