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
import java.util.ResourceBundle;
import javafx.event.ActionEvent;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.Model;
import qupath.bioimageio.spec.tensor.axes.Axis;
import qupath.bioimageio.spec.tensor.axes.AxisType;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.imagej.tools.IJTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.classes.PathClass;
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
import java.util.List;
import java.util.Objects;


/**
 * Very early exploration of BioImage Model Zoo support within QuPath.
 * 
 * @author Pete Bankhead
 */
class BioimageIoCommand {
	
	private final static Logger logger = LoggerFactory.getLogger(BioimageIoCommand.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.bioimageio.strings");

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

			var params = BioimageIoPane.createDialog(qupath, model);

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
				var action = qupath.lookupActionByText("Load pixel classifier...");
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


	static class BioimageIoTest implements AutoCloseable {
		
		private static final Logger logger = LoggerFactory.getLogger(BioimageIoTest.class);
		
		private final Model model;
		private Mat matInput;
		private Mat matOutput;
		
		BioimageIoTest(Model model) {
			this.model = model;
			var testInputs = model.getTestInputs();
			var testOutputs = model.getTestOutputs();
			var baseUri = model.getBaseURI();
			if (baseUri == null)
				return;
			if (!testInputs.isEmpty()) {
				var pathInput = Paths.get(baseUri.resolve(testInputs.getFirst()));
				matInput = tryToReadMat(pathInput);
			}
			if (!testOutputs.isEmpty()) {
				var pathOutput = Paths.get(baseUri.resolve(testOutputs.getFirst()));
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
