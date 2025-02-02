package ch.epfl.biop.atlas.aligner;

import bdv.util.*;
import bdv.viewer.Interpolation;
import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.ResourcesMonitor;
import ch.epfl.biop.atlas.BiopAtlas;
import ch.epfl.biop.atlas.aligner.commands.*;
import ch.epfl.biop.atlas.aligner.serializers.*;
import ch.epfl.biop.atlas.aligner.sourcepreprocessors.*;
import ch.epfl.biop.atlas.plugin.IABBARegistrationPlugin;
import ch.epfl.biop.atlas.plugin.RegistrationPluginHelper;
import ch.epfl.biop.bdv.BdvScijavaHelper;
import ch.epfl.biop.bdv.select.SourceSelectorBehaviour;
import ch.epfl.biop.registration.Registration;
import ch.epfl.biop.scijava.ui.swing.ScijavaSwingUI;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Tile;
import net.imglib2.*;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import org.apache.commons.io.FilenameUtils;
import org.scijava.Context;
import org.scijava.InstantiableException;
import org.scijava.cache.CacheService;
import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.object.ObjectService;
import org.scijava.plugin.PluginService;
import org.scijava.ui.behaviour.*;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;
import sc.fiji.bdvpg.bdv.BdvHandleHelper;
import sc.fiji.bdvpg.behaviour.EditorBehaviourUnInstaller;
import sc.fiji.bdvpg.scijava.services.SourceAndConverterService;
import sc.fiji.bdvpg.scijava.services.ui.swingdnd.BdvTransferHandler;
import sc.fiji.bdvpg.services.SourceAndConverterServiceLoader;
import sc.fiji.bdvpg.services.SourceAndConverterServiceSaver;
import sc.fiji.bdvpg.services.SourceAndConverterServices;
import sc.fiji.bdvpg.services.serializers.RuntimeTypeAdapterFactory;
import sc.fiji.bdvpg.sourceandconverter.SourceAndConverterHelper;

import javax.swing.*;
import java.awt.Point;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static bdv.ui.BdvDefaultCards.*;
import static sc.fiji.bdvpg.scijava.services.SourceAndConverterService.SPIM_DATA_INFO;

/**
 * All specific methods and fields dedicated to the multislice positioner
 *
 * There is:
 *
 * - a positioning mode
 *      This is mosly useful at the beginning of the registration
 *      Slices can be moved along the axis / stretched and shrunk
 *      Only certain sections of the atlas are shown to improve global overview, based on the user need
 *
 * - a review mode
 *      This is mostly useful for reviewing the quality of registration
 *      Only one slice is visible at a time
 *      The atlas is fully displayed
 */

public class MultiSlicePositioner extends BdvOverlay implements  GraphicalHandleListener, MouseMotionListener { // SelectedSourcesListener,

    /**
     * Object which serves as a lock in order to allow
     * for executing one task at a time if the user feedback is going
     * to be necessary
     */
    static public final Object manualActionLock = new Object();

    // BdvHandle displaying the multislice positioner - publicly accessible through getBdvh();
    private final BdvHandle bdvh;

    // Behaviour (and linked overlay) that handles user rectangular selection of sources
    // private SourceSelectorBehaviour ssb;

    /**
     * Slicing Model Properties
     * nPixD : number of pixels of the atlas
     * sD : physical dimension of the atlas
     * sizePixD : physical pixel size
     */
    final public int nPixX, nPixY, nPixZ;
    final public double sX, sY, sZ;
    final public double sizePixX, sizePixY, sizePixZ;

    // List of slices contained in multislicepositioner - publicly accessible through getSlices() method
    private List<SliceSources> slices = Collections.synchronizedList(new ArrayList<>());

    // Stack of actions that have been performed by the user - used for undo
    protected List<CancelableAction> userActions = new ArrayList<>();

    // Stack of actions that have been cancelled by the user - used for redo
    protected List<CancelableAction> redoableUserActions = new ArrayList<>();

    // Current coordinate where Sources are dragged
    int iSliceNoStep;

    // scijava context
    Context scijavaCtx;

    // Multislice observer observes and display events happening to slices
    protected MultiSliceObserver mso;

    // Multipositioner display mode
    int displayMode = POSITIONING_MODE_INT;

    public final static int POSITIONING_MODE_INT = 0;
    final static String POSITIONING_MODE = "positioning-mode";
    final static String POSITIONING_BEHAVIOURS_KEY = POSITIONING_MODE + "-behaviours";
    Behaviours positioning_behaviours = new Behaviours(new InputTriggerConfig(), POSITIONING_MODE);

    public final static int REVIEW_MODE_INT = 1;
    final static String REVIEW_MODE = "review-mode";
    final static String REVIEW_BEHAVIOURS_KEY = REVIEW_MODE + "-behaviours";
    Behaviours review_behaviours = new Behaviours(new InputTriggerConfig(), REVIEW_MODE);

    // Slices display mode
    int sliceDisplayMode = ALL_SLICES_DISPLAY_MODE;

    final public static int NO_SLICE_DISPLAY_MODE = 2; // For faster draw when restoring a state
    final public static int CURRENT_SLICE_DISPLAY_MODE = 1; // Only the current slice is displayed
    final public static int ALL_SLICES_DISPLAY_MODE = 0; // All slices are displayed

    final static String COMMON_BEHAVIOURS_KEY = "multipositioner-behaviours";
    Behaviours common_behaviours = new Behaviours(new InputTriggerConfig(), "multipositioner");

    private static final String BLOCKING_MAP = "multipositioner-blocking";

    // Index of the current slice
    int iCurrentSlice = 0;

    // Maximum right position of the selected slices
    Integer[] rightPosition = new Integer[]{0, 0, 0};

    // Maximum left position of the selected slices
    Integer[] leftPosition = new Integer[]{0, 0, 0};

    // Resliced atlas
    ReslicedAtlas reslicedAtlas;

    // Original biop atlas
    BiopAtlas biopAtlas;

    // Selection layer : responsible to listen to mouse drawing events that select sources
    SelectionLayer selectionLayer;

    // Temporary saves the previous slicing steps - I don't remember why it was useful, but it is
    int previouszStep;

    // Rectangle user defined regions that crops the region of interest for registrations
    double roiPX, roiPY, roiSX, roiSY;

    // Loggers

    /**
     * Non blocking log message for users
     */
    public Consumer<String> log = (message) -> {
        System.out.println("Multipositioner : "+message);
        getBdvh().getViewerPanel().showMessage(message);
    };

    /**
     * Non blocking error message for users
     */
    public BiConsumer<String, String> nonBlockingErrorMessageForUser = (title, message) ->
            System.err.println(title+":"+message);

    /**
     * Blocking error message for users
     */
    public BiConsumer<String, String> errorMessageForUser = (title, message) ->
            JOptionPane.showMessageDialog(new JFrame(), message, title, JOptionPane.ERROR_MESSAGE);

    /**
     * Blocking warning message for users
     */
    public BiConsumer<String, String> warningMessageForUser = (title, message) ->
            JOptionPane.showMessageDialog(new JFrame(), message, title, JOptionPane.WARNING_MESSAGE);

    public Consumer<String> errlog = (message) -> {
        System.err.println("Multipositioner : "+message);
        errorMessageForUser.accept("Error", message);
    };

    public Consumer<String> debuglog = (message) -> {};//System.err.println("Multipositioner Debug : "+message);

    /**
     * @return a copy of the array of current slices in this ABBA instance
     */
    public List<SliceSources> getSlices() {
        return new ArrayList<>(slices);
    }

    // Used for CreateSlice action
    protected List<SliceSources> getPrivateSlices() {
        return slices;
    }

    // Flag for overlaying the position of the mouse on the atlas + the region name
    boolean showAtlasPosition = true;

    // Flag for overlaying the info of the slice under which the mouse is
    boolean showSliceInfo = true;

