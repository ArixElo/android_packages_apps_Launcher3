/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep.inputconsumers;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.INVALID_POINTER_ID;
import static com.android.launcher3.Utilities.EDGE_NAV_BAR;
import static com.android.launcher3.Utilities.squaredHypot;
import static com.android.launcher3.uioverrides.RecentsUiFactory.ROTATION_LANDSCAPE;
import static com.android.launcher3.uioverrides.RecentsUiFactory.ROTATION_SEASCAPE;
import static com.android.launcher3.util.RaceConditionTracker.ENTER;
import static com.android.launcher3.util.RaceConditionTracker.EXIT;
import static com.android.quickstep.SysUINavigationMode.Mode.NO_BUTTON;
import static com.android.quickstep.TouchInteractionService.TOUCH_INTERACTION_LOG;
import static com.android.systemui.shared.system.ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS;

import android.annotation.TargetApi;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.annotation.UiThread;

import com.android.launcher3.R;
import com.android.launcher3.graphics.RotationMode;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.RaceConditionTracker;
import com.android.launcher3.util.TraceHelper;
import com.android.quickstep.ActivityControlHelper;
import com.android.quickstep.OverviewCallbacks;
import com.android.quickstep.RecentsModel;
import com.android.quickstep.SwipeSharedState;
import com.android.quickstep.SysUINavigationMode;
import com.android.quickstep.SysUINavigationMode.Mode;
import com.android.quickstep.WindowTransformSwipeHandler;
import com.android.quickstep.WindowTransformSwipeHandler.GestureEndTarget;
import com.android.quickstep.util.CachedEventDispatcher;
import com.android.quickstep.util.MotionPauseDetector;
import com.android.quickstep.util.RecentsAnimationListenerSet;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.BackgroundExecutor;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.shared.system.QuickStepContract;

import java.util.function.Consumer;

/**
 * Input consumer for handling events originating from an activity other than Launcher
 */
@TargetApi(Build.VERSION_CODES.P)
public class OtherActivityInputConsumer extends ContextWrapper implements InputConsumer {

    public static final String DOWN_EVT = "OtherActivityInputConsumer.DOWN";
    private static final String UP_EVT = "OtherActivityInputConsumer.UP";

    private final CachedEventDispatcher mRecentsViewDispatcher = new CachedEventDispatcher();
    private final RunningTaskInfo mRunningTask;
    private final RecentsModel mRecentsModel;
    private final Intent mHomeIntent;
    private final ActivityControlHelper mActivityControlHelper;
    private final OverviewCallbacks mOverviewCallbacks;
    private final InputConsumerController mInputConsumer;
    private final SwipeSharedState mSwipeSharedState;
    private final InputMonitorCompat mInputMonitorCompat;
    private final SysUINavigationMode.Mode mMode;
    private final RectF mSwipeTouchRegion;

    private final int mDisplayRotation;

    private final Consumer<OtherActivityInputConsumer> mOnCompleteCallback;
    private final MotionPauseDetector mMotionPauseDetector;
    private final float mMotionPauseMinDisplacement;
    private VelocityTracker mVelocityTracker;

    private WindowTransformSwipeHandler mInteractionHandler;

    private final boolean mIsDeferredDownTarget;
    private final PointF mDownPos = new PointF();
    private final PointF mLastPos = new PointF();
    private int mActivePointerId = INVALID_POINTER_ID;

    private final float mDragSlop;
    private final float mSquaredTouchSlop;

    // Slop used to check when we start moving window.
    private boolean mPassedDragSlop;
    // Slop used to determine when we say that the gesture has started.
    private boolean mPassedTouchSlop;

    // Might be displacement in X or Y, depending on the direction we are swiping from the nav bar.
    private float mStartDisplacement;

    private Handler mMainThreadHandler;
    private Runnable mCancelRecentsAnimationRunnable = () -> {
        ActivityManagerWrapper.getInstance().cancelRecentsAnimation(
                true /* restoreHomeStackPosition */);
    };

