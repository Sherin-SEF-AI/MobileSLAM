# ADR 0003 — Multi-module Gradle with build-logic convention plugins

- Status: Accepted
- Date: 2026-06-19

## Context

The system spans ~25 modules across capture, recording, SLAM, perception,
storage, geo, viz, and cloud. Duplicating Android/Kotlin/Hilt/Compose config in
each `build.gradle.kts` is error-prone and drifts over time.

## Decision

A composite build `build-logic/` defines four convention plugins applied by
modules via the version catalog:

- `mappilot.android.library` — Android library defaults (SDK 35/31, JDK 17,
  Kotlin opts, base test deps).
- `mappilot.android.library.compose` — adds Compose on top.
- `mappilot.kotlin.library` — pure-JVM modules (e.g. `:core:model`).
- `mappilot.android.hilt` — Hilt + KSP wiring.

SDK levels are centralized in `MapPilotSdk`. Dependency versions live in
`gradle/libs.versions.toml`.

## Consequences

- One place to bump SDK/Kotlin/test config.
- `:core:model` stays Android-free (faster, testable on plain JVM).
- New modules are three lines of `build.gradle.kts`.
