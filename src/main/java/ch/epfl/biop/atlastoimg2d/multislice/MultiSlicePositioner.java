package ch.epfl.biop.atlastoimg2d.multislice;

import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvOptions;
import bdv.util.BdvOverlay;
import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.bdv.select.SelectedSourcesListener;
import ch.epfl.biop.bdv.select.SourceSelectorBehaviour;
import ch.epfl.biop.registration.Registration;
import ch.epfl.biop.scijava.command.Elastix2DAffineRegisterCommand;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Tile;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import org.scijava.Context;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.ui.behaviour.*;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;
import sc.fiji.bdvpg.behaviour.EditorBehaviourUnInstaller;
import sc.fiji.bdvpg.scijava.services.SourceAndConverterService;
import sc.fiji.bdvpg.scijava.services.ui.swingdnd.BdvTransferHandler;
import sc.fiji.bdvpg.services.SourceAndConverterServices;
import sc.fiji.bdvpg.sourceandconverter.SourceAndConverterAndTimeRange;
import sc.fiji.bdvpg.sourceandconverter.SourceAndConverterUtils;
import sc.fiji.bdvpg.sourceandconverter.transform.SourceTransformHelper;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static sc.fiji.bdvpg.scijava.services.SourceAndConverterService.SPIM_DATA_INFO;

/**
 * All specific functions and method dedicated to the multislice positioner
 *
 * Let's think a bit:
 * There will be:
 * - a positioning mode
 * - a registration mode
 * - a 3d view mode
 * - an export mode
 * In the registration mode, all slices are displayed, and z step equals 1, and source are overlayed
 *
 */

public class MultiSlicePositioner extends BdvOverlay implements SelectedSourcesListener, GraphicalHandleListener, MouseMotionListener {

    /**
     * BdvHandle displaying everything
     */
    final BdvHandle bdvh;

    final SourceSelectorBehaviour ssb;

    /**
     * Controller of the number of steps displayed
     */
    final MultiSlicePositioner.ZStepSetter zStepSetter;

    /**
     * The slicing model
     */
    final SourceAndConverter slicingModel;

    /**
     * Slicing Model Properties
     */
    int nPixX, nPixY, nPixZ;
    double sX, sY, sZ;
    double sizePixX, sizePixY, sizePixZ;

    List<SliceSources> slices = new ArrayList<>();

    /**
     * Keep track of already contained sources to avoid duplicates
     */
    Set<SourceAndConverter> containedSources = new HashSet<>();

    int totalNumberOfActionsRecorded = 30; // TODO : Implement
    List<CancelableAction> userActions = new ArrayList<>();

   // boolean avoidOverlap = true;

    /**
     * Current coordinate where Sources are dragged
     */
    int iSliceNoStep;

    /**
     * Shift in Y : control overlay or not of sources
     * @param bdvh
     * @param slicingModel
     */

    SourceAndConverter[] slicedSources;

    private String currentMode = "";//POSITIONING_MODE;

    final static String POSITIONING_MODE = "positioning-mode";
    final static String POSITIONING_BEHAVIOURS_KEY = POSITIONING_MODE+"-behaviours";
    Behaviours positioning_behaviours = new Behaviours(new InputTriggerConfig(), POSITIONING_MODE);

    final static String REGISTRATION_MODE = "Registration";
    final static String REGISTRATION_BEHAVIOURS_KEY = REGISTRATION_MODE+"-behaviours";
    Behaviours registration_behaviours = new Behaviours(new InputTriggerConfig(), REGISTRATION_MODE);

    final static String VIEWING3D_MODE = "3d_mode";
    final static String VIEWING3D_BEHAVIOURS_KEY = VIEWING3D_MODE+"-behaviours";
    Behaviours viewing3d_behaviours = new Behaviours(new InputTriggerConfig(), VIEWING3D_MODE);

    final static String COMMON_BEHAVIOURS_KEY = "multipositioner-behaviours";
    Behaviours common_behaviours = new Behaviours(new InputTriggerConfig(), "multipositioner" );

    final static String DRAG_SOURCES_MAP = "drag_positioning_sources-behaviours";
    Behaviours drag_sources_positioning = new Behaviours(new InputTriggerConfig(), "drag_positioning_sources" );

