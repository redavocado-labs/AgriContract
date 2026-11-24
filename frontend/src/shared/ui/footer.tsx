import { Link } from "react-router-dom"

function Footer() {
  return (
    <footer className="bg-text-primary text-slate-300">
      <div className="mx-auto flex max-w-[1160px] flex-wrap items-start justify-between gap-6 px-4 py-10 sm:px-10">
        <div>
          <div className="mb-1.5 text-base font-medium text-white">AgriContract</div>
          <div className="max-w-[320px] text-[13px] leading-[1.6]">
            Giao dịch cà phê theo hợp đồng có ký quỹ.
          </div>
        </div>
        <div className="flex flex-wrap gap-x-14 gap-y-6 text-[13.5px]">
          <div className="flex flex-col gap-2.5">
            <span className="text-[#E2E8F0]">Sản phẩm</span>
            <Link to="/listing" className="hover:text-white">Sàn nguồn hàng</Link>
            <Link to="/prices" className="hover:text-white">Bảng giá tham khảo</Link>
            <Link to="/how-it-works" className="hover:text-white">Cách hoạt động</Link>
          </div>
          <div className="flex flex-col gap-2.5">
            <span className="text-[#E2E8F0]">Tuân thủ</span>
            <Link to="/escrow" className="hover:text-white">Ký quỹ & giải ngân</Link>
            <span>Giải quyết tranh chấp</span>
            <span>EUDR & truy xuất</span>
          </div>
        </div>
      </div>
      <div className="border-t-[0.5px] border-white/[0.08]">
        <div className="mx-auto max-w-[1160px] px-4 py-4 text-[12.5px] sm:px-10">
          © 2026 AgriContract. Giá hiển thị là giá tham khảo, không phải giá giao dịch trên nền
          tảng.
        </div>
      </div>
    </footer>
  )
}

export { Footer }