    public OtherActivityInputConsumer(Context base, RunningTaskInfo runningTaskInfo,
            RecentsModel recentsModel, Intent homeIntent, ActivityControlHelper activityControl,
            boolean isDeferredDownTarget, OverviewCallbacks overviewCallbacks,
            InputConsumerController inputConsumer,
            Consumer<OtherActivityInputConsumer> onCompleteCallback,
            SwipeSharedState swipeSharedState, InputMonitorCompat inputMonitorCompat,
            RectF swipeTouchRegion) {
        super(base);

        mMainThreadHandler = new Handler(Looper.getMainLooper());
        mRunningTask = runningTaskInfo;
        mRecentsModel = recentsModel;
        mHomeIntent = homeIntent;
        mMode = SysUINavigationMode.getMode(base);
        mSwipeTouchRegion = swipeTouchRegion;

        mMotionPauseDetector = new MotionPauseDetector(base);
        mMotionPauseMinDisplacement = base.getResources().getDimension(
                R.dimen.motion_pause_detector_min_displacement_from_app);
        mOnCompleteCallback = onCompleteCallback;
        mVelocityTracker = VelocityTracker.obtain();
        mInputMonitorCompat = inputMonitorCompat;

        mActivityControlHelper = activityControl;
        boolean continuingPreviousGesture = swipeSharedState.getActiveListener() != null;
        mIsDeferredDownTarget = !continuingPreviousGesture && isDeferredDownTarget;
        mOverviewCallbacks = overviewCallbacks;
        mInputConsumer = inputConsumer;
        mSwipeSharedState = swipeSharedState;

        mDisplayRotation = getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
        mDragSlop = QuickStepContract.getQuickStepDragSlopPx();
        float slop = QuickStepContract.getQuickStepTouchSlopPx();
        mSquaredTouchSlop = slop * slop;

        mPassedTouchSlop = mPassedDragSlop = continuingPreviousGesture;
    }

    @Override
    public int getType() {
        return TYPE_OTHER_ACTIVITY;
    }