    public MultiSlicePositioner(BdvHandle bdvh, SourceAndConverter slicingModel, SourceAndConverter[] slicedSources) {
        this.slicedSources = slicedSources;
        this.bdvh = bdvh;
        this.slicingModel = slicingModel;
        zStepSetter = new ZStepSetter();
        this.bdvh.getViewerPanel().setTransferHandler(new MultiSlicePositioner.TransferHandler());

        nPixX = (int) slicingModel.getSpimSource().getSource(0,0).dimension(0);
        nPixY = (int) slicingModel.getSpimSource().getSource(0,0).dimension(1);
        nPixZ = (int) slicingModel.getSpimSource().getSource(0,0).dimension(2);

        AffineTransform3D at3D = new AffineTransform3D();
        slicingModel.getSpimSource().getSourceTransform(0,0,at3D);

        double[] m = at3D.getRowPackedCopy();

        sizePixX = Math.sqrt(m[0]*m[0]+m[4]*m[4]+m[8]*m[8]);
        sizePixY = Math.sqrt(m[1]*m[1]+m[5]*m[5]+m[9]*m[9]);
        sizePixZ = Math.sqrt(m[2]*m[2]+m[6]*m[6]+m[10]*m[10]);

        sX = nPixX*sizePixX;
        sY = nPixY*sizePixY;
        sZ = nPixZ*sizePixZ;

        BdvFunctions.showOverlay( this, "MultiSlice Overlay", BdvOptions.options().addTo( bdvh ) );

        ssb = (SourceSelectorBehaviour) SourceAndConverterServices.getSourceAndConverterDisplayService().getDisplayMetadata(
                bdvh, SourceSelectorBehaviour.class.getSimpleName());
        new EditorBehaviourUnInstaller(bdvh).run();

        // ssb.remove();
        // Disable edit mode by default
        bdvh.getTriggerbindings().removeInputTriggerMap(SourceSelectorBehaviour.SOURCES_SELECTOR_TOGGLE_MAP);

        setPositioningMode();

        common_behaviours.behaviour((ClickBehaviour)(x, y) -> this.cancelLastAction(), "cancel_last_action", "ctrl Z");
        common_behaviours.behaviour((ClickBehaviour)(x,y) -> this.navigateNextSlice(), "navigate_next_slice", "N");
        common_behaviours.behaviour((ClickBehaviour)(x,y) -> this.navigatePreviousSlice(), "navigate_previous_slice", "M"); // P taken for panel
        common_behaviours.behaviour((ClickBehaviour)(x,y) -> this.navigateCurrentSlice(), "navigate_current_slice", "C");
        common_behaviours.behaviour((ClickBehaviour)(x,y) -> this.nextMode(), "change_mode", "Q");
        common_behaviours.install(bdvh.getTriggerbindings(), COMMON_BEHAVIOURS_KEY);

        positioning_behaviours.behaviour((ClickBehaviour)(x,y) ->  this.toggleOverlap(), "toggle_superimpose", "O");
        positioning_behaviours.behaviour((ClickBehaviour)(x,y) ->  {if (ssb.isEnabled()) {
            ssb.disable();
            refreshBlockMap();
        } else {
            ssb.enable();
            refreshBlockMap();
        }}, "toggle_editormode", "E");
        positioning_behaviours.behaviour((ClickBehaviour)(x,y) ->  this.equalSpacingSelectedSlices(), "equalSpacingSelectedSlices", "A");

        drag_sources_positioning.behaviour(new SelectedSliceSourcesDrag(), "dragSelectedSources", "button1");

        ssb.addSelectedSourcesListener(this);

        registration_behaviours.behaviour((ClickBehaviour)(x,y) ->  this.elastixRegister(), "register_test", "R");

        setPositioningMode();
        /*new ClickBehaviourInstaller(bdvh, (x,y) -> this.cancelLastAction()).install("cancel_last_action", "ctrl Z");
        new ClickBehaviourInstaller(bdvh, (x,y) -> this.toggleOverlap()).install("toggle_superimpose", "O");
        new ClickBehaviourInstaller(bdvh, (x,y) -> this.elastixRegister()).install("register_test", "R");
        new ClickBehaviourInstaller(bdvh, (x,y) -> this.navigateNextSlice()).install("navigate_next_slice", "N");
        new ClickBehaviourInstaller(bdvh, (x,y) -> this.navigatePreviousSlice()).install("navigate_previous_slice", "P");*/
        bdvh.getViewerPanel().getDisplay().addHandler(this);
    }

    int iCurrentSlice = 0;

    public void navigateNextSlice() {
        iCurrentSlice++;
        List<SliceSources> sortedSlices = getSortedSlices();

        if (iCurrentSlice>= sortedSlices.size()) {
            iCurrentSlice = 0;
        }

        if (sortedSlices.size()>0) {
            centerBdvViewOn(sortedSlices.get(iCurrentSlice));
        }
    }

    // Cycle between three modes
    public void nextMode() {
        switch (currentMode) {
            case POSITIONING_MODE:
                this.setRegistrationMode();
                break;
            case REGISTRATION_MODE:
                this.set3dViewingMode();
                break;
            case VIEWING3D_MODE:
                this.setPositioningMode();
                break;
        }
    }

    public void navigateCurrentSlice() {
        List<SliceSources> sortedSlices = getSortedSlices();

        if (iCurrentSlice>= sortedSlices.size()) {
            iCurrentSlice = 0;
        }

        if (sortedSlices.size()>0) {
            centerBdvViewOn(sortedSlices.get(iCurrentSlice));
        }
    }

    public void navigatePreviousSlice() {
        iCurrentSlice--;
        List<SliceSources> sortedSlices = getSortedSlices();

        if (iCurrentSlice< 0) {
            iCurrentSlice = sortedSlices.size()-1;
        }

        if (sortedSlices.size()>0) {
            centerBdvViewOn(sortedSlices.get(iCurrentSlice));
        }
    }

