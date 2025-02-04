/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera.Area;
import android.hardware.Camera.Parameters;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import org.codeaurora.snapcam.R;

import com.android.camera.app.CameraApp;
import com.android.camera.util.CameraUtil;
import com.android.camera.util.UsageStatistics;

import java.util.ArrayList;
import java.util.List;

/* A class that handles everything about focus in still picture mode.
 * This also handles the metering area because it is the same as focus area.
 *
 * The test cases:
 * (1) The camera has continuous autofocus. Move the camera. Take a picture when
 *     CAF is not in progress.
 * (2) The camera has continuous autofocus. Move the camera. Take a picture when
 *     CAF is in progress.
 * (3) The camera has face detection. Point the camera at some faces. Hold the
 *     shutter. Release to take a picture.
 * (4) The camera has face detection. Point the camera at some faces. Single tap
 *     the shutter to take a picture.
 * (5) The camera has autofocus. Single tap the shutter to take a picture.
 * (6) The camera has autofocus. Hold the shutter. Release to take a picture.
 * (7) The camera has no autofocus. Single tap the shutter and take a picture.
 * (8) The camera has autofocus and supports focus area. Touch the screen to
 *     trigger autofocus. Take a picture.
 * (9) The camera has autofocus and supports focus area. Touch the screen to
 *     trigger autofocus. Wait until it times out.
 * (10) The camera has no autofocus and supports metering area. Touch the screen
 *     to change metering area.
 */
public class FocusOverlayManager {
    private static final String TAG = "CAM_FocusManager";

    private static final int RESET_TOUCH_FOCUS = 0;
    private static final int RESET_FACE_DETECTION = 1;
    private static final int RESET_TOUCH_FOCUS_DELAY = 3000;
    private static final int RESET_FACE_DETECTION_DELAY = 3000;
    private int mState = STATE_IDLE;
    public static final int STATE_IDLE = 0; // Focus is not active.
    public static final int STATE_FOCUSING = 1; // Focus is in progress.
    // Focus is in progress and the camera should take a picture after focus finishes.
    public static final int STATE_FOCUSING_SNAP_ON_FINISH = 2;
    public static final int STATE_SUCCESS = 3; // Focus finishes and succeeds.
    public static final int STATE_FAIL = 4; // Focus finishes and fails.

    private boolean mInitialized;
    private boolean mFocusAreaSupported;
    private boolean mMeteringAreaSupported;
    private boolean mLockAeAwbNeeded;
    private boolean mAeAwbLock;
    private Matrix mMatrix;

    private boolean mMirror; // true if the camera is front-facing.
    private int mDisplayOrientation;
    private List<Object> mFocusArea; // focus area in driver format
    private List<Object> mMeteringArea; // metering area in driver format
    private String mFocusMode;
    private String[] mDefaultFocusModes;
    private String mOverrideFocusMode;
    private Parameters mParameters;
    private ComboPreferences mPreferences;
    private Handler mHandler;
    Listener mListener;
    private boolean mPreviousMoving;
    private boolean mZslEnabled = false;  //QCom Parameter to disable focus for ZSL
    private boolean mTouchAFRunning = false;
    private boolean mIsAFRunning = false;

    private FocusUI mUI;
    private final Rect mPreviewRect = new Rect(0, 0, 0, 0);

    public  interface FocusUI {
        public boolean hasFaces();
        public void clearFocus();
        public void setFocusPosition(int x, int y);
        public void onFocusStarted();
        public void onFocusSucceeded(boolean timeOut);
        public void onFocusFailed(boolean timeOut);
        public void pauseFaceDetection();
        public void resumeFaceDetection();
    }

    private Point mDispSize;
    private int mBottomMargin;
    private int mTopMargin;

    public interface Listener {
        public void autoFocus();
        public void cancelAutoFocus();
        public boolean capture();
        public void startFaceDetection();
        public void stopFaceDetection();
        public void setFocusParameters();
    }

