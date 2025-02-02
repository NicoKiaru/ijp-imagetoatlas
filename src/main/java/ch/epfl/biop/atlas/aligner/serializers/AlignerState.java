package ch.epfl.biop.atlas.aligner.serializers;

import ch.epfl.biop.atlas.aligner.*;
import net.imglib2.realtransform.AffineTransform3D;
import spimdata.util.Displaysettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class AlignerState {

    public AlignerState(MultiSlicePositioner mp) {

        displayMode = mp.getDisplayMode();
        sliceDisplayMode = mp.getSliceDisplayMode();
        overlapMode = mp.getOverlapMode();
        bdvView = mp.getBdvh().getViewerPanel().state().getViewerTransform();
        rotationX = mp.getReslicedAtlas().getRotateX();
        rotationY = mp.getReslicedAtlas().getRotateY();
        iCurrentSlice = mp.getCurrentSliceIndex();

        mp.getSortedSlices().forEach(sliceSource -> {
            SliceSourcesState slice_state = new SliceSourcesState();
            filterSerializedActions(mp.getActionsFromSlice(sliceSource))
                    .forEach(action -> slice_state.actions.add(action));

            slice_state.channelsVisibility = sliceSource.getGUIState().getChannelsVisibility();
            slice_state.settings_per_channel = sliceSource.getGUIState().getDisplaysettings();
            slice_state.preTransform = sliceSource.getTransformSourceOrigin();
            slice_state.sliceVisibleUser = sliceSource.getGUIState().isSliceVisible();

            slices_state_list.add(slice_state);
        });
    }

    public int displayMode;

    public int sliceDisplayMode;

    public int overlapMode;

    public int iCurrentSlice;

    public double rotationX;

    public double rotationY;

    public AffineTransform3D bdvView;

    public List<SliceSourcesState> slices_state_list = new ArrayList<>();

    public static class SliceSourcesState {
        transient public SliceSources slice;
        public AffineTransform3D preTransform;
        public List<CancelableAction> actions = new ArrayList<>();
        public Displaysettings[] settings_per_channel;
        public boolean sliceVisibleUser;
        public boolean[] channelsVisibility;
    }

    /*
    Some actions will not be serialized like the export actions and we
    need to somehow 'compile' actions to get rid of some actions which are there
    for user convenience but that we do not want to keep.
    For instance a series of attempted registration then deleted will not be saved.
     */

    static List<CancelableAction> filterSerializedActions(List<CancelableAction> ini_actions) {
        Set<Class<? extends CancelableAction>> serializableActions = new HashSet<>();
        serializableActions.add(CreateSlice.class);
        serializableActions.add(MoveSlice.class);
        serializableActions.add(RegisterSlice.class);

        Set<Class<? extends CancelableAction>> skipableActions = new HashSet<>();
        skipableActions.add(ExportSliceRegionsToFile.class);
        skipableActions.add(ExportSliceRegionsToQuPathProject.class);
        skipableActions.add(ExportSliceRegionsToRoiManager.class);

        if ((ini_actions == null)||(ini_actions.size()==0)) {
            System.err.println("Wrong number of actions to be serialized");
            return null;
        }
        if (!(ini_actions.get(0) instanceof CreateSlice)) {
            System.err.println("Error : the first action is not a CreateSlice action");
            return null;
        }
        List<CancelableAction> compiledActions = new ArrayList<>();
        int idxCompiledActions = 0;
        int idxIniActions = 0;
        while (ini_actions.size()>idxIniActions) {
            CancelableAction nextAction = ini_actions.get(idxIniActions);
            if (serializableActions.contains(nextAction.getClass())) {
                idxCompiledActions++;
                idxIniActions++;
                compiledActions.add(nextAction);
            } else {
                if (skipableActions.contains(nextAction.getClass())) {
                    idxIniActions++;
                } else {
                    if (nextAction instanceof DeleteLastRegistration) {
                        // For now...
                        if (compiledActions.get(idxCompiledActions-1) instanceof RegisterSlice) {
                            compiledActions.remove(idxCompiledActions-1);
                            idxCompiledActions--;
                            idxIniActions++;
                        } else {
                            System.err.println("Error : issue with filtering serializable actions");
                            idxIniActions++;
                        }
                    } else {
                        System.err.println("Error : issue with filtering serializable actions. Action class = "+nextAction.getClass());
                        idxIniActions++;
                    }
                }
            }
        }

        return compiledActions;
    }


}
