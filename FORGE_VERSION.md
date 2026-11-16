# Which Forge is vendored here

`FORGE_VERSION` holds the upstream tag this engine is pinned to. The upgrade workflow reads
it, compares it against the newest `forge-X.Y.Z` tag upstream, and proposes an upgrade when
they differ.

## History

The first import was an unreleased snapshot of upstream's master from before the
`forge-2.0.12` tag (recorded here as `pre-forge-2.0.12`). The weekly upgrade job then failed
silently for eight weeks on a checkout bug, so sets released after June 2026 said "won't work
in Forge" on import. On 2026-11-16 the engine and card data were brought to `forge-2.0.16` by
hand, following the workflow's steps exactly, together with the bridge changes that release
needed (see below). From here the pin is a real upstream tag and the job compares against it.

## Bridge changes an upgrade can need

The bridge implements two upstream interfaces, `PlayerController` (108 abstract methods) and
`IGuiGame`, and both move between releases. Upstream signature changes land as compile
errors in the upgrade job, which is the loud failure we want: the PR is not opened until the
bridge compiles. 2.0.16 renamed or re-shaped a dozen controller methods (announce bounds now
come from the engine, discard and mulligan prompts take the hand as a view, stickers,
`applyManaToCost`, `chooseCardsForCost`, `getCostDecisionMaker`), made most of `IGuiGame`
default methods, and turned card counters into a multiset. Each needed a few lines in
`forge-server/`, nothing in the engine.

## What is and is not modified

The four module directories — `forge-core`, `forge-game`, `forge-ai`, `forge-gui` — are
unmodified upstream code. No file in them mentions Undercroft, and nothing has been edited
since the original import (the one exception, `[TriggerDebug]` tracing added to
`TriggerHandler.java` in July 2026, was reverted).

`forge-engine/ENGINE_BASELINE.txt` records a content hash of every one of those 1,532 files.
CI verifies it on every push, and the upgrade workflow refuses to overwrite anything if it
does not match — so a patch to the engine can never be silently destroyed by an upgrade.

Two things *are* ours, and upgrades preserve both:

- **`forge-engine/pom.xml`** — upstream's parent pom with the `<modules>` list cut to the
  four modules a headless server needs, dropping `forge-gui-desktop`, `forge-gui-mobile` and
  the rest. An upgrade copies upstream's pom (and the `checkstyle.xml` beside it) and re-cuts
  the modules; it cannot carry only the version number across, because the module poms name
  their parent's exact version and everything else they rely on (properties, dependency and
  plugin management) must match. The bridge's `forge-server/pom.xml` has its `forge.version`
  pointed at the new parent version in the same step.
- **`forge-server/`** — the entire bridge: `BridgePlayerController`, `GameStateSerializer`,
  `GameEventForwarder`, `ForgeInit`, `ForgeServer`. Upgrades never touch this directory.

If you ever do need to patch the engine itself, put the change in, run
`scripts/engine-baseline.sh write`, and say why in the commit message — knowing the next
upgrade will replace it, because it replaces the module wholesale.
