# Agent Instructions

Before working on this project:

- Always read `SYMPTOM_GRAPH_PLAN.md` first.
- Always read `docs/session-handoff.md` after the project plan.
- Treat `SYMPTOM_GRAPH_PLAN.md` as the source of truth for milestones and scope.
- Treat `docs/session-handoff.md` as the cross-session handoff note only. It can summarize current status, next entry points, verification results, and dirty-worktree warnings, but it must not override `SYMPTOM_GRAPH_PLAN.md`.
- If `docs/session-handoff.md` conflicts with `SYMPTOM_GRAPH_PLAN.md`, follow `SYMPTOM_GRAPH_PLAN.md` and update the handoff note when appropriate.
- Do not implement later milestones unless explicitly requested.
- Before starting implementation, run `git status --short` and account for existing uncommitted changes without reverting user work.
- Before code changes, identify the current milestone and next unchecked task.
- After completing work, update `SYMPTOM_GRAPH_PLAN.md` when progress, decisions, or blockers change.
- At the end of a session, update `docs/session-handoff.md` with current status, completed work, verification results, known uncommitted changes, and the recommended next step.
- During review, build, test, or debug work, compare findings against the current milestone and project plan.
