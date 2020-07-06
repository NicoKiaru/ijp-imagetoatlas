package ch.epfl.biop.atlastoimg2d.multislice;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.registration.Registration;
import ch.epfl.biop.registration.sourceandconverter.AffineTransformedSourceWrapperRegistration;
import ch.epfl.biop.registration.sourceandconverter.CenterZeroRegistration;
import ch.qos.logback.core.util.ExecutorServiceUtil;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;
import sc.fiji.bdvpg.services.SourceAndConverterServices;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

public class SliceSources {

    // What are they ?
    SourceAndConverter[] original_sacs;

    // Visible to the user in slicing mode
    SourceAndConverter[] relocated_sacs_positioning_mode;

    // Used for registration : like 3D, but tilt and roll ignored because it is handled on the fixed source side
    SourceAndConverter[] registered_sacs;

    Map<Registration, SourceAndConverter[]> registered_sacs_sequence = new HashMap<>();

    // Where are they ?
    double slicingAxisPosition;

    private boolean isSelected = false;

    double yShift_slicing_mode = 0;

    final MultiSlicePositioner mp;

    List<GraphicalHandle> ghs = new ArrayList<>();

    Behaviours behavioursHandleSlice;

    volatile AffineTransformedSourceWrapperRegistration zPositioner;

    volatile AffineTransformedSourceWrapperRegistration slicingModePositioner;

    CenterZeroRegistration centerPositioner;

    Set<Registration> pendingRegistrations = new HashSet<>();

    Set<Registration> lockedRegistrations = new HashSet<>();

    // For fast display : Icon TODO : see https://github.com/bigdataviewer/bigdataviewer-core/blob/17d2f55d46213d1e2369ad7ef4464e3efecbd70a/src/main/java/bdv/tools/RecordMovieDialog.java#L256-L318
    protected SliceSources(SourceAndConverter[] sacs, double slicingAxisPosition, MultiSlicePositioner mp) {
        this.mp = mp;
        this.original_sacs = sacs;
        this.slicingAxisPosition = slicingAxisPosition;
        this.registered_sacs = this.original_sacs;

        centerPositioner = new CenterZeroRegistration();
        centerPositioner.setMovingImage(registered_sacs);

        zPositioner = new AffineTransformedSourceWrapperRegistration();

        behavioursHandleSlice = new Behaviours(new InputTriggerConfig());
        behavioursHandleSlice.behaviour(mp.getSelectedSourceDragBehaviour(this), "dragSelectedSources" + this.toString(), "button1");
        behavioursHandleSlice.behaviour((ClickBehaviour) (x, y) -> {
            deSelect();
            mp.bdvh.getViewerPanel().requestRepaint();
        }, "deselectedSources" + this.toString(), "button3", "ctrl button1");

        GraphicalHandle gh = new CircleGraphicalHandle(mp,
                behavioursHandleSlice,
                mp.bdvh.getTriggerbindings(),
                this.toString(), // pray for unicity ? TODO : do better than thoughts and prayers
                this::getBdvHandleCoords,
                this::getBdvHandleRadius,
                this::getBdvHandleColor
        );
        ghs.add(gh);
        iniPosition();
    }

    void iniPosition() {
        addRegistration(centerPositioner, Function.identity(), Function.identity());
        addRegistration(zPositioner, Function.identity(), Function.identity());
        waitForEndOfRegistrations();
        updatePosition();
    }

    public synchronized void select() {
        this.isSelected = true;
    }

    public synchronized void deSelect() {
        this.isSelected = false;
    }

    public synchronized boolean isSelected() {
        return this.isSelected;
    }

    private int currentSliceIndex = -1;

    public int getIndex() {
        return currentSliceIndex;
    }

    public void setIndex(int idx) {
        currentSliceIndex = idx;
    }

    public Integer[] getBdvHandleCoords() {
        AffineTransform3D bdvAt3D = new AffineTransform3D();
        mp.bdvh.getViewerPanel().state().getViewerTransform(bdvAt3D);
        RealPoint sliceCenter = new RealPoint(3);
        if (mp.currentMode == MultiSlicePositioner.POSITIONING_MODE) {
            sliceCenter = getCenterPositionPMode();
            bdvAt3D.apply(sliceCenter, sliceCenter);
            return new Integer[]{(int) sliceCenter.getDoublePosition(0), (int) sliceCenter.getDoublePosition(1), (int) sliceCenter.getDoublePosition(2)};
        } else if (mp.currentMode == MultiSlicePositioner.REGISTRATION_MODE) {
            RealPoint zero = new RealPoint(3);
            zero.setPosition(0, 0);
            bdvAt3D.apply(zero, zero);
            return new Integer[]{35 * (currentSliceIndex - mp.slices.size() / 2) + (int) zero.getDoublePosition(0), 20, 0};
        } else {
            return new Integer[]{0, 0, 0};
        }
    }

