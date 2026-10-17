# 0007 — Platform versions

- **Status:** Accepted
- **Date:** 2026-10-17

## Context
The plans were written assuming Java 21, Spring Boot 3 and React 18. When implementation started (October 2026), Spring Initializr only offered Spring Boot 4.x, and the installed JDK was 25 (an LTS release).

## Decision
| Area | Version |
|---|---|
| Java | 25 (Gradle toolchain) |
| Spring Boot | 4.1.x (Spring Framework 7, Jackson 3, modular starters) |
| Spring Cloud | 2025.1.x (Gateway Server WebFlux) |
| Gradle | 9.8 (wrapper) |
| Testcontainers | 2.x (via the Spring Boot BOM) |
| Postgres / Kafka | 16 / 4.1 (KRaft) |
| React | 19 |
| Tooling | Vite 7, React Router 7, TanStack Query 5, Tailwind 4, TypeScript 5.9 |
| Package manager | pnpm 10 |

Frontend packages were held one major version behind the newest release (for example Vite 7 instead of 8, TypeScript 5.9 instead of 7) because the newest majors were only weeks old and their ecosystem plugins had not caught up yet.

## Consequences
- Some names differ from older tutorials. Examples: the `spring-boot-starter-webmvc` starter, Jackson's `tools.jackson.*` packages, and the `spring.cloud.gateway.server.webflux.*` properties.
- Upgrading the frontend to the newest majors is a later chore.
