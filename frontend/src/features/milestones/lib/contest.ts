import type {
  MilestoneStatus,
  ParticipantRole,
} from "@/features/contracts"
import type { BreachCase } from "../api/milestones"

export function contestAction(
  breach: Pick<
    BreachCase,
    | "milestoneId"
    | "status"
    | "contestStatus"
    | "contestDeadline"
    | "finalBreachingRole"
    | "decisionSource"
  >,
  milestoneStatus: MilestoneStatus,
  role: ParticipantRole,
  now = Date.now()
) {
  if (
    milestoneStatus !== "CONTESTED" ||
    !breach.milestoneId ||
    breach.status !== "RESOLVED" ||
    breach.finalBreachingRole !== role ||
    breach.decisionSource === "MUTUAL"
  )
    return null
  if (breach.contestStatus === "CONTESTED") return "evidence"
  return breach.contestStatus === "OPEN" &&
    breach.contestDeadline &&
    Date.parse(breach.contestDeadline) > now
    ? "contest"
    : null
}
