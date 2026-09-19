# Which Forge is vendored here

`FORGE_VERSION` holds the upstream tag this engine is pinned to. The upgrade workflow reads
it, compares it against the newest `forge-X.Y.Z` tag upstream, and proposes an upgrade when
they differ.

## Why it currently says `pre-forge-2.0.12`

`forge-engine/pom.xml` says `<versionCode>2.0.12</versionCode>`, which looks like the answer
but is not. Forge's `master` carries the *upcoming* version number, so a snapshot taken from
master while it read 2.0.12 is somewhere *before* the `forge-2.0.12` tag, not at it.

Compared against tag `forge-2.0.12` by git blob hash, the vendored tree differs in 255 files
and is missing 10 that upstream deleted. The giveaway is `CardSplitType.java`: tag 2.0.12
already has the `Prepare` constant and this copy does not — which is precisely why upstream's
current card scripts abort the card database against this engine.

So the honest pin is "an unreleased master snapshot from before 2.0.12". Any value that does
not match an upstream tag makes the workflow treat the engine as out of date, which is
correct here. After the first successful upgrade this file will hold a real tag.

## What is and is not modified

The four module directories — `forge-core`, `forge-game`, `forge-ai`, `forge-gui` — are
unmodified upstream code. No file in them mentions Undercroft, and nothing has been edited
since the original import (the one exception, `[TriggerDebug]` tracing added to
`TriggerHandler.java` in July 2026, was reverted).

`forge-engine/ENGINE_BASELINE.txt` records a content hash of every one of those 1,532 files.
CI verifies it on every push, and the upgrade workflow refuses to overwrite anything if it
does not match — so a patch to the engine can never be silently destroyed by an upgrade.

Two things *are* ours, and upgrades preserve both:

- **`forge-engine/pom.xml`** — the `<modules>` list is cut to the four modules a headless
  server needs, dropping `forge-gui-desktop`, `forge-gui-mobile` and the rest. An upgrade
  copies across only the version number, never the file.
- **`forge-server/`** — the entire bridge: `BridgePlayerController`, `GameStateSerializer`,
  `GameEventForwarder`, `ForgeInit`, `ForgeServer`. Upgrades never touch this directory.

If you ever do need to patch the engine itself, put the change in, run
`scripts/engine-baseline.sh write`, and say why in the commit message — knowing the next
upgrade will replace it, because it replaces the module wholesale.
