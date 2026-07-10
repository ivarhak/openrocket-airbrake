package airbrake;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog.ModalityType;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SwingConstants;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.RectangleAnchor;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.extension.SimulationExtension;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.swing.gui.SpinnerEditor;
import info.openrocket.swing.gui.adaptors.BooleanModel;
import info.openrocket.swing.gui.adaptors.DoubleModel;
import info.openrocket.swing.gui.adaptors.EnumModel;
import info.openrocket.swing.gui.components.UnitSelector;
import info.openrocket.swing.simulation.extension.AbstractSwingSimulationExtensionConfigurator;

/**
 * The "sim config menu" for the airbrake extension: one checkbox to toggle
 * it on/off (so the same simulation can be re-run with and without the
 * brake), its deployment trigger/timing/drag parameters, target-apogee
 * closed-loop control, and a button that runs the achievable-range bounds
 * plus the controlled trajectory and overlays them - OpenRocket's own plot
 * dialog only ever shows one simulation at a time.
 */
@Plugin
public class AirbrakeConfigurator extends AbstractSwingSimulationExtensionConfigurator<AirbrakeExtension> {

	public AirbrakeConfigurator() {
		super(AirbrakeExtension.class);
	}

	@Override
	protected JComponent getConfigurationComponent(AirbrakeExtension extension, Simulation simulation, JPanel panel) {
		JCheckBox enabledBox = new JCheckBox(new BooleanModel(extension, "Enabled"));
		enabledBox.setText("Enabled (uncheck to simulate the same rocket with no airbrake)");
		panel.add(enabledBox, "span, wrap para");

		panel.add(new JLabel("Deploy trigger:"));
		EnumModel<AirbrakeExtension.TriggerEvent> triggerModel = new EnumModel<>(extension, "TriggerEvent");
		panel.add(new JComboBox<>(triggerModel), "wrap");

		panel.add(new JLabel("Deploy delay after trigger:"));
		addDoubleField(panel, extension, "DeployDelay", UnitGroup.UNITS_SHORT_TIME);

		panel.add(new JLabel("Deploy/slew duration (0→100%):"));
		addDoubleField(panel, extension, "DeployDuration", UnitGroup.UNITS_SHORT_TIME);

		panel.add(new JLabel("Exposed area at full deployment:"));
		addDoubleField(panel, extension, "ExposedAreaM2", UnitGroup.UNITS_AREA);

		panel.add(new JLabel("Drag coefficient of exposed area:"));
		addDoubleField(panel, extension, "DragCoefficient", UnitGroup.UNITS_NONE);

		JCheckBox targetControlBox = new JCheckBox(new BooleanModel(extension, "TargetApogeeControlEnabled"));
		targetControlBox.setText("Closed-loop target-apogee control (instead of a fixed deploy ramp)");
		panel.add(targetControlBox, "span, wrap");

		panel.add(new JLabel("Target apogee:"));
		addDoubleField(panel, extension, "TargetApogeeM", UnitGroup.UNITS_DISTANCE);

		JButton compareButton = new JButton("Compare with / without airbrake…");
		compareButton.addActionListener(e -> runComparison(extension, simulation));
		panel.add(compareButton, "span, wrap para");

		JLabel note = new JLabel("<html><i>Note: modeled for flat, disk-shaped airbrake paddles presented "
				+ "flat to the airflow - other shapes (curved, cupped, etc.) are untested and may not fit "
				+ "the same drag coefficient assumptions. The comparison graph only shows achievable apogee, "
				+ "not full mission timing - simulate the full launch separately to determine flight duration "
				+ "and event timing. Triggering deployment at LAUNCH extends the brake during powered ascent, "
				+ "which can shift CP and reduce stability margin - check stability carefully before flying "
				+ "with this trigger.</i></html>");
		note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1f));
		panel.add(note, "span, wrap, w 500lp");

		return panel;
	}

	private void addDoubleField(JPanel panel, AirbrakeExtension extension, String property, UnitGroup units) {
		DoubleModel model = new DoubleModel(extension, property, units, 0);
		JSpinner spin = new JSpinner(model.getSpinnerModel());
		spin.setEditor(new SpinnerEditor(spin));
		panel.add(spin, "w 65lp!");
		panel.add(new UnitSelector(model), "w 40, wrap");
	}

	private void runComparison(AirbrakeExtension liveExtension, Simulation simulation) {
		try {
			// Bounding runs always use the fixed-schedule, fully-deployed brake (or no
			// brake), regardless of whichever mode is currently toggled live, since
			// they're meant to show the achievable range.
			Simulation noBrake = simulation.copy();
			findAirbrakeExtension(noBrake).setEnabled(false);

			Simulation fullBrake = simulation.copy();
			AirbrakeExtension fullBrakeExt = findAirbrakeExtension(fullBrake);
			fullBrakeExt.setEnabled(true);
			fullBrakeExt.setTargetApogeeControlEnabled(false);

			Simulation controlled = simulation.copy();
			AirbrakeExtension controlledExt = findAirbrakeExtension(controlled);
			controlledExt.setEnabled(true);
			controlledExt.setTargetApogeeControlEnabled(true);

			noBrake.simulate();
			fullBrake.simulate();
			controlled.simulate();

			XYSeries noSeries = altitudeSeries(noBrake, "Without airbrake");
			XYSeries fullSeries = altitudeSeries(fullBrake, "Maximum airbrake (full deployment)");
			XYSeries controlledSeries = altitudeSeries(controlled, "Target-tracking airbrake");

			double apogeeNo = maxY(noSeries);
			double apogeeFull = maxY(fullSeries);
			double apogeeControlled = maxY(controlledSeries);
			double target = liveExtension.getTargetApogeeM();

			XYSeriesCollection dataset = new XYSeriesCollection();
			dataset.addSeries(noSeries);
			dataset.addSeries(fullSeries);
			dataset.addSeries(controlledSeries);

			JFreeChart chart = ChartFactory.createXYLineChart(
					"Altitude vs. time", "Time (s)", "Altitude (m)", dataset);

			ValueMarker targetMarker = new ValueMarker(target);
			targetMarker.setPaint(Color.GREEN.darker());
			targetMarker.setLabel(String.format("Target: %.1f m", target));
			targetMarker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
			targetMarker.setLabelTextAnchor(TextAnchor.BOTTOM_LEFT);
			XYPlot plot = chart.getXYPlot();
			plot.addRangeMarker(targetMarker);

			boolean achievable = target <= apogeeNo && target >= apogeeFull;

			JDialog chartDialog = new JDialog(getDialog(), "Airbrake comparison", ModalityType.MODELESS);
			JPanel content = new JPanel(new BorderLayout());
			content.add(new ChartPanel(chart), BorderLayout.CENTER);
			JLabel summary = new JLabel("<html><center>" + String.format(
					"Achievable range: %.1f m (maximum airbrake) to %.1f m (no brake)   |   Target %.1f m is %s<br>"
							+ "Target-tracking run reached: %.1f m (error %.1f m)",
					apogeeFull, apogeeNo, target, achievable ? "WITHIN range" : "OUT OF range",
					apogeeControlled, apogeeControlled - target)
					+ "</center></html>", SwingConstants.CENTER);
			content.add(summary, BorderLayout.SOUTH);
			chartDialog.setContentPane(content);
			chartDialog.setSize(850, 650);
			chartDialog.setLocationRelativeTo(getDialog());
			chartDialog.setVisible(true);

		} catch (SimulationException ex) {
			JOptionPane.showMessageDialog(getDialog(), "Simulation failed: " + ex.getMessage(),
					"Comparison failed", JOptionPane.ERROR_MESSAGE);
		}
	}

	private AirbrakeExtension findAirbrakeExtension(Simulation sim) {
		for (SimulationExtension ext : sim.getSimulationExtensions()) {
			if (ext instanceof AirbrakeExtension) {
				return (AirbrakeExtension) ext;
			}
		}
		throw new IllegalStateException("Airbrake extension not found on simulation copy");
	}

	private XYSeries altitudeSeries(Simulation sim, String label) {
		FlightDataBranch branch = sim.getSimulatedData().getBranches().get(0);
		List<Double> time = branch.get(FlightDataType.TYPE_TIME);
		List<Double> altitude = branch.get(FlightDataType.TYPE_ALTITUDE);
		XYSeries series = new XYSeries(label);
		for (int i = 0; i < time.size(); i++) {
			series.add(time.get(i), altitude.get(i));
		}
		return series;
	}

	private double maxY(XYSeries series) {
		double max = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < series.getItemCount(); i++) {
			max = Math.max(max, series.getY(i).doubleValue());
		}
		return max;
	}
}
