package ch.epfl.biop.atlastoimg2d.commands;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import ch.epfl.biop.atlas.commands.PutAtlasStructureToImage;
import ch.epfl.biop.atlastoimg2d.AtlasToImg2D;
import ch.epfl.biop.java.utilities.roi.ConvertibleRois;
import ij.IJ;
import ij.gui.YesNoCancelDialog;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.gui.WaitForUserDialog;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Atlas>Image To Atlas>Wizard")
public class ImageToAtlasWizard implements Command {

	@Parameter
	public CommandService cs;
	
	@Parameter(type = ItemIO.OUTPUT)
	public AtlasToImg2D aligner;

	@Override
	public void run() {
		try {
			Future<CommandModule> task;
			CommandModule cm;
			task = cs.run(ImageToAtlasConstruct.class, true);
			cm = task.get();
			aligner = (AtlasToImg2D) cm.getOutput("aligner");

			WaitForUserDialog dialog = new WaitForUserDialog("Choose slice","Pick carefully the slice you'd like.");
			dialog.show();
			aligner.setAtlasLocation(
			        aligner.getAtlas().map.getCurrentLocation()
            );

			//cs.run(ImageToAtlasRegister.class,true,"aligner", aligner ).get();

			// ---- Adds as many registration as the user wants
			boolean registrationDone = false;

			while (!registrationDone) {
				dialog = new WaitForUserDialog("Modify images ?","Perform changes on images. Click when you're done.");
				dialog.show();
				YesNoCancelDialog dialogw = new YesNoCancelDialog(IJ.getInstance(),
						"Register slice", "Add a registration ?");

				if (dialogw.cancelPressed()||(!dialogw.yesPressed())) {
					registrationDone=true;
				} else {
					cs.run(ImageToAtlasRegister.class,true,"aligner", aligner ).get();
					dialogw = new YesNoCancelDialog(IJ.getInstance(),
							"Was this registration ok ?", "Keep Registration ?");
					if (dialogw.cancelPressed()||(!dialogw.yesPressed())) {

						dialogw = new YesNoCancelDialog(IJ.getInstance(),
								"Delete current registration.", "Are you sure ?");
						if (dialogw.yesPressed()) {
							cs.run(ImageToAtlasRemoveRegister.class,true,"aligner", aligner ).get();
						}
					}
				}
			}

			// ---- Ok -> now let's compute the transformed ROI
			YesNoCancelDialog dialogw = new YesNoCancelDialog(IJ.getInstance(),
					"Compute ROIs ?", "Only if you're happy with the current registration.");

			if (dialogw.yesPressed()) {
				ConvertibleRois cr = (ConvertibleRois) cs.run(ImageToAtlasComputeROIS.class,true,"aligner", aligner ).get().getOutput("cr");
				dialogw = new YesNoCancelDialog(IJ.getInstance(),
						"Diplay ROIs ?", "Display brain regions ?");
				if (dialogw.yesPressed()) {
					cs.run(PutAtlasStructureToImage.class,true,"atlas", aligner.getAtlas(), "cr", cr ).get();

				}

			}


		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
	
}