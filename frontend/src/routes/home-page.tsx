import { Link } from "react-router-dom"

import { cn } from "@/shared/lib/utils"
import { buttonVariants } from "@/shared/ui/button"
import { Footer } from "@/shared/ui/footer"
import { MoneyDisplay } from "@/shared/ui/money-display"
import { StatusBadge } from "@/shared/ui/status-badge"
import { PublicNav } from "./layouts/public-nav"

function Hero() {
  return (
    <div className="bg-primary text-white">
      <div className="mx-auto grid max-w-[1160px] items-center gap-8 px-4 py-10 sm:px-10 lg:grid-cols-[1.15fr_1fr] lg:gap-14 lg:pt-[72px] lg:pb-[76px]">
        <div>
          <div className="mb-[22px] inline-flex items-center gap-2 rounded-full bg-white/[0.12] px-3 py-1.5 text-[12.5px] text-primary-tint">
            <span className="ms text-base" aria-hidden="true">verified_user</span> Nền tảng hợp đồng nông sản có ký quỹ
          </div>
          <h1 className="mb-5 text-[32px] leading-[1.18] font-medium tracking-[-0.015em] sm:text-[42px]">
            Mua bán nông sản kỳ hạn, không lo bẻ kèo.
          </h1>
          <div className="mb-8 max-w-[520px] text-base leading-[1.65] text-primary-tint">
            Tiền hàng được khoá trước mỗi đợt giao và giải ngân khi cột mốc đủ điều kiện tất toán.
            Hệ thống hỗ trợ giám định độc lập khi có tranh chấp và cho phép xem uy tín đối tác sau khi đăng nhập.
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Link
              to="/register"
              className="rounded-[9px] bg-white px-6 py-[13px] text-[15px] font-medium text-primary"
            >
              Đăng ký doanh nghiệp
            </Link>
            <Link
              to="/listing"
              className="inline-flex items-center gap-2 rounded-[9px] border border-white/35 px-6 py-[13px] text-[15px] font-medium text-white hover:bg-white/10"
            >
              Xem sàn nguồn hàng <span className="ms text-lg" aria-hidden="true">arrow_forward</span>
            </Link>
          </div>
        </div>

        <div className="rounded-2xl bg-surface px-6 py-[22px] text-text-primary shadow-[0_20px_50px_rgba(15,23,42,0.18)]">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
            <span className="text-[13px] font-medium text-text-secondary">Hợp đồng minh hoạ</span>
            <StatusBadge variant="info">Đang thực hiện</StatusBadge>
          </div>
          <div className="mb-0.5 text-[13px] text-text-secondary">Tiền hàng đang khoá</div>
          <div className="mb-4.5">
            <MoneyDisplay amount={2_500_000_000} sign="neutral" emphasis="large" />
          </div>
          <div className="flex flex-col gap-3.5">
            <div className="flex items-start gap-3">
              <span className="ms text-xl text-success" aria-hidden="true">check_circle</span>
              <div className="flex-1">
                <div className="text-[13.5px]">Cột mốc 1 — 10 tấn</div>
                <div className="text-xs text-text-muted">Đã tất toán · giải ngân 2.500.000.000 ₫</div>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <span className="ms text-xl text-warning" aria-hidden="true">radio_button_checked</span>
              <div className="flex-1">
                <div className="text-[13.5px]">Cột mốc 2 — 10 tấn</div>
                <div className="text-xs text-text-muted">Chờ xác nhận nhận hàng · 2.500.000.000 ₫</div>
              </div>
            </div>
          </div>
          <div className="mt-4.5 flex items-start gap-2 rounded-[9px] bg-surface-muted px-3.5 py-2.5">
            <span className="ms mt-px text-base text-text-secondary" aria-hidden="true">lock</span>
            <span className="text-xs leading-[1.5] text-text-secondary">
              Sổ cái chỉ ghi thêm — không thể sửa hoặc xoá bút toán.
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}

function Eyebrow({ children }: { children: string }) {
  return (
    <div className="mb-2.5 text-[13px] font-medium tracking-[0.04em] text-primary uppercase">
      {children}
    </div>
  )
}

function ProblemStatement() {
  return (
    <div className="mx-auto max-w-[1160px] px-4 pt-16 pb-2 sm:px-10">
      <Eyebrow>Vấn đề</Eyebrow>
      <div className="max-w-[820px] text-[26px] leading-[1.4] font-medium">
        Hợp đồng nông sản kỳ hạn ở Việt Nam thường bị bẻ kèo khi giá biến động — và bên yếu thế là
        nông dân, hợp tác xã chịu rủi ro lớn nhất.
      </div>
    </div>
  )
}

const pillars = [
  {
    icon: "account_balance_wallet",
    title: "Ký quỹ tự thực thi",
    body: "Tiền bị khoá trước khi giao hàng và giải ngân tự động theo từng cột mốc. Không bên nào rút được ngoài quy tắc đã ký.",
  },
  {
    icon: "flag",
    title: "Giao hàng theo cột mốc",
    body: "Mỗi đợt giao là một cột mốc có vòng đời và giải ngân riêng, giúp bên bán được thanh toán sớm cho phần đã giao.",
  },
  {
    icon: "gavel",
    title: "Tranh chấp 3 tầng",
    body: "Từ quản trị nội bộ đến Vinacontrol/Quatest và SGS/Bureau Veritas — giám định độc lập, kết luận có ràng buộc.",
  },
]

function PillarsSection() {
  return (
    <div className="mx-auto grid max-w-[1160px] gap-5 px-4 pt-10 pb-6 sm:grid-cols-2 sm:px-10 lg:grid-cols-3">
      {pillars.map((pillar) => (
        <div key={pillar.title} className="rounded-xl border-[0.5px] border-border bg-surface p-6">
          <span className="ms text-[26px] text-primary" aria-hidden="true">{pillar.icon}</span>
          <div className="mt-3.5 mb-2 text-[17px] font-medium">{pillar.title}</div>
          <div className="text-sm leading-[1.6] text-text-secondary">{pillar.body}</div>
        </div>
      ))}
    </div>
  )
}

const steps = [
  {
    title: "Ký hợp đồng",
    body: "Hai bên thống nhất điều khoản trong dải guardrail và ký bằng OTP.",
  },
  {
    title: "Khoá ký quỹ",
    body: "Khoá cọc theo điều khoản để kích hoạt hợp đồng. Tiền hàng được khoá trước từng đợt giao.",
  },
  {
    title: "Giao & xác nhận",
    body: "Bên bán cân hàng, bên mua cân lại và xác nhận theo từng đợt.",
  },
  {
    title: "Giải ngân",
    body: "Hệ thống giải ngân đúng phần đã nhận, tính pro-rata, không cần trung gian.",
  },
]

function HowItWorks() {
  return (
    <div className="mx-auto max-w-[1160px] px-4 py-12 sm:px-10">
      <Eyebrow>Cách hoạt động</Eyebrow>
      <div className="mb-8 text-2xl font-medium">
        Từ đề nghị đến tất toán, mọi bước đều kiểm chứng được
      </div>
      <div className="grid gap-y-5 sm:grid-cols-2 lg:grid-cols-4">
        {steps.map((step, index) => {
          const stepNumber = index + 1
          const isLast = stepNumber === steps.length

          return (
            <div key={step.title} className={cn(!isLast && "pr-5")}>
              <div className="mb-3 flex items-center gap-3">
                <span
                  className={cn(
                    "flex size-8 items-center justify-center rounded-full font-mono text-[15px] font-medium",
                    isLast ? "bg-primary text-white" : "bg-primary-tint text-primary"
                  )}
                >
                  {stepNumber}
                </span>
                {!isLast ? <div className="h-px flex-1 bg-border" /> : null}
              </div>
              <div className="mb-1.5 text-[15px] font-medium">{step.title}</div>
              <div className="text-[13.5px] leading-[1.55] text-text-secondary">{step.body}</div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function TraceabilitySection() {
  return (
    <div className="border-y-[0.5px] border-border bg-surface-muted">
      <div className="mx-auto grid max-w-[1160px] items-center gap-6 px-4 py-12 sm:px-10 lg:grid-cols-2 lg:gap-12">
        <div>
          <Eyebrow>Truy xuất nguồn gốc</Eyebrow>
          <div className="mb-3.5 text-2xl leading-[1.35] font-medium">
            Truy xuất vùng trồng cà phê
          </div>
          <div className="mb-4.5 text-sm leading-[1.65] text-text-secondary">
            Bên bán khai báo vùng trồng khi đăng nguồn hàng cà phê. Thông tin vùng trồng hỗ trợ đối chiếu nguồn gốc.
          </div>
          <div className="flex flex-col gap-2.5">
            <div className="flex items-center gap-2.5 text-[13.5px] text-text-secondary">
              <span className="ms text-lg text-success" aria-hidden="true">check_circle</span>
              Chi tiết nguồn hàng cho biết số vùng trồng đã khai báo.
            </div>
          </div>
        </div>
        <div className="rounded-xl border-[0.5px] border-border bg-surface px-4.5 py-4">
          <div className="text-[15px] font-medium">Cà phê</div>
          <div className="mt-1 text-[12.5px] text-text-secondary">Mặt hàng đang được hỗ trợ</div>
        </div>
      </div>
    </div>
  )
}

function FinalCta() {
  return (
    <div className="mx-auto max-w-[1160px] px-4 py-16 text-center sm:px-10">
      <div className="mb-3 text-[28px] font-medium">Bắt đầu giao dịch có bảo chứng</div>
      <div className="mb-7 text-[15px] text-text-secondary">
        Mở tài khoản miễn phí. Điều phối viên xác minh hồ sơ doanh nghiệp.
      </div>
      <div className="flex flex-wrap justify-center gap-3">
        <Link
          to="/login"
          className={cn(buttonVariants({ variant: "outline", size: "cta" }))}
        >
          Đăng nhập
        </Link>
        <Link to="/register" className={cn(buttonVariants({ size: "cta" }))}>
          Đăng ký doanh nghiệp
        </Link>
      </div>
    </div>
  )
}

function HomePage() {
  return (
    <div className="bg-page-bg text-text-primary">
      <PublicNav />
      <main>
        <Hero />
        <ProblemStatement />
        <PillarsSection />
        <HowItWorks />
        <TraceabilitySection />
        <FinalCta />
      </main>
      <Footer />
    </div>
  )
}

export { HomePage }