    public void centerBdvViewOn(SliceSources slice) {

        RealPoint centerSlice;
        switch (currentMode) {
            case POSITIONING_MODE:
                centerSlice = SourceAndConverterUtils.getSourceAndConverterCenterPoint(slice.relocated_sacs_positioning_mode[0]);
                break;
            case REGISTRATION_MODE:
                centerSlice = SourceAndConverterUtils.getSourceAndConverterCenterPoint(slice.relocated_sacs_registration_mode[0]);
                break;
            case VIEWING3D_MODE:
                centerSlice = SourceAndConverterUtils.getSourceAndConverterCenterPoint(slice.relocated_sacs_3D_mode[0]);
                break;
            default:
                System.err.println("Invalid multislicer mode");
                return;
        }

        AffineTransform3D nextAffineTransform = new AffineTransform3D();

        // It should have the same scaling and rotation than the current view
        AffineTransform3D at3D = new AffineTransform3D();
        bdvh.getViewerPanel().state().getViewerTransform(at3D);
        nextAffineTransform.set(at3D);

        // No Shift
        nextAffineTransform.set(0, 0, 3);
        nextAffineTransform.set(0, 1, 3);
        nextAffineTransform.set(0, 2, 3);

        double next_wcx = bdvh.getViewerPanel().getWidth() / 2.0; // Next Window Center X
        double next_wcy = bdvh.getViewerPanel().getHeight() / 2.0; // Next Window Center Y

        RealPoint centerScreenNextBdv = new RealPoint(new double[]{next_wcx, next_wcy, 0});
        RealPoint shiftNextBdv = new RealPoint(3);

        nextAffineTransform.inverse().apply(centerScreenNextBdv, shiftNextBdv);

        double sx = -centerSlice.getDoublePosition(0) + shiftNextBdv.getDoublePosition(0);
        double sy = -centerSlice.getDoublePosition(1) + shiftNextBdv.getDoublePosition(1);
        double sz = -centerSlice.getDoublePosition(2) + shiftNextBdv.getDoublePosition(2);

        RealPoint shiftWindow = new RealPoint(new double[]{sx, sy, sz});
        RealPoint shiftMatrix = new RealPoint(3);
        nextAffineTransform.apply(shiftWindow, shiftMatrix);

        nextAffineTransform.set(shiftMatrix.getDoublePosition(0), 0, 3);
        nextAffineTransform.set(shiftMatrix.getDoublePosition(1), 1, 3);
        nextAffineTransform.set(shiftMatrix.getDoublePosition(2), 2, 3);

        bdvh.getViewerPanel().setCurrentViewerTransform(nextAffineTransform);
        bdvh.getViewerPanel().requestRepaint();

    }

