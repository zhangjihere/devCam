/* DesignResult class, which complements a CaptureDesign object. The DesignResult contains all of
 * the relevant information about the outputs of the capturing process of a CaptureDesign.
 * This includes, for each frame of the sequence:
 * - the timestamp of when the frame started capture. Used as a unique ID to indicate an image belonging to the sequence.
 * - the Image that was captured.
 * - the CaptureResult metadata associated with the frame
 * - the filename that this frame is to be written out to
 *
 * This information about each frame is captured as soon as it is available. In general, the
 * timestamp will come in first, since that is available as soon as the exposure starts integrating,
 * and obviously the CaptureResult and Image are not available until it is done. However, depending
 * on the device and the requested image format, the order in which these latter two pieces is
 * available is uncertain.
 *
 * To get around this, whenever a new CaptureResult or Image is available, it is stored in the
 * DesignResult. The DesignResult then checks to see if it has already registered the corresponding
 * element, based on unique timestamp ID.
 *
 * Once both elements are available and registered, the DesignResult generates a filename for the
 * frame capture and sends all three pieces back to the main activity thread for whatever action it
 * wants to take- generally writing out of the image via an ImageSaver object.
 */

package com.devcam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DesignResult {

    private final int mDesignLength;
    private final List<CaptureResult> mCaptureResults = new ArrayList<>();
    //	private Map<Long,CaptureResult> mCaptureResults = new HashMap<Long,CaptureResult>();
    private final List<Long> mCaptureTimestamps = new ArrayList<>();
    private final List<Image> mImages = new CopyOnWriteArrayList<>();
    //	private Map<Long,Image> mImages = new HashMap<Long,Image>();
    private final OnCaptureAvailableListener mRegisteredListener;

    private CountDownLatch capturedResultAndImageLatch;

    // - - - Constructor - - -
    public DesignResult(int designLength, OnCaptureAvailableListener listener, int imageFormat) {
        mDesignLength = designLength;
        mRegisteredListener = listener;
        if (imageFormat == ImageFormat.RAW_SENSOR) {
            //  CaptureResult number + image Dng and extra JPEG number
            capturedResultAndImageLatch = new CountDownLatch(mDesignLength + 2 * mDesignLength);
        } else {
            //  CaptureResult number + image number
            capturedResultAndImageLatch = new CountDownLatch(mDesignLength + mDesignLength);
        }
    }

    public DesignResult(int designLength, OnCaptureAvailableListener listener) {
        this(designLength, listener, -1);
    }

    // - - Setters and Getter - -
    public int getDesignLength() {
        return mDesignLength;
    }

    public CaptureResult getCaptureResult(int i) {
        return mCaptureResults.get(i);
    }

    public List<CaptureResult> getCaptureResults() {
        return mCaptureResults;
    }

    public Long getCaptureTimestamp(int i) {
        return mCaptureTimestamps.get(i);
    }

    public void recordCaptureTimestamp(Long timestampID) {
        mCaptureTimestamps.add(timestampID);
    }

    public boolean containsCaptureTimestamp(Long timestampID) {
        return mCaptureTimestamps.contains(timestampID);
    }


    /* void recordCaptureResult(CaptureResult)
     *
     * Whenever a new CaptureResult is available from the onCaptureComplete() call, record it in the
     * DesignResult. Compare with the Images which have been generated and stored, to see if any
     * match. If so, call the function which initiates writing the frame to disk, so it can be
     * saved and the Image buffer freed ASAP. If not, record it later for when the right Image comes
     * in.
     *
     */
    public void recordCaptureResult(CaptureResult result) {
        mCaptureResults.add(result);
        capturedResultAndImageLatch.countDown();
    }


    /* void recordImage(Image)
     *
     * Whenever a new Image is available from the ImageReader, record it in the DesignResult.
     * Compare with the CaptureResults which have already been generated and stored, to see if any
     * match. If so, call the function which initiates writing the frame to disk, so it can be
     * saved and the Image buffer freed ASAP. If not, record it for later for when the right
     * CaptureResult comes in.
     */
    public void recordImage(Image image) {
        // If there was no CaptureResult associated with this image yet, save it until one is.
        mImages.add(image);
        capturedResultAndImageLatch.countDown();
    }



    /* void checkIfComplete()
     *
     * Function called whenever an Image/CaptureResult pair has been registered, associated, and
     * passed out for writing to disk. Since a filename is created for that frame only once this
     * happens, we use the length of the filename list to indicate how many frames of the total
     * expected number have been captured/saved.
     * Once all frames have been captured and saved, invoke the CaptureDesignCallback's onFinished()
     * method to inform the main Activity class.
     */

    protected void checkIfComplete() {
        Log.w("tombear", "checkIfComplete Start: " + SystemClock.elapsedRealtime() / 1000);
        try {
            capturedResultAndImageLatch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.w("tombear", "checkIfComplete End: " + SystemClock.elapsedRealtime() / 1000);
    }

    protected void saveImages() {
        Log.w("tombear", "saveImages Start: " + SystemClock.elapsedRealtime() / 1000);
        mCaptureResults.sort((r1, r2) -> (int) (r1.get(CaptureResult.SENSOR_TIMESTAMP) - r2.get(CaptureResult.SENSOR_TIMESTAMP)));
        mImages.sort((i1, i2) -> (int) (i1.getTimestamp() - i2.getTimestamp()));
        for (Image image : mImages) {
            for (CaptureResult result : mCaptureResults) {
                if (image.getTimestamp() == result.get(CaptureResult.SENSOR_TIMESTAMP)) {
                    mRegisteredListener.onCaptureAvailable(image, result);
                    mImages.remove(image);
                    break;
                }
            }
        }
        mRegisteredListener.onAllCapturesReported(this);
        Log.w("tombear", "saveImages Report: " + SystemClock.elapsedRealtime() / 1000);
    }

    static public abstract class OnCaptureAvailableListener {
        public void onCaptureAvailable(Image image, CaptureResult result) {
        }

        public void onAllCapturesReported(DesignResult designResult) {
        }
    }


} // end whole class