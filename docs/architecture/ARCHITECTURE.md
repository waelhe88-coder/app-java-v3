# Architecture Document — Marketplace Backend (app-java-v3)

> **Status:** Living document • Last updated: 2026-06-20
> **Audience:** Architects, senior developers, DevOps engineers
> **Scope:** End-to-end system architecture, module boundaries, event flow, deployment topology, security model

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [System Overview (5-tier architecture)](#2-system-overview)
3. [Module Architecture (Spring Modulith)](#3-module-architecture)
4. [Event-Driven Data Flow](#4-event-driven-data-flow)
5. [Cloudflare-First Deployment](#5-cloudflare-first-deployment)
6. [Security &amp; Authentication](#6-security--authentication)
7. [Technology Stack](#7-technology-stack)
8. [Architectural Decisions (ADRs)](#8-architectural-decisions)
9. [Quality Attributes](#9-quality-attributes)

---

## 1. Executive Summary

The Marketplace Backend is a **modular monolith** built on **Spring Boot 4.1.0 + Java 25 LTS** with **Spring Modulith 2.1.0** enforcing bounded contexts. It comprises **16 Maven modules** organized in 5 layers (composition root → infra → shared contracts → domain core → domain support).

**Key characteristics:**
- ✅ **Modular monolith** (not microservices) — operational simplicity, single deployment unit
- ✅ **Event-driven** — Spring Modulith Event Publication Log (JDBC) with automatic retry
- ✅ **Security-hardened** — 26 fixes across PRs #152–#155, verified against RFCs + OWASP + NIST
- ✅ **Cloudflare-first** — designed for Cloudflare Containers + Workers + Neon + Upstash
- ✅ **Verified boundaries** — `ApplicationModules.of(...).verify()` runs in CI (ArchUnit 1.4.2)
- ✅ **70%+ test coverage** — JaCoCo enforced threshold on all domain modules

---

## 2. System Overview

![System Architecture Overview](diagrams/01-system-overview.png)

### Five-Tier Architecture

| Tier | Purpose | Hosting | Components |
|------|---------|---------|------------|
| **1. Client** | End-user devices | Browser / mobile | React SPA, Admin Console, OAuth2 providers |
| **2. Edge** | Global CDN + edge compute | Cloudflare (300+ POPs) | Worker (proxy), Hyperdrive, R2, Pages |
| **3. Application** | Business logic | Cloudflare Container | Spring Boot 4.1 (16 modules) |
| **4. Data** | Persistence | External managed | Neon (PostgreSQL), Upstash (Redis), SES (SMTP) |
| **5. Observability** | Cross-cutting monitoring | Mixed | Cloudflare Analytics, Prometheus, audit log |

### Request Flow

```
User → HTTPS → CF Worker (proxy + CORS) → HTTP → CF Container (Spring Boot)
                                                          ↓
                                  Hyperdrive → Neon (PostgreSQL)
                                  REST API   → Upstash (Redis)
                                  SMTP       → Amazon SES
```

**Latency budget:**
- User → Worker: ~20-40ms (edge POP)
- Worker → Container: ~5-15ms (same region)
- Container → Neon (via Hyperdrive): ~10-30ms (pooled)
- **Total p50:** ~50-100ms for cached reads

---

## 3. Module Architecture

![Module Architecture](diagrams/02-module-architecture.png)

### Layer Organization (L0 → L4)

```
L4: marketplace-app              ← Composition root (REST, admin, bootstrap)
L3: marketplace-platform-infra   ← Cross-cutting (JPA, Security, Cache, Observability)
L2: marketplace-shared           ← API contracts (SPIs, events, exceptions, DTOs)
L1: 13 domain modules            ← Bounded contexts (each owns its data + logic)
```

### The 16 Modules

| # | Module | Layer | Role | Key Artifacts |
|---|--------|-------|------|---------------|
| 1 | `marketplace-shared` | L2 | API contracts | Port interfaces, event records, exceptions |
| 2 | `marketplace-platform-infra` | L3 | Infra | JPA, Security, Cache, Observability, Email, JWK keystore signing |
| 3 | `marketplace-identity` | L1 | Domain core | Users, auth, MFA/2FA, OAuth2, brute-force |
| 4 | `marketplace-catalog` | L1 | Domain core | Listings, GraphQL, CatalogSpi |
| 5 | `marketplace-booking` | L1 | Domain core | Bookings, expiration, 3 events |
| 6 | `marketplace-payments` | L1 | Domain core | Payment intents, refunds, webhooks |
| 7 | `marketplace-provider` | L1 | Domain core | Provider profiles, verification |
| 8 | `marketplace-ledger` | L1 | Domain core | Double-entry ledger, balances |
| 9 | `marketplace-pricing` | L1 | Domain core | Pricing rules, ListingPriceProvider |
| 10 | `marketplace-reviews` | L1 | Domain core | Reviews, ratings, ReviewUpdatedEvent |
| 11 | `marketplace-disputes` | L1 | Domain core | Disputes, resolution workflow |
| 12 | `marketplace-availability` | L1 | Domain core | Slots, rules, @ApplicationModuleListener |
| 13 | `marketplace-messaging` | L1 | Domain support | Conversations, WebSocket STOMP |
| 14 | `marketplace-notifications` | L1 | Domain support | Email + WS dispatch, event listeners |
| 15 | `marketplace-search` | L1 | Domain support | Full-text search |
| 16 | `marketplace-app` | L4 | Composition | @SpringBootApplication, Admin REST |

### Spring Modulith Boundaries

Each module declares its `@ApplicationModule(allowedDependencies = {...})` in `package-info.java`:

```java
// Example: marketplace-booking
@org.springframework.modulith.NamedInterface("booking")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
package com.marketplace.booking;
```

**Rule:** Domain modules may only depend on `marketplace-shared`'s named interfaces (shared-api, shared-security, shared-jpa). They **cannot** depend on each other directly — cross-module communication goes through SPIs in `shared` or via events.

**Verification:** `ModulithVerificationTest.verifyModulesAndWriteDocs()` runs `ApplicationModules.of(MarketplaceApplication.class).verify()` in CI. Any violation fails the build.

---

## 4. Event-Driven Data Flow

![Event-Driven Data Flow](diagrams/03-event-driven-flow.png)

### Spring Modulith Event Publication Log

> **Official doc** (https://docs.spring.io/spring-modulith/reference/events.html):
> "Each transactional event listener is wrapped into an aspect that marks that log entry as completed if the execution of the listener succeeds. In case the listener fails, the log entry stays untouched so that retry mechanisms can be deployed."

**How it works:**
1. Business transaction publishes event → entry written to `spring_modulith_event_publications` table (same transaction)
2. `@TransactionalEventListener` / `@ApplicationModuleListener` executes after commit
3. On success → log entry marked COMPLETED
4. On failure → log entry stays UNCOMPLETED → republished on next restart (if `spring.modulith.events.republish-outstanding-events-on-restart=true`)

### Key Event Flows

#### Flow 1 — Booking Lifecycle
```
POST /api/v1/bookings → BookingService.createBooking()
    → publishes BookingCreatedEvent
        → NotificationService.onBookingCreated() [email + WS]
        → AvailabilityService.reserveSlot()
    → (later) PaymentSucceeded event
        → BookingService.confirm() → publishes BookingConfirmedEvent
```

#### Flow 2 — Payment + Auto-Refund
```
Stripe webhook → PaymentsService.confirmIntent()
    → publishes PaymentStateChangedEvent
        → NotificationService.onPaymentState()
    → (on cancellation) BookingCancelledEvent
        → PaymentsService.autoRefundByBooking() [@Transactional(REQUIRES_NEW)]
            → PaymentIntent.markRefunded() + paymentRepository.save()
```

#### Flow 3 — Availability Slot Generation (time-driven)
```
spring-modulith-moments → publishes DayHasPassed (daily)
    → AvailabilityService.onDayHasPassed() [@ApplicationModuleListener]
        → generateSlotsForDate() for next 7 days
            → for each rule: try createSlot()
            → catch DataAccessException only (programming errors propagate for retry)
```

#### Flow 4 — Two-Step Login with MFA
```
POST /login/step1 → verify password → issue mfaToken (Redis, TTL 5min)
POST /login/step2 → verify TOTP (constant-time + replay guard) OR recovery code
    → publish LOGIN_SUCCESS → AuthAuditService.log()
    → issue JWT (JWKSource — persistent JKS keystore in prod, runbook `keys/README.md`)
```

### Exception Handling Policy

| Listener | Catch | Rationale |
|----------|-------|-----------|
| `BookingPaymentEventListener` | None — propagate | Let retry mechanism handle failures |
| `BookingCancelledEventListener` | None — propagate | Same |
| `BookingExpirationService` | None — propagate | Same |
| `NotificationEventListener` | None — propagate | Same |
| `AvailabilityService.generateSlotsForDate` | `DataAccessException` only | Best-effort per rule; programming errors propagate |

---

## 5. Cloudflare-First Deployment

![Cloudflare Deployment](diagrams/04-cloudflare-deployment.png)

### Design Principle

**Cloudflare hosts everything it can. External services are used only where Cloudflare has no managed equivalent.**

### Component Matrix

| Component | Cloudflare Service | Status | Notes |
|-----------|-------------------|--------|-------|
| **Spring Boot app** | Cloudflare Containers | Requires Workers Paid ($5/mo) | `eclipse-temurin:25-jre-alpine`, `standard-1` instance |
| **Worker (proxy)** | Cloudflare Workers | Ready | Routes `/api/*` → Container, adds CORS |
| **PostgreSQL** | Neon (external) + Hyperdrive | External | CF has no managed Postgres; Hyperdrive pools connections |
| **Redis** | Upstash (external) | External | CF has no managed Redis; KV is not Redis-compatible |
| **File storage** | Cloudflare R2 | Ready | S3-compatible, zero egress to CF services |
| **Frontend** | Cloudflare Pages | Ready | React SPA + Admin Console |
| **Edge rate-limit** | Cloudflare KV | Ready | For Worker-level rate limiting |
| **Container lifecycle** | Durable Objects | Requires Workers Paid | Container class extends DurableObject |

### Topology

```
┌─────────────┐     HTTPS      ┌─────────────┐    HTTP (internal)    ┌─────────────────┐
│ User Browser │ ──────────── → │ CF Worker   │ ──────────────────→ │ CF Container    │
│  (React SPA) │                │ (Proxy+CORS)│                      │ (Spring Boot 4.1)│
└─────────────┘                └─────────────┘                      └─────────────────┘
                                       │                                      │
                                       │ Hyperdrive                           │ REST
                                       ▼                                      ▼
                               ┌─────────────┐                      ┌─────────────────┐
                               │ Neon        │ ◄────── JDBC ──────  │ Upstash         │
                               │ (PostgreSQL)│                      │ (Redis REST)    │
                               └─────────────┘                      └─────────────────┘
```

### Cold-Start Considerations

> **Cloudflare docs:** "Container cold starts can often be in the 1-3 second range, but this is dependent on image size and code execution time."

**For Spring Boot 4.1 + Java 25:** Expect **5-15s cold start** (JVM startup + Spring context init).

**Mitigations:**
- `sleepAfter: "30m"` (keep instances warm)
- `instance_type: "standard-1"` (½ vCPU + 4 GiB RAM)
- Optional: enable Spring AOT Cache (see `docs/deployment/aot-cache.md`)
- No built-in autoscaling yet — use N instances with `getRandom` helper for stateless load balancing

### Implementation Plan (5 phases)

| Phase | Task | Dependency | Est. Effort |
|-------|------|-----------|-------------|
| 0 | Fix Dockerfile (`layertools` → `tools`) on main | None (BLOCKER) | 1 commit |
| 1 | Upgrade Cloudflare account to Workers Paid ($5/mo) | Phase 0 | Account action |
| 2 | Provision Neon (PostgreSQL) + Upstash (Redis) | Phase 0 | 30 min |
| 3 | Deploy Container + Worker (`wrangler.jsonc` with `[[containers]]` + `[[durable_objects.bindings]]`) | Phases 1, 2 | 2-4 hours |
| 4 | Deploy frontend to Pages + wire R2 for uploads | Phase 3 | 1-2 hours |

---

## 6. Security & Authentication

![Security Architecture](diagrams/05-security-auth.png)

### Authentication Mechanisms (4 paths, all converge on JWT)

| Mechanism | Spec | Implementation |
|-----------|------|----------------|
| Password + MFA/2FA | RFC 6238 (TOTP), RFC 4648 (Base32) | TwoStepLoginService, MfaService, TotpService |
| OAuth2 social login | RFC 6749, RFC 8252 (PKCE), RFC 9068 (JWT) | OAuth2LoginSuccessHandler (GitHub + Google) |
| JWT resource server | Spring Security 7 | SecurityConfig, JwtDecoder, JwtRevocationValidator |
| Brute-force protection | OWASP Authentication Cheat Sheet | BruteForceProtectionService, DistributedRateLimiter |

### Security HTTP Headers (applied to both SecurityFilterChains)

| Header | Value | Reference |
|--------|-------|-----------|
| Strict-Transport-Security | max-age=31536000; includeSubDomains | RFC 6797 |
| Content-Security-Policy | default-src 'self'; frame-ancestors 'none'; base-uri 'self'; object-src 'none' | OWASP |
| Permissions-Policy | camera=(), microphone=(), geolocation=(), payment=() | OWASP |
| X-Frame-Options | DENY | Spring Security default |
| X-Content-Type-Options | nosniff | Spring Security default |
| Referrer-Policy | strict-origin-when-cross-origin | Spring Security |

### Key Management

| Asset | Rotation | Storage |
|-------|----------|---------|
| JWT signing key (RSA-2048) | On keytool regeneration (≤90 days, secrets-policy §3) | Immutable `JWKSet` from JKS (`$JWT_KEYSTORE_PATH`) — see `keys/README.md` |
| JWT keystore | On keytool regeneration | JKS file (`$JWT_KEYSTORE_PATH`) |
| OAuth2 client secrets | On provider rotation | Env vars (`GITHUB_CLIENT_SECRET`, `GOOGLE_CLIENT_SECRET`) |
| Dev admin password | N/A (dev only) | `DEV_ADMIN_PASSWORD` env var (dev profile only) |

### Audit & Observability

- **AuthAuditService** records 10 event types (LOGIN_SUCCESS/FAILURE, MFA_FAILURE, RECOVERY_CODE_USED, ACCOUNT_LOCKED/UNLOCKED, LOGOUT, SESSION_REVOKED, TOKEN_REVOKED, OAUTH2_LOGIN_SUCCESS)
- **Log redaction** — `logback-spring.xml` strips passwords, tokens, JWTs (eyJ...), BCrypt hashes
- **CorrelationIdFilter** validates charset (alphanumeric + dash, max 128) to prevent log injection
- **Trusted proxy** — `server.forward-headers-strategy=framework` + trust only `request.getRemoteAddr()`

---

## 7. Technology Stack

### Core Framework (non-negotiable)

| Component | Version | Reference |
|-----------|---------|-----------|
| Java | 25 LTS | https://openjdk.org/projects/jdk/25/ |
| Spring Boot | 4.1.0 GA | https://docs.spring.io/spring-boot/ |
| Spring Modulith | 2.1.0 GA | https://docs.spring.io/spring-modulith/ |
| Spring Security | 7.x | https://docs.spring.io/spring-security/ |
| Maven | 3.9.16 | https://maven.apache.org/ |

### Build & Quality

| Tool | Version | Purpose |
|------|---------|---------|
| JaCoCo | 0.8.15 | Coverage (threshold 0.70) |
| ArchUnit | 1.4.2 | Module boundary verification |
| instancio | 6.0.0-RC3 | Test data generation |
| MapStruct | (latest) | DTO mapping |
| Maven Enforcer | (latest) | Dependency convergence |

### Data Layer

| Technology | Version | Usage |
|-----------|---------|-------|
| PostgreSQL | 17 | Primary OLTP (Neon managed) |
| Redis | 7 | Cache, sessions, rate-limit, TOTP replay guard (Upstash managed) |
| Flyway | (Spring-managed) | Migrations (V1–V30) |
| Spring Data JPA | (Spring 4.1) | ORM |

### Infrastructure

| Technology | Usage |
|-----------|-------|
| Docker | Container image build |
| Cloudflare Containers | Hosting |
| Cloudflare Workers | Edge proxy |
| Cloudflare Hyperdrive | PostgreSQL connection pooling |
| Cloudflare R2 | File storage |
| Amazon SES | Transactional email |
| GitHub/Google OAuth2 | Social login |

---

## 8. Architectural Decisions

### ADR-001: Modular Monolith over Microservices

**Context:** 16 bounded contexts with shared data and frequent cross-domain queries.
**Decision:** Single deployable unit (modular monolith) with Spring Modulith enforcing boundaries.
**Rationale:** Operational simplicity (single deployment, single database), while preserving domain separation. Microservices would add distributed transaction complexity without proportional benefit at this scale.
**Trade-off:** Cannot independently scale individual modules. Acceptable for current load.

### ADR-002: Event-Driven Communication

**Context:** Domain modules need to react to each other's state changes without direct coupling.
**Decision:** Spring Modulith Event Publication Log (JDBC-backed, transactional, retryable).
**Rationale:** Events are persisted in the same business transaction → at-least-once delivery → retry on failure. No external message broker needed.
**Reference:** https://docs.spring.io/spring-modulith/reference/events.html

### ADR-003: Cloudflare-First Deployment

**Context:** Need global low-latency hosting with minimal operational overhead.
**Decision:** Cloudflare Containers for app, Workers for edge, Neon for PostgreSQL (via Hyperdrive), Upstash for Redis.
**Rationale:** Cloudflare has no managed Postgres/Redis. Using Neon + Upstash (best-in-class serverless) connected via Hyperdrive/REST keeps the app on Cloudflare while leveraging external managed data stores.
**Trade-off:** Two external vendors (Neon, Upstash) for data tier. Acceptable given Cloudflare's gap.

### ADR-004 (Revised): Persistent JWK Source for JWT Signing

**Status:** supersedes the original ADR-004 ("RotatingJWKSource", automatic 90-day rotation with active+previous overlap) — **that decision was never implemented**; the shipped code uses an `ImmutableJWKSet` with a single active key. This revision records the implemented design.
**Context:** NIST SP 800-57 recommends cryptoperiod of 1-2 years for asymmetric signing keys; the secrets policy (§3) mandates rotation of signing keys every 30-60 days and service keys every 90 days.
**Decision:** Persistent JKS keystore bound from environment variables (`JWT_KEYSTORE_*`), single active key, loaded by `SecurityConfig.jwkSource()`. Rotation = keytool regeneration + redeploy. Profile-gated fail-fast: `prod` + blank keystore ⇒ startup failure (never a silent ephemeral key) — enforced in CI by `JwkSourceProdHardeningTest`. Runbook: `keys/README.md`.
**Rationale:** Implements governing plan D6/INV-7 ("no ephemeral signing keys outside development"). Simple, auditable, matches `application-prod.yml` env-driven binding.
**Trade-off (documented honestly, CodeRabbit #241 correction):** no active+previous overlap — replacing the key immediately invalidates outstanding **access tokens (TTL 900 s)**, forcing API re-authentication. It does **NOT** revoke persisted refresh tokens: `JdbcOAuth2AuthorizationService` keeps them (7-day TTL, `OAuth2ClientSecretInitializer` line ~204) until natural expiry, so a stolen refresh token remains replayable for new access tokens after a key rotation — revocation of live refresh tokens is an operator action (purge `oauth2_authorization` rows) documented in the runbook, not an automatic effect. Accepted for the current scale; an overlap or a revocation step would be a new documented decision. (The 300 s figure applies to authorization codes, not access tokens.)
**Reference:** https://nvd.nist.gov/800-57 ; `docs/security/auth-system-redesign-plan.md` (D6/INV-7) ; `keys/README.md`

### ADR-005: Catch DataAccessException only in Event Listeners

**Context:** `catch (Exception)` in `@ApplicationModuleListener` swallows all errors → Spring Modulith marks event COMPLETED → no retry.
**Decision:** Narrow catch to `DataAccessException`. Let programming errors propagate.
**Rationale:** Preserves "best-effort per rule" while enabling retry for genuine bugs. Data-access failures (constraint violations) are logged but don't abort the batch.
**Reference:** https://docs.spring.io/spring-modulith/reference/events.html

---

## 9. Quality Attributes

### Reliability
- ✅ Event Publication Log ensures at-least-once event delivery
- ✅ `@Transactional(REQUIRES_NEW)` on auto-refund survives caller rollback
- ✅ DistributedRateLimiter fails closed by default (OWASP "Fail Securely")
- ✅ JaCoCo ≥ 70% enforced on all domain modules

### Security
- ✅ 26 fixes across PRs #152–#155 (RFC + OWASP + NIST compliant)
- ✅ All auth events audited
- ✅ Log redaction for secrets
- ✅ Constant-time TOTP comparison (timing-attack resistant)
- ✅ TOTP replay protection (Redis SETNX, 90s window)
- ✅ Brute-force protection (atomic counter, 5 attempts → 15min lock)
- ✅ HSTS + CSP + Permissions-Policy + X-Frame-Options

### Maintainability
- ✅ Spring Modulith boundaries verified in CI
- ✅ No `@ConditionalOnBean` cascades (use `@ConditionalOnProperty` + `@Profile`)
- ✅ No reflection in production code (package-private + constructor injection)
- ✅ Every fix cites official documentation in Javadoc

### Operability
- ✅ Actuator health endpoints (`/actuator/health/{liveness,readiness}`)
- ✅ Prometheus metrics (`/actuator/prometheus`)
- ✅ Deploy-time health gate via Railway `healthcheckPath` on `/actuator/health/liveness` (`railway.toml` [deploy]) — no Dockerfile `HEALTHCHECK` by design: the official Spring Boot 4.1 container recipe does not include one and the platform owns deploy gating (corrected 2026-09-04; the old line claimed a Dockerfile HEALTHCHECK that never existed — `git log -S HEALTHCHECK -- Dockerfile` is empty)
- ✅ Structured logging (Logstash format)
- ✅ Correlation IDs propagated across requests

### Scalability
- ⚠️ Single Container instance by default (no autoscaling yet)
- ✅ Stateless (sessions in Redis, files in R2)
- ✅ Horizontal scaling possible via multiple Container instances + `getRandom` routing
- ✅ Hyperdrive pools DB connections across Worker instances

---

## Appendix: Diagrams

All diagrams are in `docs/architecture/diagrams/`:
- [01-system-overview.html](diagrams/01-system-overview.html) / [.png](diagrams/01-system-overview.png)
- [02-module-architecture.html](diagrams/02-module-architecture.html) / [.png](diagrams/02-module-architecture.png)
- [03-event-driven-flow.html](diagrams/03-event-driven-flow.html) / [.png](diagrams/03-event-driven-flow.png)
- [04-cloudflare-deployment.html](diagrams/04-cloudflare-deployment.html) / [.png](diagrams/04-cloudflare-deployment.png)
- [05-security-auth.html](diagrams/05-security-auth.html) / [.png](diagrams/05-security-auth.png)

## Appendix: References

### Spring Framework (mandatory)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/)
- [Spring Boot — How to Deploy](https://docs.spring.io/spring-boot/how-to/deployment/index.html) ⭐ mandatory
- [Spring Boot — Container Images](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)
- [Spring Boot — AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)
- [Spring Modulith Reference](https://docs.spring.io/spring-modulith/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Spring Security — What's New (7.1)](https://docs.spring.io/spring-security/reference/whats-new.html) ⭐ mandatory
- [Spring Security — What's New (7.0)](https://docs.spring.io/spring-security/reference/7.0/whats-new.html)
- [Spring Security — Migration to 7.0](https://docs.spring.io/spring-security/reference/migration/index.html)
- [Spring Security — Headers](https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html)
- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway) ⭐ mandatory
- [Spring Cloud Gateway — Reference](https://docs.spring.io/spring-cloud-gateway/reference/)
- [Spring Guides](https://spring.io/guides)

### Maven (mandatory)
- [Maven Guides Index](https://maven.apache.org/guides/index.html) ⭐ mandatory
- [Maven Build Lifecycle](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)
- [Maven Enforcer Plugin](https://maven.apache.org/enforcer/)

### Cloudflare
- [Cloudflare Containers](https://developers.cloudflare.com/containers/)
- [Cloudflare Workers](https://developers.cloudflare.com/workers/)
- [Cloudflare Hyperdrive](https://developers.cloudflare.com/hyperdrive/)

### External Managed Services
- [Neon PostgreSQL](https://neon.tech/docs)
- [Upstash Redis](https://docs.upstash.com/redis)

### Standards
- [OWASP Cheat Sheets](https://cheatsheetseries.owasp.org/)
- [NIST SP 800-63B](https://pages.nist.gov/800-63-3/sp800-63b.html) (Authentication)
- [NIST SP 800-57](https://nvd.nist.gov/800-57) (Key Management)
- [RFC 6238](https://datatracker.ietf.org/doc/html/rfc6238) (TOTP)
- [RFC 6749](https://datatracker.ietf.org/doc/html/rfc6749) (OAuth 2.0)
- [RFC 8252](https://datatracker.ietf.org/doc/html/rfc8252) (PKCE)
- [RFC 9068](https://datatracker.ietf.org/doc/html/rfc9068) (JWT for OAuth 2.0)
- [RFC 6797](https://datatracker.ietf.org/doc/html/rfc6797) (HSTS)
- [RFC 7009](https://datatracker.ietf.org/doc/html/rfc7009) (Token Revocation)
- [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) (Problem Details)
