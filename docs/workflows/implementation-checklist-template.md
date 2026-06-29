# Implementation Checklist Template

Use this template as the handoff artifact from a Design session to an Implementation session.

## Goal

- <One concrete implementation goal.>

## Current Milestone

- Milestone: <number and title>
- Plan item: <exact unchecked or active task from SYMPTOM_GRAPH_PLAN.md>

## Non-Goals

- <Explicitly list what this task must not implement.>

## Existing Context

- <Relevant current behavior.>
- <Relevant constraints from SYMPTOM_GRAPH_PLAN.md.>
- <Relevant handoff notes or dirty-worktree warnings.>

## Files Or Modules

- `<path>`: <expected role>
- `<path>`: <expected role>

## Implementation Steps

1. <Small, ordered step.>
2. <Small, ordered step.>
3. <Small, ordered step.>

## Tests

- <Unit test or controller test to add/update.>
- <Integration test or manual verification, if needed.>
- Run:

```powershell
mvn test
```

## Acceptance Criteria

- <Observable behavior.>
- <Data stored or returned correctly.>
- <No regression in existing behavior.>
- `mvn test` passes.

## Risks And Notes

- <Concurrency or dirty-worktree risk.>
- <External dependency risk.>
- <Known follow-up that is intentionally deferred.>