    private class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case RESET_TOUCH_FOCUS: {
                    cancelAutoFocus();
                    mListener.startFaceDetection();
                    break;
                }
                case RESET_FACE_DETECTION: {
                    mListener.startFaceDetection();
                    break;
                }
            }
        }
    }

    public FocusOverlayManager(ComboPreferences preferences, String[] defaultFocusModes,
            Parameters parameters, Listener listener,
            boolean mirror, Looper looper, FocusUI ui, CameraActivity activity) {
        mHandler = new MainHandler(looper);
        mMatrix = new Matrix();
        mPreferences = preferences;
        mDefaultFocusModes = defaultFocusModes;
        setParameters(parameters);
        mListener = listener;
        setMirror(mirror);
        mUI = ui;
        mDispSize = new Point();
        activity.getWindowManager().getDefaultDisplay().getRealSize(mDispSize);
        Context context = CameraApp.getContext();
        mBottomMargin =
            context.getResources().getDimensionPixelSize(R.dimen.preview_bottom_margin);
        mTopMargin =
            context.getResources().getDimensionPixelSize(R.dimen.preview_top_margin);
    }

    public void setPhotoUI(FocusUI ui) {
        mUI = ui;
    }

    public void setParameters(Parameters parameters) {
        // parameters can only be null when onConfigurationChanged is called
        // before camera is open. We will just return in this case, because
        // parameters will be set again later with the right parameters after
        // camera is open.
        if (parameters == null) return;
        mParameters = parameters;
        mFocusAreaSupported = CameraUtil.isFocusAreaSupported(parameters);
        mMeteringAreaSupported = CameraUtil.isMeteringAreaSupported(parameters);
        mLockAeAwbNeeded = (CameraUtil.isAutoExposureLockSupported(mParameters) ||
                CameraUtil.isAutoWhiteBalanceLockSupported(mParameters));
    }

    public void setPreviewSize(int previewWidth, int previewHeight) {
        if (mPreviewRect.width() != previewWidth || mPreviewRect.height() != previewHeight) {
            setPreviewRect(new Rect(0, 0, previewWidth, previewHeight));
        }
    }

    /** This setter should be the only way to mutate mPreviewRect. */
    public void setPreviewRect(Rect previewRect) {
        if (!mPreviewRect.equals(previewRect)) {
            mPreviewRect.set(previewRect);
            setMatrix();
        }
    }

    /** Returns a copy of mPreviewRect so that outside class cannot modify preview
     *  rect except deliberately doing so through the setter. */
    public Rect getPreviewRect() {
        return new Rect(mPreviewRect);
    }

    public void setMirror(boolean mirror) {
        mMirror = mirror;
        setMatrix();
    }

    public void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        setMatrix();
    }

    private void setMatrix() {
        if (mPreviewRect.width() != 0 && mPreviewRect.height() != 0) {
            Matrix matrix = new Matrix();
            CameraUtil.prepareMatrix(matrix, mMirror, mDisplayOrientation, getPreviewRect());
            // In face detection, the matrix converts the driver coordinates to UI
            // coordinates. In tap focus, the inverted matrix converts the UI
            // coordinates to driver coordinates.
            matrix.invert(mMatrix);
            mInitialized = true;
        }
    }

    private void lockAeAwbIfNeeded() {
        if (mLockAeAwbNeeded && !mAeAwbLock && !mZslEnabled) {
            mAeAwbLock = true;
            mListener.setFocusParameters();
        }
    }

    private void unlockAeAwbIfNeeded() {
        if (mLockAeAwbNeeded && mAeAwbLock && (mState != STATE_FOCUSING_SNAP_ON_FINISH)) {
            mAeAwbLock = false;
            mListener.setFocusParameters();
        }
    }

    public void onShutterDown() {
        if (!mInitialized) return;

        boolean autoFocusCalled = false;
        if (needAutoFocusCall()) {
            // Do not focus if touch focus has been triggered.
            if (mState != STATE_SUCCESS && mState != STATE_FAIL) {
                autoFocus();
                autoFocusCalled = true;
            }
        }

        if (!autoFocusCalled) lockAeAwbIfNeeded();
    }

    public void onShutterUp() {
        if (!mInitialized) return;

        if (needAutoFocusCall()) {
            // User releases half-pressed focus key.
            if (mState == STATE_FOCUSING || mState == STATE_SUCCESS
                    || mState == STATE_FAIL) {
                cancelAutoFocus();
            }
        }

        // Unlock AE and AWB after cancelAutoFocus. Camera API does not
        // guarantee setParameters can be called during autofocus.
        unlockAeAwbIfNeeded();
    }

    public void doSnap() {
        if (!mInitialized) return;

        // If the user has half-pressed the shutter and focus is completed, we
        // can take the photo right away. If the focus mode is infinity, we can
        // also take the photo.
        if (!needAutoFocusCall() || (mState == STATE_SUCCESS || mState == STATE_FAIL)) {
            capture();
        } else if (mState == STATE_FOCUSING) {
            // Half pressing the shutter (i.e. the focus button event) will
            // already have requested AF for us, so just request capture on
            // focus here.
            mState = STATE_FOCUSING_SNAP_ON_FINISH;
        } else if (mState == STATE_IDLE) {
            // We didn't do focus. This can happen if the user press focus key
            // while the snapshot is still in progress. The user probably wants
            // the next snapshot as soon as possible, so we just do a snapshot
            // without focusing again.
            capture();
        }
    }

    public void onAutoFocus(boolean focused, boolean shutterButtonPressed) {
        if (mState == STATE_FOCUSING_SNAP_ON_FINISH) {
            // Take the picture no matter focus succeeds or fails. No need
            // to play the AF sound if we're about to play the shutter
            // sound.
            if (focused) {
                mState = STATE_SUCCESS;
                // Lock exposure and white balance
                setAeAwbLock(true);
                mListener.setFocusParameters();
            } else {
                mState = STATE_FAIL;
            }
            updateFocusUI();
            capture();
        } else if (mState == STATE_FOCUSING) {
            // This happens when (1) user is half-pressing the focus key or
            // (2) touch focus is triggered. Play the focus tone. Do not
            // take the picture now.
            if (focused) {
                mState = STATE_SUCCESS;
                // Lock exposure and white balance
                setAeAwbLock(true);
                mListener.setFocusParameters();
            } else {
                mState = STATE_FAIL;
            }
            updateFocusUI();
            // If this is triggered by touch focus, cancel focus after a
            // while.
            if (mFocusArea != null) {
                mHandler.sendEmptyMessageDelayed(RESET_TOUCH_FOCUS, RESET_TOUCH_FOCUS_DELAY);
            }
            if (shutterButtonPressed) {
                // Lock AE & AWB so users can half-press shutter and recompose.
                lockAeAwbIfNeeded();
            }
        } else if (mState == STATE_IDLE) {
            // User has released the focus key before focus completes.
            // Do nothing.
        }
    }

    public void onAutoFocusMoving(boolean moving) {
        if (!mInitialized) return;


        // Ignore if the camera has detected some faces.
        if (mUI.hasFaces()) {
            mUI.clearFocus();
            if (mIsAFRunning) {
                mUI.onFocusSucceeded(true);
                mIsAFRunning = false;
            }
            return;
        }

        // Ignore if we have requested autofocus. This method only handles
        // continuous autofocus.
        if (mState != STATE_IDLE) return;

        // animate on false->true trasition only b/8219520
        if (moving && !mPreviousMoving) {
            mUI.onFocusStarted();
            mIsAFRunning = true;
        } else if (!moving) {
            mUI.onFocusSucceeded(true);
            mIsAFRunning = false;
        }

        mHandler.sendEmptyMessageDelayed(RESET_FACE_DETECTION, RESET_FACE_DETECTION_DELAY);
        mPreviousMoving = moving;
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void initializeFocusAreas(int x, int y) {
        if (mFocusArea == null) {
            mFocusArea = new ArrayList<Object>();
            mFocusArea.add(new Area(new Rect(), 1));
        }

        // Convert the coordinates to driver format.
        calculateTapArea(x, y, 1f, ((Area) mFocusArea.get(0)).rect);
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void initializeMeteringAreas(int x, int y) {
        if (mMeteringArea == null) {
            mMeteringArea = new ArrayList<Object>();
            mMeteringArea.add(new Area(new Rect(), 1));
        }

        // Convert the coordinates to driver format.
        // AE area is bigger because exposure is sensitive and
        // easy to over- or underexposure if area is too small.
        calculateTapArea(x, y, 1.5f, ((Area) mMeteringArea.get(0)).rect);
    }

    private void resetMeteringAreas() {
        mMeteringArea = null;
    }

    public void onSingleTapUp(int x, int y) {
        if (!mInitialized || mState == STATE_FOCUSING_SNAP_ON_FINISH) return;

        UsageStatistics.onEvent(UsageStatistics.COMPONENT_CAMERA,
                UsageStatistics.ACTION_TOUCH_FOCUS, x + "," + y);

        // Let users be able to cancel previous touch focus.
        if ((mState == STATE_FOCUSING ||
                    mState == STATE_SUCCESS || mState == STATE_FAIL)) {
            cancelAutoFocus();
        }
        if (mPreviewRect.width() == 0 || mPreviewRect.height() == 0 ||
            (y > (mDispSize.y - mBottomMargin) || y < mTopMargin)) {
            return;
        }
        // Initialize variables.
        // Initialize mFocusArea.
        if (mFocusAreaSupported) {
            initializeFocusAreas(x, y);
        }
        // Initialize mMeteringArea.
        if (mMeteringAreaSupported) {
            initializeMeteringAreas(x, y);
        }

        // Use margin to set the focus indicator to the touched area.
        mUI.setFocusPosition(x, y);

        if (mZslEnabled) {
            mTouchAFRunning = true;
        }

        // Stop face detection because we want to specify focus and metering area.
        mListener.stopFaceDetection();

        // Set the focus area and metering area.
        mListener.setFocusParameters();
        if (mFocusAreaSupported) {
            autoFocus();
        } else {  // Just show the indicator in all other cases.
            updateFocusUI();
            // Reset the metering area in 3 seconds.
            mHandler.removeMessages(RESET_TOUCH_FOCUS);
            mHandler.sendEmptyMessageDelayed(RESET_TOUCH_FOCUS, RESET_TOUCH_FOCUS_DELAY);
        }
    }

    public void onPreviewStarted() {
        mState = STATE_IDLE;
    }

    public void onPreviewStopped() {
        // If auto focus was in progress, it would have been stopped.
        mState = STATE_IDLE;
        resetTouchFocus();
        updateFocusUI();
    }

    public void onCameraReleased() {
        mTouchAFRunning = false;
        onPreviewStopped();
    }

    private void autoFocus() {
        Log.v(TAG, "Start autofocus.");
        mListener.autoFocus();
        mState = STATE_FOCUSING;
        updateFocusUI();
        mHandler.removeMessages(RESET_TOUCH_FOCUS);
    }

    public void cancelAutoFocus() {
        Log.v(TAG, "Cancel autofocus.");

        // Reset the tap area before calling mListener.cancelAutofocus.
        // Otherwise, focus mode stays at auto and the tap area passed to the
        // driver is not reset.
        resetTouchFocus();
        setAeAwbLock(false);
        mListener.cancelAutoFocus();
        mUI.resumeFaceDetection();
        mState = STATE_IDLE;
        updateFocusUI();
        mHandler.removeMessages(RESET_TOUCH_FOCUS);
    }

    private void capture() {
        if (mListener.capture()) {
            mState = STATE_IDLE;
            mHandler.removeMessages(RESET_TOUCH_FOCUS);
        }
    }

    public String getFocusMode() {
        if (mOverrideFocusMode != null) return mOverrideFocusMode;
        if (mParameters == null) return Parameters.FOCUS_MODE_AUTO;
        List<String> supportedFocusModes = mParameters.getSupportedFocusModes();

        if (mFocusAreaSupported && mFocusArea != null) {
            // Always use autofocus in tap-to-focus.
            mFocusMode = Parameters.FOCUS_MODE_AUTO;
        } else {
            // The default is continuous autofocus.
            mFocusMode = mPreferences.getString(
                    CameraSettings.KEY_FOCUS_MODE, null);

            // Try to find a supported focus mode from the default list.
            if (mFocusMode == null) {
                for (int i = 0; i < mDefaultFocusModes.length; i++) {
                    String mode = mDefaultFocusModes[i];
                    if (CameraUtil.isSupported(mode, supportedFocusModes)) {
                        mFocusMode = mode;
                        break;
                    }
                }
            }
        }
        if (!CameraUtil.isSupported(mFocusMode, supportedFocusModes)) {
            // For some reasons, the driver does not support the current
            // focus mode. Fall back to auto.
            if (CameraUtil.isSupported(Parameters.FOCUS_MODE_AUTO,
                    mParameters.getSupportedFocusModes())) {
                mFocusMode = Parameters.FOCUS_MODE_AUTO;
            } else {
                mFocusMode = mParameters.getFocusMode();
            }
        }
        return mFocusMode;
    }

    public List getFocusAreas() {
        return mFocusArea;
    }

    public List getMeteringAreas() {
        return mMeteringArea;
    }

    public void updateFocusUI() {
        if (!mInitialized) return;
        // Show only focus indicator or face indicator.

        if (mState == STATE_IDLE) {
            if (mFocusArea == null) {
                mUI.clearFocus();
            } else {
                // Users touch on the preview and the indicator represents the
                // metering area. Either focus area is not supported or
                // autoFocus call is not required.
                mUI.onFocusStarted();
            }
        } else if (mState == STATE_FOCUSING || mState == STATE_FOCUSING_SNAP_ON_FINISH) {
            mUI.onFocusStarted();
        } else {
            if (CameraUtil.FOCUS_MODE_CONTINUOUS_PICTURE.equals(mFocusMode)) {
                // TODO: check HAL behavior and decide if this can be removed.
                mUI.onFocusSucceeded(false);
            } else if (mState == STATE_SUCCESS) {
                mUI.onFocusSucceeded(false);
            } else if (mState == STATE_FAIL) {
                mUI.onFocusFailed(false);
            }
        }
    }

    public void resetTouchFocus() {
        if (!mInitialized) return;

        // Put focus indicator to the center. clear reset position
        if (mUI != null) {
            mUI.clearFocus();
        }
        // Initialize mFocusArea.
        mFocusArea = null;
        // Initialize mMeteringArea.
        mMeteringArea = null;

        // Reset metering area when no specific region is selected.
        if (mMeteringAreaSupported) {
            resetMeteringAreas();
        }

        if (mTouchAFRunning && mZslEnabled) {
            mTouchAFRunning = false;
        }
    }

    private void calculateTapArea(int x, int y, float areaMultiple, Rect rect) {
        int areaSize = (int) (getAreaSize() * areaMultiple);
        int left = CameraUtil.clamp(x - areaSize / 2, mPreviewRect.left,
                mPreviewRect.right - areaSize);
        int top = CameraUtil.clamp(y - areaSize / 2, mPreviewRect.top,
                mPreviewRect.bottom - areaSize);

        RectF rectF = new RectF(left, top, left + areaSize, top + areaSize);
        mMatrix.mapRect(rectF);
        CameraUtil.rectFToRect(rectF, rect);
    }

    private int getAreaSize() {
        // Recommended focus area size from the manufacture is 1/8 of the image
        // width (i.e. longer edge of the image)
        return Math.max(mPreviewRect.width(), mPreviewRect.height()) / 8;
    }

    /* package */ int getFocusState() {
        return mState;
    }

    public boolean isFocusCompleted() {
        return mState == STATE_SUCCESS || mState == STATE_FAIL;
    }

    public int getCurrentFocusState() {
        return mState;
    }

    public boolean isFocusingSnapOnFinish() {
        return mState == STATE_FOCUSING_SNAP_ON_FINISH;
    }

    public void removeMessages() {
        mHandler.removeMessages(RESET_TOUCH_FOCUS);
    }

    public void overrideFocusMode(String focusMode) {
        mOverrideFocusMode = focusMode;
    }

    public void setAeAwbLock(boolean lock) {
        mAeAwbLock = lock;
    }

    public boolean getAeAwbLock() {
        return mAeAwbLock;
    }

    private boolean needAutoFocusCall() {
        String focusMode = getFocusMode();
        return !(focusMode.equals(Parameters.FOCUS_MODE_INFINITY)
                || focusMode.equals(Parameters.FOCUS_MODE_FIXED)
                || focusMode.equals(Parameters.FOCUS_MODE_EDOF));
    }

    public void setZslEnable(boolean value) {
        mZslEnabled = value;
    }

    public boolean isZslEnabled() {
        return mZslEnabled;
    }

    public boolean isTouch() {
        return mTouchAFRunning;
    }

}
