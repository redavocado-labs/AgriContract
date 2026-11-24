import type { BreachReasonCode } from '../commands';

/**
 * The contract's breachReasonCode values in words, for the breach notice and
 * the remedy decision emails. Every code of the enum has an entry: the
 * compiler rejects a code added to BREACH_REASON_CODES without its words.
 */
export const BREACH_REASONS: Record<BreachReasonCode, string> = {
  NON_DELIVERY: 'goods not delivered',
  SHORTFALL: 'less delivered than committed',
  LATE_DELIVERY: 'late delivery',
  QUALITY_BELOW_COMMITTED: 'quality below what was committed',
  SIDE_SELLING: 'goods sold to someone else',
  PRODUCTION_SHOCK_NON_FM: 'production shortfall that is not force majeure',
  FUNDING_FAILURE: 'milestone not funded',
  LATE_PAYMENT: 'late payment',
  FAILURE_TO_RECEIVE: 'goods not received',
  WRONGFUL_REJECTION: 'goods wrongly rejected',
  FORCE_MAJEURE: 'force majeure',
  MUTUAL_EXIT: 'mutual exit',
  ACTIVATION_FAILURE: 'deposits could not be locked',
};

/**
 * "Reason:" lines: the code after the label, its words on the next line
 * under it, so the longest code and words stay within 76 characters.
 */
export const reasonLines = (
  label: string,
  code: BreachReasonCode | null,
): string[] =>
  code === null
    ? [`${label}none`]
    : [
        `${label}${code}`,
        `${' '.repeat(label.length)}(${BREACH_REASONS[code]})`,
      ];
