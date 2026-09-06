# Phase 0 Baseline Checklist

Use this checklist before implementing any new backend module.

## Environment and compatibility
- [x] Java version is compatible with current Spring Boot version.
- [x] Maven version meets project minimum requirements.
- [x] No unmanaged or duplicated dependency versions were introduced.

## Build and test gates
- [x] `mvn clean verify` passes.
- [x] `mvn -pl marketplace-app -am test` passes.
- [x] Modulith verification test passes.
- [x] Architecture rules test passes.

## Local runtime gates
- [ ] `docker compose up -d postgres redis` healthy (compose service `postgres` = `postgres:18-alpine`; since the 2026-09-06 compose fix the named volume `pgdata18` mounts the PG18 data root `/var/lib/postgresql` — the versioned cluster lives at `/var/lib/postgresql/18/docker`).
- [ ] **Local PostgreSQL 17 → 18 data carry-over (CodeRabbit #241 note):** an older local `pgdata` volume (mounted at the pre-18 path `/var/lib/postgresql/data`) does NOT migrate by changing the image or the mount — the cluster is initialized fresh under `pgdata18`. To carry data over, dump/restore (`pg_dump -Fc` on the old container → `pg_restore` into the new one) or run `pg_upgrade` with both clusters available; for a throwaway dev dataset, simply `docker volume rm` the old `pgdata` and let 18 initialize.
- [ ] `mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev`

## Governance gates
- [x] New work item references official Spring/Maven sources.
- [x] Planned changes are scoped to one feature PR.
- [x] DB changes include a new migration file (if applicable).
- [x] Security changes include authorization rules + negative access tests.

## PR readiness
- [ ] `.github/pull_request_template.md` fully completed.
- [ ] Tests and commands output captured in PR body.
- [ ] Any deviation from official docs is explicitly documented.
