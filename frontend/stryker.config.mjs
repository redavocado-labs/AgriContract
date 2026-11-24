export default {
  testRunner: "vitest",
  vitest: { configFile: "scripts/vitest.mutation.config.ts", related: true },
  // PRE_SIGNATURE, participantRole, canNegotiate and canWithdraw.
  mutate: ["src/features/contracts/types/presentation.ts:35-64"],
  ignorePatterns: ["docs/**", "dist/**", "coverage/**", "test-results/**", "tests/qa/**", ".env*"],
  concurrency: 2,
  reporters: ["clear-text", "progress"],
}
