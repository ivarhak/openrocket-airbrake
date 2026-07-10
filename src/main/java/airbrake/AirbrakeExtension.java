package airbrake;

import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.AbstractSimulationExtension;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;
import info.openrocket.core.util.MathUtil;

/**
 * Adds a configurable drag-brake effect to a simulation: extra drag area
 * that ramps in linearly, starting some delay after a trigger event (motor
 * burnout or apogee). Assumes the mass of the airbrake mechanism is already
 * accounted for in the rocket design, so it isn't added here.
 * <p>
 * Modeled as an added frontal area + a drag coefficient for that area
 * (converted internally to an added CD referenced to the rocket's own
 * reference area), rather than as a directly-configured added CD, because
 * the physical thing that changes as the paddles extend is the exposed
 * area, not the shape's drag coefficient.
 * <p>
 * Optionally, instead of a fixed open-loop deployment ramp, the brake can
 * run closed-loop: each step it predicts the apogee achievable at 0% and
 * 100% deployment (from current altitude/velocity/mass/air density via the
 * standard constant-drag ballistic closed form), and picks a deployment
 * fraction between those bounds to steer toward a configured target apogee,
 * slew-rate-limited by the same "deploy duration" used as the actuator's
 * 0->100% travel time.
 */
public class AirbrakeExtension extends AbstractSimulationExtension {

	private static final double GRAVITY = 9.80665;

	public enum TriggerEvent {
		BURNOUT, APOGEE
	}

	@Override
	public String getName() {
		if (!isEnabled()) {
			return "Airbrake (disabled)";
		}
		if (isTargetApogeeControlEnabled()) {
			return String.format("Airbrake (target %.0f m, %s +%.1fs)",
					getTargetApogeeM(), getTriggerEvent(), getDeployDelay());
		}
		return String.format("Airbrake (%.0f cm² @ Cd %.2f, %s +%.1fs)",
				getExposedAreaM2() * 1e4, getDragCoefficient(), getTriggerEvent(), getDeployDelay());
	}

	@Override
	public String getDescription() {
		return "Simulates a drag brake that extends after a trigger event (burnout or apogee). " +
				"Either ramps open on a fixed schedule, or (if target-apogee control is enabled) " +
				"closes the loop each step to steer toward a target apogee. Use the 'enabled' " +
				"checkbox to quickly compare a flight with and without the brake.";
	}

	@Override
	public void initialize(SimulationConditions conditions) throws SimulationException {
		if (isEnabled()) {
			conditions.getSimulationListenerList().add(new AirbrakeListener());
		}
	}

	// ---- Configuration (backed by the extension's Config object) ----

	public boolean isEnabled() {
		return config.getBoolean("enabled", true);
	}

	public void setEnabled(boolean enabled) {
		config.put("enabled", enabled);
		fireChangeEvent();
	}

	public TriggerEvent getTriggerEvent() {
		return TriggerEvent.valueOf(config.getString("triggerEvent", TriggerEvent.BURNOUT.name()));
	}

	public void setTriggerEvent(TriggerEvent event) {
		config.put("triggerEvent", event.name());
		fireChangeEvent();
	}

	/** Delay in seconds after the trigger event before deployment starts. */
	public double getDeployDelay() {
		return config.getDouble("deployDelay", 0.0);
	}

	public void setDeployDelay(double seconds) {
		config.put("deployDelay", seconds);
		fireChangeEvent();
	}

	/**
	 * In fixed-schedule mode: time in seconds to ramp from fully retracted to
	 * fully extended. In closed-loop mode: the actuator's slew rate, expressed
	 * the same way (seconds to travel 0% -> 100%), which caps how fast the
	 * controller is allowed to move the brake.
	 */
	public double getDeployDuration() {
		return config.getDouble("deployDuration", 0.5);
	}

	public void setDeployDuration(double seconds) {
		config.put("deployDuration", Math.max(0.01, seconds));
		fireChangeEvent();
	}

	/** Extra frontal area exposed at full deployment, in m^2 - the brake's maximum authority. */
	public double getExposedAreaM2() {
		return config.getDouble("exposedArea", 0.002);
	}

	public void setExposedAreaM2(double areaM2) {
		config.put("exposedArea", Math.max(0.0, areaM2));
		fireChangeEvent();
	}

	/** Drag coefficient of the exposed area (~1.0-1.3 for a flat plate normal to flow). */
	public double getDragCoefficient() {
		return config.getDouble("dragCoefficient", 1.0);
	}

	public void setDragCoefficient(double cd) {
		config.put("dragCoefficient", Math.max(0.0, cd));
		fireChangeEvent();
	}

	/** If true, deployment is driven by closed-loop apogee targeting instead of a fixed ramp. */
	public boolean isTargetApogeeControlEnabled() {
		return config.getBoolean("targetApogeeControlEnabled", false);
	}

	public void setTargetApogeeControlEnabled(boolean enabled) {
		config.put("targetApogeeControlEnabled", enabled);
		fireChangeEvent();
	}

	/** Target apogee altitude in meters (AGL), used by closed-loop control and shown as a reference line when comparing flights. */
	public double getTargetApogeeM() {
		return config.getDouble("targetApogeeM", 200.0);
	}

