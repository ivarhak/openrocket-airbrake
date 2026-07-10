package airbrake;

import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.simulation.extension.AbstractSimulationExtensionProvider;

@Plugin
public class AirbrakeExtensionProvider extends AbstractSimulationExtensionProvider {

	public AirbrakeExtensionProvider() {
		super(AirbrakeExtension.class, "Aerodynamics", "Airbrake");
	}
}
