import { Component, createRef, type ReactNode } from "react"
import { reportClientFailure } from "@/shared/api/telemetry"

interface Props {
  children: ReactNode
  scope: "app" | "route"
  resetKey?: string
}

interface State {
  failed: boolean
  resetKey?: string
}

export class RenderErrorBoundary extends Component<Props, State> {
  state: State = { failed: false, resetKey: this.props.resetKey }
  private readonly heading = createRef<HTMLHeadingElement>()

  static getDerivedStateFromError(): Partial<State> {
    return { failed: true }
  }

  static getDerivedStateFromProps(props: Props, state: State): State | null {
    return props.resetKey === state.resetKey ? null : { failed: false, resetKey: props.resetKey }
  }

  componentDidCatch() {
    reportClientFailure({ category: "render", scope: this.props.scope })
    this.heading.current?.focus()
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <main className="mx-auto max-w-xl space-y-4 px-6 py-16 text-text-primary">
        <h1 ref={this.heading} tabIndex={-1} className="text-2xl font-semibold">
          Không thể hiển thị trang
        </h1>
        <p className="text-text-secondary">Vui lòng tải lại trang để tiếp tục.</p>
        <div className="flex flex-wrap items-center gap-4">
          <button
            type="button"
            className="rounded-lg bg-primary px-4 py-2 font-medium text-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-primary"
            onClick={() => window.location.reload()}
          >
            Tải lại trang
          </button>
          <a className="text-primary underline" href="/">Về trang chủ</a>
        </div>
      </main>
    )
  }
}