    public String getRegistrationState(Registration registration) {
        if (lockedRegistrations.contains(registration)) {
            return "(locked)";
        }
        if (registrations.contains(registration)) {
            return "(done)";
        }
        if (pendingRegistrations.contains(registration)) {
            return "(pending)";
        }
        return "(!)";
    }

    public Integer[] getBdvHandleColor() {
        if (isSelected) {
            return new Integer[]{0, 255, 0, 200};

        } else {
            return new Integer[]{255, 255, 0, 64};
        }
    }

    public Integer getBdvHandleRadius() {
        return 12;
    }

    public void drawGraphicalHandles(Graphics2D g) {
        ghs.forEach(gh -> gh.draw(g));
    }

    public void disableGraphicalHandles() {
        ghs.forEach(gh -> gh.disable());
    }

    public void enableGraphicalHandles() {
        ghs.forEach(gh -> gh.enable());
    }

    protected boolean exactMatch(List<SourceAndConverter<?>> testSacs) {
        Set originalSacsSet = new HashSet();
        for (SourceAndConverter sac : original_sacs) {
            originalSacsSet.add(sac);
        }
        if (originalSacsSet.containsAll(testSacs) && testSacs.containsAll(originalSacsSet)) {
            return true;
        }
        Set transformedSacsSet = new HashSet();
        for (SourceAndConverter sac : relocated_sacs_positioning_mode) {
            transformedSacsSet.add(sac);
        }
        if (transformedSacsSet.containsAll(testSacs) && testSacs.containsAll(transformedSacsSet)) {
            return true;
        }

        return false;
    }

    protected boolean isContainingAny(Collection<SourceAndConverter<?>> sacs) {
        Set originalSacsSet = new HashSet();
        for (SourceAndConverter sac : original_sacs) {
            originalSacsSet.add(sac);
        }
        if (sacs.stream().distinct().anyMatch(originalSacsSet::contains)) {
            return true;
        }
        Set transformedSacsSet = new HashSet();
        for (SourceAndConverter sac : relocated_sacs_positioning_mode) {
            transformedSacsSet.add(sac);
        }
        if (sacs.stream().distinct().anyMatch(transformedSacsSet::contains)) {
            return true;
        }
        return false;
    }

    public void waitForEndOfRegistrations() {
        //if (registrationTasks!=null) {
        try {
            CompletableFuture.allOf(registrationTasks).get();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Some registration were cancelled");
        }
        //}
    }

    protected void updatePosition() {
        AffineTransform3D zShiftAffineTransform = new AffineTransform3D();
        zShiftAffineTransform.translate(0, 0, slicingAxisPosition);
        zPositioner.setAffineTransform(zShiftAffineTransform); // Moves the registered slices to the correct position
        AffineTransform3D slicingModePositionAffineTransform = new AffineTransform3D();
        RealPoint center = getCenterPositionPMode();
        slicingModePositionAffineTransform.translate(center.getDoublePosition(0), center.getDoublePosition(1), -slicingAxisPosition);
        slicingModePositioner.setAffineTransform(slicingModePositionAffineTransform);
    }

    public RealPoint getCenterPositionPMode() {
        double slicingAxisSnapped = (((int) ((slicingAxisPosition) / mp.sizePixX)) * mp.sizePixX);
        double posX = (slicingAxisSnapped / mp.sizePixX * mp.sX / mp.reslicedAtlas.getStep()) + 0.5 * (mp.sX);
        double posY = mp.sY * yShift_slicing_mode;
        return new RealPoint(posX, posY, 0);
    }

    public RealPoint getCenterPositionRMode() {
        return new RealPoint(0, 0, slicingAxisPosition);
    }

    boolean processInProgress = false; // flag : true if a registration process is in progress

    public CompletableFuture<Boolean> registrationTasks = CompletableFuture.completedFuture(true);

    private List<Registration> registrations = new ArrayList<>();

    /**
     * Asynchronous handling of registrations + combining with manual sequential registration if necessary
     *
     * @param reg
     */