    @Override
    public void onMotionEvent(MotionEvent ev) {
        if (mVelocityTracker == null) {
            return;
        }

        // Proxy events to recents view
        if (mPassedDragSlop && mInteractionHandler != null
                && !mRecentsViewDispatcher.hasConsumer()) {
            mRecentsViewDispatcher.setConsumer(mInteractionHandler.getRecentsViewDispatcher(
                    isNavBarOnLeft()
                            ? ROTATION_SEASCAPE
                            : (isNavBarOnRight() ? ROTATION_LANDSCAPE : RotationMode.NORMAL)));
        }
        int edgeFlags = ev.getEdgeFlags();
        ev.setEdgeFlags(edgeFlags | EDGE_NAV_BAR);
        mRecentsViewDispatcher.dispatchEvent(ev);
        ev.setEdgeFlags(edgeFlags);

        mVelocityTracker.addMovement(ev);
        if (ev.getActionMasked() == ACTION_POINTER_UP) {
            mVelocityTracker.clear();
            mMotionPauseDetector.clear();
        }

        switch (ev.getActionMasked()) {
            case ACTION_DOWN: {
                RaceConditionTracker.onEvent(DOWN_EVT, ENTER);
                TraceHelper.beginSection("TouchInt");
                mActivePointerId = ev.getPointerId(0);
                mDownPos.set(ev.getX(), ev.getY());
                mLastPos.set(mDownPos);

                // Start the window animation on down to give more time for launcher to draw if the
                // user didn't start the gesture over the back button
                if (!mIsDeferredDownTarget) {
                    startTouchTrackingForWindowAnimation(ev.getEventTime());
                }

                RaceConditionTracker.onEvent(DOWN_EVT, EXIT);
                break;
            }
            case ACTION_POINTER_DOWN: {
                if (!mPassedTouchSlop) {
                    // Cancel interaction in case of multi-touch interaction
                    int ptrIdx = ev.getActionIndex();
                    if (!mSwipeTouchRegion.contains(ev.getX(ptrIdx), ev.getY(ptrIdx))) {
                        int action = ev.getAction();
                        ev.setAction(ACTION_CANCEL);
                        finishTouchTracking(ev);
                        ev.setAction(action);
                    }
                }
                break;
            }
            case ACTION_POINTER_UP: {
                int ptrIdx = ev.getActionIndex();
                int ptrId = ev.getPointerId(ptrIdx);
                if (ptrId == mActivePointerId) {
                    final int newPointerIdx = ptrIdx == 0 ? 1 : 0;
                    mDownPos.set(
                            ev.getX(newPointerIdx) - (mLastPos.x - mDownPos.x),
                            ev.getY(newPointerIdx) - (mLastPos.y - mDownPos.y));
                    mLastPos.set(ev.getX(newPointerIdx), ev.getY(newPointerIdx));
                    mActivePointerId = ev.getPointerId(newPointerIdx);
                }
                break;
            }
            case ACTION_MOVE: {
                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex == INVALID_POINTER_ID) {
                    break;
                }
                mLastPos.set(ev.getX(pointerIndex), ev.getY(pointerIndex));
                float displacement = getDisplacement(ev);
                float displacementX = mLastPos.x - mDownPos.x;

                if (!mPassedDragSlop) {
                    if (!mIsDeferredDownTarget) {
                        // Normal gesture, ensure we pass the drag slop before we start tracking
                        // the gesture
                        if (Math.abs(displacement) > mDragSlop) {
                            mPassedDragSlop = true;
                            mStartDisplacement = displacement;
                        }
                    }
                }

                if (!mPassedTouchSlop) {
                    if (squaredHypot(displacementX, mLastPos.y - mDownPos.y) >= mSquaredTouchSlop) {
                        mPassedTouchSlop = true;

                        if (mIsDeferredDownTarget) {
                            // Deferred gesture, start the animation and gesture tracking once
                            // we pass the actual touch slop
                            startTouchTrackingForWindowAnimation(ev.getEventTime());
                        }
                        if (!mPassedDragSlop) {
                            mPassedDragSlop = true;
                            mStartDisplacement = displacement;
                        }
                        notifyGestureStarted();
                    }
                }

                if (mInteractionHandler != null) {
                    if (mPassedDragSlop) {
                        // Move
                        mInteractionHandler.updateDisplacement(displacement - mStartDisplacement);
                    }

                    if (mMode == Mode.NO_BUTTON) {
                        float horizontalDist = Math.abs(displacementX);
                        float upDist = -displacement;
                        boolean isLikelyToStartNewTask = horizontalDist > upDist;
                        mMotionPauseDetector.setDisallowPause(upDist < mMotionPauseMinDisplacement
                                || isLikelyToStartNewTask);
                        mMotionPauseDetector.addPosition(displacement, ev.getEventTime());
                        mInteractionHandler.setIsLikelyToStartNewTask(isLikelyToStartNewTask);
                    }
                }
                break;
            }
            case ACTION_CANCEL:
            case ACTION_UP: {
                finishTouchTracking(ev);
                break;
            }
        }
    }

    private void notifyGestureStarted() {
        TOUCH_INTERACTION_LOG.addLog("startQuickstep");
        if (mInteractionHandler == null) {
            return;
        }
        mInputMonitorCompat.pilferPointers();

        mOverviewCallbacks.closeAllWindows();
        ActivityManagerWrapper.getInstance().closeSystemWindows(
                CLOSE_SYSTEM_WINDOWS_REASON_RECENTS);

        // Notify the handler that the gesture has actually started
        mInteractionHandler.onGestureStarted();
    }

    private boolean isNavBarOnRight() {
        return mMode != NO_BUTTON && mDisplayRotation == Surface.ROTATION_90;
    }

    private boolean isNavBarOnLeft() {
        return mMode != NO_BUTTON && mDisplayRotation == Surface.ROTATION_270;
    }

    private void startTouchTrackingForWindowAnimation(long touchTimeMs) {
        TOUCH_INTERACTION_LOG.addLog("startRecentsAnimation");

        RecentsAnimationListenerSet listenerSet = mSwipeSharedState.getActiveListener();
        final WindowTransformSwipeHandler handler = new WindowTransformSwipeHandler(
                mRunningTask, this, touchTimeMs, mActivityControlHelper,
                listenerSet != null, mInputConsumer);

        // Preload the plan
        mRecentsModel.getTasks(null);
        mInteractionHandler = handler;
        handler.setGestureEndCallback(this::onInteractionGestureFinished);
        mMotionPauseDetector.setOnMotionPauseListener(handler::onMotionPauseChanged);
        handler.initWhenReady();

        if (listenerSet != null) {
            listenerSet.addListener(handler);
            mSwipeSharedState.applyActiveRecentsAnimationState(handler);
            notifyGestureStarted();
        } else {
            RecentsAnimationListenerSet newListenerSet =
                    mSwipeSharedState.newRecentsAnimationListenerSet();
            newListenerSet.addListener(handler);
            BackgroundExecutor.get().submit(
                    () -> ActivityManagerWrapper.getInstance().startRecentsActivity(
                            mHomeIntent, null, newListenerSet, null, null));
        }
    }

    /**
     * Called when the gesture has ended. Does not correlate to the completion of the interaction as
     * the animation can still be running.
     */
    private void finishTouchTracking(MotionEvent ev) {
        RaceConditionTracker.onEvent(UP_EVT, ENTER);
        TraceHelper.endSection("TouchInt");

        if (mPassedDragSlop && mInteractionHandler != null) {
            if (ev.getActionMasked() == ACTION_CANCEL) {
                mInteractionHandler.onGestureCancelled();
            } else {
                mVelocityTracker.computeCurrentVelocity(1000,
                        ViewConfiguration.get(this).getScaledMaximumFlingVelocity());
                float velocityX = mVelocityTracker.getXVelocity(mActivePointerId);
                float velocityY = mVelocityTracker.getYVelocity(mActivePointerId);
                float velocity = isNavBarOnRight() ? velocityX
                        : isNavBarOnLeft() ? -velocityX
                                : velocityY;

                mInteractionHandler.updateDisplacement(getDisplacement(ev) - mStartDisplacement);
                mInteractionHandler.onGestureEnded(velocity, new PointF(velocityX, velocityY),
                        mDownPos);
            }
        } else {
            // Since we start touch tracking on DOWN, we may reach this state without actually
            // starting the gesture. In that case, just cleanup immediately.
            onConsumerAboutToBeSwitched();
            onInteractionGestureFinished();

            // Cancel the recents animation if SysUI happens to handle UP before we have a chance
            // to start the recents animation. In addition, workaround for b/126336729 by delaying
            // the cancel of the animation for a period, in case SysUI is slow to handle UP and we
            // handle DOWN & UP and move the home stack before SysUI can start the activity
            mMainThreadHandler.removeCallbacks(mCancelRecentsAnimationRunnable);
            mMainThreadHandler.postDelayed(mCancelRecentsAnimationRunnable, 100);
        }
        mVelocityTracker.recycle();
        mVelocityTracker = null;
        mMotionPauseDetector.clear();
        RaceConditionTracker.onEvent(UP_EVT, EXIT);
    }

    @Override
    public void onConsumerAboutToBeSwitched() {
        Preconditions.assertUIThread();
        mMainThreadHandler.removeCallbacks(mCancelRecentsAnimationRunnable);
        if (mInteractionHandler != null) {
            // The consumer is being switched while we are active. Set up the shared state to be
            // used by the next animation
            removeListener();
            GestureEndTarget endTarget = mInteractionHandler.getGestureEndTarget();
            mSwipeSharedState.canGestureBeContinued = endTarget != null && endTarget.canBeContinued;
            mSwipeSharedState.goingToLauncher = endTarget != null && endTarget.isLauncher;
            if (mSwipeSharedState.canGestureBeContinued) {
                mInteractionHandler.cancelCurrentAnimation(mSwipeSharedState);
            } else {
                mInteractionHandler.reset();
            }
        }
    }

    @UiThread
    private void onInteractionGestureFinished() {
        Preconditions.assertUIThread();
        removeListener();
        mInteractionHandler = null;
        mOnCompleteCallback.accept(this);
    }

    private void removeListener() {
        RecentsAnimationListenerSet listenerSet = mSwipeSharedState.getActiveListener();
        if (listenerSet != null) {
            listenerSet.removeListener(mInteractionHandler);
        }
    }

    private float getDisplacement(MotionEvent ev) {
        if (isNavBarOnRight()) {
            return ev.getX() - mDownPos.x;
        } else if (isNavBarOnLeft()) {
            return mDownPos.x - ev.getX();
        } else {
            return ev.getY() - mDownPos.y;
        }
    }

    @Override
    public boolean useSharedSwipeState() {
        return mInteractionHandler != null;
    }

    @Override
    public boolean allowInterceptByParent() {
        return !mPassedTouchSlop;
    }
}