	public void setTargetApogeeM(double meters) {
		config.put("targetApogeeM", meters);
		fireChangeEvent();
	}

	// ---- Simulation listener doing the actual work ----

	private class AirbrakeListener extends AbstractSimulationListener {

		private Double triggerTime = null; // sim time at which deployment starts ramping, or null if not yet triggered
		private double cachedRefArea = Double.NaN;
		private double cachedDensity = Double.NaN;
		private double currentFraction = 0.0; // closed-loop deployment state, persists across steps
		private Double lastControlTime = null;

		@Override
		public boolean handleFlightEvent(SimulationStatus status, FlightEvent event) throws SimulationException {
			if (triggerTime == null) {
				boolean isTrigger = (getTriggerEvent() == TriggerEvent.BURNOUT
						&& event.getType() == FlightEvent.Type.BURNOUT)
						|| (getTriggerEvent() == TriggerEvent.APOGEE
								&& event.getType() == FlightEvent.Type.APOGEE);
				if (isTrigger) {
					triggerTime = status.getSimulationTime() + getDeployDelay();
				}
			}
			return true;
		}

		@Override
		public FlightConditions postFlightConditions(SimulationStatus status, FlightConditions flightConditions)
				throws SimulationException {
			cachedRefArea = flightConditions.getRefArea();
			cachedDensity = flightConditions.getAtmosphericConditions().getDensity();
			return null; // null = "no override", per SimulationComputationListener convention
		}

		@Override
		public AerodynamicForces postAerodynamicCalculation(SimulationStatus status, AerodynamicForces forces)
				throws SimulationException {
			if (triggerTime == null || status.getSimulationTime() < triggerTime
					|| Double.isNaN(cachedRefArea) || cachedRefArea <= 0) {
				return null;
			}

			double fraction = isTargetApogeeControlEnabled()
					? updateControlledFraction(status, forces)
					: MathUtil.clamp((status.getSimulationTime() - triggerTime) / getDeployDuration(), 0.0, 1.0);

			if (fraction <= 0) {
				return null;
			}
			double deltaCD = getDragCoefficient() * getExposedAreaM2() / cachedRefArea * fraction;
			forces.setCD(forces.getCD() + deltaCD);
			// The translational EOM integrator (RK4/RK6SimulationStepper) actually consumes
			// CDaxial, not CD - CD alone is just reported/plotted. CDaxial = mul(AoA) * CD
			// with mul ~ 1 at low AoA, which holds for a stable rocket coasting post-burnout,
			// so add the same delta directly rather than replicating the AoA correction.
			forces.setCDaxial(forces.getCDaxial() + deltaCD);
			return forces;
		}

		/**
		 * Closed-loop deployment: predicts the apogee reachable at 0% and 100%
		 * deployment from the current state (constant-drag ballistic estimate),
		 * picks a fraction between them aimed at the target apogee, and
		 * slew-limits the move toward it. Recomputed every step, so it's a
		 * receding-horizon controller rather than a single one-shot solve -
		 * it self-corrects as altitude/velocity/air density evolve.
		 */
		private double updateControlledFraction(SimulationStatus status, AerodynamicForces forces) {
			double now = status.getSimulationTime();
			double dt = (lastControlTime == null) ? 0.0 : Math.max(0.0, now - lastControlTime);
			lastControlTime = now;

			double altitude = status.getRocketPosition().z;
			double velocity = status.getRocketVelocity().z;

			if (velocity <= 0 || Double.isNaN(cachedDensity) || cachedDensity <= 0) {
				return currentFraction; // past apogee, or no atmosphere data yet - hold position
			}

			double mass = MassCalculator.calculateStructure(status.getConfiguration()).getMass()
					+ MassCalculator.calculateMotor(status).getMass();
			if (mass <= 0) {
				return currentFraction;
			}

			double baseCDaxial = forces.getCDaxial();
			double maxDeltaCD = getDragCoefficient() * getExposedAreaM2() / cachedRefArea;

			double apogeeNoBrake = predictApogee(altitude, velocity, baseCDaxial, mass);
			double apogeeFullBrake = predictApogee(altitude, velocity, baseCDaxial + maxDeltaCD, mass);

			double range = apogeeNoBrake - apogeeFullBrake;
			double desiredFraction = (range < 1e-6)
					? currentFraction // negligible brake authority at this state - nothing sensible to solve for
					: MathUtil.clamp((apogeeNoBrake - getTargetApogeeM()) / range, 0.0, 1.0);

			double maxStep = (dt <= 0 || getDeployDuration() <= 0) ? 1.0 : dt / getDeployDuration();
			double delta = MathUtil.clamp(desiredFraction - currentFraction, -maxStep, maxStep);
			currentFraction = MathUtil.clamp(currentFraction + delta, 0.0, 1.0);
			return currentFraction;
		}

		/** Predicted apogee assuming the given (constant) axial CD holds for the rest of the coast. */
		private double predictApogee(double altitude, double velocity, double cdAxial, double mass) {
			double k = 0.5 * cachedDensity * Math.max(cdAxial, 0) * cachedRefArea / mass;
			if (k <= 1e-9) {
				return altitude + velocity * velocity / (2 * GRAVITY);
			}
			return altitude + Math.log1p(k * velocity * velocity / GRAVITY) / (2 * k);
		}
	}
}
