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

import org.controlsfx.control.action.Action;

import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.actions.annotations.ActionMenu;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;

/**
 * Extension to make some bioimage.io models available within QuPath.
 * Currently at an early stage of development and <b>very</b> limited...
 */
public class BioimageIoExtension implements QuPathExtension, GitHubProject {

	private boolean isInstalled = false;
	
//	@ActionConfig(value = "action", bundle = "qupath/ext/bioimageio/strings")
	@ActionMenu("Extensions>BioImage Model Zoo...>Import pixel classifier (bioimage.io)")
	private Action actionBioimageIO;
	
	   @Override
    public void installExtension(QuPathGUI qupath) {
		   if (isInstalled)
			   return;
		   isInstalled = true;
		   var command = new BioimageIoCommand(qupath);
		   var actionBioimageIO = qupath.createImageDataAction(imageData -> command.promptForModel());
		   actionBioimageIO.setText("Create pixel classifier (Bioimage Model Zoo)");
		   actionBioimageIO.setLongText("Create a pixel classifier from a Bioimage Model Zoo specification");
		   MenuTools.addMenuItems(
	                qupath.getMenu("Extensions>BioImage Model Zoo", true),
	                actionBioimageIO
	        );
		   // TODO: Support drag & drop if we have a sensible dialog to use
//		   qupath.getDefaultDragDropListener().addFileDropHandler((v, files) -> {
//			   if (files.size() == 1 && files.get(0).isDirectory()) {
//				   var dir = files.get(0);
//				   if (new File(dir, SpecificationWriter.getModelFileName()).exists()) {
//					   
//				   }
//			   }
//		   });
    }

    @Override
    public String getName() {
        return "Bioimage Model Zoo extension";
    }

    @Override
    public String getDescription() {
        return "Preview extension that aims to add support for (some) Bioimage Model Zoo models";
    }

	@Override
	public GitHubRepo getRepository() {
		return GitHubRepo.create(getName(), "qupath", "qupath-extension-bioimageio");
	}

	@Override
	public Version getQuPathVersion() {
		return Version.parse("0.4.0-SNAPSHOT");
	}

}
