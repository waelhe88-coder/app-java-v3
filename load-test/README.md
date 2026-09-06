# Load-Test Baseline — Single-Replica Read Surface (roadmap A5)

> **Deliverable of gap A5** (airbnb-level gap analysis): "اختبار حمل أساس
> (k6/Gatling) لسقف النسخة الواحدة | أداة خارج المستودع + توثيق الرقم".
> The k6 **binary** is external to this repository (official grafana/k6
> release — never a Maven dependency); `baseline.js` is the reproducible
> measurement harness, and this file is the documented number.

## Method

- **Tool**: k6 v1.4.0 (official binary, `grafana/k6` releases).
- **Executor**: `constant-arrival-rate` — the **open model** (k6 docs
  "Open and closed models": arrivals start at a fixed rate regardless of
  iteration completion; the rate is the independent variable). The ceiling
  is the highest rate where every threshold still holds.
- **Thresholds (pass/fail gate)**: `http_req_failed` rate < 1% (only HTTP
  200 counts as success — `http.expectedStatuses(200)`, the official
  recipe) and `p(95) < 500ms` per endpoint class.
- **Traffic mix (steady-state production shape)**: 70% browse
  (`GET /api/v1/listings?page=0..2&size=20`) / 30% search
  (`GET /api/v1/search?q=…`, 4 terms) over a small hot working set — every
  cache key is a HIT after the first iteration (mirrors hot pages under
  real traffic). A liveness probe runs at a constant 2 rps alongside
  (platform probes keep firing under load). Cold-miss stampede behavior is
  a separate measurement behind the A1 observability gate.
- **Ladder**: one 60s run per rate (50 → 1600 rps) + one sustained run
  (600 rps × 180s). Each run from the same app instance.

## Measured environment (session, 2026-09-05)

| Component | Value |
|---|---|
| Host | sandbox, 2 CPU cores **shared by k6 + app + PostgreSQL + Redis**, 4 GB RAM |
| App | full jar (18-module reactor, main `09c7c39` + the cold-cache serialization fix), Java 25, virtual threads on, `-Xmx512m` |
| Database | PostgreSQL 18 (session instance), 62 ACTIVE listings, HikariCP pool 20 |
| Cache | Redis 8.10.1, `spring.cache.type=redis`, TTL 1h, **cold at measurement start** (`FLUSHALL`) |
| Tracing/OTEL | production default shape: sampling 0.1, OTLP export failing to `localhost:4318` (the documented A1 gate state — background noise, not on the request path) |
| Rate limiter | see the two shapes below |

**Honesty note:** k6 shares the 2 CPU cores with the application. The
latency numbers and the ~1600 rps failure point are therefore inflated by
harness contention — the documented ceiling is a **lower bound** for a
dedicated-CPU production replica. Dropped iterations (k6-side VU
starvation) are reported per run.

## Result 1 — as-configured policy ceiling (production today)

The public read surface is protected by Resilience4j `@RateLimiter`
(`application.yml`: catalog `limit-for-period: 50` per 1m, search `30`
per 1m, `timeout-duration: 5s`). At a 50 rps arrival the policy rejects
beyond budget:

| Arrival | p95 | http_req_failed | Notes |
|---|---|---|---|
| 50 rps | 5s | **90.5%** (429 after 5s permit wait) | effective public ceiling = limiter budget ≈ **1.3 rps** |

**The effective public ceiling of the deployed system is the rate-limiter
policy (~1.3 rps), orders of magnitude below its serving capacity.** The
limiter budgets are env-tunable without code changes
(`RESILIENCE4J_RATELIMITER_INSTANCES_CATALOG_LIMIT_FOR_PERIOD` /
`..._SEARCH_...`) — a deployment-tuning decision, documented here with
numbers, not changed in code by this layer.

## Result 2 — serving-capacity ceiling (limiter budgets raised via the documented env knobs)

`RESILIENCE4J_RATELIMITER_INSTANCES_{CATALOG,SEARCH}_LIMIT_FOR_PERIOD=100000`
(+ `TIMEOUT_DURATION=0s`). Everything else identical, cache cold at start.

