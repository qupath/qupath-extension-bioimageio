/*-
 * Copyright 2022 QuPath developers, University of Edinburgh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qupath.ext.bioimageio;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.Model;
import qupath.bioimageio.spec.tensor.axes.Axes;
import qupath.bioimageio.spec.tensor.axes.Axis;
import qupath.bioimageio.spec.tensor.axes.AxisType;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.fx.utils.GridPaneUtils;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ColorTransforms.ColorTransform;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.classes.PathClass;
import qupath.opencv.ml.BioimageIoTools;
import qupath.opencv.ml.PatchClassifierParams;
import qupath.opencv.ml.pixel.PixelClassifierTools;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.NumpyTools;
import qupath.opencv.tools.OpenCVTools;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * Very early exploration of BioImage Model Zoo support within QuPath.
 * 
 * @author Pete Bankhead
 */
class BioimageIoCommand {
	
	private final static Logger logger = LoggerFactory.getLogger(BioimageIoCommand.class);
	
	private final QuPathGUI qupath;
	private static final String title = "Bioimage.io to Pixel Classifier";
	
	BioimageIoCommand(QuPathGUI qupath) {
		this.qupath = qupath;
	}
	
	/**
	 * Show a prompt to select a bioimage.io directory and show the prediction for the current image.
	 */
	void promptForModel() {
		
		// TODO: In the future consider handling .zip files
		var file = FileChoosers.promptForFile(title,
				FileChoosers.createExtensionFilter("Bioimage Model Zoo YAML file", "*.yml", "*.yaml"));
		if (file == null)
			return;
		
		
		boolean showLoadPixelClassifier = false;
		try {
			var model = Model.parse(file);

			var inputs = model.getInputs();
			var outputs = model.getOutputs();
			if (inputs.size() > 1 || outputs.size() > 1) {
				Dialogs.showErrorMessage("Bioimage Model Zoo extension", "Unable to run models with more than one input or output.");
				return;
			}
			var inputAxes = inputs.getFirst().getAxes();
			long nSpaceAxes = Arrays.stream(inputAxes)
					.filter(BioimageIoCommand::isSpaceAxis)
					.count();
			if (nSpaceAxes > 2) {
				Dialogs.showErrorMessage("Bioimage Model Zoo extension", "This extension currently only supports 2D models.");
				return;
			}
			var params = new DnnBuilderPane(qupath, title)
					.promptForParameters(model, qupath.getImageData());
			
			if (params == null)
				return;
			
			var classifier = PatchClassifierParams.buildPixelClassifier(params);
			if (classifier == null) {
				logger.info("No pixel classifier created!");
				return;
			}
			
			// Try to save in the current project
			var project = qupath.getProject();
			if (project != null) {
				var name = Dialogs.showInputDialog(title, "Choose classifier name", model.getName());
				if (name != null) {
					Dialogs.showInfoNotification(title, "Pixel classifier saved as " + name);
					project.getPixelClassifiers().put(name, classifier);
					showLoadPixelClassifier = true;
				}
			} else {
				var fileSaved = FileChoosers.promptToSaveFile(title,
						FileChoosers.promptToSaveFile(new FileChooser.ExtensionFilter("Pixel classifier", "*.json")));
				if (fileSaved != null) {
					PixelClassifiers.writeClassifier(classifier, fileSaved.toPath());
					Dialogs.showInfoNotification(title, "Pixel classifier saved to \n" + fileSaved.getAbsolutePath());
				}
			}
			

			// Offer to show the prediction in the current image, if it's small enough
			var imageData = qupath.getImageData();
			if (imageData != null) {
				var classifierServer = PixelClassifierTools.createPixelClassificationServer(imageData, classifier);
				int maxSize = 4096;
				if (classifierServer.getWidth() < maxSize && classifierServer.getHeight() < maxSize) {
					if (Dialogs.showYesNoDialog(title, "Apply prediction & open in ImageJ?")) {
						var imp = IJTools.extractHyperstack(classifierServer, null);
						tryToShowImages(Collections.singleton(imp));
					}
				}
			}
			
			if (showLoadPixelClassifier) {
				// Try to show 'Load pixel classifier' dialog
				var action = qupath.lookupActionByText("Load pixel classifier");
				if (action != null && !action.isDisabled())
					action.handle(new ActionEvent());
			}

			
		} catch (Exception e) {
			Dialogs.showErrorMessage(title, "Error loading or running model. See the log for more details.");
			logger.error("Error loading model", e);
		}
	}

