package qupath.ext.bioimageio;

import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class BioImageIOBrowser {

    BioImageIOBrowser() {
        var pane = new BorderPane();
        var scene = new Scene(pane);
        var stage = new Stage();
        stage.setScene(scene);
        stage.show();
    }

    // todo:
}