| Rate | p95 | p90 | Errors | Dropped iters | Verdict |
|---|---|---|---|---|---|
| 50 rps | 9.54 ms | 5.76 ms | 0 / 3122 | 0 | ✅ (first window includes cold rebuilds: max 1.41 s) |
| 100 rps | 3.64 ms | 2.22 ms | 0 / 6122 | 0 | ✅ |
| 200 rps | 4.91 ms | 1.83 ms | 0 / 12121 | 0 | ✅ |
| 400 rps | 3.36 ms | 1.36 ms | 0 / 24122 | 0 | ✅ |
| 800 rps | 20.2 ms | 11.11 ms | 0 / 48084 | 26 | ✅ |
| **1200 rps** | **117.91 ms** | 72.94 ms | **0 / 71675** | 447 | ⚠️ **qualified — see below** |
| 1600 rps | 6.59 s | 4.85 s | 0 / 56079 | 40045 (65%) | ❌ p95 budget broken; heavy k6-side starvation on 2 shared cores |

**Sustained-throughput evidence (CodeRabbit #241 qualification): the 1200 rps
arrival rate was NOT fully sustained — k6 dropped 447 of 72,122 scheduled
iterations (0.6%) because the load generator shared the same two CPUs as the
application, so the run cannot separate application capacity from k6-side VU
starvation.** What the run does prove: **71675 requests were actually served in
the 60 s window (~1194 rps sustained) with p95 ≈ 118 ms and zero HTTP
failures**, and every served request succeeded at every rate — the failure
mode beyond that point is latency, never errors. Treat ~1200 rps as the
measured single-replica serving floor with the k6-co-location caveat, NOT as
a clean sustained-arrival ceiling: a replica-capacity decision from a clean
launch rate needs a rerun with an **isolated load generator**. Until then the
dashed-iterations budget rule applies: any run that drops iterations is
reported as qualified (⚠️), never as a plain ceiling.

### Sustained run (stability proof)

600 rps × 180s = **108,361 requests, 0 failures, p95 = 6.43 ms**
(median 0.93 ms). Liveness stayed `UP` throughout; the app log shows zero
application errors (only the known OTEL export-refused noise of the A1
gate state).

## Bottleneck reading

- **Cache-hit read path** (the steady-state shape measured here): Redis
  GET + JDK deserialization + JSON serialization — the knee at ~1200 rps
  on 2 shared cores. HikariCP (pool 20) is not the constraint for the
  cached read surface; it becomes the constraint for cold-miss and write
  paths (not in this baseline's scope).
- **Probes stay fast under overload**: liveness p95 = 3.54 ms even while
  read-surface requests wait/429 — the platform keeps its health signal
  during traffic storms.
- **The 1600 rps cliff is steep** (p95 118 ms → 6.6 s): capacity planning
  should treat 1200 as the operating ceiling with headroom below it
  (600 rps sustained with 6 ms p95 is a comfortable operating point).

## How to reproduce

```bash
# 1. App up in the measured shape (production defaults + raised limiter):
#    DB_URL=… RESILIENCE4J_RATELIMITER_INSTANCES_CATALOG_LIMIT_FOR_PERIOD=100000 \
#    RESILIENCE4J_RATELIMITER_INSTANCES_SEARCH_LIMIT_FOR_PERIOD=100000 \
#    RESILIENCE4J_RATELIMITER_INSTANCES_CATALOG_TIMEOUT_DURATION=0s \
#    RESILIENCE4J_RATELIMITER_INSTANCES_SEARCH_TIMEOUT_DURATION=0s \
#    java -jar marketplace-app/target/marketplace-app-0.1.0-SNAPSHOT.jar
# 2. Cold cache: redis-cli FLUSHALL (optional — first window shows the
#    rebuild cost; steady state is the same)
# 3. Ladder:
k6 run -e RATE=200 -e DURATION=60s load-test/baseline.js
```

Numbers are reproducible in spirit, not in the digit: CPU count, host
sharing, and data volume all move the absolute values. The methodology
(the harness + the thresholds + the ladder) is the durable artifact.

## Follow-ups (gated, not part of this layer)

- **A1 (user gate)**: with a real OTLP provider, re-measure per-path
  percentiles from the production side and re-evaluate the limiter
  budgets against real traffic (the current 50/30 per minute are the
  as-shipped defaults).
- **Cold-miss stampede / write paths** (bookings, checkout): a separate
  scenario set once observability (A1) makes the miss/hit split visible.
- **Phase C (replicas)**: the C-phase decision now has its baseline
  number — a second replica is justified only when sustained load
  approaches ~1200 rps on equivalent hardware. **Readiness is proven,
  not assumed**: `MultiReplicaReadinessIntegrationTest` (PR #234) boots
  two full application contexts against the same PostgreSQL + Redis and
  pins the cross-replica invariants (rolling-deploy boot, login-flow
  failover through the shared session/SAS state and JKS keystore,
  cross-instance cache serve) in CI — the replica count itself stays a
  user/platform (paid) decision behind this measured number.
