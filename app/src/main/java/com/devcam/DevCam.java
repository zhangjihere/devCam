package com.devcam;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.TonemapCurve;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * DevCam class.
 * <p>
 * The
 */
final public class DevCam {

    // - - - - Class Constants - - - -
    public final static String APP_TAG = "devCam";
    // ID tag for inter-thread communication of A3 results from preview results
    final static int AUTO_RESULTS = 100;
    final static int CAMERA_LOAD_ERROR = 101;
    final static int INADEQUATE_CAMERA = 102;
    final static int CAE = 103;
    final static int SESSION_CONFIGURE_FAILED = 104;
    final static int EMPTY_DESIGN = 106;
    final static int UNKNOWN = 128294;

    // Keep track of the asynchronous states involved with establishing a CameraCaptureSession.
    private boolean outstandingSessionRequest;
    private boolean awaitingCaptureSession;

    // Keep track of the single DevCam instance that is allowed
    static private DevCam mInstance = null;

    private final Context mContext;
    private String mBackCamId;

    // Keep track of a callback handler the user must have created.
    private final DevCamListener mRegisteredCallback;

    // Camera-related member variables that the DevCam manages.
    private CameraDevice mCamera;
    private CameraCaptureSession mCaptureSession;
    private CameraCharacteristics mCamChars;
    private CaptureRequest.Builder mPreviewCRB;

    // Keep lists of Surfaces to have registered with the DevCam-managed CameraCaptureSession.
    // Those stored as preview surfaces will get recurring images sent to them, AS WELL AS the
    // frames involved in a target capture sequence. Those stored as output surfaces will only
    // receive frames that are specified as part of a CaptureDesign.
    private List<Surface> mPreviewSurfaces = new ArrayList<>();
    private List<Surface> mOutputSurfaces = new ArrayList<>();

    // Operation-related member variables
    protected Handler mBackgroundHandler;
    protected Handler mMainHandler;
    private HandlerThread mBackgroundThread;
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    // Flags for indicating capabilities of camera hardware the DevCam opens
    private boolean mHasManualSensor = false;
    private boolean mHasPostProcessingControl = false;
    private boolean mReadyFlag = false; // is the DevCam ready for accepting CaptureDesigns?


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // - - - - - - - - - Important Camera Lifecycle Callback / Listener object fields - - - - - - -


    /* Callback for when the CameraDevice is accessed. We need the CameraDevice
     * object passed to us here in order to open a CameraCaptureSession and to
     * create CaptureRequest.Builders, so save it as a member variable when we get
     * it.
     */
    private final CameraDevice.StateCallback CDSC = new CameraDevice.StateCallback() {

        @Override
        public void onClosed(CameraDevice camera) {
            Log.v(APP_TAG, "camera device onClosed() called.");
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.v(APP_TAG, "camera device onDisconnected() called.");
            // If camera disconnected, free resources.
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.v(APP_TAG, "onError() when loading camera device!");
            // If camera error, free resources.
            mCameraOpenCloseLock.release();
            camera.close();
            mCamera = null;
            mRegisteredCallback.onCameraDeviceError(CAMERA_LOAD_ERROR);
        }

        @Override
        public void onOpened(CameraDevice camera) {
            Log.v(APP_TAG, "CameraDevice opened correctly.");
            mCameraOpenCloseLock.release();
            mCamera = camera;


            // Now that camera is open, and we know the preview Surface is
            // ready to receive from it, try creating a CameraCaptureSession.
            updateCaptureSession();
        }
    };

    /**
     * This callback object has to do with (un)successful creation of the CameraCaptureSession,
     * which is associated with both the camera device and the target output Surfaces for captures.
     * Once the Session is successfully configured the DevCam is ready to use, and informs the user.
     */
    private final CameraCaptureSession.StateCallback CCSSC = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.v(APP_TAG, "CameraCaptureSession configured correctly.");
            mCaptureSession = session; // Keep track of the Session itself for later commands

            // We have successfully finished the configuration, so release the flag holding for that state
            awaitingCaptureSession = false;

            // If new Surfaces were registered with DevCam while the CaptureSession was setting up,
            // we need to call for creation of a NEW CaptureSession so that the outputs the user
            // expects to be valid actually are. So instead of reporting that this (now obsolete)
            // Session is ready, simply prompt for set up of a new Session and let that report back
            // to the user.
            if (outstandingSessionRequest) {
                Log.v(APP_TAG, "Outstanding session request exists (surfaces must have been updated). Re-requesting capture session.");
                outstandingSessionRequest = false;
                updateCaptureSession();
                return;
            }

