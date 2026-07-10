# OpenRocket Airbrake Extension

A [SimulationExtension](https://github.com/openrocket/openrocket) plugin for
[OpenRocket](https://openrocket.info/) that models a drag-brake ("airbrake")
deploying after motor burnout, so you can check whether your airbrake design
can shed enough energy to hit a target apogee — without needing to redesign
it as a placeable rocket component.

It adds:

- A configurable drag brake: deploy trigger (launch, burnout, or apogee),
  delay, deploy/slew duration, exposed area, and drag coefficient.
- An **enabled** checkbox so the same simulation can be re-run with and
  without the brake.
- A **Compare with / without airbrake…** button that runs the no-brake
  case, the fully-deployed brake case, and (if enabled) a closed-loop
  target-apogee run, and overlays all three altitude-vs-time curves on one
  chart with a reference line at your target apogee — OpenRocket's own plot
  dialog only ever shows one simulation at a time.
- Optional **closed-loop target-apogee control**: instead of a fixed
  open-loop deploy ramp, the brake predicts (every simulation step) the
  apogee reachable at 0% and 100% deployment from the current
  altitude/velocity/mass/air density, and modulates deployment between
  those bounds — slew-rate-limited by the actuator's deploy duration — to
  steer toward a target altitude. This is the same closed-form ballistic
  apogee-prediction approach real electronic active-drag-control (ADC)
  flight computers use.

## Limitations

- Modeled for **flat, disk-shaped airbrake paddles/plates** presented flat
  to the airflow. Other shapes (curved, cupped, etc.) are untested and may
  not fit the same drag-coefficient assumptions.
- Deploy trigger can be set to **launch**, burnout, or apogee. Triggering
  at launch extends the brake during powered ascent, which can shift the
  center of pressure and reduce stability margin — check stability
  carefully before flying with this trigger.
- The comparison graph only shows achievable apogee — it does not model
  full mission timing (launch rod clearance, descent, ground hit). Run the
  full simulation separately (OpenRocket's own Plot/Export) to determine
  flight duration and event timing.
- Assumes the mass of the airbrake mechanism is already accounted for
  elsewhere in your rocket design — it is not added by this extension.
- Only tested against OpenRocket 24.12.

## Installation

### 1. Get the plugin jar

Either download a prebuilt jar from this repo's
[Releases](../../releases) page, or build it yourself:

```bash
git clone https://github.com/ivarhak/openrocket-airbrake
cd openrocket-airbrake
./gradlew jar
```

The first build downloads OpenRocket's release jar (~80 MB, needed only to
compile against — it is never bundled into the plugin) into `libs/`
automatically. The built plugin appears at
`build/libs/airbrake-extension-0.1.0.jar`.

### 2. Copy it into OpenRocket's plugin folder

OpenRocket only scans this folder at startup, so create it if it doesn't
exist yet and drop the jar in:

| OS      | Plugin folder                                    |
|---------|---------------------------------------------------|
| macOS   | `~/Library/Application Support/OpenRocket/Plugins/` |
| Windows | `%APPDATA%\OpenRocket\Plugins\`                     |
| Linux   | `~/.openrocket/Plugins/`                            |

### 3. Open OpenRocket

Plugins are only discovered at startup, so if OpenRocket is already
running, **fully quit it first** (not just close the window) before
opening it again. Once it's open, the extension is installed — see
[Usage](#usage) below to add it to a simulation.

## Usage

1. Open a rocket, add a simulation, and open its **Edit simulation** dialog.
2. Under the simulation's extensions/options section, click **Add
   extension**, find **Airbrake** under the **Aerodynamics** category, and
   add it.
3. Configure:
   - **Deploy trigger** — launch, burnout, or apogee.
   - **Deploy delay after trigger** — seconds to wait before starting to
     extend.
   - **Deploy/slew duration (0→100%)** — how fast the actuator can move,
     used both as the fixed-schedule ramp time and as the closed-loop
     controller's maximum slew rate.
   - **Exposed area at full deployment** and **drag coefficient** — the
     brake's maximum authority (a flat plate normal to the flow is
     typically Cd ≈ 1.0–1.3).
   - Optionally, **closed-loop target-apogee control** and a **target
     apogee** to have the brake actively steer toward a specific altitude
     instead of just ramping to 100%.
4. Click **Compare with / without airbrake…** to see the achievable apogee
   range and (if a target is set) whether it's reachable.

## Why area *and* Cd, not just one added CD?

Drag force only depends on the product `Cd × A` ("drag area"), so a single
combined number would be mathematically sufficient. The two are kept
separate because they're independent physical facts about your brake: area
is a geometry fact you can measure off a CAD model, while Cd is a
roughly-constant shape fact. As the paddles physically extend, it's the
*area* that ramps — the shape's Cd stays essentially constant across
deployment — so only the area term is time-varying internally, avoiding a
squared (rather than linear) ramp that double-applying both would cause.

## License

This project's own code is MIT-licensed (see [LICENSE](LICENSE)).
OpenRocket itself is licensed under the GPLv3 — see the
[OpenRocket repository](https://github.com/openrocket/openrocket) for its
license. This repository does not bundle or redistribute any OpenRocket
code; it only compiles against and runs inside a separately-installed copy
of OpenRocket via its public plugin API.

IvarHak 2026 
With help from Claude Code