	private static boolean isSpaceAxis(Axis ax) {
		return ax.getType() == AxisType.X || ax.getType() == AxisType.Y || ax.getType() == AxisType.Z;
	}


	static void showDialog(ImageData<?> imageData, String path) throws IOException {
		
		var model = Model.parse(Paths.get(path));
		
		var params = new DnnBuilderPane(QuPathGUI.getInstance(), title)
				.promptForParameters(model, imageData);
		
		
		var classifier = PatchClassifierParams.buildPixelClassifier(params);
		if (classifier == null) {
			logger.info("No pixel classifier created!");
			return;
		}
		
	}
	

	
	static class DnnBuilderPane {
		
		private final QuPathGUI qupath;
		private final String title;
		
		private static final Font font = Font.font("Arial");
		
		private DnnBuilderPane(QuPathGUI qupath, String title) {
			this.qupath = qupath;
			this.title = title;
		}

		private PatchClassifierParams promptForParameters(Model model, ImageData<?> imageData) {
			
			Objects.requireNonNull(imageData, "ImageData must not be null!");
			
			// Parse the parameters
			var params = BioimageIoTools.buildPatchClassifierParams(model);

			// Create a builder so that we can update parameters
			var builder = PatchClassifierParams.builder(params);
			
			int nChannels = params.getInputChannels().size();
			int nOutputClasses = params.getOutputClasses().size();

			GridPane pane = new GridPane();
			pane.setHgap(5);
			pane.setVgap(5);
			
			int row = 0;
			
			addTitleRow(pane, "Experimental - use with caution!", row++);
			addDescriptionRow(pane,
					"This command tries to create a QuPath "
					+ "pixel classifier from a BioImage Model Zoo spec."
					, row++);
			addDescriptionRow(pane,
					"It may not work in all (or even most) cases."
					, row++);
			addDescriptionRow(pane,
					"It can also sometimes give different results to "
					+ "other software, because of different per-tile normalization."
					, row++);
			
			addSeparatorRow(pane, row++);

			// Handle input channels & their order
			addTitleRow(pane, "Input channels", row++);
			addDescriptionRow(pane, "The image channels provided as input to the model", row++);
			
			var server = imageData.getServer();
			ObservableList<ColorTransform> availableChannels = FXCollections.observableArrayList(
						server.getMetadata().getChannels()
							.stream()
							.map(c -> ColorTransforms.createChannelExtractor(c.getName()))
							.collect(Collectors.toList()));				
			availableChannels.addAll(
					ColorTransforms.createMeanChannelTransform(),
					ColorTransforms.createMaximumChannelTransform(),
					ColorTransforms.createMinimumChannelTransform()
					);
			
			List<ColorTransform> inputChannels = new ArrayList<>();
			for (int c = 1; c <= nChannels; c++) {
				var comboChannel = new ComboBox<>(availableChannels);
				comboChannel.getSelectionModel().select((c-1) % availableChannels.size());
				inputChannels.add(comboChannel.getSelectionModel().getSelectedItem());

				GridPaneUtils.setToExpandGridPaneWidth(comboChannel);
				addLabeledRow(pane, "Channel "+c, row++, "Choose channel " + c + " input", comboChannel);
				
				int ind = c - 1;
				comboChannel.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
					if (n == null) {
						logger.warn("Cannot set channel to null");
						return;
					}
					inputChannels.set(ind, n);
					builder.inputChannels(inputChannels);
				});
			}
			