    /**
     * Starts ABBA in a bigdataviewer window
     * @param bdvh a BdvHandle
     * @param biopAtlas an atlas
     * @param reslicedAtlas a resliced atlas
     * @param ctx a scijava context
     */
    public MultiSlicePositioner(BdvHandle bdvh, BiopAtlas biopAtlas, ReslicedAtlas reslicedAtlas, Context ctx) {
        this.reslicedAtlas = reslicedAtlas;
        this.biopAtlas = biopAtlas;
        this.bdvh = bdvh;
        this.scijavaCtx = ctx;

        iSliceNoStep = (int) (reslicedAtlas.getStep());

        this.bdvh.getViewerPanel().setTransferHandler(new MultiSlicePositioner.TransferHandler());

        nPixX = (int) reslicedAtlas.slicingModel.getSpimSource().getSource(0, 0).dimension(0);
        nPixY = (int) reslicedAtlas.slicingModel.getSpimSource().getSource(0, 0).dimension(1);
        nPixZ = (int) reslicedAtlas.slicingModel.getSpimSource().getSource(0, 0).dimension(2);

        AffineTransform3D at3D = new AffineTransform3D();
        reslicedAtlas.slicingModel.getSpimSource().getSourceTransform(0, 0, at3D);

        double[] m = at3D.getRowPackedCopy();

        sizePixX = Math.sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8]);
        sizePixY = Math.sqrt(m[1] * m[1] + m[5] * m[5] + m[9] * m[9]);
        sizePixZ = Math.sqrt(m[2] * m[2] + m[6] * m[6] + m[10] * m[10]);

        sX = nPixX * sizePixX;
        sY = nPixY * sizePixY;
        sZ = nPixZ * sizePixZ;

        BdvFunctions.showOverlay(this, "MultiSlice Overlay", BdvOptions.options().addTo(bdvh));

        /*ssb = (SourceSelectorBehaviour) SourceAndConverterServices.getSourceAndConverterDisplayService().getDisplayMetadata(
                bdvh, SourceSelectorBehaviour.class.getSimpleName());*/
        new EditorBehaviourUnInstaller(bdvh).run();

        // Disable edit mode by default
        bdvh.getTriggerbindings().removeInputTriggerMap(SourceSelectorBehaviour.SOURCES_SELECTOR_TOGGLE_MAP);

        setPositioningMode();

        common_behaviours.behaviour((ClickBehaviour) (x, y) -> this.changeSliceDisplayMode(), "toggle_single_source_mode", "S");
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> this.cancelLastAction(), "cancel_last_action", "ctrl Z", "meta Z");
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> this.redoAction(), "redo_last_action", "ctrl Y", "ctrl shift Z", "meta Y", "ctrl meta Z");
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> this.navigateNextSlice(), "navigate_next_slice", "RIGHT");
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> this.navigatePreviousSlice(), "navigate_previous_slice",  "LEFT"); // P taken for panel
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> this.navigateCurrentSlice(), "navigate_current_slice", "C");
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> this.nextMode(), "change_mode", "R");
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> {slices.forEach(SliceSources::select);bdvh.getViewerPanel().getDisplay().repaint();}, "selectAllSlices", "ctrl A", "meta A");
        common_behaviours.behaviour((ClickBehaviour) (x, y) -> {slices.forEach(SliceSources::deSelect);bdvh.getViewerPanel().getDisplay().repaint();}, "deselectAllSlices", "ctrl shift A", "meta shift A");


        bdvh.getTriggerbindings().addBehaviourMap(COMMON_BEHAVIOURS_KEY, common_behaviours.getBehaviourMap());
        bdvh.getTriggerbindings().addInputTriggerMap(COMMON_BEHAVIOURS_KEY, common_behaviours.getInputTriggerMap()); // "transform", "bdv"

        overrideStandardNavigation();

        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.toggleOverlap(), "toggle_superimpose", "O");

        /*positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> {
            if (ssb.isEnabled()) {
                ssb.disable();
                refreshBlockMap();
            } else {
                ssb.enable();
                refreshBlockMap();
            }
        }, "toggle_editormode", "E");*/

        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.equalSpacingSelectedSlices(), "equalSpacingSelectedSlices", "D");
        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.stretchRightSelectedSlices(), "stretch_selectedslices_right", "ctrl RIGHT", "meta RIGHT");
        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.shrinkRightSelectedSlices(), "shrink_selectedslices_right", "ctrl LEFT", "meta LEFT");
        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.stretchLeftSelectedSlices(), "stretch_selectedslices_left", "ctrl shift LEFT", "meta shift LEFT");
        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.shrinkLeftSelectedSlices(), "shrink_selectedslices_left", "ctrl shift RIGHT", "meta shift RIGHT");
        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.shiftUpSelectedSlices(), "shift_selectedslices_up", "ctrl UP", "meta UP");
        positioning_behaviours.behaviour((ClickBehaviour) (x, y) -> this.shiftDownSelectedSlices(), "shift_selectedslices_down", "ctrl DOWN", "meta DOWN");

        List<SourceAndConverter<?>> sacsToAppend = new ArrayList<>();
        for (int i = 0; i < biopAtlas.map.getStructuralImages().size(); i++) {
            sacsToAppend.add(reslicedAtlas.extendedSlicedSources[i]);
            sacsToAppend.add(reslicedAtlas.nonExtendedSlicedSources[i]);
        }

        SourceAndConverterServices.getSourceAndConverterDisplayService()
                .show(bdvh, sacsToAppend.toArray(new SourceAndConverter[0]));

        bdvh.getViewerPanel().getDisplay().addHandler(this);

        SquareGraphicalHandle ghRight = new SquareGraphicalHandle(this, new DragRight(), "drag_right", "button1", bdvh.getTriggerbindings(),
                () -> new Integer[]{rightPosition[0]+25, rightPosition[1], rightPosition[2]}, () -> 25, () -> new Integer[]{255, 0, 255, 200});

        GraphicalHandleToolTip ghRightToolTip = new GraphicalHandleToolTip(ghRight, "Ctrl + \u25C4 \u25BA ",0,20);

        CircleGraphicalHandle ghCenter = new CircleGraphicalHandle(this, new SelectedSliceSourcesDrag(), "translate_selected", "button1", bdvh.getTriggerbindings(),
                () -> new Integer[]{(leftPosition[0]+rightPosition[0])/2,(leftPosition[1]+rightPosition[1])/2, 0}, () -> 25, () -> new Integer[]{255, 0, 255, 200});

        GraphicalHandleToolTip ghCenterToolTip = new GraphicalHandleToolTip(ghCenter, "Ctrl + \u25B2 \u25BC ",0,0);

        SquareGraphicalHandle ghLeft = new SquareGraphicalHandle(this, new DragLeft(), "drag_right", "button1", bdvh.getTriggerbindings(),
                () -> new Integer[]{leftPosition[0]-25, leftPosition[1], leftPosition[2]}, () -> 25, () -> new Integer[]{255, 0, 255, 200});

        GraphicalHandleToolTip ghLeftToolTip = new GraphicalHandleToolTip(ghLeft, "Ctrl + Shift + \u25C4 \u25BA ", 0,-20);

        stretchRight = ghRight;
        stretchLeft = ghLeft;
        center = ghCenter;

        ghs.add(stretchRight);
        ghs_tool_tip.add(ghRightToolTip);
        ghs_tool_tip.add(ghCenterToolTip);
        ghs_tool_tip.add(ghLeftToolTip);
        ghs.add(center);
        ghs.add(stretchLeft);

        this.bdvh.getCardPanel().setCardExpanded("Sources", false);
        this.bdvh.getCardPanel().setCardExpanded("Groups", false);

        reslicedAtlas.addListener(() -> {
            recenterBdvh();
            updateDisplay();
        });

        previouszStep = (int) reslicedAtlas.getStep();

        mso = new MultiSliceObserver(this);

        //bdvh.getCardPanel().removeCard(DEFAULT_SOURCES_CARD); // Cannot do this : errors
        bdvh.getCardPanel().removeCard(DEFAULT_SOURCEGROUPS_CARD);
        bdvh.getCardPanel().removeCard(DEFAULT_VIEWERMODES_CARD);
        bdvh.getCardPanel().setCardExpanded(DEFAULT_SOURCES_CARD, false);

        BdvScijavaHelper.clearBdvHandleMenuBar(bdvh);

        // Skips 4 levels of hierarchy in scijava command path (Plugins>BIOP>Atlas>Multi Image To Atlas>)
        // And uses the rest to make the hierarchy of the top menu in the bdv window

        int hierarchyLevelsSkipped = 4;

        // Load and Save state
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, MSPStateLoadCommand.class, hierarchyLevelsSkipped,"mp", this );
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, MSPStateSaveCommand.class, hierarchyLevelsSkipped,"mp", this);

        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Edit>Undo [Ctrl+Z]",0, this::cancelLastAction);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Edit>Redo [Ctrl+Shift+Z]",0, this::redoAction);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Edit>Select all slices [Ctrl+A]",0,() -> slices.forEach(SliceSources::select));
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Edit>Deselect all slices [Ctrl+Shift+A]",0,() -> slices.forEach(SliceSources::deSelect));
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Edit>Delete selected slices",0,() ->
                getSortedSlices()
                        .stream()
                        .filter(SliceSources::isSelected)
                        .forEach(slice -> new DeleteSlice(this, slice).runRequest())
        );

        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Edit>Distribute spacing [D]",0,() -> {
            if (this.displayMode == POSITIONING_MODE_INT) this.equalSpacingSelectedSlices();
        });

        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Positioning Mode",0, this::setPositioningMode);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Review Mode",0, this::setRegistrationMode);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Change Slice Display Mode [S]",0, this::changeSliceDisplayMode);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Change Overlap Mode [O]",0, this::toggleOverlap);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Show mouse atlas Position",0, this::showAtlasPosition);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Hide mouse atlas Position",0, this::hideAtlasPosition);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Show slice info at mouse position",0, this::showSliceInfo);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Display>Hide slice info at mouse position",0, this::hideSliceInfo);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Navigate>Next Slice [Right]",0, this::navigateNextSlice);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Navigate>Previous Slice [Left]",0, this::navigatePreviousSlice);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Navigate>Center On Current Slice [C]",0, this::navigateCurrentSlice);

        // Slice importer
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ImportQuPathProjectCommand.class, hierarchyLevelsSkipped,"mp", this );
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ImportImagePlusCommand.class, hierarchyLevelsSkipped,"mp", this );
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ImportImageCommand.class, hierarchyLevelsSkipped,"mp", this );

        // Adds registration plugin commands : discovered via scijava plugin autodiscovery mechanism

        PluginService pluginService = ctx.getService(PluginService.class);

        pluginService.getPluginsOfType(IABBARegistrationPlugin.class).forEach(registrationPluginClass -> {
            IABBARegistrationPlugin plugin = pluginService.createInstance(registrationPluginClass);
            for (Class<? extends Command> commandUI: RegistrationPluginHelper.userInterfaces(plugin)) {
                BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, commandUI, hierarchyLevelsSkipped,"mp", this);
            }
        });

        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, EditLastRegistrationCommand.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addActionToBdvHandleMenu(bdvh,"Align>Remove Last Registration",0, this::removeLastRegistration );

        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ExportRegionsToFileCommand.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ExportRegionsToRoiManagerCommand.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ExportRegionsToQuPathCommand.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ExportSlicesToBDVJsonDataset.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ExportSlicesToBDV.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, RotateSourcesCommand.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, EditSliceThicknessCommand.class, hierarchyLevelsSkipped,"mp", this);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, SliceThicknessMatchNeighborsCommand.class, hierarchyLevelsSkipped,"mp", this);

        // Help commands
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ABBAForumHelpCommand.class, hierarchyLevelsSkipped);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ABBADocumentationCommand.class, hierarchyLevelsSkipped);
        BdvScijavaHelper.addCommandToBdvHandleMenu(bdvh, scijavaCtx, ABBAUserFeedbackCommand.class, hierarchyLevelsSkipped);

        /* Obsolete
        AtlasDisplayPanel adp = new AtlasDisplayPanel(this);
        Hide useless channels on startup -
        adp.getModel().setValueAt(Boolean.FALSE,0,8); // X Coord
        adp.getModel().setValueAt(Boolean.FALSE,0,10);// Y Coord
        adp.getModel().setValueAt(Boolean.FALSE,0,12);// Z Coord
        adp.getModel().setValueAt(Boolean.FALSE,0,14);// Left Right
        adp.getModel().setValueAt(Boolean.FALSE,0,16);// Label ?

        bdvh.getCardPanel().addCard("Atlas Display Options", adp.getPanel(), true);*/

        bdvh.getCardPanel().addCard("Atlas Display", ScijavaSwingUI.getPanel(scijavaCtx, AllenAtlasDisplayCommand.class, "mp", this), true);

        bdvh.getCardPanel().addCard("Slices Display", new SliceDisplayPanel(this).getPanel(), true);

        bdvh.getCardPanel().addCard("Edit Slices", new EditPanel(this).getPanel(), true);

        bdvh.getCardPanel().addCard("Atlas Slicing", ScijavaSwingUI.getPanel(scijavaCtx, SlicerAdjusterInteractiveCommand.class, "reslicedAtlas", reslicedAtlas), true);

        bdvh.getCardPanel().addCard("Define region of interest",
                ScijavaSwingUI.getPanel(scijavaCtx, RectangleROIDefineInteractiveCommand.class, "mp", this),
                false);

        bdvh.getCardPanel().addCard("Tasks Info", mso.getJPanel(), false);

        final ResourcesMonitor rm = new ResourcesMonitor();

        bdvh.getCardPanel().addCard("Resources Monitor", rm, false);

        // Default registration region = full atlas size
        roiPX = -sX / 2.0;
        roiPY = -sY / 2.0;
        roiSX = sX;
        roiSY = sY;

        BiConsumer<RealLocalizable, UnsignedShortType> fun = (loc,val) -> {
            double px = loc.getFloatPosition(0);
            double py = loc.getFloatPosition(1);

            if (py<-sY/1.9) {val.set(0); return;}
            if (py>sY/1.9) {val.set(0); return;}

            if (displayMode == POSITIONING_MODE_INT) {
                final double v = Math.IEEEremainder(px + sX * 0.5, sX);
                if (v < roiPX) {val.set(255); return;}
                if (v > roiPX+roiSX) {val.set(255); return;}
                if (py<roiPY) {val.set(255); return;}
                if (py>roiPY+roiSY) {val.set(255); return;}
                val.set(0);
            }

            if (displayMode == REVIEW_MODE_INT) {
                if (loc.getFloatPosition(0) < roiPX) {val.set(255); return;}
                if (loc.getFloatPosition(0) > roiPX+roiSX) {val.set(255); return;}
                if (loc.getFloatPosition(1)<roiPY) {val.set(255); return;}
                if (loc.getFloatPosition(1)>roiPY+roiSY) {val.set(255); return;}
                val.set(0);
            }

        };

        FunctionRealRandomAccessible<UnsignedShortType> roiOverlay = new FunctionRealRandomAccessible<>(3, fun, UnsignedShortType::new);

        BdvStackSource<?> bss = BdvFunctions.show(roiOverlay,
                new FinalInterval(new long[]{0, 0, 0}, new long[]{10, 10, 10}),"ROI", BdvOptions.options().addTo(bdvh));

        bss.setDisplayRangeBounds(0,1600);
        displayMode = REVIEW_MODE_INT; // For correct toggling
        setPositioningMode();

        addRightClickActions();

        AffineTransform3D iniView = new AffineTransform3D();

        bdvh.getViewerPanel().state().getViewerTransform(iniView);
        iniView.scale(15);
        bdvh.getViewerPanel().state().setViewerTransform(iniView);

        reslicedAtlas.setStep(50);
        reslicedAtlas.setRotateX(0);
        reslicedAtlas.setRotateY(0);

        RealPoint center = SourceAndConverterHelper.getSourceAndConverterCenterPoint(reslicedAtlas.extendedSlicedSources[0]);
        iniView = BdvHandleHelper.getViewerTransformWithNewCenter(bdvh, new double[]{center.getDoublePosition(0), center.getDoublePosition(1), 0});
        bdvh.getViewerPanel().state().setViewerTransform(iniView);

        bdvh.getSplitPanel().setCollapsed(false);
        bdvh.getSplitPanel().setDividerLocation(0.7);

        // Close hook to try to release as many resources as possible
        BdvHandleHelper.setBdvHandleCloseOperation(bdvh, ctx.getService(CacheService.class),
                SourceAndConverterServices.getSourceAndConverterDisplayService(), false,
                () -> {
                    if (mso!=null) this.mso.clear();
                    if (userActions!=null) this.userActions.clear();
                    if (slices!=null) this.slices.clear();
                    this.biopAtlas = null;
                    this.slices = null;
                    this.userActions = null;
                    ctx.getService(ObjectService.class).removeObject(this);
                    this.mso = null;
                    this.selectionLayer = null;
                    this.common_behaviours = null;
                    this.positioning_behaviours = null;
                    this.review_behaviours = null;
                    //this.ssb = null;
                    this.reslicedAtlas = null;
                    this.info = null;
                    rm.stop();
                }
        );
    }

    public void showAtlasPosition() {
        showAtlasPosition = true;
    }

    public void hideAtlasPosition() {
        showAtlasPosition = false;
    }

    public void showSliceInfo() {
        showSliceInfo = true;
    }

    public void hideSliceInfo() {
        showSliceInfo = false;
    }

    void addRightClickActions() {
        common_behaviours.behaviour(new MultiSliceContextMenuClickBehaviour( this, this::getSelectedSources ), "Slices Context Menu", "button3", "ctrl button1", "meta button1");
    }

    public void changeSliceDisplayMode() {
        sliceDisplayMode = 1-sliceDisplayMode;
        setSliceDisplayMode(sliceDisplayMode);
    }

    public void setSliceDisplayMode(int newSliceDisplayMode) {
        modeListeners.forEach(l -> l.sliceDisplayModeChanged(this, this.sliceDisplayMode,newSliceDisplayMode));
        this.sliceDisplayMode = newSliceDisplayMode;
        getSlices().forEach(slice -> slice.getGUIState().sliceDisplayModeChanged());
    }

    public ReslicedAtlas getReslicedAtlas() {
        return reslicedAtlas;
    }

    public List<SliceSources> getSelectedSources() {
        return getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
    }

    public BdvHandle getBdvh() {
        return bdvh;
    }

    private void overrideStandardNavigation() {
        bdvh.getKeybindings().addInputMap("blocking-multipositioner", new InputMap(), "bdv", "navigation");
        InputTriggerMap itm = new InputTriggerMap();

        itm.put(InputTrigger.getFromString("button3"), "drag translate");
        itm.put(InputTrigger.getFromString("UP"), "zoom in");
        itm.put(InputTrigger.getFromString("shift UP"), "zoom in fast");
        //itm.put(InputTrigger.getFromString("ctrl UP"), "zoom in slow");
        itm.put(InputTrigger.getFromString("scroll"), "scroll zoom");

        itm.put(InputTrigger.getFromString("DOWN"), "zoom out");
        itm.put(InputTrigger.getFromString("shift DOWN"), "zoom out fast");
        //itm.put(InputTrigger.getFromString("ctrl DOWN"), "zoom out slow");

        selectionLayer = new SelectionLayer(this);
        selectionLayer.addSelectionBehaviours(common_behaviours);
        refreshBlockMap();

        bdvh.getTriggerbindings().addInputTriggerMap("default_navigation", itm, "transform");
    }

    public void recenterBdvh() {
        double cur_wcx = bdvh.getViewerPanel().getWidth() / 2.0; // Current Window Center X
        double cur_wcy = bdvh.getViewerPanel().getHeight() / 2.0; // Current Window Center Y

        RealPoint centerScreenCurrentBdv = new RealPoint(cur_wcx, cur_wcy, 0);
        RealPoint centerScreenGlobalCoord = new RealPoint(3);

        AffineTransform3D at3D = new AffineTransform3D();
        bdvh.getBdvHandle().getViewerPanel().state().getViewerTransform(at3D);

        at3D.inverse().apply(centerScreenCurrentBdv, centerScreenGlobalCoord);

        // New target
        centerScreenGlobalCoord.setPosition((centerScreenGlobalCoord.getDoublePosition(0) - sX / 2.0) * (double) previouszStep / (double) reslicedAtlas.getStep() + sX / 2.0, 0);

        // How should we translate at3D, such as the screen center is the new one

        // Now compute what should be the matrix in the next bdv frame:
        AffineTransform3D nextAffineTransform = new AffineTransform3D();

        // It should have the same scaling and rotation than the current view
        nextAffineTransform.set(at3D);

        // No Shift
        nextAffineTransform.set(0, 0, 3);
        nextAffineTransform.set(0, 1, 3);
        nextAffineTransform.set(0, 2, 3);

        // But the center of the window should be centerScreenGlobalCoord
        // Let's compute the shift
        double next_wcx = bdvh.getViewerPanel().getWidth() / 2.0; // Next Window Center X
        double next_wcy = bdvh.getViewerPanel().getHeight() / 2.0; // Next Window Center Y

        RealPoint centerScreenNextBdv = new RealPoint(next_wcx, next_wcy, 0);
        RealPoint shiftNextBdv = new RealPoint(3);

        nextAffineTransform.inverse().apply(centerScreenNextBdv, shiftNextBdv);

        double sx = -centerScreenGlobalCoord.getDoublePosition(0) + shiftNextBdv.getDoublePosition(0);
        double sy = -centerScreenGlobalCoord.getDoublePosition(1) + shiftNextBdv.getDoublePosition(1);
        double sz = -centerScreenGlobalCoord.getDoublePosition(2) + shiftNextBdv.getDoublePosition(2);

        RealPoint shiftWindow = new RealPoint(sx, sy, sz);
        RealPoint shiftMatrix = new RealPoint(3);
        nextAffineTransform.apply(shiftWindow, shiftMatrix);

        nextAffineTransform.set(shiftMatrix.getDoublePosition(0), 0, 3);
        nextAffineTransform.set(shiftMatrix.getDoublePosition(1), 1, 3);
        nextAffineTransform.set(shiftMatrix.getDoublePosition(2), 2, 3);

        bdvh.getBdvHandle().getViewerPanel().state().setViewerTransform(nextAffineTransform);
        previouszStep = (int) reslicedAtlas.getStep();
    }

    /**
     * Gets all slices sorted along the slicing axis
     *
     * @return the list of slices present, sorted along the z axis
     */
    public List<SliceSources> getSortedSlices() {
        List<SliceSources> sortedSlices = new ArrayList<>(slices);
        sortedSlices.sort(Comparator.comparingDouble(SliceSources::getSlicingAxisPosition));
        // Sending index info to slices each time this function is called
        for (int i = 0; i < sortedSlices.size(); i++) {
            sortedSlices.get(i).setIndex(i);
        }
        return sortedSlices;
    }

    // --------------------------------------------------------- SETTING MODES

    /**
     * Toggles between positioning and registration mode
     */
    public void nextMode() {
        switch (displayMode) {
            case POSITIONING_MODE_INT:
                setRegistrationMode();
                break;
            case REVIEW_MODE_INT:
                setPositioningMode();
                break;
        }
    }

    /**
     * Set the positioning mode
     */
    public void setPositioningMode() {
        if (!(displayMode == POSITIONING_MODE_INT)) {
            int oldMode = displayMode;
            reslicedAtlas.unlock();
            displayMode = POSITIONING_MODE_INT;
            ghs.forEach(GraphicalHandle::enable);
            slices.forEach(slice -> slice.getGUIState().enableGraphicalHandles());
            getSortedSlices().forEach(slice -> slice.getGUIState().displayModeChanged());

            bdvh.getTriggerbindings().removeInputTriggerMap(REVIEW_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeBehaviourMap(REVIEW_BEHAVIOURS_KEY);
            positioning_behaviours.install(bdvh.getTriggerbindings(), POSITIONING_BEHAVIOURS_KEY);
            navigateCurrentSlice();
            refreshBlockMap();

            modeListeners.forEach(ml -> ml.modeChanged(this, oldMode, displayMode));
        }
    }

    public void hide(SliceSources slice) {
        slice.getGUIState().setSliceInvisible();
    }

    public void show(SliceSources slice) {
        slice.getGUIState().setSliceVisible();
    }

    /**
     * Set the registration mode
     */
    public void setRegistrationMode() {
        if (!(displayMode == REVIEW_MODE_INT)) {
            int oldMode = REVIEW_MODE_INT;
            displayMode = POSITIONING_MODE_INT;
            reslicedAtlas.lock();
            displayMode = REVIEW_MODE_INT;

            ghs.forEach(GraphicalHandle::disable);

            //synchronized (slices) {
            getSortedSlices().forEach(slice -> slice.getGUIState().displayModeChanged());
            //}

            bdvh.getTriggerbindings().removeInputTriggerMap(POSITIONING_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeBehaviourMap(POSITIONING_BEHAVIOURS_KEY);
            review_behaviours.install(bdvh.getTriggerbindings(), REVIEW_BEHAVIOURS_KEY);
            navigateCurrentSlice();
            refreshBlockMap();
            modeListeners.forEach(ml -> ml.modeChanged(this, oldMode, displayMode));
        }
    }

    // -------------------------------------------------------- NAVIGATION ( BOTH MODES )

    /**
     * Center bdv on next slice (iCurrentSlice + 1)
     */
    public void navigateNextSlice() {
        List<SliceSources> sortedSlices = getSortedSlices();
        int previousSliceIndex = iCurrentSlice;
        iCurrentSlice++;
        if (iCurrentSlice >= sortedSlices.size()) {
            iCurrentSlice = 0;
        }
        if (sortedSlices.size() > 0) {

            SliceSources previousSlice = null;
            if ((previousSliceIndex>=0)&&(previousSliceIndex<sortedSlices.size())) {
                previousSlice = sortedSlices.get(previousSliceIndex);
            }
            centerBdvViewOn(sortedSlices.get(iCurrentSlice), true, previousSlice);
            //centerBdvViewOn(sortedSlices.get(iCurrentSlice), true);
            if ((previousSliceIndex>=0)&&(previousSliceIndex<sortedSlices.size())) { // Could have been deleted
                sortedSlices.get(previousSliceIndex).getGUIState().isNotCurrent();
            }
            sortedSlices.get(iCurrentSlice).getGUIState().isCurrent();
        }
    }

    /**
     * Defines, in physical units, the region that will be used to perform the automated registration
     * @param px center of the region, x axis
     * @param py center of the region, y axis
     * @param sx size of the region, x axis
     * @param sy size of the region, y axis
     */
    public void setROI(double px, double py, double sx, double sy) {
        if (px<-sX / 2.0) {
            double delta = -(px+sX/2.0);
            px = -sX / 2.0;
            sx = sx-delta;
        }
        if (px > sX/2.0) {px = sX/2.0;}

        if (px+sx>sX / 2.0) {
            sx = sX / 2.0 -px;
        }

        if (py<-sY / 2.0) {
            double delta = -(py+sY/2.0);
            py = -sY / 2.0;
            sy = sy-delta;
        }
        if (py > sY/2.0) {py = sY/2.0;}

        if (py+sy>sY/2.0) {
            sy = sY/2.0-py;
        }

        roiPX = px;
        roiPY = py;
        roiSX = sx;
        roiSY = sy;

    }

    public SliceSources getCurrentSlice() {
        List<SliceSources> sortedSlices = getSortedSlices();

        if (sortedSlices.size()>0) {
            if (iCurrentSlice >= sortedSlices.size()) {
                iCurrentSlice = 0;
            }
            return sortedSlices.get(iCurrentSlice);
        } else {
            return null;
        }
    }

    public int getCurrentSliceIndex() {
        return iCurrentSlice;
    }

    /**
     * Center bdv on current slice (iCurrentSlice)
     */
    public void navigateCurrentSlice() {
        List<SliceSources> sortedSlices = getSortedSlices();

        if (iCurrentSlice >= sortedSlices.size()) {
            iCurrentSlice = 0;
        }

        if (sortedSlices.size() > 0) {
            centerBdvViewOn(sortedSlices.get(iCurrentSlice));
        }
    }

    /**
     * Center bdv on previous slice (iCurrentSlice - 1)
     */
    public void navigatePreviousSlice() {
        int previousSliceIndex = iCurrentSlice;
        iCurrentSlice--;
        List<SliceSources> sortedSlices = getSortedSlices();

        if (iCurrentSlice < 0) {
            iCurrentSlice = sortedSlices.size() - 1;
        }

        if (sortedSlices.size() > 0) {
            SliceSources previousSlice = null;
            if ((previousSliceIndex>=0)&&(previousSliceIndex<sortedSlices.size())) {
                previousSlice = sortedSlices.get(previousSliceIndex);
            }
            centerBdvViewOn(sortedSlices.get(iCurrentSlice), true, previousSlice);
            if ((previousSliceIndex>=0)&&(previousSliceIndex<sortedSlices.size())) { // Could have been deleted
                sortedSlices.get(previousSliceIndex).getGUIState().isNotCurrent();
            }
            sortedSlices.get(iCurrentSlice).getGUIState().isCurrent();
        }
    }

    public void selectSlice(SliceSources... slices) {
        for (SliceSources slice : slices) {
            slice.select();
        }
    }

    public void selectSlice(List<SliceSources> slices) {
        selectSlice(slices.toArray(new SliceSources[0]));
    }

    public void deselectSlice(SliceSources... slices) {
        for (SliceSources slice : slices) {
            slice.deSelect();
        }
    }

    public void deselectSlice(List<SliceSources> slices) {
        deselectSlice(slices.toArray(new SliceSources[0]));
    }

    public void waitForTasks() {
        slices.forEach(SliceSources::waitForEndOfTasks);
    }

    public void centerBdvViewOn(SliceSources slice) {
        centerBdvViewOn(slice, false, null);
    }

    // Center bdv on a slice
    public void centerBdvViewOn(SliceSources current_slice, boolean maintainoffset, SliceSources previous_slice) {

        RealPoint offset = new RealPoint(3);

        if ((maintainoffset)&&(previous_slice!=null)) {

            RealPoint oldCenter = new RealPoint(3);

            if (displayMode == MultiSlicePositioner.POSITIONING_MODE_INT) {
                oldCenter = previous_slice.getGUIState().getCenterPositionPMode();
            } else if (displayMode == MultiSlicePositioner.REVIEW_MODE_INT) {
                oldCenter = previous_slice.getGUIState().getCenterPositionRMode();
            }

            RealPoint centerScreen = getCurrentBdvCenter();
            offset.setPosition(-oldCenter.getDoublePosition(0) + centerScreen.getDoublePosition(0), 0);
            offset.setPosition(-oldCenter.getDoublePosition(1) + centerScreen.getDoublePosition(1), 1);
            //offset.setPosition(-oldCenter.getDoublePosition(2) + centerScreen.getDoublePosition(2), 2); // hmm no reason to maintain offset in z

            if (Math.abs(offset.getDoublePosition(0))>sX/2.0) {maintainoffset = false;}
            if (Math.abs(offset.getDoublePosition(1))>sY/2.0) {maintainoffset = false;}

        } else {
            maintainoffset = false;
        }

        RealPoint centerSlice = new RealPoint(3);

        if (displayMode == MultiSlicePositioner.POSITIONING_MODE_INT) {
            centerSlice = current_slice.getGUIState().getCenterPositionPMode();
        } else if (displayMode == MultiSlicePositioner.REVIEW_MODE_INT) {
            centerSlice = current_slice.getGUIState().getCenterPositionRMode();
        }

        if(maintainoffset) {
            centerSlice.move(offset);
        }
        AffineTransform3D at3d = BdvHandleHelper.getViewerTransformWithNewCenter(bdvh, centerSlice.positionAsDoubleArray());

        bdvh.getViewerPanel().state().setViewerTransform(at3d);
        bdvh.getViewerPanel().requestRepaint();

    }

    RealPoint getCurrentBdvCenter() {
        RealPoint centerBdv = new RealPoint(3);

        double px = bdvh.getViewerPanel().getDisplay().getWidth() / 2.0;
        double py = bdvh.getViewerPanel().getDisplay().getHeight() / 2.0;
        bdvh.getViewerPanel().displayToGlobalCoordinates(px,py,centerBdv);

        return centerBdv;
    }

    public void updateDisplay() {
        // Sort slices along slicing axis
        if (overlapMode == 0) {
            slices.forEach(slice -> slice.getGUIState().setYShift(1));
        } else if (overlapMode == 2) {

            double lastPositionAlongX = -Double.MAX_VALUE;

            int stairIndex = 0;

            for (SliceSources slice : getSortedSlices()) {
                double posX = slice.getGUIState().getCenterPositionPMode().getDoublePosition(0);
                if (posX >= (lastPositionAlongX + sX)) {
                    stairIndex = 0;
                    lastPositionAlongX = posX;
                    slice.getGUIState().setYShift(1);
                } else {
                    stairIndex++;
                    slice.getGUIState().setYShift(1 + stairIndex);
                }
            }
        } else if (overlapMode == 1) {
            slices.forEach(slice -> slice.getGUIState().setYShift(0));
        }

        bdvh.getViewerPanel().requestRepaint();
    }

    int overlapMode = 2;

    @Override
    protected void draw(Graphics2D g) {
        // Gets a copy of the slices to avoid concurrent exception
        List<SliceSources> slicesCopy = getSlices();

        // Gets current bdv view position
        AffineTransform3D bdvAt3D = new AffineTransform3D();
        bdvh.getViewerPanel().state().getViewerTransform(bdvAt3D);

        drawDragAndDropRectangle(g, bdvAt3D);

        slicesCopy.forEach(slice -> slice.getGUIState().drawGraphicalHandles(g));

        drawCurrentSliceOverlay(g, slicesCopy);

        if (displayMode == POSITIONING_MODE_INT) drawSetOfSliceControls(g, bdvAt3D, slicesCopy);

        if (selectionLayer != null) selectionLayer.draw(g);

        if (mso != null) mso.draw(g);

        if (showAtlasPosition) drawAtlasPosition(g);

        if (showSliceInfo) drawSliceInfo(g, slicesCopy);

    }

    private void drawSliceInfo(Graphics2D g, List<SliceSources> slicesCopy) {
        RealPoint mouseWindowPosition = new RealPoint(2);
        bdvh.getViewerPanel().getMouseCoordinates(mouseWindowPosition);
        // Slice info is displayed if the mouse is over the round slice handle
        Optional<SliceSources> optSlice = slicesCopy.stream()
                .filter(slice -> {
                    Integer[] coords = slice.getGUIState().getBdvHandleCoords();
                    if (coords==null) return false;
                    int radius = slice.getGUIState().getBdvHandleRadius();
                    double dx = coords[0]-mouseWindowPosition.getDoublePosition(0);
                    double dy = coords[1]-mouseWindowPosition.getDoublePosition(1);
                    double dist = Math.sqrt(dx*dx+dy*dy);
                    return dist<radius;
                }).findFirst();

        if (optSlice.isPresent()) {
            SliceSources slice = optSlice.get();
            String info = slice.getInfo();
            g.setFont(new Font("TimesRoman", Font.BOLD, 16));
            g.setColor(new Color(32, 125, 49, 220));
            Point mouseLocation = bdvh.getViewerPanel().getMousePosition();
            if ((info!=null)&&(mouseLocation!=null)) {
                drawString(g,info,mouseLocation.x,mouseLocation.y+40);
            }
        }

    }

    private void drawString(Graphics2D g, String info, int x, int y) {
        int lineHeight = g.getFontMetrics().getHeight();
        for (String line: info.split("\n"))
            g.drawString(line,x, y += lineHeight);
    }

    private void drawSetOfSliceControls(Graphics2D g, AffineTransform3D bdvAt3D, List<SliceSources> slicesCopy) {

        if (slicesCopy.stream().anyMatch(SliceSources::isSelected)) {

            List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
            RealPoint precedentPoint = null;

            for (int i = 0; i < sortedSelected.size(); i++) {
                SliceSources slice = sortedSelected.get(i);

                Integer[] coords = slice.getGUIState().getBdvHandleCoords();
                RealPoint sliceCenter = new RealPoint(coords[0], coords[1], 0);

                if (precedentPoint != null) {
                    g.drawLine((int) precedentPoint.getDoublePosition(0), (int) precedentPoint.getDoublePosition(1),
                            (int) sliceCenter.getDoublePosition(0), (int) sliceCenter.getDoublePosition(1));
                } else {
                    precedentPoint = new RealPoint(2);
                }

                precedentPoint.setPosition(sliceCenter.getDoublePosition(0), 0);
                precedentPoint.setPosition(sliceCenter.getDoublePosition(1), 1);

                bdvAt3D.apply(sliceCenter, sliceCenter);

                if (i == 0) {
                    RealPoint handleLeftPoint = slice.getGUIState().getCenterPositionPMode();
                    handleLeftPoint.setPosition(+sY/2.0, 1);
                    bdvAt3D.apply(handleLeftPoint, handleLeftPoint);

                    leftPosition[0] = (int) handleLeftPoint.getDoublePosition(0);
                    leftPosition[1] = (int) handleLeftPoint.getDoublePosition(1);
                }

                if (i == sortedSelected.size() - 1) {
                    RealPoint handleRightPoint = slice.getGUIState().getCenterPositionPMode();
                    handleRightPoint.setPosition(+sY/2.0, 1);
                    bdvAt3D.apply(handleRightPoint, handleRightPoint);

                    rightPosition[0] = (int) handleRightPoint.getDoublePosition(0);
                    rightPosition[1] = (int) handleRightPoint.getDoublePosition(1);
                }
            }

            if (sortedSelected.size() > 1) {
                center.enable();
                stretchLeft.enable();
                stretchRight.enable();
                ghs.forEach(GraphicalHandle::enable);
                g.setColor(new Color(255, 0, 255, 200));
                g.drawLine(leftPosition[0], leftPosition[1], rightPosition[0], rightPosition[1]);
            } else if (sortedSelected.size() == 1) {
                center.enable();//ghs.forEach(GraphicalHandle::enable);
                stretchLeft.disable();
                stretchRight.disable();
                g.setColor(new Color(255, 0, 255, 200));
                g.drawLine(leftPosition[0], leftPosition[1], rightPosition[0], rightPosition[1]);
            } else {
                ghs.forEach(GraphicalHandle::disable);
            }
            ghs.forEach(gh -> gh.draw(g));
            ghs_tool_tip.forEach(gh -> gh.draw(g));
        }
    }

    private void drawAtlasPosition(Graphics2D g) {

        RealPoint globalMouseCoordinates = new RealPoint(3);
        bdvh.getViewerPanel().getGlobalMouseCoordinates(globalMouseCoordinates);
        int labelValue;
        int leftRight;
        float[] coords = new float[3];
        if (displayMode==POSITIONING_MODE_INT) {
            SourceAndConverter label = reslicedAtlas.extendedSlicedSources[reslicedAtlas.extendedSlicedSources.length-1]; // By convention the label image is the last one
            labelValue = ((UnsignedShortType) getSourceValueAt(label, globalMouseCoordinates)).get();
            SourceAndConverter lrSource = reslicedAtlas.extendedSlicedSources[reslicedAtlas.extendedSlicedSources.length-2]; // By convention the left right indicator image is the next to last one
            leftRight = ((UnsignedShortType) getSourceValueAt(lrSource, globalMouseCoordinates)).get();

            SourceAndConverter xSource = reslicedAtlas.extendedSlicedSources[reslicedAtlas.extendedSlicedSources.length-5]; // (bad) convention TODO : safer indexing
            SourceAndConverter ySource = reslicedAtlas.extendedSlicedSources[reslicedAtlas.extendedSlicedSources.length-4]; // By convention the left right indicator image is the next to last one
            SourceAndConverter zSource = reslicedAtlas.extendedSlicedSources[reslicedAtlas.extendedSlicedSources.length-3]; // By convention the left right indicator image is the next to last one

            coords[0] = ((FloatType) getSourceValueAt(xSource, globalMouseCoordinates)).get();
            coords[1] = ((FloatType) getSourceValueAt(ySource, globalMouseCoordinates)).get();
            coords[2] = ((FloatType) getSourceValueAt(zSource, globalMouseCoordinates)).get();
        } else {
            assert displayMode == REVIEW_MODE_INT;
            SourceAndConverter label = reslicedAtlas.nonExtendedSlicedSources[reslicedAtlas.nonExtendedSlicedSources.length-1]; // By convention the label image is the last one
            labelValue = ((UnsignedShortType) getSourceValueAt(label, globalMouseCoordinates)).get();
            SourceAndConverter lrSource = reslicedAtlas.nonExtendedSlicedSources[reslicedAtlas.nonExtendedSlicedSources.length-2]; // By convention the left right indicator image is the next to last one
            leftRight = ((UnsignedShortType) getSourceValueAt(lrSource, globalMouseCoordinates)).get();

            SourceAndConverter xSource = reslicedAtlas.nonExtendedSlicedSources[reslicedAtlas.nonExtendedSlicedSources.length-5]; // (bad) convention TODO : safer indexing
            SourceAndConverter ySource = reslicedAtlas.nonExtendedSlicedSources[reslicedAtlas.nonExtendedSlicedSources.length-4]; // By convention the left right indicator image is the next to last one
            SourceAndConverter zSource = reslicedAtlas.nonExtendedSlicedSources[reslicedAtlas.nonExtendedSlicedSources.length-3]; // By convention the left right indicator image is the next to last one

            coords[0] = ((FloatType) getSourceValueAt(xSource, globalMouseCoordinates)).get();
            coords[1] = ((FloatType) getSourceValueAt(ySource, globalMouseCoordinates)).get();
            coords[2] = ((FloatType) getSourceValueAt(zSource, globalMouseCoordinates)).get();
        }

        DecimalFormat df = new DecimalFormat("#0.00");
        String coordinates = "["+df.format(coords[0])+";"+df.format(coords[1])+";"+df.format(coords[2])+"]";
        if (leftRight == 255) {
            coordinates += "(R)";
        }
        if (leftRight == 0) {
            coordinates += "(L)";
        }
        StringBuilder ontologyLocation = null;
        if (labelValue!=0) {
            ontologyLocation = new StringBuilder(biopAtlas.ontology.getProperties(labelValue).get("acronym"));
            while (labelValue!=biopAtlas.ontology.getRootIndex()) {
                labelValue = biopAtlas.ontology.getParent(labelValue);
                if (labelValue!=biopAtlas.ontology.getRootIndex())
                    ontologyLocation.append("<").append(biopAtlas.ontology.getProperties(labelValue).get("acronym"));
            }

        }

        g.setFont(new Font("TimesRoman", Font.BOLD, 16));
        g.setColor(new Color(255, 255, 100, 250));
        Point mouseLocation = bdvh.getViewerPanel().getMousePosition();
        if ((ontologyLocation!=null)&&(mouseLocation!=null)) {
            g.drawString(ontologyLocation.toString(),mouseLocation.x,mouseLocation.y);
        }
        if ((mouseLocation!=null)&&(!coordinates.startsWith("[0.00;0.00;0.00]"))) {
            g.drawString(coordinates, mouseLocation.x, mouseLocation.y - 20);
        }

    }

    private void drawCurrentSliceOverlay(Graphics2D g, List<SliceSources> slicesCopy) {

        if (iCurrentSlice != -1 && slicesCopy.size() > iCurrentSlice) {
            SliceSources slice = getSortedSlices().get(iCurrentSlice);
            listeners.forEach(listener -> listener.isCurrentSlice(slice));
            g.setColor(new Color(255, 255, 255, 128));
            g.setStroke(new BasicStroke(5));
            Integer[] coords = slice.getGUIState().getBdvHandleCoords();
            RealPoint sliceCenter = new RealPoint(coords[0], coords[1], 0);
            g.drawOval((int) sliceCenter.getDoublePosition(0) - 15, (int) sliceCenter.getDoublePosition(1) - 15, 29, 29);
            Integer[] c = {255,255,255,128};
            g.setColor(new Color(c[0], c[1], c[2], c[3]));
            g.setFont(new Font("TimesRoman", Font.PLAIN, 16));
            g.drawString("\u25C4 \u25BA", (int) (sliceCenter.getDoublePosition(0) - 15), (int) (sliceCenter.getDoublePosition(1) - 20));
        }
    }

    void drawDragAndDropRectangle(Graphics2D g, AffineTransform3D bdvAt3D) {
        int colorCode = this.info.getColor().get();

        Color color = new Color(ARGBType.red(colorCode), ARGBType.green(colorCode), ARGBType.blue(colorCode), ARGBType.alpha(colorCode));

        g.setColor(color);

        RealPoint[][] ptRectWorld = new RealPoint[2][2];

        Point[][] ptRectScreen = new Point[2][2];

        for (int xp = 0; xp < 2; xp++) {
            for (int yp = 0; yp < 2; yp++) {
                ptRectWorld[xp][yp] = new RealPoint(3);
                RealPoint pt = ptRectWorld[xp][yp];
                pt.setPosition(sX * (iSliceNoStep + xp), 0);
                pt.setPosition(sY * (0.5 + yp), 1);
                pt.setPosition(0, 2);
                bdvAt3D.apply(pt, pt);
                ptRectScreen[xp][yp] = new Point((int) pt.getDoublePosition(0), (int) pt.getDoublePosition(1));
            }
        }

        g.drawLine(ptRectScreen[0][0].x, ptRectScreen[0][0].y, ptRectScreen[1][0].x, ptRectScreen[1][0].y);
        g.drawLine(ptRectScreen[1][0].x, ptRectScreen[1][0].y, ptRectScreen[1][1].x, ptRectScreen[1][1].y);
        g.drawLine(ptRectScreen[1][1].x, ptRectScreen[1][1].y, ptRectScreen[0][1].x, ptRectScreen[0][1].y);
        g.drawLine(ptRectScreen[0][1].x, ptRectScreen[0][1].y, ptRectScreen[0][0].x, ptRectScreen[0][0].y);

        g.setColor(color);
    }

    Object getSourceValueAt(SourceAndConverter sac, RealPoint pt) {
        RealRandomAccessible rra_ible = sac.getSpimSource().getInterpolatedSource(0, 0, Interpolation.NEARESTNEIGHBOR);
        if (rra_ible != null) {
            AffineTransform3D sourceTransform = new AffineTransform3D();
            sac.getSpimSource().getSourceTransform(0, 0, sourceTransform);
            RealRandomAccess rra = rra_ible.realRandomAccess();
            RealPoint iPt = new RealPoint(3);
            sourceTransform.inverse().apply(pt, iPt);
            rra.setPosition(iPt);
            return rra.get();
        } else {
            return null;
        }
    }

    public String getUndoMessage() {
        if (userActions.size()==0) {
            return "(None)";
        } else {
            CancelableAction lastAction = userActions.get(userActions.size()-1);
            if (lastAction instanceof MarkActionSequenceBatch) {
                CancelableAction lastlastAction = userActions.get(userActions.size()-2);
                return "("+lastlastAction.actionClassString()+" [batch])";
            } else {
                return "("+lastAction.actionClassString()+")";
            }
        }
    }

    public String getRedoMessage() {
        if (redoableUserActions.size()==0) {
            return "(None)";
        } else {
            CancelableAction lastAction = redoableUserActions.get(redoableUserActions.size()-1);
            if (lastAction instanceof MarkActionSequenceBatch) {
                CancelableAction lastlastAction = redoableUserActions.get(redoableUserActions.size()-2);
                return "("+lastlastAction.actionClassString()+" [batch])";
            } else {
                return "("+lastAction.actionClassString()+")";
            }
        }
    }

    public void rotateSlices(int axis, double angle_rad) {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        for (SliceSources slice : sortedSelected) {
            slice.rotateSourceOrigin(axis, angle_rad);
        }
        bdvh.getViewerPanel().requestRepaint();
    }

    public int getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(int mode) {
        switch (mode) {
            case REVIEW_MODE_INT:
                setRegistrationMode();
                break;
            case POSITIONING_MODE_INT :
                setPositioningMode();
                break;
            default:
                errlog.accept("Unknown display mode "+mode);
        }
    }

    public int getSliceDisplayMode() {
        return sliceDisplayMode;
    }

    public void showAllSlices() {
        for (SliceSources slice : getSortedSlices()) {
            slice.getGUIState().setSliceVisible();
        }
    }

    public void showCurrentSlice() {
        getSortedSlices().get(iCurrentSlice).getGUIState().setSliceVisible();
    }

    protected void removeSlice(SliceSources sliceSource) {
        listeners.forEach(listener -> {
            System.out.println(listener);
            listener.sliceDeleted(sliceSource);
        });
        slices.remove(sliceSource);
        sliceSource.getGUIState().sliceDeleted();
    }

    public boolean isCurrentSlice(SliceSources slice) {
        List<SliceSources> sortedSlices = getSortedSlices();
        if (iCurrentSlice >= sortedSlices.size()) {
            iCurrentSlice = 0;
        }

        if (sortedSlices.size() > 0) {
            return slice.equals(sortedSlices.get(iCurrentSlice));
        } else {
            return false;
        }
    }

    protected void createSlice(SliceSources sliceSource) {
        slices.add(sliceSource);
        listeners.forEach(listener -> listener.sliceCreated(sliceSource));
    }

    public void positionZChanged(SliceSources slice) {
        listeners.forEach(listener -> listener.sliceZPositionChanged(slice));
    }

    public void sliceVisibilityChanged(SliceSources slice) {
        listeners.forEach(listener -> listener.sliceVisibilityChanged(slice));
    }

    public void sliceSelected(SliceSources slice) {
        listeners.forEach(listener -> listener.sliceSelected(slice));
    }

    public void sliceDeselected(SliceSources slice) {
        listeners.forEach(listener -> listener.sliceDeselected(slice));
    }

    List<ModeListener> modeListeners = new ArrayList<>();

    public void addModeListener(ModeListener modeListener) {
        modeListeners.add(modeListener);
    }

    public void removeModeListener(ModeListener modeListener) {
        modeListeners.remove(modeListener);
    }

    public BiopAtlas getAtlas() {
        return biopAtlas;
    }

    public int getNumberOfAtlasChannels() {
        return reslicedAtlas.nonExtendedSlicedSources.length;
    }

    public int getNumberOfSelectedSources() {
        return getSelectedSources().size();
    }

    public int getChannelBoundForSelectedSlices() {
        List<SliceSources> sources = getSelectedSources();
        if (sources.size()==0) {
            return 0;
        } else {
            return sources.stream()
                    .mapToInt(slice -> slice.nChannels)
                    .min().getAsInt();
        }
    }

    public AffineTransform3D getAffineTransformFormAlignerToAtlas() {
        return reslicedAtlas.getSlicingTransformToAtlas();
    }

    public List<CancelableAction> getActionsFromSlice(SliceSources sliceSource) {
        return mso.getActionsFromSlice(sliceSource);
    }

    /**
     * TransferHandler class :
     * Controls drag and drop actions in the multislice positioner
     */
    class TransferHandler extends BdvTransferHandler {

        @Override
        public void updateDropLocation(TransferSupport support, DropLocation dl) {

            // Gets the point in real coordinates
            RealPoint pt3d = new RealPoint(3);
            bdvh.getViewerPanel().displayToGlobalCoordinates(dl.getDropPoint().x, dl.getDropPoint().y, pt3d);

            // Computes which slice it corresponds to (useful for overlay redraw)
            iSliceNoStep = (int) (pt3d.getDoublePosition(0) / sX);

            //Repaint the overlay only
            bdvh.getViewerPanel().getDisplay().repaint();
        }

        /**
         * When the user drops the data -> import the slices
         *
         * @param support weird stuff for swing drag and drop TODO : link proper documentation
         * @param sacs list of source and converter to import
         */
        @Override
        public void importSourcesAndConverters(TransferSupport support, List<SourceAndConverter<?>> sacs) {
            Optional<BdvHandle> bdvh = getBdvHandleFromViewerPanel(((bdv.viewer.ViewerPanel) support.getComponent()));
            if (bdvh.isPresent()) {
                double slicingAxisPosition = iSliceNoStep * sizePixX * (int) reslicedAtlas.getStep();
                createSlice(sacs.toArray(new SourceAndConverter[0]), slicingAxisPosition, 0.01, Tile.class, new Tile(-1));
            }
        }
    }

    //-----------------------------------------

    public SliceSources createSlice(SourceAndConverter[] sacsArray, double slicingAxisPosition) {
        CreateSlice cs = new CreateSlice(this, Arrays.asList(sacsArray), slicingAxisPosition,1,0);
        cs.runRequest();
        return cs.getSlice();
    }

    public <T extends Entity> List<SliceSources> createSlice(SourceAndConverter[] sacsArray, double slicingAxisPosition, double axisIncrement, final Class<T> attributeClass, T defaultEntity) {
        List<SliceSources> out = new ArrayList<>();
        List<SourceAndConverter<?>> sacs = Arrays.asList(sacsArray);
        if ((sacs.size() > 1) && (attributeClass != null)) {

            // Check whether the source can be splitted
            // Split based on attribute argument (usually Tile.class)

            Map<T, List<SourceAndConverter<?>>> sacsGroups =
                    sacs.stream().collect(Collectors.groupingBy(sac -> {
                        if (SourceAndConverterServices.getSourceAndConverterService().getMetadata(sac, SPIM_DATA_INFO) != null) {
                            SourceAndConverterService.SpimDataInfo sdi = (SourceAndConverterService.SpimDataInfo) SourceAndConverterServices.getSourceAndConverterService().getMetadata(sac, SPIM_DATA_INFO);
                            AbstractSpimData<AbstractSequenceDescription<BasicViewSetup, ?, ?>> asd = (AbstractSpimData<AbstractSequenceDescription<BasicViewSetup, ?, ?>>) sdi.asd;
                            BasicViewSetup bvs = asd.getSequenceDescription().getViewSetups().get(sdi.setupId);
                            return bvs.getAttribute(attributeClass);
                        } else {
                            return defaultEntity;
                        }
                    }));

            List<T> sortedTiles = new ArrayList<>(sacsGroups.keySet());

            sortedTiles.sort(Comparator.comparingInt(T::getId));

            new MarkActionSequenceBatch(this).runRequest();
            for (int i = 0; i < sortedTiles.size(); i++) {
                T group = sortedTiles.get(i);
                if (group.getId()!=-1) {
                    CreateSlice cs = new CreateSlice(this, sacsGroups.get(group), slicingAxisPosition + i * axisIncrement,1,0);
                    cs.runRequest();
                    if (cs.getSlice() != null) {
                        out.add(cs.getSlice());
                    }
                }
            }
            new MarkActionSequenceBatch(this).runRequest();

        } else {
            CreateSlice cs = new CreateSlice(this, sacs, slicingAxisPosition,1,0);
            cs.runRequest();
            if (cs.getSlice() != null) {
                out.add(cs.getSlice());
            }
        }
        return out;
    }

    /**
     * Helper function to move a slice
     * @param slice the slice you want to move
     * @param axisPosition the axis at which it should be
     */
    public void moveSlice(SliceSources slice, double axisPosition) {
        new MoveSlice(this, slice, axisPosition).runRequest();
    }

    public void exportSelectedSlicesRegionsToRoiManager(String namingChoice) {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        if (sortedSelected.size()==0) {
            errorMessageForUser.accept("No slice selected", "You did not select any slice.");
        } else {
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
            for (SliceSources slice : sortedSelected) {
                exportSliceRegionsToRoiManager(slice, namingChoice);
            }
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
        }
    }

    public void exportSelectedSlicesRegionsToQuPathProject(boolean erasePreviousFile) {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        if (sortedSelected.size()==0) {
            errorMessageForUser.accept("No slice selected", "You did not select any slice.");
        } else {
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
            for (SliceSources slice : sortedSelected) {
                exportSliceRegionsToQuPathProject(slice, erasePreviousFile);
            }
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
        }
    }

    public void exportSelectedSlicesRegionsToFile(String namingChoice, File dirOutput, boolean erasePreviousFile) {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        if (sortedSelected.size()==0) {
            errorMessageForUser.accept("No slice selected", "You did not select any slice.");
        } else {

            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
            for (SliceSources slice : sortedSelected) {
                exportSliceRegionsToFile(slice, namingChoice, dirOutput, erasePreviousFile);
            }
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
        }
    }

    public void exportSliceRegionsToFile(SliceSources slice, String namingChoice, File dirOutput, boolean erasePreviousFile) {
        new ExportSliceRegionsToFile(this, slice, namingChoice, dirOutput, erasePreviousFile).runRequest();
    }

    public void exportSliceRegionsToRoiManager(SliceSources slice, String namingChoice) {
        new ExportSliceRegionsToRoiManager(this, slice, namingChoice).runRequest();
    }

    public void exportSliceRegionsToQuPathProject(SliceSources slice, boolean erasePreviousFile) {
        new ExportSliceRegionsToQuPathProject(this, slice, erasePreviousFile).runRequest();
    }

    /**
     * Equal spacing between selected slices
     */
    public void equalSpacingSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        if (sortedSelected.size() > 2) {
            SliceSources first = sortedSelected.get(0);
            SliceSources last = sortedSelected.get(sortedSelected.size() - 1);
            double totalSpacing = last.getSlicingAxisPosition() - first.getSlicingAxisPosition();
            double delta = totalSpacing / (sortedSelected.size() - 1);
            new MarkActionSequenceBatch(this).runRequest();
            for (int idx = 1; idx < sortedSelected.size() - 1; idx++) {
                moveSlice(sortedSelected.get(idx), first.getSlicingAxisPosition() + ((double) idx) * delta);
            }
            new MarkActionSequenceBatch(this).runRequest();
        }
    }

    /**
     * Stretch right spacing between selected slices
     */
    public void stretchRightSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        double startAxis = sortedSelected.get(0).getSlicingAxisPosition();
        double endAxis = sortedSelected.get(sortedSelected.size()-1).getSlicingAxisPosition();
        double range = endAxis - startAxis;
        if (range!=0) {
            double stepSize = sizePixX * (int) reslicedAtlas.getStep();
            double ratio = (range + stepSize) / range;
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
            for (SliceSources slice : sortedSelected) {
                double dist = slice.getSlicingAxisPosition() - startAxis;
                moveSlice(slice,startAxis + dist * ratio );
            }
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
        }
    }

    /**
     * Stretch right spacing between selected slices
     */
    public void shrinkRightSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        double startAxis = sortedSelected.get(0).getSlicingAxisPosition();
        double endAxis = sortedSelected.get(sortedSelected.size()-1).getSlicingAxisPosition();
        double range = endAxis - startAxis;
        if (range!=0) {
            double stepSize = sizePixX * (int) reslicedAtlas.getStep();
            double ratio = (range - stepSize) / range;
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
            for (SliceSources slice : sortedSelected) {
                double dist = slice.getSlicingAxisPosition() - startAxis;
                moveSlice(slice,startAxis + dist * ratio );
            }
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
        }
    }

    /**
     * Stretch right spacing between selected slices
     */
    public void stretchLeftSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        double startAxis = sortedSelected.get(0).getSlicingAxisPosition();
        double endAxis = sortedSelected.get(sortedSelected.size()-1).getSlicingAxisPosition();
        double range = endAxis - startAxis;
        if (range!=0) {
            double stepSize = sizePixX * (int) reslicedAtlas.getStep();
            double ratio = (range + stepSize) / range;
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
            for (SliceSources slice : sortedSelected) {
                double dist = endAxis - slice.getSlicingAxisPosition();
                moveSlice(slice,endAxis - dist * ratio );
            }
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
        }
    }

    /**
     * Stretch right spacing between selected slices
     */
    public void shrinkLeftSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        double startAxis = sortedSelected.get(0).getSlicingAxisPosition();
        double endAxis = sortedSelected.get(sortedSelected.size()-1).getSlicingAxisPosition();
        double range = endAxis - startAxis;
        if (range!=0) {
            double stepSize = sizePixX * (int) reslicedAtlas.getStep();
            double ratio = (range - stepSize) / range;
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
            for (SliceSources slice : sortedSelected) {
                double dist = endAxis - slice.getSlicingAxisPosition();
                moveSlice(slice,endAxis - dist * ratio );
            }
            new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
        }
    }

    /**
     * Shift position of all slices to the right (further along the slicing axis)
     */
    public void shiftUpSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        if (sortedSelected.size()>1) new MarkActionSequenceBatch(this).runRequest();
        double shift = sizePixX * (int) reslicedAtlas.getStep();
        for (SliceSources slice : sortedSelected) {
            moveSlice(slice, slice.getSlicingAxisPosition() + shift);
        }
        if (sortedSelected.size()>1) new MarkActionSequenceBatch(this).runRequest();
    }

    /**
     * Shift position of all slices to the right (nearer along the slicing axis)
     */
    public void shiftDownSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
        if (sortedSelected.size()>1) new MarkActionSequenceBatch(this).runRequest();
        double shift = sizePixX * (int) reslicedAtlas.getStep();
        for (SliceSources slice : sortedSelected) {
            moveSlice(slice, slice.getSlicingAxisPosition() - shift);
        }
        if (sortedSelected.size()>1) new MarkActionSequenceBatch(this).runRequest();
    }

    /**
     * Overlap or not of the positioned slices
     */
    public void toggleOverlap() {
        overlapMode++;
        if (overlapMode == 3) overlapMode = 0;
        updateDisplay();
        navigateCurrentSlice();
    }

    private void setOverlapMode(int overlapMode) {
        if (overlapMode<0) overlapMode=0;
        if (overlapMode>2) overlapMode=2;
        this.overlapMode = overlapMode;
        updateDisplay();
        navigateCurrentSlice();
    }

    public int getOverlapMode() {
        return overlapMode;
    }

    public static SourcesProcessor getChannel(int... channels) {
        return new SourcesChannelsSelect(channels);
    }

    public void editLastRegistration(boolean editWithAllChannels) {
        if (getSelectedSources().size()==0) {
            warningMessageForUser.accept("No selected slice", "Please select the slice you want to edit");
            log.accept("Edit registration ignored : no slice selected");
        } else {
            for (SliceSources slice : slices) {
                if (slice.isSelected()) {
                    new EditLastRegistration(this, slice, editWithAllChannels).runRequest();
                }
            }
        }
    }

    public void removeLastRegistration() {
        if (getSelectedSources().size()==0) {
            warningMessageForUser.accept("No selected slice", "Please select the slice where you want to remove the registration");
            log.accept("Remove registration ignored : no slice selected");
        } else {
            new MarkActionSequenceBatch(this).runRequest();
            for (SliceSources slice : slices) {
                if (slice.isSelected()) {
                    new DeleteLastRegistration(this, slice).runRequest();
                }
            }
            new MarkActionSequenceBatch(this).runRequest();
        }
    }

    Map<String,String> convertToString(Map<String, Object> params) {
        Map<String,String> convertedParams = new HashMap<>();

        ConvertService cs = scijavaCtx.getService(ConvertService.class);

        params.keySet().forEach(k -> convertedParams.put(k, cs.convert(params.get(k), String.class)));

        return convertedParams;
    }

    public void register(Command command,
                         SourcesProcessor preprocessFixed,
                         SourcesProcessor preprocessMoving) {
        register(command,
                preprocessFixed,
                preprocessMoving,
                new HashMap<>()
        );
    }

    /**
     * Main function which triggers registration of the selected slices
     * @param command the ui command
     * @param preprocessFixed how fixed sources need to be preprocessed before being registered
     * @param preprocessMoving how moving sources need to be preprocessed before being registered
     * @param parameters parameters used for the registration - all objects will be converted
     *                   to String using the scijava {@link ConvertService}. They need to be strings
     *                   to be serialized
     */
    public void register(Command command,
                         SourcesProcessor preprocessFixed,
                         SourcesProcessor preprocessMoving,
                         Map<String,Object> parameters) {
        register(RegistrationPluginHelper.registrationFromUI(scijavaCtx,command.getClass()),
                preprocessFixed,
                preprocessMoving,
                parameters
        );
    }

    /**
     * Main function which triggers registration of the selected slices
     * @param registrationClass the kind of registration which should be started
     * @param preprocessFixed how fixed sources need to be preprocessed before being registered
     * @param preprocessMoving how moving sources need to be preprocessed before being registered
     * @param parameters parameters used for the registration - all objects will be converted
     *                   to String using the scijava {@link ConvertService}. They need to be strings
     *                   to be serialized
     */
    public void register(Class<? extends IABBARegistrationPlugin> registrationClass,
                         SourcesProcessor preprocessFixed,
                         SourcesProcessor preprocessMoving,
                         Map<String,Object> parameters) {
        if (getSelectedSources().size()==0) {
            warningMessageForUser.accept("No selected slice", "Please select the slice(s) you want to register");
            log.accept("Registration ignored : no slice selected");
        } else {
            try {

                // Putting user defined ROIs
                parameters.put("px", roiPX);
                parameters.put("py", roiPY);
                parameters.put("sx", roiSX);
                parameters.put("sy", roiSY);

                PluginService ps = scijavaCtx.getService(PluginService.class);
                for (SliceSources slice : slices) {
                    if (slice.isSelected()) {
                        IABBARegistrationPlugin registration = (IABBARegistrationPlugin) ps.getPlugin(registrationClass).createInstance();
                        registration.setScijavaContext(scijavaCtx);

                        registration.setSliceInfo(new SliceInfo(slice));

                        // Sends parameters to the registration
                        registration.setRegistrationParameters(convertToString(parameters));

                        // Always set slice at zero position for registration
                        parameters.put("pz", 0);
                        AffineTransform3D at3d = new AffineTransform3D();
                        at3d.translate(0,0,-slice.getSlicingAxisPosition());
                        SourcesAffineTransformer z_zero = new SourcesAffineTransformer(at3d);

                        new RegisterSlice(this, slice, registration, SourcesProcessorHelper.compose(z_zero, preprocessFixed), SourcesProcessorHelper.compose(z_zero, preprocessMoving)).runRequest();
                    }
                }
            } catch (InstantiableException e) {
                e.printStackTrace();
            }
        }
    }


    // --------------------------------- ACTION CLASSES

    /**
     * Cancels last action
     */
    public void cancelLastAction() {
        if (userActions.size() > 0) {
            CancelableAction action = userActions.get(userActions.size() - 1);
            if (action instanceof MarkActionSequenceBatch) {
                action.cancelRequest();
                action = userActions.get(userActions.size() - 1);
                while (!(action instanceof MarkActionSequenceBatch)) {
                    action.cancelRequest();
                    action = userActions.get(userActions.size() - 1);
                }
                action.cancelRequest();
            } else {
                userActions.get(userActions.size() - 1).cancelRequest();
            }
        } else {
            log.accept("No action can be cancelled.");
        }
    }

    /**
     * Redo last action
     */
    public void redoAction() {
        if (redoableUserActions.size() > 0) {
            CancelableAction action = redoableUserActions.get(redoableUserActions.size() - 1);
            if (action instanceof MarkActionSequenceBatch) {
                action.runRequest();
                action = redoableUserActions.get(redoableUserActions.size() - 1);
                while (!(action instanceof MarkActionSequenceBatch)) {
                    action.runRequest();
                    action = redoableUserActions.get(redoableUserActions.size() - 1);
                }
                action.runRequest();
            } else {
                redoableUserActions.get(redoableUserActions.size() - 1).runRequest();
            }

        } else {
            log.accept("No action can be redone.");
        }
    }

    public volatile boolean drawActions = true;

    //------------------------------ Multipositioner Graphical handles

    Set<GraphicalHandle> ghs = new HashSet<>();

    GraphicalHandle stretchLeft, center, stretchRight;

    Set<GraphicalHandle> ghs_tool_tip = new HashSet<>();

    Set<GraphicalHandle> gh_below_mouse = new HashSet<>();

    @Override
    public void disabled(GraphicalHandle gh) {

    }

    @Override
    public void enabled(GraphicalHandle gh) {

    }

    @Override
    public synchronized void hover_in(GraphicalHandle gh) {
        gh_below_mouse.add(gh);
        if (gh_below_mouse.size() == 1) {
            block();
        }
    }

    @Override
    public synchronized void hover_out(GraphicalHandle gh) {
        gh_below_mouse.remove(gh);
        if (gh_below_mouse.size() == 0) {
            unblock();
        }
    }

    @Override
    public synchronized void mouseDragged(MouseEvent e) {
        this.ghs.forEach(gh -> gh.mouseDragged(e));
        this.slices.forEach(slice -> slice.getGUIState().ghs.forEach(gh -> gh.mouseDragged(e)));
    }

    @Override
    public synchronized void mouseMoved(MouseEvent e) {
        this.ghs.forEach(gh -> gh.mouseMoved(e));
        slices.forEach(slice -> slice.getGUIState().ghs.forEach(gh -> gh.mouseMoved(e)));
    }

    @Override
    public synchronized void created(GraphicalHandle gh) {

    }

    @Override
    public synchronized void removed(GraphicalHandle gh) {
        if (gh_below_mouse.contains(gh)) {
            gh_below_mouse.remove(gh);
            if (gh_below_mouse.size() == 0) unblock();
        }
        ghs.remove(gh);
    }


    //------------------------------------------ DRAG BEHAVIOURS

    AtomicBoolean dragActionInProgress = new AtomicBoolean(false);

    synchronized boolean startDragAction() {
        if (dragActionInProgress.get()) {
            return false;
        } else {
            dragActionInProgress.set(true);
            return true;
        }
    }

    synchronized void stopDragAction() {
        dragActionInProgress.set(false);
    }

    /*
     * Stretching selected slices to the left
     */
    class DragLeft implements DragBehaviour {
        List<SliceSources> slicesDragged;
        Map<SliceSources, Double> initialAxisPositions = new HashMap<>();
        double range;
        double lastAxisPos;
        RealPoint iniPointBdv = new RealPoint(3);
        double iniSlicePointing;
        double iniSlicingAxisPosition;
        boolean perform;

        @Override
        public void init(int x, int y) {
            debuglog.accept(" DragLeft start ("+x+":"+y+")");
            perform = startDragAction();

            if (perform) {
                slicesDragged = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());

                slicesDragged.forEach(slice -> initialAxisPositions.put(slice, slice.getSlicingAxisPosition()));

                range = initialAxisPositions.get(slicesDragged.get(slicesDragged.size() - 1)) - initialAxisPositions.get(slicesDragged.get(0));

                lastAxisPos = initialAxisPositions.get(slicesDragged.get(slicesDragged.size() - 1));

                // Computes which slice it corresponds to (useful for overlay redraw)
                bdvh.getViewerPanel().getGlobalMouseCoordinates(iniPointBdv);
                iniSlicePointing = iniPointBdv.getDoublePosition(0) / sX;
                iniSlicingAxisPosition = (iniSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();
            }
            debuglog.accept(" DragLeft perform : ("+perform+")");
        }

        @Override
        public void drag(int x, int y) {
            if (perform) {
                debuglog.accept(" DragLeft drag (" + x + ":" + y + ")");
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                int currentSlicePointing = (int) (currentMousePosition.getDoublePosition(0) / sX);
                double currentSlicingAxisPosition = (currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();

                double ratio;

                if (range == 0) {
                    ratio = 1.0 / (slicesDragged.size()-1);
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(lastAxisPos + (currentSlicingAxisPosition - lastAxisPos) * ratio * slicesDragged.indexOf(slice));
                    }
                } else {
                    ratio = (lastAxisPos - currentSlicingAxisPosition) / range;
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(lastAxisPos + (initialAxisPositions.get(slice) - lastAxisPos) * ratio);
                    }
                }

                updateDisplay();
            }
        }

        @Override
        public void end(int x, int y) {
            if (perform) {
                debuglog.accept(" DragLeft end (" + x + ":" + y + ")");
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                int currentSlicePointing = (int) (currentMousePosition.getDoublePosition(0) / sX);
                double currentSlicingAxisPosition = (currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();

                double ratio;

                new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
                if (range == 0) {
                    ratio = 1.0 / (slicesDragged.size()-1);
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(initialAxisPositions.get(slice));
                        moveSlice(slice, lastAxisPos + (currentSlicingAxisPosition - lastAxisPos) * ratio * slicesDragged.indexOf(slice));
                    }
                } else {
                    ratio = (lastAxisPos - currentSlicingAxisPosition) / range;
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(initialAxisPositions.get(slice));
                        moveSlice(slice, lastAxisPos + (initialAxisPositions.get(slice) - lastAxisPos) * ratio);
                    }
                }
                new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();

                updateDisplay();
                stopDragAction();
            }
        }
    }

    /*
     * Stretching selected slices to the right
     */
    class DragRight implements DragBehaviour {

        List<SliceSources> slicesDragged;
        Map<SliceSources, Double> initialAxisPositions = new HashMap<>();
        double range;
        double lastAxisPos;
        RealPoint iniPointBdv = new RealPoint(3);
        double iniSlicePointing;
        double iniSlicingAxisPosition;
        boolean perform;

        @Override
        public void init(int x, int y) {

            debuglog.accept(" DragRight start ("+x+":"+y+")");
            perform = startDragAction();
            if (perform) {
                slicesDragged = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());

                slicesDragged.forEach(slice -> initialAxisPositions.put(slice, slice.getSlicingAxisPosition()));

                range = initialAxisPositions.get(slicesDragged.get(slicesDragged.size() - 1)) - initialAxisPositions.get(slicesDragged.get(0));
                lastAxisPos = initialAxisPositions.get(slicesDragged.get(0));

                // Computes which slice it corresponds to (useful for overlay redraw)
                bdvh.getViewerPanel().getGlobalMouseCoordinates(iniPointBdv);
                iniSlicePointing = iniPointBdv.getDoublePosition(0) / sX;
                iniSlicingAxisPosition = (iniSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();
            }
        }

        @Override
        public void drag(int x, int y) {
            if (perform) {
                debuglog.accept(" DragRight drag (" + x + ":" + y + ")");

                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                int currentSlicePointing = (int) (currentMousePosition.getDoublePosition(0) / sX);
                double currentSlicingAxisPosition = (currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();

                double ratio;

                if (range == 0) {
                    ratio = 1.0 / (slicesDragged.size()-1);
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(lastAxisPos + (currentSlicingAxisPosition - lastAxisPos) * ratio * slicesDragged.indexOf(slice));
                    }
                } else {
                    ratio = (lastAxisPos - currentSlicingAxisPosition) / range;
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(lastAxisPos - (initialAxisPositions.get(slice) - lastAxisPos) * ratio);
                    }
                }


                updateDisplay();
            }
        }

        @Override
        public void end(int x, int y) {
            if (perform) {
                debuglog.accept(" DragRight end (" + x + ":" + y + ")");

                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                int currentSlicePointing = (int) (currentMousePosition.getDoublePosition(0) / sX);
                double currentSlicingAxisPosition = (currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();

                double ratio;

                new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
                if (range == 0) {
                    ratio = 1.0 / (slicesDragged.size()-1);
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(initialAxisPositions.get(slice));
                        moveSlice(slice, lastAxisPos + (currentSlicingAxisPosition - lastAxisPos) * ratio * slicesDragged.indexOf(slice));
                    }
                } else {
                    ratio = (lastAxisPos - currentSlicingAxisPosition) / range;
                    for (SliceSources slice : slicesDragged) {
                        slice.setSlicingAxisPosition(initialAxisPositions.get(slice));
                        moveSlice(slice, lastAxisPos - (initialAxisPositions.get(slice) - lastAxisPos) * ratio);
                    }
                }
                new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
                updateDisplay();
                stopDragAction();
            }
        }
    }

    /*
     * Single slice dragging
     */
    class SliceSourcesDrag implements DragBehaviour {

        Map<SliceSources, Double> initialAxisPositions = new HashMap<>();

        RealPoint iniPointBdv = new RealPoint(3);
        double iniSlicePointing;
        double iniSlicingAxisPosition;
        double deltaOrigin;
        boolean perform = false;

        final SliceSources sliceOrigin;

        public SliceSourcesDrag(SliceSources slice) {
            this.sliceOrigin = slice;
        }

        @Override
        public void init(int x, int y) {
            debuglog.accept(" SliceSourcesDrag start ("+x+":"+y+")");
            bdvh.getViewerPanel().getGlobalMouseCoordinates(iniPointBdv);
            // Computes which slice it corresponds to (useful for overlay redraw)
            iniSlicePointing = iniPointBdv.getDoublePosition(0) / sX + 0.5;
            iniSlicingAxisPosition = ((int) iniSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();

            if ((sliceOrigin.isSelected())) {
                initialAxisPositions.put(sliceOrigin, sliceOrigin.getSlicingAxisPosition());
            } else {
                if (!sliceOrigin.isSelected()) {
                    sliceOrigin.select();
                    perform = false;
                }
            }

            if (displayMode != POSITIONING_MODE_INT) {
                perform = false;
            }

            perform = perform && startDragAction(); // ensure unicity of drag action

            // Initialize the delta on a step of the zStepper

            if (perform) {
                deltaOrigin = iniSlicingAxisPosition - sliceOrigin.getSlicingAxisPosition();
                if (initialAxisPositions.containsKey(sliceOrigin)) {
                    sliceOrigin.setSlicingAxisPosition( initialAxisPositions.get(sliceOrigin) + deltaOrigin );
                }
                updateDisplay();
            }
            debuglog.accept(" SliceSourcesDrag perform : ("+perform+")");
        }

        @Override
        public void drag(int x, int y) {
            if (perform) {
                debuglog.accept(" SliceSourcesDrag drag ("+x+":"+y+")");
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                double currentSlicePointing = currentMousePosition.getDoublePosition(0) / sX;
                double currentSlicingAxisPosition = ((int) currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();
                double deltaAxis = currentSlicingAxisPosition - iniSlicingAxisPosition;

                if (initialAxisPositions.containsKey(sliceOrigin)) {
                    sliceOrigin.setSlicingAxisPosition( initialAxisPositions.get(sliceOrigin) + deltaAxis + deltaOrigin );
                }
                updateDisplay();
            }
        }

        @Override
        public void end(int x, int y) {
            if (perform) {
                debuglog.accept(" SliceSourcesDrag end ("+x+":"+y+")");
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);
                double currentSlicePointing = currentMousePosition.getDoublePosition(0) / sX;
                double currentSlicingAxisPosition = ((int) currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();
                double deltaAxis = currentSlicingAxisPosition - iniSlicingAxisPosition;

                if (initialAxisPositions.containsKey(sliceOrigin)) {
                    sliceOrigin.setSlicingAxisPosition( initialAxisPositions.get(sliceOrigin) );
                    moveSlice(sliceOrigin, initialAxisPositions.get(sliceOrigin) + deltaAxis + deltaOrigin);
                }
                updateDisplay();
                stopDragAction();
            }
        }
    }

    public DragBehaviour getSelectedSourceDragBehaviour(SliceSources slice) {
        return new SliceSourcesDrag(slice);
    }

    /*
     * Drag selected sources
     */
    class SelectedSliceSourcesDrag implements DragBehaviour {

        Map<SliceSources, Double> initialAxisPositions = new HashMap<>();

        List<SliceSources> selectedSources = new ArrayList<>();

        RealPoint iniPointBdv = new RealPoint(3);
        double iniSlicePointing;
        double iniSlicingAxisPosition;
        double deltaOrigin;
        boolean perform = false;

        public SelectedSliceSourcesDrag(){ }

        @Override
        public void init(int x, int y) {

            debuglog.accept(" SelectedSliceSourcesDrag init ("+x+":"+y+")");

            bdvh.getViewerPanel().getGlobalMouseCoordinates(iniPointBdv);

            // Computes which slice it corresponds to (useful for overlay redraw)
            iniSlicePointing = iniPointBdv.getDoublePosition(0) / sX + 0.5;
            iniSlicingAxisPosition = ((int) iniSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();

            selectedSources = getSortedSlices().stream().filter(SliceSources::isSelected).collect(Collectors.toList());
            if ((selectedSources.size() > 0)) {// && (sliceOrigin.isSelected())) {
                perform = true;
                selectedSources.forEach(slice -> initialAxisPositions.put(slice, slice.getSlicingAxisPosition()));
            }

            if (displayMode != POSITIONING_MODE_INT) {
                perform = false;
            }

            perform = perform && startDragAction(); // ensure unicity of drag action

            // Initialize the delta on a step of the zStepper
            if (perform) {
                deltaOrigin = 0;
                for (SliceSources slice : selectedSources) {
                    if (initialAxisPositions.containsKey(slice)) {
                        slice.setSlicingAxisPosition( initialAxisPositions.get(slice) + deltaOrigin );
                    }
                }
                updateDisplay();
            }
            debuglog.accept(" SelectedSliceSourcesDrag perform : ("+perform+")");
        }

        @Override
        public void drag(int x, int y) {
            if (perform) {
                debuglog.accept(" SelectedSliceSourcesDrag drag ("+x+":"+y+")");
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                double currentSlicePointing = currentMousePosition.getDoublePosition(0) / sX;
                double currentSlicingAxisPosition = ((int) currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();
                double deltaAxis = currentSlicingAxisPosition - iniSlicingAxisPosition;

                for (SliceSources slice : selectedSources) {
                    if (initialAxisPositions.containsKey(slice)) {
                        slice.setSlicingAxisPosition( initialAxisPositions.get(slice) + deltaAxis + deltaOrigin );
                    }
                }
                updateDisplay();
            }
        }

        @Override
        public void end(int x, int y) {
            if (perform) {
                debuglog.accept(" SelectedSliceSourcesDrag end ("+x+":"+y+")");
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                double currentSlicePointing = currentMousePosition.getDoublePosition(0) / sX;
                double currentSlicingAxisPosition = ((int) currentSlicePointing) * sizePixX * (int) reslicedAtlas.getStep();
                double deltaAxis = currentSlicingAxisPosition - iniSlicingAxisPosition;

                new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
                for (SliceSources slice : selectedSources) {
                    if (initialAxisPositions.containsKey(slice)) {
                        slice.setSlicingAxisPosition( initialAxisPositions.get(slice) );
                        moveSlice(slice, initialAxisPositions.get(slice) + deltaAxis + deltaOrigin);
                    }
                }
                new MarkActionSequenceBatch(MultiSlicePositioner.this).runRequest();
                updateDisplay();
                stopDragAction();
            }
        }
    }

    // ------------------------------------------- Block map

    /*
     * Create BehaviourMap to block behaviours interfering with
     * DragBehaviours. The block map is only active if the mouse hovers over any of the Graphical handle
     */
    private final BehaviourMap blockMap = new BehaviourMap();

    private void block() {
        bdvh.getTriggerbindings().addBehaviourMap(BLOCKING_MAP, blockMap);
    }

    private void unblock() {
        bdvh.getTriggerbindings().removeBehaviourMap(BLOCKING_MAP);
    }

    private static final String[] DRAG_TOGGLE_EDITOR_KEYS = new String[]{"button1"};

    private void refreshBlockMap() {
        bdvh.getTriggerbindings().removeBehaviourMap(BLOCKING_MAP);

        final Set<InputTrigger> moveCornerTriggers = new HashSet<>();
        for (final String s : DRAG_TOGGLE_EDITOR_KEYS)
            moveCornerTriggers.add(InputTrigger.getFromString(s));

        final Map<InputTrigger, Set<String>> bindings = bdvh.getTriggerbindings().getConcatenatedInputTriggerMap().getAllBindings();
        final Set<String> behavioursToBlock = new HashSet<>();
        for (final InputTrigger t : moveCornerTriggers)
            behavioursToBlock.addAll(bindings.get(t));

        blockMap.clear();
        final Behaviour block = new Behaviour() {
        };
        for (final String key : behavioursToBlock)
            blockMap.put(key, block);
    }

    // ------------------------------------------------ Serialization / Deserialization

    Gson getGsonStateSerializer(List<SourceAndConverter> serialized_sources) {
        GsonBuilder gsonbuilder = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(SourceAndConverter.class, new IndexedSourceAndConverterAdapter(serialized_sources))
                .registerTypeAdapter(SourceAndConverter[].class, new IndexedSourceAndConverterArrayAdapter(serialized_sources));

        // Now gets all custom serializers for RealTransform.class, using Scijava extensibility plugin
        // Most of the adapters comes from Bdv-Playground

        RealTransformHelper.registerTransformAdapters(gsonbuilder, scijavaCtx);

        gsonbuilder.registerTypeHierarchyAdapter(AlignerState.SliceSourcesState.class, new SliceSourcesStateDeserializer((slice) -> currentSerializedSlice = slice));


        // For actions serialization
        RuntimeTypeAdapterFactory factoryActions = RuntimeTypeAdapterFactory.of(CancelableAction.class);

        factoryActions.registerSubtype(CreateSlice.class);
        factoryActions.registerSubtype(MoveSlice.class);
        factoryActions.registerSubtype(RegisterSlice.class);

        gsonbuilder.registerTypeAdapterFactory(factoryActions);
        gsonbuilder.registerTypeHierarchyAdapter(CreateSlice.class, new CreateSliceAdapter(this));
        gsonbuilder.registerTypeHierarchyAdapter(MoveSlice.class, new MoveSliceAdapter(this, this::currentSliceGetter));
        gsonbuilder.registerTypeHierarchyAdapter(RegisterSlice.class, new RegisterSliceAdapter(this, this::currentSliceGetter));

        // For registration registration
        RuntimeTypeAdapterFactory factoryRegistrations = RuntimeTypeAdapterFactory.of(Registration.class);
        gsonbuilder.registerTypeAdapterFactory(factoryRegistrations);

        PluginService pluginService = scijavaCtx.getService(PluginService.class);

        // Creates adapter for all registration plugins
        RegistrationAdapter registrationAdapter = new RegistrationAdapter(scijavaCtx);
        pluginService.getPluginsOfType(IABBARegistrationPlugin.class).forEach(registrationPluginClass -> {
            IABBARegistrationPlugin plugin = pluginService.createInstance(registrationPluginClass);
            factoryRegistrations.registerSubtype(plugin.getClass());
            gsonbuilder.registerTypeHierarchyAdapter(plugin.getClass(), registrationAdapter);
        });

        // For sources processor

        RuntimeTypeAdapterFactory factorySourcesProcessor = RuntimeTypeAdapterFactory.of(SourcesProcessor.class);

        factorySourcesProcessor.registerSubtype(SourcesAffineTransformer.class);
        factorySourcesProcessor.registerSubtype(SourcesChannelsSelect.class);
        factorySourcesProcessor.registerSubtype(SourcesProcessComposer.class);
        factorySourcesProcessor.registerSubtype(SourcesResampler.class);
        factorySourcesProcessor.registerSubtype(SourcesIdentity.class);

        gsonbuilder.registerTypeAdapterFactory(factorySourcesProcessor);
        gsonbuilder.registerTypeHierarchyAdapter(SourcesChannelsSelect.class, new SourcesChannelSelectAdapter());
        gsonbuilder.registerTypeHierarchyAdapter(SourcesAffineTransformer.class, new SourcesAffineTransformerAdapter());
        gsonbuilder.registerTypeHierarchyAdapter(SourcesResampler.class, new SourcesResamplerAdapter());
        gsonbuilder.registerTypeHierarchyAdapter(SourcesProcessComposer.class, new SourcesComposerAdapter());
        gsonbuilder.registerTypeHierarchyAdapter(SourcesIdentity.class, new SourcesIdentityAdapter());


        return gsonbuilder.create();
    }

    public void saveState(File stateFile, boolean overwrite) {

        if (slices.size() == 0) {
            errorMessageForUser.accept("No Slices To Save", "No slices are present. Nothing saved");
            return;
        }

        slices.get(0).waitForEndOfTasks();

        // Wait patiently for all tasks to be performed
        log.accept("Waiting for all tasks to be finished ... ");
        this.getSortedSlices().forEach(SliceSources::waitForEndOfTasks);
        log.accept("All tasks have been performed!");

        // First save all sources required in the state
        List<SourceAndConverter> allSacs = new ArrayList<>();

        this.getSortedSlices().forEach(sliceSource -> allSacs.addAll(Arrays.asList(sliceSource.getOriginalSources())));

        String fileNoExt = FilenameUtils.removeExtension(stateFile.getAbsolutePath());
        File sacsFile = new File(fileNoExt+"_sources.json");

        if (sacsFile.exists()&&(!overwrite)) {
            System.err.println("File "+sacsFile.getAbsolutePath()+" already exists. Abort command");
            return;
        }

        SourceAndConverterServiceSaver sacss = new SourceAndConverterServiceSaver(sacsFile,this.scijavaCtx,allSacs);
        sacss.run();
        List<SourceAndConverter> serialized_sources = new ArrayList<>();

        sacss.getSacToId().values().stream().sorted().forEach(i -> {
            System.out.println(i);
            serialized_sources.add(sacss.getIdToSac().get(i));
        });

        try {
            FileWriter writer = new FileWriter(stateFile.getAbsolutePath());
            getGsonStateSerializer(serialized_sources).toJson(new AlignerState(this), writer);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadState(File stateFile) {
        // TODO : add a clock as an overlay
        this.getSortedSlices().forEach(SliceSources::waitForEndOfTasks);

        String fileNoExt = FilenameUtils.removeExtension(stateFile.getAbsolutePath());
        File sacsFile = new File(fileNoExt+"_sources.json");

        if (!sacsFile.exists()) {
            errlog.accept("File "+sacsFile.getAbsolutePath()+" not found!");
            return;
        }

        SourceAndConverterServiceLoader sacsl = new SourceAndConverterServiceLoader(sacsFile.getAbsolutePath(), sacsFile.getParent(), this.scijavaCtx, false);
        sacsl.run();
        List<SourceAndConverter> serialized_sources = new ArrayList<>();

        sacsl.getSacToId().values().stream().sorted().forEach(i -> serialized_sources.add(sacsl.getIdToSac().get(i)));

        Gson gson = getGsonStateSerializer(serialized_sources);

        if (stateFile.exists()) {
            try {
                FileReader fileReader = new FileReader(stateFile);

                setSliceDisplayMode(NO_SLICE_DISPLAY_MODE);

                AlignerState state = gson.fromJson(fileReader, AlignerState.class); // actions are executed during deserialization
                fileReader.close();

                String warningMessageForUser = "";

                DecimalFormat df = new DecimalFormat("###.000");

                Function<Double, String> a = (d) -> df.format(d*180/Math.PI);

                if (state.rotationX!=reslicedAtlas.getRotateX()) {
                    warningMessageForUser+="Current X Angle : "+a.apply(reslicedAtlas.getRotateX())+" has been updated to "+a.apply(state.rotationX)+"\n";
                    reslicedAtlas.setRotateX(state.rotationX);
                }

                if (state.rotationY!=reslicedAtlas.getRotateY()) {
                    warningMessageForUser+="Current Y Angle : "+a.apply(reslicedAtlas.getRotateY())+" has been updated to "+a.apply(state.rotationY)+"\n";
                    reslicedAtlas.setRotateY(state.rotationY);
                }

                if (!warningMessageForUser.equals("")) {
                    this.warningMessageForUser.accept("Warning", warningMessageForUser);
                }

                setDisplayMode(state.displayMode);
                setOverlapMode(state.overlapMode);

                bdvh.getViewerPanel().state().setViewerTransform(state.bdvView);

                state.slices_state_list.forEach(sliceState -> {
                    sliceState.slice.waitForEndOfTasks();
                    sliceState.slice.getGUIState().setChannelsVisibility(sliceState.channelsVisibility); // TODO : restore
                    sliceState.slice.getGUIState().setDisplaysettings(sliceState.settings_per_channel);
                    sliceState.slice.transformSourceOrigin(sliceState.preTransform);
                });

                this.iCurrentSlice = state.iCurrentSlice; // Does not work if multiple states are opened TODO : fix, but honestly not important enough
                setSliceDisplayMode(state.sliceDisplayMode);

            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            errlog.accept("Error : file "+stateFile.getAbsolutePath()+" not found!");
        }
    }

    volatile private SliceSources currentSerializedSlice = null;

    //-------------------- Event listeners

    SliceSources currentSliceGetter() {
        return currentSerializedSlice;
    }

    List<SliceChangeListener> listeners = new ArrayList<>();

    public void addSliceListener(SliceChangeListener listener) {
        System.out.println("listener :"+listener);
        listeners.add(listener);
    }

    public void removeSliceListener(SliceChangeListener listener) {
        listeners.remove(listener);
    }

    public interface SliceChangeListener {
        void sliceDeleted(SliceSources slice);
        void sliceCreated(SliceSources slice);
        void sliceZPositionChanged(SliceSources slice);
        void sliceVisibilityChanged(SliceSources slice);
        void sliceSelected(SliceSources slice);
        void sliceDeselected(SliceSources slice);
        void isCurrentSlice(SliceSources slice);
    }

    public interface ModeListener {
        void modeChanged(MultiSlicePositioner mp, int oldmode, int newmode);
        void sliceDisplayModeChanged(MultiSlicePositioner mp, int oldmode, int newmode);
    }

    /**
     * Informations sent to the registration server (provided the user agrees)
     */
    public class SliceInfo {

        public SliceInfo(SliceSources slice) {
            sliceAxisPosition = slice.getSlicingAxisPosition();
            matrixAtlas = reslicedAtlas.getSlicingTransform().getRowPackedCopy();
            matrixAlignerToAtlas = MultiSlicePositioner.this.getAffineTransformFormAlignerToAtlas().getRowPackedCopy();
            rotX = reslicedAtlas.getRotateX();
            rotY = reslicedAtlas.getRotateY();
            sliceHashCode = slice.hashCode();
            sessionHashcode = MultiSlicePositioner.this.hashCode();
        }

        String type = "ABBA Registration"; // Tag to know where from which software this job comes from

        String TaskInfoVersion = "0.1"; // To handle future versions

        String Atlas = "Allen Adult Mouse Brain CCFv3"; // Could be made modular later - for now ABBA only uses this

        int sliceHashCode; // Provide a way to know if the same slice was registered several times

        int sessionHashcode; // Anonymously identifies a full ABBA session

        double[] matrixAtlas; // How the atlas is sliced (rotation correction NOT taken into acount )

        double[] matrixAlignerToAtlas; // How the atlas is sliced (rotation correction taken into account)

        double sliceAxisPosition; // Position of the slice along the atlas

        double rotX, rotY; // Rotation x and y slicing correction

    }

}