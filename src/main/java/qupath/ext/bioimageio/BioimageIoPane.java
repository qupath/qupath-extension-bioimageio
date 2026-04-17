package qupath.ext.bioimageio;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.Model;
import qupath.bioimageio.spec.tensor.axes.Axes;
import qupath.bioimageio.spec.tensor.axes.SpaceAxes;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.objects.classes.PathClass;
import qupath.opencv.ml.BioimageIoTools;
import qupath.opencv.ml.PatchClassifierParams;

public class BioimageIoPane extends BorderPane {
    private final QuPathGUI qupath;
    private static final Logger logger = LoggerFactory.getLogger(BioimageIoPane.class);
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.bioimageio.strings");

    private final PatchClassifierParams params;
    private final PatchClassifierParams.Builder builder;
    private final Model model;

    @FXML
    private VBox inputChannelSelectors;
    @FXML
    private VBox outputClassesBox;
    @FXML
    private ComboBox<ImageServerMetadata.ChannelType> outputTypeCombo;
    @FXML
    private Label fixedTileLabel;
    @FXML
    private VBox tileSpinnerBox;
    @FXML
    private Spinner<Integer> tileWidthSpinner;
    @FXML
    private Spinner<Integer> tileHeightSpinner;
    @FXML
    private VBox tileShapeOptionsBox;
    @FXML
    private Spinner<Double> pixelSizeSpinner;


    public static PatchClassifierParams createDialog(QuPathGUI qupath, Model model) {
        try {
			var pane = new BioimageIoPane(qupath, model);

			var result = Dialogs.builder()
					.title(resources.getString("title"))
					.content(pane)
					.buttons(ButtonType.CANCEL, ButtonType.APPLY)
					.prefHeight(500) // Setting height & resizable deal with dialogs that are too 'tall'
					.prefWidth(400)
                    .resizable()
					.showAndWait()
					.orElse(ButtonType.CANCEL);

			if (result.equals(ButtonType.CANCEL))
				return null;

			return pane.builder.build();
		} catch (IOException e) {
			Dialogs.showErrorMessage("BioimageIo", "GUI loading failed");
			logger.error("Unable to load BioimageIo FXML", e);
		}
        return null;
    }