            // We now have access to a Session, so let's use it to start a recurring preview request
            // Camera existence check is necessary in some strange cases, and does no harm.
            if (mCamera != null) {
                try {
                    // Make sure Preview surfaces are valid, and then start a repeating preview
                    mPreviewCRB = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    for (Surface s : mPreviewSurfaces) {
                        if (isInvalidSurface(s)) {
                            Log.v(APP_TAG, "Surface is not valid for device session.");
                            return;
                        }
                        mPreviewCRB.addTarget(s);
                    }
                    mCaptureSession.setRepeatingRequest(mPreviewCRB.build(), previewCCB, mBackgroundHandler);
                } catch (CameraAccessException cae) {
                    cae.printStackTrace();
                    Log.v(APP_TAG, "CameraAccessException when trying to set up preview request.");
                    mRegisteredCallback.onCameraDeviceError(CAE);
                }

                // We are now completely ready to start initiating capture sequences.
                // Alert the user.
                mReadyFlag = true;
                mRegisteredCallback.onDevCamReady(mHasPostProcessingControl);
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.v(APP_TAG, "CameraCaptureSession.StateCallback.onConfigurationFailed() called!");
            awaitingCaptureSession = false;

            // Try to create a new session if new Surfaces have been registered
            if (outstandingSessionRequest) {
                Log.v(APP_TAG, "Outstanding session request exists (surfaces must have been updated). Re-requesting capture session.");
                outstandingSessionRequest = false;
                updateCaptureSession();
                return;
            }

            mRegisteredCallback.onCameraDeviceError(SESSION_CONFIGURE_FAILED);
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            Log.v(APP_TAG, "Capture Session onClosed() called.");
        }
    };

    /* CaptureCallback for the preview window. This should be going as a
     * repeating request whenever we are not actively capturing a Design.
     *
     * This runs on a background thread and whenever a preview frame is
     * available, complete with AE/AF results, send the CaptureResult back to
     * the main thread so that the auto-views on the preview window can be
     * updated. This allows the user to know what the auto-algorithms suggest
     * for the current scene, if they care.
     */
    private final CameraCaptureSession.CaptureCallback previewCCB =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request, TotalCaptureResult result) {
//                    Log.v(APP_TAG,"Preview Image ready!");
                    // Send the auto values back to the main thread for display
                    Message msg = mMainHandler.obtainMessage(AUTO_RESULTS, result);
                    msg.sendToTarget();
                }
            };


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // - - - - - - - - - - - - - - - - Public-facing API methods - - - - - - - - - - - - - - - - - -


    /**
     * A DevCamListener must be registered with the DevCam when its instance is created. This
     * allows the calling class to be alerted to necessary events in the DevCam lifecycle. These
     * include events regarding the DevCam itself, as well as events relating to its output of
     * desired capture sequences.
     */
    public static abstract class DevCamListener {
        // Whenever a new frame is ready from the auto-recurring preview (which will be frequently!)
        // this returns the metadata about that frame to the main Activity, in case it can make
        // use of it.
        void onAutoResultsReady(CaptureResult result) {
        }

        // If there is an issue with accessing the CameraDevice or its other related classes for
        // some reason, this feeds that error forward to the calling Activity so it knows not to
        // try to use the DevCam.
        void onCameraDeviceError(int error) {
            Log.v(APP_TAG, "DevCam.StateCallback.onCameraDeviceError() called. Error: " + error);
        }

        // When the DevCam has established all of its necessary resources, it will inform the caller
        // via this function that it is ready to accept CaptureRequests. DevCam has been set up to
        // work without requiring the ability to apply post-processing control, and so this returns
        // a flag indicating if the camera device has this capability or not. If not, any requests
        // for post-processing in submitted CaptureDesigns will not throw an error but will simply
        // be ignored.
        void onDevCamReady(boolean postProcControl) {
            Log.v(APP_TAG, "DevCam.StateCallback.onDevCamReady() called. Post-processing control?: " + postProcControl);
        }

        // The following callbacks happen during the capture sequence of a submitted CaptureDesign.

        // When an exposure of the target sequence is started, this is called and supplies the time
        // stamp identifying the frame. This is usually useful to save in a DesignResult for later.
        void onCaptureStarted(Long timestamp) {
            Log.v(APP_TAG, "DevCam.StateCallback.onCaptureStarted() called. Timestamp: " + timestamp / 1000);
        }

        // When a capture is completed, successfully or not, one of the following functions is
        // called

        void onCaptureCompleted(CaptureResult result) {
            Log.v(APP_TAG, "DevCam.StateCallback.OnCaptureCompleted() called. Timestamp: " + result.get(CaptureResult.SENSOR_TIMESTAMP) / 1000);
        }

        void onCaptureFailed(int code) {
            Log.v(APP_TAG, "DevCam.StateCallback.onCaptureFailed() called. Code: " + code);
        }

        // When the entire sequence of captures associated with a submitted CaptureDesign is
        // completed- that is, when the last exposure has been completed, NOT when the last image
        // has been made available- this function alerts the user. The last CaptureResult may or may
        // not have been returned yet via onCaptureCompleted(), and the last image MAY or may not
        // have been made available alrady (YUV often will be, other formats not).
        void onCaptureSequenceCompleted() {
            Log.v(APP_TAG, "DevCam.StateCallback.onCaptureSequenceCompleted() called.");
        }

    }

    /**
     * Get the CameraCharacteristics of the camera that DevCam will access.
     *
     * <p>Useful for obtaining the size/format output stream combinations the device can provide,
     * for setting up the output Surfaces before registering them wth the DevCam. This can be called
     * at any time.</p>
     *
     * @param context The Context of the application, usually just the Activity itself.
     * @return CameraCharacteristics instance for the camera to be used
     */
    static public CameraCharacteristics getCameraCharacteristics(Context context) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics camChars = null;
        try {
            // ASSUMING there is one backward facing camera, and that it is the
            // device we want to use, find the ID for it and its capabilities.
            String[] deviceList = cm.getCameraIdList();
            for (String backCamId : deviceList) {
                camChars = cm.getCameraCharacteristics(backCamId);
                if (camChars.get(CameraCharacteristics.LENS_FACING)
                        == CameraMetadata.LENS_FACING_BACK) {
                    break;
                }
            }
        } catch (CameraAccessException cae) {
            // If we couldn't load the camera, that's a bad sign. Just quit.
            Log.v(APP_TAG, "Error loading CameraDevice");
            cae.printStackTrace();
        }
        return camChars;
    }

    /**
     * Start the process of establishing the DevCam's connection to the camera device. When this
     * process is complete, onDevCamReady() will be called and preview images will be sent to the
     * registered preview Surfaces.
     *
     * <p>The user must provide some preview image target Surfaces with registerPreviewSurfaces()
     * prior to calling this method. If the camera device does not allow manual sensor control, the
     * camera access protocol will never begin, and onDevCamReady() will never be called.</p>
     */
    public void startCam() {
        Log.v(APP_TAG, "DevCam.startCam() called.");

        // Check that we have the necessary components
        // Check preview surface
        if (mPreviewSurfaces.size() == 0) {
            Log.v(APP_TAG, "No Surface to send preview to.");
            // alert the user somehow?
            return;
        }

        for (Surface s : mPreviewSurfaces) {
            if (isInvalidSurface(s)) {
                Log.v(APP_TAG, "Surface is not valid for device session.");
                return;
            }
        }

        if (!mHasManualSensor) {
            Log.v(APP_TAG, "Sensor does not allow manual control.");
            return;
        }

        // start the background thread
        establishThreads();

        // start the camera()
        accessCamera();
    }

    /**
     * Stop the vital processes DevCam commands. Always make sure the DevCam is stopped before the
     * Activity is paused.
     *
     * <p>This stops the threads DevCam created for itself, as well as releases the Surfaces
     * registered with it, and the CameraDevice itself, so that another app can use it.</p>
     */
    public void stopCam() {
        Log.v(APP_TAG, "DevCam.stopCam() called.");
        mReadyFlag = false;

        // stop the camera
        closeCamera();

        // stop the background thread
        stopBackgroundThreads();

        // unregister the surfaces
        mPreviewSurfaces = new ArrayList<>();
        mOutputSurfaces = new ArrayList<>();

        // NOTE! The surfaces are released every time, so make sure they are re-created and re-added
        // the next time you use the DevCam.
    }

    /**
     * Generate and return a DevCam instance for the main Activity to use. DevCam uses a singular-
     * instance model and future calls to this function will only return the same DevCam reference.
     *
     * @param context  The Context of the application, typically the Activity itself.
     * @param callback A DevCam.StateCallback instance to register with the DevCam
     * @return A singular instance of a DevCam.
     */
    static public DevCam getInstance(Context context, DevCamListener callback) {
        if (mInstance == null) {
            mInstance = new DevCam(context, callback);
            Log.v(APP_TAG, " * New DevCam instance created! *");
        } else {
            Log.v(APP_TAG, " * Returning old DevCam instance. *");
        }
        return mInstance;
    }

    /**
     * Check to see if the DevCam is configured and ready to accept CaptureRequests.
     *
     * @return boolean flag indicating readiness
     */
    public boolean isReady() {
        return mReadyFlag;
    }


    /**
     * Provide a list of output Surfaces to receive frame data from every preview image generated
     * by DevCam. This overrides any previously registered preview target surfaces.
     *
     * <p>Note that these preview images will be generated constantly (except during actual DevCam
     * target capture).</p>
     * <p>It is the user's responsibility to make sure Surfaces are valid size/format combinations
     * for this device. Registering new Surfaces will trigger the internal creation of a new
     * CameraCaptureSession, which will call onDevCamReady() when complete.</p>
     *
     * @param surfaces A list of Surfaces set up to receive DevCam output frames
     */
    public void registerPreviewSurfaces(List<Surface> surfaces) {
        Log.v(APP_TAG, "Registering new Surface with DevCam.");
        mPreviewSurfaces = surfaces;
        // If a CameraCaptureSession has requested but not yet obtained, wait for that process to
        // finish before requesting another one. Set a flag instead.
        if (awaitingCaptureSession) {
            outstandingSessionRequest = true;
        } else if (mCamera != null) {
            updateCaptureSession();
        }
    }

    /**
     * Provide a list of output Surfaces to receive frame data from captures associated with an
     * input CaptureDesign. This overrides and previously registered output target surfaces.
     *
     * <p>It is the user's responsibility to make sure Surfaces are valid size/format combinations
     * for this device. Registering new Surfaces will trigger the internal creation of a new
     * CameraCaptureSession, which will call onDevCamReady() when complete.</p>
     *
     * @param surfaces A list of Surfaces set up to receive DevCam output frames
     */
    public void registerOutputSurfaces(List<Surface> surfaces) {
        Log.v(APP_TAG, "Registering new Surface with DevCam.");
        mOutputSurfaces = surfaces;
        // If a CameraCaptureSession has requested but not yet obtained, wait for that process to
        // finish before requesting another one. Set a flag instead.
        if (awaitingCaptureSession) {
            outstandingSessionRequest = true;
        } else if (mCamera != null) {
            updateCaptureSession();
        }
    }

    /**
     * Begins the process for capturing the desired set of exposures as a burst.
     *
     * <p>You should check isReady() before calling this, but generally after onDevCamReady() is
     * called, this should be available unless you have recently registered a new output Surface.
     * <p>
     * The following conditions will cause the capture to fail and onCaptureFailed() to be called:
     *
     * </p>
     *
     * @param design The sequence of exposures you wish to capture, as a CaptureDesign.
     */
    public void capture(CaptureDesign design) {

        mDesign = design;

        if (mPreviewSurfaces.size() == 0 || mOutputSurfaces.size() == 0) {
            mRegisteredCallback.onCaptureFailed(UNKNOWN);
            return;
        }

        for (Surface s : mOutputSurfaces) {
            if (isInvalidSurface(s)) {
                Log.v(APP_TAG, "Surface is not valid for device session.");
                mRegisteredCallback.onCaptureFailed(UNKNOWN);
                return;
            }
        }

        // If there are no exposures in the list to capture, just exit.
        if (mDesign.getExposures().size() == 0) {
            Log.v(APP_TAG, "No Exposures in list to capture!");
            mRegisteredCallback.onCaptureFailed(EMPTY_DESIGN);
            return;
        }

        if (!mReadyFlag) {
            Log.v(APP_TAG, "DevCam not ready for capture yet.");
            mRegisteredCallback.onCaptureFailed(UNKNOWN);
            return;
        }

        mReadyFlag = false; // Already capturing. Don't allow another capture yet.

        // Initialize before capture
        mNumCaptured = 0;

        try {
            Log.v(APP_TAG, "- - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
            Log.v(APP_TAG, "Starting Design Capture. Stop repeating preview images.");
            mCaptureSession.stopRepeating(); // Stop the preview repeating requests from clogging the works

            // Generate a CaptureRequest.Builder with the appropriate settings
            mCaptureCRB = makeDesignCrb(mDesign);

            // Initially set for manual control and then, depending on the settings, re-instate auto
            assert mCaptureCRB != null;
            mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);


            // Now loop over all Exposures in the sequence, and see if any of them require
            // 1) any Auto at all
            // 2) Auto focus
            // 3) Auto exposure
            // Keep track for later.
            int withoutVariables = 0;
            mNeedsAF = false;
            mNeedsAE = false;
            for (Exposure e : mDesign.getExposures()) {
                if (!e.hasVariableValues()) {
                    withoutVariables++;
                }
                if (e.hasVariableFocusDistance()) {
                    mNeedsAF = true;
                }
                if (e.hasVariableSensitivity()
                        || e.hasVariableExposureTime()
                        || e.hasVariableAperture()) {
                    mNeedsAE = true;
                }
            }

            // If the Exposures don't require ANY Auto information, i.e. if all parameter values
            // were explicit, simply capture the burst.
            if (withoutVariables == mDesign.getExposures().size()) {
                Log.v(APP_TAG, "No Auto needed, simply capturing burst.");
                captureSequenceBurst(mDesign);
                return;
            }


            // Otherwise, we want the auto-process capture sequence to start.
            // Now set the auto controls as desired, and the states accordingly. Note that we use
            // a state machine that goes from determining focus to determining exposure, so we
            // only change the state to WAITING_FOR_AE if the we are not waiting for AF first
            if (mNeedsAF) {
                state = AutoState.WAITING_FOR_AF;
                mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                //mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            }

            if (mNeedsAE) {
                if (!mNeedsAF) state = AutoState.WAITING_FOR_AE;
                mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            }

            // Actually post the first request to start the auto-routines
            mCaptureSession.capture(mCaptureCRB.build(), mAutoCCB, mBackgroundHandler);

        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            mRegisteredCallback.onCameraDeviceError(CAE);
            mReadyFlag = true;
        }
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    // - - - - - - - - - - - - - - - - Internal methods - - - - - - - - - - - - - - - - - - - - - -


    /**
     * DevCam constructor, private to enforce single instance rule. Requires a Context in order to
     * access CameraManager, and a DevCamListener in order to know where to send relevant
     * information, since a DevCam cannot exist in a void.
     *
     * @param context  Android Context
     * @param callback DevCamListener
     */
    private DevCam(Context context, DevCamListener callback) {
        mContext = context;
        mRegisteredCallback = callback;

        // Find the ID for the camera we are going to use, but don't try to access it yet.
        CameraManager cm = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            // ASSUMING there is one backward facing camera, and that it is the
            // device we want to use, find the ID for it and its capabilities.
            String[] deviceList = cm.getCameraIdList();
            for (String s : deviceList) {
                mBackCamId = s;
                mCamChars = cm.getCameraCharacteristics(mBackCamId);
                if (mCamChars.get(CameraCharacteristics.LENS_FACING)
                        == CameraMetadata.LENS_FACING_BACK) {
                    break;
                }
            }

            // Catch inadequate cameras, those which will not allow manual setting of exposure
            // settings and/or processing settings
            int[] capabilities = mCamChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            for (int capability : capabilities) {
                if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                    mHasManualSensor = true;
                }
                if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) {
                    mHasPostProcessingControl = true;
                }
            }
            if (!mHasManualSensor) {
                mRegisteredCallback.onCameraDeviceError(INADEQUATE_CAMERA);
                // SOMETHING ELSE HERE TO MAKE SURE CAMERA ISN'T ACTUALLY USED? Sloppy, fix this
            }
        } catch (CameraAccessException cae) {
            // If we couldn't load the camera, that's a bad sign. Just quit.
            Log.v(APP_TAG, "Error loading CameraDevice");
            cae.printStackTrace();
            mRegisteredCallback.onCameraDeviceError(CAE);
        }

    }

    /**
     * Starts a repeating capture request with default "preview" settings.
     * <p>
     * Assumes there is a registered preview Surface to send outputs to.
     */
    private void restorePreview() {
        Log.v(APP_TAG, "*internal* DevCam.restorePreview() called.");
        try {
            // Stop any repeating requests
            mCaptureSession.stopRepeating();
            // Explicitly unlock AE and set AF back to idle, in case last capture design locked them.
            mPreviewCRB.set(CaptureRequest.CONTROL_AE_LOCK, false);
            mPreviewCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            mCaptureSession.capture(mPreviewCRB.build(), previewCCB, mBackgroundHandler);

            // Now let the repeating normal (non-AF-canceling) preview request run.
            mPreviewCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            mCaptureSession.setRepeatingRequest(mPreviewCRB.build(), previewCCB, mBackgroundHandler);
        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            Log.v(APP_TAG, "CameraAccessException when trying to restore preview request.");
            mRegisteredCallback.onCameraDeviceError(CAE);
        }
    }

    /**
     * Establishes the thread resources that need to be set up every time the activity restarts.
     */
    private void establishThreads() {
        Log.v(APP_TAG, "*internal* DevCam.establishThreads() called.");

        // Set up a main-thread handler to receive information from the
        // background thread-based mPreviewCCB object, which sends A3
        // information back from the continuously generated preview results in
        // order to update the "auto views", which must be done in main thread.
        mMainHandler = new Handler(mContext.getMainLooper()) {
            @Override
            public void handleMessage(Message inputMessage) {
                // If mPreviewCCB sent back some CaptureResults, save them
                // and update views.
                if (inputMessage.what == AUTO_RESULTS) {
                    mRegisteredCallback.onAutoResultsReady((CaptureResult) inputMessage.obj);
                } else {
                    super.handleMessage(inputMessage);
                }
            }
        };

        // Set up background threads so as not to block the main UI thread.
        // One for all the camera callbacks.
        if (null == mBackgroundThread) {
            mBackgroundThread = new HandlerThread("devCam CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    /**
     * Protected way of trying to open the CameraDevice.
     */
    private void accessCamera() {
        Log.v(APP_TAG, "*internal* DevCam.accessCamera() called.");

        if (mCamera != null) {
            Log.v(APP_TAG, "Camera already aquired. Ignoring call.");
            return;
        }
        CameraManager cm = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cm.openCamera(mBackCamId, CDSC, mBackgroundHandler);
            Log.v(APP_TAG, "Trying to open camera...");
        } catch (CameraAccessException cae) {
            // If we couldn't load the camera, that's a bad sign. Just quit.
            Log.v(APP_TAG, "Error loading CameraDevice");
            cae.printStackTrace();
            mRegisteredCallback.onCameraDeviceError(CAE);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * When finishing the activity, make sure the threads are quit safely.
     */
    private void stopBackgroundThreads() {
        Log.v(APP_TAG, "*internal* DevCam.stopBackgroundThreads() called.");
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Safely close the camera resources the DevCam is managing.
     */
    private void closeCamera() {
        Log.v(APP_TAG, "*internal* DevCam.closeCamera() called.");
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCamera) {
                mCamera.close();
                mCamera = null;
                Log.v(APP_TAG, "mCamera set to null.");
            }

        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Placeholder function for checking validity of Surfaces for CameraCaptureSession output
     * targets, since this sometimes causes difficulties.
     *
     * @param s Surface to check validity of
     * @return Validity flag
     */
    private boolean isInvalidSurface(Surface s) {
        return !s.isValid();
    }

    /**
     * Prompt the creation of a new CameraCaptureSession with all of the currently DevCam-registered
     * Surfaces as output possibilities.
     */
    private void updateCaptureSession() {
        Log.v(APP_TAG, "DevCam.updateCaptureSession() called.");

        if (mCamera == null) {
            Log.v(APP_TAG, "mCamera==null, waiting for it to be accessed.");
            return;
        }

        // creating a capture session requires a single list of surfaces to be registered
        List<Surface> surfaces = new ArrayList<>();
        surfaces.addAll(mPreviewSurfaces);
        surfaces.addAll(mOutputSurfaces);

        for (Surface s : surfaces) {
            if (isInvalidSurface(s)) {
                Log.v(APP_TAG, "Surface is not valid for device session.");
                return;
            }
        }

        // Now that we are ready to request creation of a CameraCaptureSession, mark the DevCam as
        // not ready for operation yet, until the CameraCaptureSession is created or unsuccessful.
        boolean oldFlag = mReadyFlag; // Keep track of old state in case we need to restore it
        mReadyFlag = false;

        try {
            Log.v(APP_TAG, "Requesting creation of Capture Session.");
            // Let the DevCam know that it is awaiting the asynchronous creation of a Session, in
            // case new Surfaces get registered before the Session is created. In such a case, the
            // current Session creation request will resolve, and then immediately prompt the
            // creation of a NEW CameraCaptureSession with the new Surfaces registered, so that the
            // user never knows the first Session was created and tries to use it.
            awaitingCaptureSession = true;
            mCamera.createCaptureSession(surfaces, CCSSC, mBackgroundHandler);
        } catch (CameraAccessException cae) {
            // If we couldn't create a capture session, we have trouble. Abort!
            cae.printStackTrace();
            Log.v(APP_TAG, "CameraAccessException when trying to create capture session.");
            mRegisteredCallback.onCameraDeviceError(CAE);
            mReadyFlag = oldFlag; // Restore old flag, whatever that was
        }

    }



    /* - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -*/
    /* - - - - - - - - - -  THIS STUFF HAS TO DO WITH THE ACTUAL CAPTURE PROCESS - - - - - - - - -*/


    private int mNumCaptured;
    private CaptureRequest.Builder mCaptureCRB;

    // State variable and possible static values for the auto-focus/exposure state machine
    private enum AutoState {WAITING_FOR_AF, WAITING_FOR_AE}

    AutoState state;

    // Flags to indicate which auto-processes are needed.
    private boolean mNeedsAF;
    private boolean mNeedsAE;

    private CaptureDesign mDesign;

    /* void captureSequenceBurst()
     *
     * Method for taking the list of target Exposures, turning them into CaptureRequests, and then
     * capturing them as a burst. Once this is called, the camera is completely manually controlled
     * and the Exposures better have explicit values for all parameters.
     */
    private void captureSequenceBurst(CaptureDesign design) {

        // This should already have been done, but make sure all Exposure values are explicit.
        for (Exposure e : design.getExposures()) {
            if (e.hasVariableValues()) {
                Log.v(APP_TAG, "Can't Capture Burst if it has variable values!");
                mRegisteredCallback.onCaptureFailed(UNKNOWN);
                return;
            }
        }

        Log.v(APP_TAG, "- - - - - Capturing Exposure Sequence as a Burst.");
        List<CaptureRequest> burstRequests = new ArrayList<>();

        // Though some of them may have been originally derived from the scene, all parameter values
        // are now explicitly set. So make sure control modes are both OFF (leave AWB on)
        mCaptureCRB.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        mCaptureCRB.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

        // Now that we actually want to save images, send them to the ImageReader surface
        for (Surface s : mOutputSurfaces) {
            mCaptureCRB.addTarget(s);
        }

        for (Exposure next : design.getExposures()) {
            // don't change *_MODE settings, just values, to avoid state resets
            mCaptureCRB.set(CaptureRequest.SENSOR_EXPOSURE_TIME, next.getExposureTime());
            mCaptureCRB.set(CaptureRequest.SENSOR_SENSITIVITY, next.getSensitivity());
            mCaptureCRB.set(CaptureRequest.LENS_APERTURE, next.getAperture());
            mCaptureCRB.set(CaptureRequest.LENS_FOCAL_LENGTH, next.getFocalLength());
            mCaptureCRB.set(CaptureRequest.LENS_FOCUS_DISTANCE, next.getFocusDistance());

            burstRequests.add(mCaptureCRB.build());
        }

        try {
            mCaptureSession.captureBurst(burstRequests, frameCCB, mBackgroundHandler);
        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            mRegisteredCallback.onCameraDeviceError(CAE);
            mReadyFlag = true;
        }
    }

    /* CaptureRequest.Builder makeDesignCrb(CameraDevice)
     *
     * Creates the CaptureRequest.Builder for our capture process, assigning the relevant processing
     * modes. Leaves the control mode as AUTO for focus/exposure processes because we only want to
     * overwrite these at the right time.
     */
    private CaptureRequest.Builder makeDesignCrb(CaptureDesign design) {
        try {
            CaptureRequest.Builder crb = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            crb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            crb.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);

            // Make different settings based on Processing desired

            if (mHasPostProcessingControl) {
                switch (design.getProcessingSetting()) {
                    case NONE:
                        crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
                        //crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF); // Causes error?!
                        crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
                        float[] linear = {TonemapCurve.LEVEL_BLACK, TonemapCurve.LEVEL_BLACK,
                                TonemapCurve.LEVEL_WHITE, TonemapCurve.LEVEL_WHITE};
                        crb.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(linear, linear, linear));
                        break;
                    case FAST:
                        crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST);
                        crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
                        crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST);
                        break;
                    case HIGH_QUALITY:
                        crb.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
                        crb.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                        crb.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
                        break;
                }
            }
            for (Surface s : mPreviewSurfaces) {
                crb.addTarget(s);
            }
            return crb;

        } catch (CameraAccessException cae) {
            cae.printStackTrace();
            mRegisteredCallback.onCameraDeviceError(CAE);
            return null;
        }
    }

    /* CaptureCallback that handles frames that are part of the auto-Intent convergence state
     * machine cycle.
     *
     * Note this does not currently catch the situation where the AF will never focus (e.g. if the
     * scene is too close). It will just continue forever. Fix this later.
     */
    private final CameraCaptureSession.CaptureCallback mAutoCCB = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(CameraCaptureSession session,
                                       CaptureRequest request, TotalCaptureResult result) {
            Log.v(APP_TAG, "Auto State Check-in! - - - ");

            if (AutoState.WAITING_FOR_AF == state) {
                Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                Log.v(APP_TAG, "- - - AF_STATE: " + CameraReport.sContextMap.get("android.control.afState").get(afState));

                // If the AF state has converged, either to in-focus or not-in-focus, advance to
                // the next state, WAITING_FOR_AE, or if we don't care about AE, just finish.
                if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState) {
                    Log.v(APP_TAG, "- - - AF Focused.");

                    if (!mNeedsAE) {
                        Log.v(APP_TAG, "- - - Not requiring AE convergence.");
                        finishWithAuto(result);
                    } else {
                        state = AutoState.WAITING_FOR_AE;
                    }

                    // ELSE:
                    // This should never be entered, but in case we are unfocused, allow the AF machine
                    // to enter INACTIVE again and start a new search.
                } else if (CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                    try {
                        Log.v(APP_TAG, "- - - Triggering AF Passive state to lock.");
                        mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                        mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                        mCaptureSession.capture(mCaptureCRB.build(), this, mBackgroundHandler);
                    } catch (CameraAccessException cae) {
                        mRegisteredCallback.onCaptureFailed(UNKNOWN);
                        cae.printStackTrace();
                        mReadyFlag = true;
                    }


                    // ELSE:
                    // If we are passively focused, lock the focus (though wait for proof that it is
                    // locked in the next frame).
                } else if (CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED == afState) {
                    try {
                        Log.v(APP_TAG, "- - - Triggering AF Passive state to lock.");
                        mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                        mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                        mCaptureSession.capture(mCaptureCRB.build(), this, mBackgroundHandler);
                    } catch (CameraAccessException cae) {
                        mRegisteredCallback.onCaptureFailed(UNKNOWN);
                        cae.printStackTrace();
                        mReadyFlag = true;
                    }

                    // ELSE:
                    // We are either INACTIVE, PASSIVE_SEARCH, or PASSIVE_UNFOCUSED. Just continue
                    // the search for focus by posting another frame, without triggers so the
                    // processes don't start again or lock prematurely.
                } else {
                    try {
                        Log.v(APP_TAG, "- - - Continue the sequence...");
                        mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                        mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        mCaptureSession.capture(mCaptureCRB.build(), this, mBackgroundHandler);
                    } catch (CameraAccessException cae) {
                        mRegisteredCallback.onCaptureFailed(UNKNOWN);
                        cae.printStackTrace();
                        mReadyFlag = true;
                    }
                }

            }

            if (AutoState.WAITING_FOR_AE == state) {
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                Log.v(APP_TAG, "- - - AE_STATE: " + CameraReport.sContextMap.get("android.control.aeState").get(aeState));
                if (CaptureResult.CONTROL_AE_STATE_CONVERGED == aeState ||
                        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED == aeState) {
                    Log.v(APP_TAG, "- - - AE converged.");
                    finishWithAuto(result);
                } else {
                    try {
                        // Auto process is already in progress, so we don't need/want triggers to start them again
                        Log.v(APP_TAG, "- - - Trying again for AE convergence.");
                        mCaptureCRB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                        mCaptureCRB.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        mCaptureSession.capture(mCaptureCRB.build(), this, mBackgroundHandler);
                    } catch (CameraAccessException cae) {
                        cae.printStackTrace();
                        mRegisteredCallback.onCaptureFailed(UNKNOWN);
                        mReadyFlag = true;
                    }
                }
            }
        }

        /* void finishWithAuto(CaptureResult)
         *
         * This gets called by the callback when the desired auto-processes have converged. Now that
         * the state has been met, we can lock the values from it and capture the entire burst.
         */
        private void finishWithAuto(CaptureResult result) {
            Log.v(APP_TAG, "- - - Finished with the pre-capture Auto sequence.");

            // Now fill in the variable parameter values based on what we found, and capture.
            mDesign.fillAutoValues(mCamChars, result);
            captureSequenceBurst(mDesign);
        }
    };

    /* CaptureCallback that handles frames that are actually part of the desired image sequence,
     * not part of the auto-routine-convergence cycling.
     */
    private final CameraCaptureSession.CaptureCallback frameCCB = new CameraCaptureSession.CaptureCallback() {
        // Note this callback will be running on background thread.

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
            // When a targeted capture starts, record the identifying timestamp in the DesignResult
            // so that later steps, such as the ImageReader, can identify which images are wanted
            // and which are from the auto-convergence process.

            mRegisteredCallback.onCaptureStarted(timestamp);

            //mDesign.getDesignResult().recordCaptureTimestamp(timestamp);  *** put outside this class
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session,
                                       CaptureRequest request, TotalCaptureResult result) {
            Log.v(APP_TAG, "Frame capture completed, capture metadata available.");

            // Store the result for later matching with an Image and writing out

            mRegisteredCallback.onCaptureCompleted(result);
//            mDesign.getDesignResult().recordCaptureResult(result);  *** put outside this class

            // If we have just captured the last image in the sequence, we can skip the following
            // code and return here. The camera device is done for the time being, and the whole
            // capturing process will signal its end from the DesignResult object when the last
            // Image/CaptureResult pair has been recorded and written out.
            mNumCaptured++;
            if (mNumCaptured == mDesign.getExposures().size()) {
                Log.v(APP_TAG, "That was the last exposure to capture!");
                captureCleanup();
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session,
                                    CaptureRequest request, CaptureFailure failure) {
            Log.v(APP_TAG, "!!! Frame capture failure! Writing out the failed CaptureRequest !!!");

            // SLOPPY, NEED TO FIX, so that it doesn't refer to a DIR it doesn't know exists

            // If the capture failed, write out a JSON file with the metadata about it.
            File file = new File(MainDevCamActivity.APP_DIR, "Failed_CaptureRequest_" + ".json");

            CameraReport.writeCaptureRequestToFile(request, file);

            /* not implemented yet */
//            // Also increase the number of filenames in the DesignResult so that its method of
//            // knowing when all the captures have been completed does not get thrown off.
//            mDesignResult.reportFailedCapture();

            //Also let the CaptureDesign know not to wait any more for this frame.
            mNumCaptured++;
            if (mNumCaptured == mDesign.getExposures().size()) {
                Log.v(APP_TAG, "That was the last exposure to capture!");
                captureCleanup();
            }
        }
    };

    /**
     * Perform clean-up methods to restore standard state of DevCam after a CaptureDesign has been
     * fully captured.
     */
    private void captureCleanup() {
        Log.v(APP_TAG, "*internal* DevCam.captureCleanup() called.");
        mReadyFlag = true; // The device is now ready for capture again
        mNumCaptured = 0;
        mDesign = null;
        state = null;

        mRegisteredCallback.onCaptureSequenceCompleted();

        restorePreview();
    }

} // End whole class