			// Handle pixel size
			var cal = server.getPixelCalibration();
			addTitleRow(pane, "Input resolution", row++);
			addDescriptionRow(pane, "The pixel size at which the model will be applied", row++);
			var factoryDownsample = new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0, Double.MAX_VALUE, 1.0, 0.1);
			var spinnerDownsample = new Spinner<>(factoryDownsample);
			GridPaneUtils.setToExpandGridPaneWidth(spinnerDownsample);
			addLabeledRow(pane, "Downsample", row++, "Choose downsample for input image", spinnerDownsample);
			var labelResolution = new Label(calToString(cal, 1));
			labelResolution.textProperty().bind(Bindings.createStringBinding(() -> {
				Double scale = spinnerDownsample.getValue();
				if (scale == null || scale < 1)
					scale = 1.0;
				return calToString(cal, scale);
			}, spinnerDownsample.valueProperty()));
			spinnerDownsample.valueProperty().addListener((v, o, n) -> {
				if (n >= 1.0)
					builder.inputResolution(cal, n);
			});
			addLabeledRow(pane, "Calculated resolution", row++, "Input resolution, calculated from the current image and downsample value", labelResolution);
			
			// Handle tile shape
			addTitleRow(pane, "Input tile shape", row++);
			addDescriptionRow(pane, "The size of each input tile when processing large images", row++);
			
			// Use the patch values by default, but try to offer all the options from the model spec
			int width = params.getPatchWidth();
			int height = params.getPatchHeight();
			int stepWidth = 0;
			int stepHeight = 0;
			if (model.getOutputs().size() == 1) {
				var output = model.getOutputs().get(0);
				int[] shape = output.getShape().getShape();				
				int[] steps = output.getShape().getShapeStep();				
				int[] minSize = output.getShape().getShapeMin();
				String outputAxes = Axes.getAxesString(output.getAxes()).toLowerCase();
				int indX = outputAxes.indexOf("x");
				int indY = outputAxes.indexOf("y");
				if (indX >= 0 && indY >= 0) {
					if (minSize.length > 0) {
						width = minSize[indX];
						height = minSize[indY];
					} else if (shape.length > 0) {
						width = shape[indX];
						height = shape[indY];
					}
					if (steps.length > 0) {
						stepWidth = steps[indX];
						stepHeight = steps[indY];
					}
				}
			}
			if (stepWidth > 0 || stepHeight > 0) {
				int maxTile = Math.max(width, height) * 8;
				var factoryWidth = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, maxTile);
				factoryWidth.setValue(params.getPatchWidth());
				factoryWidth.setAmountToStepBy(stepWidth);
				var spinnerWidth = new Spinner<>(factoryWidth);
				
				var factoryHeight = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, maxTile);
				factoryHeight.setValue(params.getPatchHeight());
				// TODO: Incorporate step size, if available
				factoryHeight.setAmountToStepBy(stepHeight);
				var spinnerHeight = new Spinner<>(factoryHeight);

				GridPaneUtils.setToExpandGridPaneWidth(spinnerWidth, spinnerHeight);
				addLabeledRow(pane, "Tile width", row++, "Choose input tile width in pixels", spinnerWidth);
				addLabeledRow(pane, "Tile height", row++, "Choose input tile height in pixels", spinnerHeight);
				spinnerWidth.setEditable(false);
				spinnerHeight.setEditable(false);
				spinnerWidth.valueProperty().addListener((v, o, n) -> builder.patchSize(spinnerWidth.getValue(), spinnerHeight.getValue()));
				spinnerHeight.valueProperty().addListener((v, o, n) -> builder.patchSize(spinnerWidth.getValue(), spinnerHeight.getValue()));
				GridPaneUtils.setToExpandGridPaneWidth(spinnerWidth, spinnerHeight, labelResolution);
			} else {
				addDescriptionRow(pane, String.format("Tile size fixed to %d x %d", width, height), row++);
			}
			
			// Handle output
			addTitleRow(pane, "Output classes", row++);
			addDescriptionRow(pane, "The classifications corresponding to the model output", row++);
			GridPaneUtils.setToExpandGridPaneWidth(labelResolution);

			ObservableList<PathClass> availableClasses = FXCollections.observableArrayList();
			if (qupath != null)
				availableClasses = qupath.getAvailablePathClasses();
			
			var outputClasses = new LinkedHashMap<>(params.getOutputClasses());
			for (int c = 1; c <= nOutputClasses; c++) {
				var comboOutputClasses = new ComboBox<>(availableClasses);
				comboOutputClasses.setEditable(true);
				comboOutputClasses.setConverter(new PathClassStringConverter());
				if (c <= availableClasses.size())
					comboOutputClasses.getSelectionModel().select(c-1);
				GridPaneUtils.setToExpandGridPaneWidth(comboOutputClasses);
				addLabeledRow(pane, "Class "+c, row++, "Choose output classification for channel " + c, comboOutputClasses);
				int ind = c - 1;
				comboOutputClasses.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
					outputClasses.put(ind, n);
					builder.outputClasses(outputClasses);
				});
			}
			
			// Handle output type
			addTitleRow(pane, "Output type", row++);
			addDescriptionRow(pane, "The output type of the model", row++);
			
			var comboOutputType = new ComboBox<ChannelType>();
			comboOutputType.getItems().setAll(ChannelType.values());
			comboOutputType.getSelectionModel().select(params.getOutputChannelType());
			comboOutputType.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
				if (n != null)
					builder.outputChannelType(n);
				else
					logger.warn("Output type cannot be null");
			});

			GridPaneUtils.setToExpandGridPaneWidth(comboOutputType);
			addLabeledRow(pane, "Output", row++, "Choose output type", comboOutputType);
			
			var tester = new BioimageIoTest(model);
			if (tester.hasInput()) {
				var btnTest = new Button("Show test images in ImageJ");
				btnTest.setOnAction(e -> tester.runAndShowOutput(params));
				GridPaneUtils.addGridRow(pane, row++, 0,
						"Attempt to run prediction on the test image, if available.\n"
								+ "This checks the model runes, but does not use most of the customizations here\n"
								+ "because the channel input is fixed.",
						btnTest, btnTest);
				GridPaneUtils.setToExpandGridPaneWidth(btnTest);
			}
			
			var scrollPane = new ScrollPane(pane);
			scrollPane.setFitToWidth(true);
			var result = Dialogs.builder()
					.title(title)
					.content(scrollPane)
					.buttons(ButtonType.CANCEL, ButtonType.APPLY)
					.prefHeight(500) // Setting height & resizable deal with dialogs that are too 'tall'
					.prefWidth(400)
					.resizable()
					.showAndWait()
					.orElse(ButtonType.CANCEL);
				
			tester.close();
			
			if (result.equals(ButtonType.CANCEL))
				return null;
			
			return builder.build();
		}
		
		
		private static String calToString(PixelCalibration cal, double downsample) {
			int ndp = 4;
			return String.format("%s %s x %s %s",
					GeneralTools.formatNumber(cal.getPixelWidth().doubleValue() * downsample, ndp), 
					cal.getPixelWidthUnit(),
					GeneralTools.formatNumber(cal.getPixelHeight().doubleValue() * downsample, ndp),
					cal.getPixelHeightUnit());
		}
		
		private static void addSeparatorRow(GridPane pane, int row) {
			var sep = new Separator(Orientation.HORIZONTAL);
			GridPane.setColumnSpan(sep, GridPane.REMAINING);
			GridPaneUtils.setToExpandGridPaneWidth(sep);
			GridPaneUtils.addGridRow(pane, row, 0, null, sep, sep);
		}
		
		private static void addTitleRow(GridPane pane, String labelText, int row) {
			addTextRow(pane, labelText, row, true);
		}
		
		private static void addDescriptionRow(GridPane pane, String labelText, int row) {
			addTextRow(pane, labelText, row, false);
		}
		
		private static void addTextRow(GridPane pane, String labelText, int row, boolean isTitle) {
			var label = new Label(labelText);
			if (isTitle) {
				label.setFont(Font.font(font.getFamily(), FontWeight.BOLD, font.getSize()));
				label.setPadding(new Insets(10, 0, 0, 0));
			} else
				label.setWrapText(true);
			pane.add(label, 0, row, GridPane.REMAINING, 1);
		}
		
		private static void addLabeledRow(GridPane pane, String labelText, int row, String tooltip, Node... nodes) {
			var label = new Label(labelText);
			if (nodes.length == 0) {
				GridPaneUtils.addGridRow(pane, row, 0, tooltip, label);
			} else {
				label.setLabelFor(nodes[0]);
				if (nodes.length == 1)
					GridPaneUtils.addGridRow(pane, row, 0, tooltip, label, nodes[0]);
				else {
					var nodes2 = new Node[nodes.length+1];
					nodes2[0] = label;
					System.arraycopy(nodes, 0, nodes2, 1, nodes.length);
					GridPaneUtils.addGridRow(pane, row, 0, tooltip, nodes2);
				}
			}
		}

	}
	
	
	static class BioimageIoTest implements AutoCloseable {
		
		private static final Logger logger = LoggerFactory.getLogger(BioimageIoTest.class);
		
		private Model model;
		private Mat matInput;
		private Mat matOutput;
		
		private BioimageIoTest(Model model) {
			this.model = model;
			var testInputs = model.getTestInputs();
			var testOutputs = model.getTestOutputs();
			var baseUri = model.getBaseURI();
			if (baseUri == null)
				return;
			if (!testInputs.isEmpty()) {
				var pathInput = Paths.get(baseUri.resolve(testInputs.get(0)));
				matInput = tryToReadMat(pathInput);
			}
			if (!testOutputs.isEmpty()) {
				var pathOutput = Paths.get(baseUri.resolve(testOutputs.get(0)));
				matOutput = tryToReadMat(pathOutput);
			}
		}
		
		boolean hasInput() {
			return matInput != null;
		}
		
		private static Mat tryToReadMat(Path path) {
			if (path == null || !Files.exists(path))
				return null;
			try {
				return NumpyTools.readMat(path, true);
			} catch (IOException e) {
				logger.error(e.getLocalizedMessage(), e);
				return null;
			}
		}


		/**
		 *
		 * @param params
		 */
		void runAndShowOutput(PatchClassifierParams params) {
			if (matInput == null) {
				logger.warn("Cannot run test - not input image found");
			}
			
			var ops = new ArrayList<ImageOp>();
			if (params.getPreprocessing() != null)
				ops.addAll(params.getPreprocessing());

			if (params.getPredictionOp() != null)
				ops.add(params.getPredictionOp());

			if (params.getPostprocessing() != null)
				ops.addAll(params.getPostprocessing());

			var op = ImageOps.Core.sequential(ops);

			try (var scope = new PointerScope()) {
				Mat matInput2;
				var padding = op.getPadding();
				if (!padding.isEmpty()) {
					matInput2 = new Mat();
					opencv_core.copyMakeBorder(matInput, matInput2,
							padding.getY1(), padding.getY2(),
							padding.getX1(), padding.getX2(),
							opencv_core.BORDER_REFLECT);
	
				} else
					matInput2 = matInput.clone();
				var matPrediction = op.apply(matInput2);
				
				List<ImagePlus> imps = new ArrayList<>();
				imps.add(OpenCVTools.matToImagePlus(model.getName() + "-input", matInput));
				imps.add(OpenCVTools.matToImagePlus(model.getName() + "-prediction", matPrediction));
				
				if (matOutput != null) {
					imps.add(OpenCVTools.matToImagePlus(model.getName() + "-target", matOutput));
					
					if (matPrediction.rows() == matOutput.rows() &&
							matPrediction.cols() == matOutput.cols() && 
							matPrediction.channels() == matOutput.channels()) {
						var matDifference = matPrediction.clone();
						opencv_core.subtract(matPrediction, matOutput, matDifference);
						imps.add(OpenCVTools.matToImagePlus(model.getName() + "-difference", matDifference));
					} else {
						logger.warn("Target output and prediction have different shapes!");
					}
				}
				tryToShowImages(imps);
			}
		}


		@Override
		public void close() {
			if (matInput != null)
				matInput.close();
			if (matOutput != null)
				matOutput.close();
		}
		
		
	}
	
	/**
	 * Try to show ImageJ images.
	 * @param imps The images.
	 */
	private static void tryToShowImages(Collection<? extends ImagePlus> imps) {
		if (imps.isEmpty())
			return;
		if (SwingUtilities.isEventDispatchThread()) {
			// If we got this far, try to start ImageJ instance so that the image windows can be manipulated
			// (otherwise they appear, but with no opportunity to work with them)
			var ij = IJ.getInstance();
			if (ij == null) {
				try {
					var cls = Class.forName("qupath.imagej.gui.IJExtension");
					var method = cls.getDeclaredMethod("getImageJInstance");
					ij = (ImageJ)method.invoke(null);
				} catch (Throwable t) {
					// We can get errors here, but should be able to recover
					logger.warn("Unable to create ImageJ instance: " + t.getLocalizedMessage(), t);
				}
			}
			if (ij != null)
				ij.setVisible(true);
			for (var imp : imps)
				imp.show();
		} else
			SwingUtilities.invokeLater(() -> tryToShowImages(imps));
	}
		
	
	static class PathClassStringConverter extends StringConverter<PathClass> {

		@Override
		public String toString(PathClass object) {
			return Objects.toString(object);
		}

		@Override
		public PathClass fromString(String string) {
			return string == null || string.isBlank() ? null : PathClass.fromString(string);
		}
		
	}


}
