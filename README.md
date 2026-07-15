# cloud-itonami-isco-9313

Open Occupation Blueprint for **ISCO-08 9313**: Building Construction Labourers.

This repository designs a forkable OSS business for an independent construction labour practice: a material-handling and site-prep robot manages debris clearing and material staging under a governor-gated actor, so the practice keeps its own site records instead of renting a closed labour-staffing SaaS.

**Maturity: `:implemented`.** `src/construction/` implements the
`ConstructionLabourActor` as a `langgraph.graph/state-graph`
(`construction.actor`) wired to a `Labour Advisor`
(`construction.advisor`) and an independent `ConstructionLabourGovernor`
(`construction.governor`), following the itonami actor pattern
(ADR-2607011000): `:intake -> :advise -> :govern -> :decide -+-> :commit
(:ok?) +-> :request-approval (:escalate?, human-in-the-loop interrupt)
+-> :hold (:hard?)`. 14 tests / 30 assertions green (`clojure -M:test`).
HARD invariants (always hold, never overridable): client provenance,
no-actuation (`:effect` must be `:propose`), a registered site basis
for any crew-dispatch proposal, the proposed work zone being a member
of the site's registered marked-zones set (dispatching into an
unmarked zone is an unsurveyed hazard, not efficient scheduling), and
a signed-off safety plan before any crew dispatch (dispatching
without one is an unauthorized deployment, not efficient staffing).
Always-escalate ops (human sign-off regardless of confidence, mapping
this repo's Trust Controls in
[`docs/business-model.md`](docs/business-model.md)):
`:approve-unmarked-hazard-zone-work` and
`:approve-confined-space-entry`.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a material-handling and site-prep robot performs debris clearing, material staging and site-marking under an actor that proposes
actions and an independent **Construction Labour Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
work in an unmarked hazard zone) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
site work order + safety plan + crew assignment
        |
        v
Labour Advisor -> Construction Labour Governor -> dispatch crew/approve task, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `9313`). Required capabilities:

- :robotics
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