    public void setPositioningMode() {
        if (!currentMode.equals(POSITIONING_MODE)) {
            ghs.forEach(gh -> gh.enable());
            slices.forEach(slice -> slice.enableGraphicalHandles());
            // Do stuff
            currentMode = POSITIONING_MODE;
            // Do stuff
            synchronized (slices) {
                if (slices.stream().anyMatch(slice -> slice.processInProgress)) {
                    System.err.println("Mode cannot be changed if a task is in process");
                } else {
                    List<SourceAndConverter> sacsToRemove = new ArrayList<>();
                    List<SourceAndConverter> sacsToAdd = new ArrayList<>();
                    getSortedSlices().forEach(ss -> {
                        sacsToRemove.addAll(Arrays.asList(ss.relocated_sacs_3D_mode));
                        sacsToRemove.addAll(Arrays.asList(ss.relocated_sacs_registration_mode));
                        sacsToAdd.addAll(Arrays.asList(ss.relocated_sacs_positioning_mode));
                    });
                    SourceAndConverterServices
                            .getSourceAndConverterDisplayService()
                            .remove(bdvh, sacsToRemove.toArray(new SourceAndConverter[0]));
                    SourceAndConverterServices
                            .getSourceAndConverterDisplayService()
                            .show(bdvh, sacsToAdd.toArray(new SourceAndConverter[0]));
                    ;
                }
            }
            bdvh.getTriggerbindings().removeInputTriggerMap(REGISTRATION_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeBehaviourMap(REGISTRATION_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeInputTriggerMap(VIEWING3D_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeBehaviourMap(VIEWING3D_BEHAVIOURS_KEY);
            positioning_behaviours.install(bdvh.getTriggerbindings(), POSITIONING_BEHAVIOURS_KEY);
            navigateCurrentSlice();
        }
        refreshBlockMap();
    }

    public void setRegistrationMode() {
        if (!currentMode.equals(REGISTRATION_MODE)) {
            ghs.forEach(gh -> gh.disable());
            slices.forEach(slice -> slice.disableGraphicalHandles());
            // Do stuff
            currentMode = REGISTRATION_MODE;
            synchronized (slices) {
                if (slices.stream().anyMatch(slice -> slice.processInProgress)) {
                    System.err.println("Mode cannot be changed if a task is in process");
                } else {
                    List<SourceAndConverter> sacsToRemove = new ArrayList<>();
                    List<SourceAndConverter> sacsToAdd = new ArrayList<>();
                    getSortedSlices().forEach(ss -> {
                        sacsToRemove.addAll(Arrays.asList(ss.relocated_sacs_3D_mode));
                        sacsToRemove.addAll(Arrays.asList(ss.relocated_sacs_positioning_mode));
                        sacsToAdd.addAll(Arrays.asList(ss.relocated_sacs_registration_mode));
                    });
                    SourceAndConverterServices
                            .getSourceAndConverterDisplayService()
                            .remove(bdvh, sacsToRemove.toArray(new SourceAndConverter[0]));
                    SourceAndConverterServices
                            .getSourceAndConverterDisplayService()
                            .show(bdvh, sacsToAdd.toArray(new SourceAndConverter[0]));
                    ;
                }
            }
            bdvh.getTriggerbindings().removeInputTriggerMap(POSITIONING_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeBehaviourMap(POSITIONING_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeInputTriggerMap(VIEWING3D_BEHAVIOURS_KEY);
            bdvh.getTriggerbindings().removeBehaviourMap(VIEWING3D_BEHAVIOURS_KEY);
            registration_behaviours.install(bdvh.getTriggerbindings(), REGISTRATION_BEHAVIOURS_KEY );
            navigateCurrentSlice();
        }
        refreshBlockMap();
    }

    public void set3dViewingMode() {
        if (!currentMode.equals(VIEWING3D_MODE)) {
            ghs.forEach(gh -> gh.disable());
            slices.forEach(slice -> slice.disableGraphicalHandles());
            currentMode = VIEWING3D_MODE;
            // Do stuff
            synchronized (slices) {
                if (slices.stream().anyMatch(slice -> slice.processInProgress)) {
                    System.err.println("Mode cannot be changed if a task is in process");
                } else {
                    List<SourceAndConverter> sacsToRemove = new ArrayList<>();
                    List<SourceAndConverter> sacsToAdd = new ArrayList<>();
                    getSortedSlices().forEach(ss -> {
                        sacsToRemove.addAll(Arrays.asList(ss.relocated_sacs_positioning_mode));
                        sacsToRemove.addAll(Arrays.asList(ss.relocated_sacs_registration_mode));
                        sacsToAdd.addAll(Arrays.asList(ss.relocated_sacs_3D_mode));
                    });
                    SourceAndConverterServices
                            .getSourceAndConverterDisplayService()
                            .remove(bdvh, sacsToRemove.toArray(new SourceAndConverter[0]));
                    SourceAndConverterServices
                            .getSourceAndConverterDisplayService()
                            .show(bdvh, sacsToAdd.toArray(new SourceAndConverter[0]));
                    ;
                }
                bdvh.getTriggerbindings().removeInputTriggerMap(POSITIONING_BEHAVIOURS_KEY);
                bdvh.getTriggerbindings().removeBehaviourMap(POSITIONING_BEHAVIOURS_KEY);
                bdvh.getTriggerbindings().removeInputTriggerMap(REGISTRATION_BEHAVIOURS_KEY);
                bdvh.getTriggerbindings().removeBehaviourMap(REGISTRATION_BEHAVIOURS_KEY);
                viewing3d_behaviours.install(bdvh.getTriggerbindings(),  VIEWING3D_BEHAVIOURS_KEY  );
                navigateCurrentSlice();
            }
        }
        refreshBlockMap();
    }

    Context scijavaCtx;
    public void setScijavaContext(Context ctx) {
        scijavaCtx = ctx;
    }

    public List<SliceSources> getSortedSlices() {
        List<SliceSources> sortedSlices = new ArrayList<>(slices);
        Collections.sort(sortedSlices, Comparator.comparingDouble(s -> s.slicingAxisPosition));
        return sortedSlices;
    }

    public void updateDisplay() {
        // Sort slices along slicing axis

        if (cycleToggle==0) {
            slices.forEach(slice -> slice.yShift_slicing_mode = 1);
        } else if (cycleToggle==2) {

            double lastPositionAlongX = -Double.MAX_VALUE;

            int stairIndex = 0;

            int zStep = (int) zStepSetter.getStep();

            for (SliceSources slice : getSortedSlices()) {
                double posX = ((slice.slicingAxisPosition/sizePixX/zStep)+0) * sX;
                if (posX>=(lastPositionAlongX+sX)) {
                    stairIndex = 0;
                    lastPositionAlongX = posX;
                    slice.yShift_slicing_mode = 1;
                } else {
                    stairIndex++;
                    slice.yShift_slicing_mode = 1+stairIndex;
                }
            }
        } else if (cycleToggle==1) {
            slices.forEach(slice -> slice.yShift_slicing_mode = 0);
        }

        for (SliceSources src : slices) {
            src.updatePosition();
        }

        bdvh.getViewerPanel().requestRepaint();
    }

    int cycleToggle = 0;
    public void toggleOverlap() {
        cycleToggle++;
        if (cycleToggle==3) cycleToggle = 0;
        navigateCurrentSlice();
        updateDisplay();
    }

    public ZStepSetter getzStepSetter() {
        return zStepSetter;
    }

    public void elastixRegister() {
        System.out.println("Elastix Registration");
        if (slices.size()==0) return;
        if (iCurrentSlice<0) iCurrentSlice = 0;
        if (iCurrentSlice>=slices.size()) iCurrentSlice = 0;

        SliceSources slice = slices.get(this.iCurrentSlice);
        RealPoint rpt = new RealPoint(3);

        double posX = -sX/2;
        double posY = -sY/2;

        rpt.setPosition(posX,0);
        rpt.setPosition(posY,1);
        rpt.setPosition(slice.slicingAxisPosition, 2);

        Future<CommandModule> task = scijavaCtx.getService(CommandService.class).run(Elastix2DAffineRegisterCommand.class, true,
                "sac_fixed", slicedSources[0].getSpimSource().getNumMipmapLevels()-1,
                    "tpFixed", 0,
                    "levelFixedSource", 2,
                    "sac_moving", slice.relocated_sacs_registration_mode[0],
                    "tpMoving", 0,
                    "levelMovingSource", slice.relocated_sacs_registration_mode[0].getSpimSource().getNumMipmapLevels()-1,
                    "pxSizeInCurrentUnit", 0.04, // in mm
                    "interpolate", false,
                    "showImagePlusRegistrationResult", true,
                    "px",rpt.getDoublePosition(0),
                    "py",rpt.getDoublePosition(1),
                    "pz",rpt.getDoublePosition(2),
                    "sx",sX,
                    "sy",sY
                );


            Thread t = new Thread(() -> {
                try {
                    //SourceAndConverter sac = (SourceAndConverter) task.get().getOutput("registeredSource");

                    AffineTransform3D at3d = (AffineTransform3D) task.get().getOutput("at3D");

                    SourceTransformHelper.mutate(at3d, new SourceAndConverterAndTimeRange(slice.relocated_sacs_registration_mode[0],0));
                    
                    bdvh.getViewerPanel().requestRepaint();
                    /*SourceAndConverterServices
                            .getSourceAndConverterDisplayService()
                            .show(bdvh, new SourceAffineTransformer(slice.relocated_sacs_registration_mode[0], at3d).getSourceOut());*/

                } catch (Exception e) {

                }
            });
            t.start();
            



    }

    @Override
    protected synchronized void draw(Graphics2D g) {
        synchronized (slices) {
            int colorCode = this.info.getColor().get();
            g.setColor(new Color(ARGBType.red(colorCode) , ARGBType.green(colorCode), ARGBType.blue(colorCode), ARGBType.alpha(colorCode) ));

            g.drawString(currentMode,10,10);

            RealPoint[][] ptRectWorld = new RealPoint[2][2];
            Point[][] ptRectScreen = new Point[2][2];

            AffineTransform3D bdvAt3D = new AffineTransform3D();

            bdvh.getViewerPanel().state().getViewerTransform(bdvAt3D);

            for (int xp = 0; xp < 2; xp++) {
                for (int yp = 0; yp < 2; yp++) {
                    ptRectWorld[xp][yp] = new RealPoint(3);
                    RealPoint pt = ptRectWorld[xp][yp];
                    pt.setPosition(sX * (iSliceNoStep + xp), 0);
                    pt.setPosition(sY * (1 + yp), 1);
                    pt.setPosition(0, 2);
                    bdvAt3D.apply(pt, pt);
                    ptRectScreen[xp][yp] = new Point((int) pt.getDoublePosition(0), (int) pt.getDoublePosition(1));
                }
            }

            g.drawLine(ptRectScreen[0][0].x, ptRectScreen[0][0].y, ptRectScreen[1][0].x, ptRectScreen[1][0].y);
            g.drawLine(ptRectScreen[1][0].x, ptRectScreen[1][0].y, ptRectScreen[1][1].x, ptRectScreen[1][1].y);
            g.drawLine(ptRectScreen[1][1].x, ptRectScreen[1][1].y, ptRectScreen[0][1].x, ptRectScreen[0][1].y);
            g.drawLine(ptRectScreen[0][1].x, ptRectScreen[0][1].y, ptRectScreen[0][0].x, ptRectScreen[0][0].y);

            ghs.forEach(gh -> gh.draw(g));

            for (SliceSources slice : slices) {
                slice.drawGraphicalHandles(g);
            }

           if (currentMode.equals(POSITIONING_MODE) && slices.stream().anyMatch(slice -> slice.isSelected)) {
                List<SliceSources> sortedSelected = getSortedSlices().stream().filter(slice -> slice.isSelected).collect(Collectors.toList());
                RealPoint precedentPoint = null;
                for (SliceSources slice : sortedSelected) {
                    RealPoint sliceCenter = SourceAndConverterUtils.getSourceAndConverterCenterPoint(slice.relocated_sacs_positioning_mode[0]);
                    bdvAt3D.apply(sliceCenter, sliceCenter);
                    //g.fillOval((int)sliceCenter.getDoublePosition(0)-5,(int)sliceCenter.getDoublePosition(1)-5,10,10);
                    if (precedentPoint!=null) {
                        g.drawLine((int)precedentPoint.getDoublePosition(0),(int)precedentPoint.getDoublePosition(1),(int)sliceCenter.getDoublePosition(0),(int)sliceCenter.getDoublePosition(1));
                    }
                    precedentPoint = sliceCenter;
                }
            }
        }
    }

    public void cancelLastAction() {
        if (userActions.size()>0) {
            userActions.get(userActions.size()-1).cancel();
        }
    }

    public void equalSpacingSelectedSlices() {
        List<SliceSources> sortedSelected = getSortedSlices().stream().filter(slice -> slice.isSelected).collect(Collectors.toList());
        if (sortedSelected.size()>2) {
            SliceSources first = sortedSelected.get(0);
            SliceSources last = sortedSelected.get(sortedSelected.size()-1);
            double totalSpacing = last.slicingAxisPosition-first.slicingAxisPosition;
            double delta = totalSpacing / (sortedSelected.size()-1);
            for (int idx = 1;idx<sortedSelected.size()-1;idx++) {
                moveSlice(sortedSelected.get(idx), first.slicingAxisPosition+((double)idx)*delta);
            }
        }
    }

    @Override
    public void selectedSourcesUpdated(Collection<SourceAndConverter<?>> selectedSources, String triggerMode) {
        boolean changed = false;
        for (SliceSources slice:slices) {
            if (slice.isContainingAny(selectedSources)) {
                if (!slice.isSelected) {
                    changed = true;
                }
                slice.isSelected = true;
            } else {
                if (slice.isSelected) {
                    changed = true;
                }
                slice.isSelected = false;
            }
        }
        if (changed) bdvh.getViewerPanel().requestRepaint();
    }

    @Override
    public void lastSelectionEvent(Collection<SourceAndConverter<?>> lastSelectedSources, String mode, String triggerMode) {
        // Nothing
    }

    @Override
    public synchronized void mouseDragged(MouseEvent e) {
        this.ghs.forEach(gh -> gh.mouseDragged(e));
        this.slices.forEach(slice -> slice.ghs.forEach(gh -> gh.mouseDragged(e)));
    }

    @Override
    public synchronized void mouseMoved(MouseEvent e) {
        //System.out.println("Mouse moved");
        this.ghs.forEach(gh -> gh.mouseMoved(e));
        this.slices.forEach(slice -> slice.ghs.forEach(gh -> gh.mouseMoved(e)));
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
            iSliceNoStep = (int) (pt3d.getDoublePosition(0)/sX);

            //Repaint the overlay only
            bdvh.getViewerPanel().paint();
        }

        /**
         * When the user drops the data -> import the slices
         * @param support
         * @param sacs
         */
        @Override
        public void importSourcesAndConverters(TransferSupport support, List<SourceAndConverter<?>> sacs) {
            Optional<BdvHandle> bdvh = getBdvHandleFromViewerPanel(((bdv.viewer.ViewerPanel)support.getComponent()));
            if (bdvh.isPresent()) {
                double slicingAxisPosition = iSliceNoStep*sizePixX*(int) zStepSetter.getStep();
                createSlice(sacs.toArray(new SourceAndConverter[0]), slicingAxisPosition, 0.01, Tile.class, new Tile(-1));
            }
        }
    }

    /**
     * Simple to enable setting z step and allowing its communication
     * in multiple Commands
     */
    public class ZStepSetter {

        private int zStep = 1;

        public void setStep(int zStep) {
            if (zStep>0) {
                this.zStep = zStep;
            }
            updateDisplay();
        }

        public long getStep() {
            return (long) zStep;
        }
    }

    /**
     * Inner class which contains the information necessary for the viewing of the slices:
     *
     */

    public abstract class CancelableAction {

        public void run() {
            userActions.add(this);
        }

        public void cancel() {
            if (userActions.get(userActions.size()-1).equals(this)) {
                userActions.remove(userActions.size()-1);
            } else {
                System.err.println("Error : cancel not called on the last action");
                return;
            }
        }
    }

    public void moveSlice(SliceSources slice, double axisPosition) {
        new MoveSlice(slice, axisPosition).run();
    }

    public class MoveSlice extends CancelableAction {

        private SliceSources sliceSource;
        private double oldSlicingAxisPosition;
        private double newSlicingAxisPosition;

        public MoveSlice(SliceSources sliceSource, double slicingAxisPosition ) {
            this.sliceSource = sliceSource;
            this.oldSlicingAxisPosition = sliceSource.slicingAxisPosition;
            int iSliceNoStep = (int) (slicingAxisPosition / sizePixX);
            //double slicingAxisPosition = iSliceNoStep*sizePixX;
            this.newSlicingAxisPosition = iSliceNoStep*sizePixX; //slicingAxisPosition;
        }

        public void run() {
            sliceSource.slicingAxisPosition = newSlicingAxisPosition;
            sliceSource.updatePosition();
            updateDisplay();
            super.run();
        }

        public void cancel() {
            sliceSource.slicingAxisPosition = oldSlicingAxisPosition;
            sliceSource.updatePosition();
            updateDisplay();
            super.cancel();
        }
    }

    public <T extends Entity> List<SliceSources> createSlice(SourceAndConverter[] sacsArray, double slicingAxisPosition, double axisIncrement, final Class< T > attributeClass, T defaultEntity) {
        //new CreateSlice(Arrays.asList(sacs), slicingAxisPosition).run();
        List<SliceSources> out = new ArrayList<>();
        List<SourceAndConverter<?>> sacs = Arrays.asList(sacsArray);
        if ((sacs.size()>1)&&(attributeClass!=null)) {
            // Check whether the source can be splitted, maybe based
            // Split based on Tile ?

            Map<T, List<SourceAndConverter<?>>> sacsGroups =
                    sacs.stream().collect(Collectors.groupingBy(sac -> {
                        if (SourceAndConverterServices.getSourceAndConverterService().getMetadata(sac, SPIM_DATA_INFO)!=null) {
                            SourceAndConverterService.SpimDataInfo sdi = (SourceAndConverterService.SpimDataInfo) SourceAndConverterServices.getSourceAndConverterService().getMetadata(sac, SPIM_DATA_INFO);
                            AbstractSpimData<AbstractSequenceDescription<BasicViewSetup,?,?>> asd = ( AbstractSpimData<AbstractSequenceDescription<BasicViewSetup,?,?>>)sdi.asd;
                            BasicViewSetup bvs = asd.getSequenceDescription().getViewSetups().get(sdi.setupId);
                            return (T) bvs.getAttribute(attributeClass);
                        } else {
                            return defaultEntity;
                        }
                    }));

            List<T> sortedTiles = new ArrayList<>();

            sortedTiles.addAll(sacsGroups.keySet());

            sortedTiles.sort(Comparator.comparingInt(T::getId));
            for (int i = 0; i<sortedTiles.size();i++) {
                T group = sortedTiles.get(i);
                CreateSlice cs = new CreateSlice(sacsGroups.get(group), slicingAxisPosition + i * axisIncrement);
                cs.run();
                if (cs.getSlice()!=null) {
                    out.add(cs.getSlice());
                }
            }
            /*sortedTiles.forEach(group -> {

            });*/

        } else {
            CreateSlice cs = new CreateSlice(sacs, slicingAxisPosition);
            cs.run();
            if (cs.getSlice()!=null) {
                out.add(cs.getSlice());
            }
        }
        return out;
    }

    public class CreateSlice extends CancelableAction {

        private List<SourceAndConverter<?>> sacs;
        private SliceSources sliceSource;
        private double slicingAxisPosition;

        public CreateSlice(List<SourceAndConverter<?>> sacs, double slicingAxisPosition) {
            this.sacs = sacs;
            this.slicingAxisPosition = slicingAxisPosition;
        }

        @Override
        public void run() {
            System.out.println("Create Slice run");
            boolean sacAlreadyPresent = false;
            for (SourceAndConverter sac : sacs) {
                if (containedSources.contains(sac)) {
                    sacAlreadyPresent = true;
                }
            }

            if (sacAlreadyPresent) {
                SliceSources zeSlice = null;

                // A source is already included :
                // If all sources match exactly what's in a single SliceSources -> that's a move operation

                boolean exactMatch = false;
                for (SliceSources ss : slices) {
                    if (ss.exactMatch(sacs)) {
                        exactMatch = true;
                        zeSlice = ss;
                    }
                }

                if (!exactMatch) {
                    System.err.println("A source is already used in the positioner : action ignored");
                    return;
                } else {
                    System.out.println("Moving Source Instead of creating it");
                    // Move action:
                    new MoveSlice(zeSlice, slicingAxisPosition).run();
                    return;
                }
            }

            sliceSource = new SliceSources(sacs.toArray(new SourceAndConverter[sacs.size()]),
                    slicingAxisPosition,
                    MultiSlicePositioner.this);

            slices.add(sliceSource);

            containedSources.addAll(Arrays.asList(sliceSource.original_sacs));
            containedSources.addAll(Arrays.asList(sliceSource.relocated_sacs_positioning_mode));

            updateDisplay();

            SourceAndConverterServices.getSourceAndConverterDisplayService()
                    .show(bdvh, sliceSource.relocated_sacs_positioning_mode);

            // The line below should be executed only if the action succeeded ... (if it's executed, calling cancel should have the same effect)
            super.run();
        }

        public SliceSources getSlice() {
            return sliceSource;
        }

        @Override
        public void cancel() {
            containedSources.removeAll(Arrays.asList(sliceSource.original_sacs));
            containedSources.removeAll(Arrays.asList(sliceSource.relocated_sacs_positioning_mode));
            slices.remove(sliceSource);
            SourceAndConverterServices.getSourceAndConverterDisplayService()
                    .remove(bdvh, sliceSource.relocated_sacs_positioning_mode);
            super.cancel();
        }
    }

    public class RegisterSlice extends CancelableAction {
        final SliceSources slice;
        final Registration<SourceAndConverter[]> registration;
        final Function<SourceAndConverter[], SourceAndConverter[]> preprocessFixed;
        final Function<SourceAndConverter[], SourceAndConverter[]> preprocessMoving;

        public RegisterSlice(SliceSources slice,
                             Registration<SourceAndConverter[]> registration,
                             Function<SourceAndConverter[], SourceAndConverter[]> preprocessFixed,
                             Function<SourceAndConverter[], SourceAndConverter[]> preprocessMoving) {
            this.slice = slice;
            this.registration = registration;
            this.preprocessFixed = preprocessFixed;
            this.preprocessMoving = preprocessMoving;
        }

        @Override
        public void run() {
            super.run();
        }

        @Override
        public void cancel() {
            super.cancel();
        }
    }

    class SelectedSliceSourcesDrag implements DragBehaviour {

        int x0, y0;

        Map<SliceSources,Double> initialAxisPositions = new HashMap<>();

        List<SliceSources> selectedSources = new ArrayList<>();

        RealPoint iniPointBdv = new RealPoint(3);
        double iniSlicePointing;
        double iniSlicingAxisPosition;
        boolean perform = false;

        @Override
        public void init(int x, int y) {
            // todo : block other actions...
            x0 = x;
            y0 = y;

            bdvh.getViewerPanel().getGlobalMouseCoordinates(iniPointBdv);

            // Computes which slice it corresponds to (useful for overlay redraw)
            iniSlicePointing = iniPointBdv.getDoublePosition(0)/sX;
            iniSlicingAxisPosition = iniSlicePointing*sizePixX*(int) zStepSetter.getStep();

            selectedSources =  getSortedSlices().stream().filter(slice -> slice.isSelected).collect(Collectors.toList());
            if (selectedSources.size()>0) {
                perform = true;
                selectedSources.stream().forEach(slice -> {
                    initialAxisPositions.put(slice,slice.slicingAxisPosition);
                });
            }
        }

        @Override
        public void drag(int x, int y) {
            if (perform) {
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                int currentSlicePointing = (int) (currentMousePosition.getDoublePosition(0) / sX);
                double currentSlicingAxisPosition = currentSlicePointing * sizePixX * (int) zStepSetter.getStep();
                double deltaAxis = currentSlicingAxisPosition - iniSlicingAxisPosition;

                for (SliceSources slice : selectedSources) {
                    slice.slicingAxisPosition = initialAxisPositions.get(slice) + deltaAxis;
                    slice.updatePosition();
                }

                updateDisplay();
            }
        }

        @Override
        public void end(int x, int y) {
            if (perform) {
                RealPoint currentMousePosition = new RealPoint(3);
                bdvh.getViewerPanel().getGlobalMouseCoordinates(currentMousePosition);

                int currentSlicePointing = (int) (currentMousePosition.getDoublePosition(0) / sX);
                double currentSlicingAxisPosition = currentSlicePointing * sizePixX * (int) zStepSetter.getStep();
                double deltaAxis = currentSlicingAxisPosition - iniSlicingAxisPosition;

                for (SliceSources slice : selectedSources) {
                        slice.slicingAxisPosition = initialAxisPositions.get(slice);
                        moveSlice(slice,initialAxisPositions.get(slice) + deltaAxis );
                }
                updateDisplay();
            }
        }
    }

    private static final String BLOCKING_MAP = "multipositioner-blocking";

    /*
     * Create BehaviourMap to block behaviours interfering with
     * DraBehaviour. The block map is only active while TODO determine conditions
     */
    private final BehaviourMap blockMap = new BehaviourMap();

    private void block()
    {
        bdvh.getTriggerbindings().addBehaviourMap( BLOCKING_MAP, blockMap );
    }

    private void unblock()
    {
        bdvh.getTriggerbindings().removeBehaviourMap( BLOCKING_MAP );
    }

    /*private void graphicalHandleChanged(GraphicalHandle gh)
    {
        final int index = boxOverlay.getHighlightedCornerIndex();
        if ( index < 0 )
            unblock();
        else
            block();
    }*/
    Set<GraphicalHandle> ghs = new HashSet<>();

    Set<GraphicalHandle> gh_below_mouse = new HashSet<>();

    @Override
    public synchronized void hover_in(GraphicalHandle gh) {
        //System.out.println("Hover IN");
        gh_below_mouse.add(gh);
        if (gh_below_mouse.size()==1) {
            block();
            updateEditability();
        }
    }

    @Override
    public synchronized void hover_out(GraphicalHandle gh) {
        //System.out.println("Hover OUT");
        gh_below_mouse.remove(gh);
        if (gh_below_mouse.size()==0) {
            unblock();
            updateEditability();
        }
    }

    /*@Override
    public synchronized void clicked(GraphicalHandle gh) {

    }*/

    @Override
    public synchronized void created(GraphicalHandle gh) {

    }

    @Override
    public synchronized void removed(GraphicalHandle gh) {
        if (gh_below_mouse.contains(gh)) {
            gh_below_mouse.remove(gh);
            if (gh_below_mouse.size()==0) unblock();
            updateEditability();
        }
        ghs.remove(gh);
    }


    private static final String[] DRAG_TOGGLE_EDITOR_KEYS = new String[] { "button1" };

    private void refreshBlockMap()
    {
        bdvh.getTriggerbindings().removeBehaviourMap( BLOCKING_MAP );

        final Set<InputTrigger> moveCornerTriggers = new HashSet<>();
        for ( final String s : DRAG_TOGGLE_EDITOR_KEYS )
            moveCornerTriggers.add( InputTrigger.getFromString( s ) );

        final Map< InputTrigger, Set< String > > bindings = bdvh.getTriggerbindings().getConcatenatedInputTriggerMap().getAllBindings();
        final Set< String > behavioursToBlock = new HashSet<>();
        for ( final InputTrigger t : moveCornerTriggers )
            behavioursToBlock.addAll( bindings.get( t ) );

        blockMap.clear();
        final Behaviour block = new Behaviour() {};
        for ( final String key : behavioursToBlock )
            blockMap.put( key, block );
    }

    private void updateEditability()
    {
        System.out.println("updateEditability called");
        System.out.println(currentMode.equals(POSITIONING_MODE));
        System.out.println(currentMode);
        System.out.println("gh_below_mouse.size() = "+gh_below_mouse.size());
        if ( currentMode.equals(POSITIONING_MODE) && (gh_below_mouse.size()>0) )
        {
            drag_sources_positioning.install( bdvh.getTriggerbindings(), DRAG_SOURCES_MAP );
            System.out.println("Installed");
        }
        else
        {
            bdvh.getTriggerbindings().removeInputTriggerMap( DRAG_SOURCES_MAP );
            bdvh.getTriggerbindings().removeBehaviourMap( DRAG_SOURCES_MAP );
            //unblock();
        }
    }

}