    protected void addRegistration(Registration<SourceAndConverter[]> reg,
                                   Function<SourceAndConverter[], SourceAndConverter[]> preprocessFixed,
                                   Function<SourceAndConverter[], SourceAndConverter[]> preprocessMoving) {

        pendingRegistrations.add(reg);
        lockedRegistrations.add(reg);

        if (registrationTasks == null || registrationTasks.isDone()) {
            registrationTasks = CompletableFuture.supplyAsync(() -> true); // Starts async computation, maybe there's a better way
        }

        registrationTasks = registrationTasks.thenApplyAsync((flag) -> {
            processInProgress = true;
            if (flag == false) {
                System.out.println("Downstream registration failed");
                return false;
            }
            boolean out;

            if (reg.isManual()) {
                System.out.println("Waiting for manual lock...");
                //current.setText("Lock (Manual)");
                synchronized (MultiSlicePositioner.manualActionLock) {
                    // locked = false;
                    lockedRegistrations.remove(reg);
                    System.out.println("Manual lock released...");
                    //current.setText("Current");
                    reg.setFixedImage(preprocessFixed.apply(mp.reslicedAtlas.nonExtendedSlicedSources));
                    reg.setMovingImage(preprocessMoving.apply(registered_sacs));
                    System.out.println("Registration will start");
                    out = reg.register();
                    if (!out) {

                    } else {
                        SourceAndConverterServices.getSourceAndConverterDisplayService()
                                .remove(mp.bdvh, registered_sacs);

                        registered_sacs = reg.getTransformedImageMovingToFixed(registered_sacs);

                        SourceAndConverterServices.getSourceAndConverterDisplayService()
                                .show(mp.bdvh, registered_sacs);

                        slicingModePositioner = new AffineTransformedSourceWrapperRegistration();

                        slicingModePositioner.setMovingImage(registered_sacs);
                        SourceAndConverterServices.getSourceAndConverterService().remove(relocated_sacs_positioning_mode);

                        relocated_sacs_positioning_mode = slicingModePositioner.getTransformedImageMovingToFixed(registered_sacs);
                        updatePosition();

                        registered_sacs_sequence.put(reg, registered_sacs);
                    }
                }
            } else {
                reg.setFixedImage(preprocessFixed.apply(mp.reslicedAtlas.nonExtendedSlicedSources));
                reg.setMovingImage(preprocessMoving.apply(registered_sacs));
                // locked = false;
                lockedRegistrations.remove(reg);
                out = reg.register();
                if (!out) {
                    // Registration did not went well ...
                } else {
                    SourceAndConverterServices.getSourceAndConverterDisplayService()
                            .remove(mp.bdvh, registered_sacs);

                    registered_sacs = reg.getTransformedImageMovingToFixed(registered_sacs);


                    slicingModePositioner = new AffineTransformedSourceWrapperRegistration();

                    slicingModePositioner.setMovingImage(registered_sacs);
                    SourceAndConverterServices.getSourceAndConverterService().remove(relocated_sacs_positioning_mode);

                    relocated_sacs_positioning_mode = slicingModePositioner.getTransformedImageMovingToFixed(registered_sacs);
                    updatePosition();

                    if (mp.currentMode.equals(MultiSlicePositioner.POSITIONING_MODE)) {
                        SourceAndConverterServices.getSourceAndConverterDisplayService()
                                .show(mp.bdvh, relocated_sacs_positioning_mode);
                    } else if (mp.currentMode.equals(MultiSlicePositioner.REGISTRATION_MODE)) {
                        SourceAndConverterServices.getSourceAndConverterDisplayService()
                                .show(mp.bdvh, registered_sacs);
                    }

                    registered_sacs_sequence.put(reg, registered_sacs);
                }
            }
            if (out) {
                registrations.add(reg);
            } else {

            }
            processInProgress = false;
            mp.mso.updateInfoPanel(this);
            return out;
        });

        registrationTasks.handle((result, exception) -> {
            if (result == false) {
                System.out.println("Registration task failed");
            }
            processInProgress = false;
            pendingRegistrations.remove(reg);
            System.out.println("Remaining registrations : " + pendingRegistrations.size());
            System.out.println("exception = " + exception);
            mp.mso.updateInfoPanel(this);
            return exception;
        });
    }

    // TODO
    protected void cancelCurrentRegistrations() {
        registrationTasks.cancel(true);
    }

    public synchronized boolean removeRegistration(Registration reg) {
        if (pendingRegistrations.contains(reg)) {
            System.out.println("Attempt to cancel current registrations...");
            cancelCurrentRegistrations();
            System.out.println("Attempt to cancel current registrations...");
            return true;
        }
        if (registrations.contains(reg)) {
            int idx = registrations.indexOf(reg);
            if (idx == registrations.size() - 1) {

                registrations.remove(reg);
                registered_sacs_sequence.remove(reg);

                Registration last = registrations.get(registrations.size() - 1);

                SourceAndConverterServices.getSourceAndConverterService()
                        .remove(registered_sacs);
                SourceAndConverterServices.getSourceAndConverterService()
                        .remove(relocated_sacs_positioning_mode);

                registered_sacs = registered_sacs_sequence.get(last);

                slicingModePositioner = new AffineTransformedSourceWrapperRegistration();

                slicingModePositioner.setMovingImage(registered_sacs);
                SourceAndConverterServices.getSourceAndConverterService().remove(relocated_sacs_positioning_mode);

                relocated_sacs_positioning_mode = slicingModePositioner.getTransformedImageMovingToFixed(registered_sacs);
                updatePosition();

                if (mp.currentMode.equals(MultiSlicePositioner.REGISTRATION_MODE)) {
                    SourceAndConverterServices.getSourceAndConverterDisplayService()
                            .show(mp.bdvh, registered_sacs);
                }
                if (mp.currentMode.equals(MultiSlicePositioner.POSITIONING_MODE)) {
                    SourceAndConverterServices.getSourceAndConverterDisplayService()
                            .show(mp.bdvh, relocated_sacs_positioning_mode);
                    enableGraphicalHandles();
                }

                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }
}