    private BioimageIoPane(QuPathGUI qupath, Model model) throws IOException {
        this.qupath = qupath;
        var url = BioimageIoPane.class.getResource("bioimageio.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        this.model = model;
        params = BioimageIoTools.buildPatchClassifierParams(model);
        builder = PatchClassifierParams.builder(params);

        var imageData = qupath.getImageData();
        configureInputChannels(imageData);
        configureTileSize();
        configurePixelSize(model);
        configureOutputClasses();
        configureOutputTypes();
    }

    private void configurePixelSize(Model model) {
        var axes = model.getInputs().getFirst().getAxes();
        String axString = Axes.getAxesString(axes);
        int xind = axString.indexOf("x");
        int yind = axString.indexOf("y");
        double xsize=0.25, ysize=0.25;
        if (xind != -1 && yind != -1) {
            if (axes[xind] instanceof SpaceAxes.SpaceAxis spaceAxis) {
                if (spaceAxis.getUnit() != SpaceAxes.SpaceUnit.MICROMETER) {
                    logger.warn("Unknown space unit {}", spaceAxis.getUnit());
                }
                xsize = spaceAxis.getScale();
            }
            if (axes[yind] instanceof SpaceAxes.SpaceAxis spaceAxis) {
                if (spaceAxis.getUnit() != SpaceAxes.SpaceUnit.MICROMETER) {
                    logger.warn("Unknown space unit {}", spaceAxis.getUnit());
                }
                ysize = spaceAxis.getScale();
            }
        }
        double defaultValue = (xsize + ysize) / 2;
        pixelSizeSpinner.getValueFactory().setValue(defaultValue);

        // todo consider number of decimal places
        DecimalFormat format = new DecimalFormat("0.000");
        pixelSizeSpinner.getValueFactory().setConverter(new StringConverter<>() {
            @Override
            public String toString(Double value) {
                if (value == null) return "";
                return format.format(value);
            }

            @Override
            public Double fromString(String string) {
                try {
                    return string == null || string.isEmpty() ? 0.0 : format.parse(string).doubleValue();
                } catch (ParseException e) {
                    return 0.0;
                }
            }
        });
    }

    private void configureOutputClasses() {
        int nOutputClasses = params.getOutputClasses().size();

        ObservableList<PathClass> availableClasses = FXCollections.observableArrayList();
        if (qupath != null)
            availableClasses = qupath.getAvailablePathClasses();

        var outputClasses = new LinkedHashMap<>(params.getOutputClasses());
        ObservableList<PathClass> classList = FXCollections.observableArrayList(outputClasses.values());
        for (int c = 1; c <= nOutputClasses; c++) {
            var comboOutputClasses = new ComboBox<>(classList);
            comboOutputClasses.getItems().addAll(availableClasses);
            comboOutputClasses.setEditable(true);
            comboOutputClasses.setConverter(new BioimageIoCommand.PathClassStringConverter());

            if (c <= availableClasses.size())
                comboOutputClasses.getSelectionModel().select(c-1);

            comboOutputClasses.setTooltip(new Tooltip(resources.getString("tooltip.options.output.classes") + c));
            int ind = c - 1;
            comboOutputClasses.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
                outputClasses.put(ind, n);
                builder.outputClasses(outputClasses);
            });
            Label label = new Label(String.format(resources.getString("ui.misc.class-n"), c));

            // styling
            HBox box = new HBox(label, comboOutputClasses);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("standard-spacing");
            HBox.setHgrow(comboOutputClasses, Priority.ALWAYS);
            comboOutputClasses.setMaxWidth(Double.MAX_VALUE);
            outputClassesBox.getChildren().add(box);
        }

    }

    private void configureInputChannels(ImageData<?> imageData) {

        int nChannels = params.getInputChannels().size();
        var server = imageData.getServer();
        ObservableList<ColorTransforms.ColorTransform> availableChannels = FXCollections.observableArrayList(
                server.getMetadata().getChannels()
                        .stream()
                        .map(c -> ColorTransforms.createChannelExtractor(c.getName()))
                        .collect(Collectors.toList()));
        availableChannels.addAll(
                ColorTransforms.createMeanChannelTransform(),
                ColorTransforms.createMaximumChannelTransform(),
                ColorTransforms.createMinimumChannelTransform()
        );

        List<ColorTransforms.ColorTransform> inputChannels = new ArrayList<>();

        for (int c = 1; c <= nChannels; c++) {
            var label = new Label(String.format(resources.getString("ui.misc.channel-n"), c));
            var tooltip = new Tooltip(String.format(resources.getString("ui.misc.choose-channel-n"), c));
            var comboChannel = new ComboBox<>(availableChannels);
            comboChannel.setTooltip(tooltip);
            comboChannel.getSelectionModel().select((c - 1) % availableChannels.size());
            inputChannels.add(comboChannel.getSelectionModel().getSelectedItem());

            int ind = c - 1;
            comboChannel.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
                if (n == null) {
                    logger.warn("Cannot set channel to null");
                    return;
                }
                inputChannels.set(ind, n);
                builder.inputChannels(inputChannels);
            });

            // styling
            HBox box = new HBox(label, comboChannel);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("standard-spacing");
            HBox.setHgrow(comboChannel, Priority.ALWAYS);
            comboChannel.setMaxWidth(Double.MAX_VALUE);
            this.inputChannelSelectors.getChildren().add(box);
        }
    }




    private void configureTileSize() {
        // Use the patch values by default, but try to offer all the options from the model spec
        int width = params.getPatchWidth();
        int height = params.getPatchHeight();
        int stepWidth = 0;
        int stepHeight = 0;
        if (model.getOutputs().size() == 1) {
            var output = model.getOutputs().getFirst();
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
        // if fixed tile size, just display a fixed message and exit
        if (stepWidth <= 0 && stepHeight <= 0) {
            fixedTileLabel.setText(String.format(resources.getString("ui.options.input-tile-shape-fixed"), width, height));
            tileShapeOptionsBox.getChildren().remove(tileSpinnerBox);
            return;
        }
        tileShapeOptionsBox.getChildren().remove(fixedTileLabel);

        // otherwise, configure the spinners
        int maxTile = Math.max(width, height) * 8;
        var factoryWidth = (SpinnerValueFactory.IntegerSpinnerValueFactory)tileWidthSpinner.getValueFactory();
        factoryWidth.setMax(maxTile);
        factoryWidth.setValue(params.getPatchWidth());
        factoryWidth.setAmountToStepBy(stepWidth);

        var factoryHeight = (SpinnerValueFactory.IntegerSpinnerValueFactory)tileHeightSpinner.getValueFactory();
        factoryHeight.setMax(maxTile);
        factoryHeight.setValue(params.getPatchHeight());
        factoryHeight.setAmountToStepBy(stepHeight);

        tileWidthSpinner.valueProperty().addListener((v, o, n) -> builder.patchSize(tileWidthSpinner.getValue(), tileHeightSpinner.getValue()));
        tileHeightSpinner.valueProperty().addListener((v, o, n) -> builder.patchSize(tileWidthSpinner.getValue(), tileHeightSpinner.getValue()));
        GridPaneUtils.setToExpandGridPaneWidth(tileWidthSpinner, tileHeightSpinner); // todo replace these calls with FXML settings
    }

    private void configureOutputTypes() {
        outputTypeCombo.getItems().clear();
        outputTypeCombo.getItems().addAll(
                ImageServerMetadata.ChannelType.PROBABILITY,
                ImageServerMetadata.ChannelType.MULTICLASS_PROBABILITY,
                ImageServerMetadata.ChannelType.CLASSIFICATION
        );
        outputTypeCombo.getSelectionModel().select(params.getOutputChannelType());
        outputTypeCombo.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            if (n != null)
                builder.outputChannelType(n);
            else
                logger.warn("Output type cannot be null");
        });
        GridPaneUtils.setToExpandGridPaneWidth(outputTypeCombo); // todo replaceme
    }

}
