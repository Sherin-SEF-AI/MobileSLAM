# ADR 0001 — Record architecture decisions

- Status: Accepted
- Date: 2026-06-19

## Context

MapPilot makes a number of hard, long-lived technical decisions (timebase,
camera path, recording format, on-device inference, DB, rendering). We need a
durable, reviewable record of *why* each was made.

## Decision

Use lightweight Architecture Decision Records, one Markdown file per decision in
`docs/adr/`, numbered sequentially. Each records context, decision, and
consequences. `ARCHITECTURE.md` stays the living system map; ADRs are the
immutable rationale.

## Consequences

- Decisions are traceable and challengeable.
- Superseded decisions are kept (status `Superseded by NNNN`), not deleted.
