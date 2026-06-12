// Pin the timezone for ALL unit tests so the date/clock helpers (clockTime,
// dayGroupLabel, relTime, weekLabel — they use local-time getters) are deterministic
// regardless of the machine's local zone. Without this, `npx vitest` (which, unlike
// `npm test`, does not prefix TZ=UTC) fails under e.g. Europe/Berlin — issue #148.
// Node applies a runtime TZ change to subsequent Date operations, and setupFiles run
// before any test module's Date calls, so this is enough on its own.
process.env.TZ = 'UTC'
