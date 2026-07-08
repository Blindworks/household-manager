---
name: project-angular-version
description: Household-Manager frontend actually runs Angular 19, not Angular 21 as the agent persona framing suggests
metadata:
  type: project
---

The agent system prompt describes an "Angular 21 Frontend Developer" persona, but this project
(Household-Manager, `frontend/`) is on Angular 19 (`"@angular/core": "^19.0.0"` in
`frontend/package.json`, also stated in root `CLAUDE.md`).

**Why:** CLAUDE.md is checked into the repo and is authoritative project documentation; the
persona's version number is generic framing, not a project fact.

**How to apply:** Always verify actual Angular version via `frontend/package.json` before assuming
Angular 21-only APIs are available. Standalone components, `inject()`, and the new `@if`/`@for`
control-flow syntax are all fine on Angular 19 (control flow shipped in v17). Don't assume
Angular 21-specific APIs exist without checking — Angular 19 lacks some newer signal/resource APIs.
Existing codebase does not yet use `@if`/`@for` control flow in the files reviewed so far
(weather.service.ts is a service, no template) — check existing sibling templates for the
prevailing style (`*ngIf` vs `@if`) before introducing a new pattern in a shared area.
