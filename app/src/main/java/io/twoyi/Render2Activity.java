/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Render2Activity extends Activity implements View.OnTouchListener {

    private static final String TAG = "Render2Activity";

    private TextureView mTextureView;
    private Surface mSurface;

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mBootLogView;

    private final AtomicBoolean mIsExtracting = new AtomicBoolean(false);


    // ---- Vsync-driven repaint pump ----
    // We drive one repaint request per display vsync via Choreographer. This aligns the "present"
    // requests to the system frame clock and reduces pacing wobble / bursty frame delivery.
    private boolean mVsyncPumpEnabled = false;

    private final Choreographer.FrameCallback mVsyncFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mVsyncPumpEnabled) return;
            try {
                Renderer.repaint();
            } catch (Throwable t) {
                Log.w(TAG, "Renderer.repaint failed", t);
                // Stop spamming if something goes wrong
                mVsyncPumpEnabled = false;
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startVsyncPump() {
        if (mVsyncPumpEnabled) return;
        // Only start once the surface exists; repaint() needs a live RenderWindow.
        if (mSurface == null) return;
        mVsyncPumpEnabled = true;
        Choreographer.getInstance().postFrameCallback(mVsyncFrameCallback);
        Log.i(TAG, "Vsync pump started");
    }

    private void stopVsyncPump() {
        if (!mVsyncPumpEnabled) return;
        mVsyncPumpEnabled = false;
        try {
            Choreographer.getInstance().removeFrameCallback(mVsyncFrameCallback);
        } catch (Throwable ignored) {}
        Log.i(TAG, "Vsync pump stopped");
    }

    // For refresh-switch detection + re-init
    private float mLastRefreshRate = -1f;
    private float mXdpi;
    private float mYdpi;
    private String mLoaderPath;

    private final Runnable mRefreshWatcher = new Runnable() {
        @Override
        public void run() {
            try {
                if (mSurface != null && mTextureView != null && mLoaderPath != null) {
                    float rr = getWindowManager().getDefaultDisplay().getRefreshRate();
                    if (mLastRefreshRate < 0) mLastRefreshRate = rr;

                    // If refresh rate switched (e.g. 120 -> 60/90), re-init with new FPS.
                    if (Math.abs(rr - mLastRefreshRate) > 1.0f) {
                        mLastRefreshRate = rr;
                        int fps = clampFps(Math.round(rr));

                        Log.i(TAG, "Refresh switched -> re-init renderer. rr=" + rr + " fps=" + fps);

                        try { Renderer.removeWindow(mSurface); } catch (Throwable ignored) {}
                        Renderer.init(mSurface, mLoaderPath, mXdpi, mYdpi, fps);
                        startVsyncPump();
                        Renderer.resetWindow(mSurface, 0, 0, mTextureView.getWidth(), mTextureView.getHeight());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "RefreshWatcher error", t);
            }

            if (mRootView != null) {
                mRootView.postDelayed(this, 1000);
            }
        }
    };

    private final TextureView.SurfaceTextureListener mTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            // Make TextureView behave closer to a real output surface
            try {
                surfaceTexture.setDefaultBufferSize(width, height);
            } catch (Throwable ignored) {}
            try {
                mTextureView.setOpaque(true);
            } catch (Throwable ignored) {}

            // Cleanup previous surface if any
            if (mSurface != null) {
                try { Renderer.removeWindow(mSurface); } catch (Throwable ignored) {}
                try { mSurface.release(); } catch (Throwable ignored) {}
                mSurface = null;
            }

            mSurface = new Surface(surfaceTexture);

            // Cache loader path + dpi for possible re-init
            mLoaderPath = RomManager.getLoaderPath(getApplicationContext());

            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            mXdpi = dm.xdpi;
            mYdpi = dm.ydpi;

            float rr = getWindowManager().getDefaultDisplay().getRefreshRate();
            mLastRefreshRate = rr;
            int fps = clampFps(Math.round(rr));

            Log.i(TAG, "Surface available. refreshRate=" + rr + " -> fps=" + fps);

            Renderer.init(mSurface, mLoaderPath, mXdpi, mYdpi, fps);

            Log.i(TAG, "surfaceCreated");
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            if (mSurface == null) return;
            Renderer.resetWindow(mSurface, 0, 0, mTextureView.getWidth(), mTextureView.getHeight());
            Log.i(TAG, "surfaceChanged: " + mTextureView.getWidth() + "x" + mTextureView.getHeight());
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            stopVsyncPump();
            if (mSurface != null) {
                try { Renderer.removeWindow(mSurface); } catch (Throwable ignored) {}
                try { mSurface.release(); } catch (Throwable ignored) {}
                mSurface = null;
            }

            Log.i(TAG, "surfaceDestroyed!");
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            // no-op
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean started = TwoyiStatusManager.getInstance().isStarted();
        Log.i(TAG, "onCreate: " + savedInstanceState + " isStarted: " + started);

        if (started) {
            finish();
            RomManager.reboot(this);
            return;
        }

        TwoyiStatusManager.getInstance().reset();
        NavUtils.hideNavigation(getWindow());

        super.onCreate(savedInstanceState);

        // Prevent screen-off policies from downshifting too aggressively
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Request a 120Hz display mode if available (no API 30 required)
        requestHighRefreshDisplayMode();

        setContentView(R.layout.ac_render);
        mRootView = findViewById(R.id.root);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(mTextureListener);

        mLoadingLayout = findViewById(R.id.loadingLayout);
        mLoadingView = findViewById(R.id.loading);
        mLoadingText = findViewById(R.id.loadingText);
        mBootLogView = findViewById(R.id.bootlog);

        mLoadingLayout.setVisibility(View.VISIBLE);
        mLoadingView.startAnimation();

        UITips.checkForAndroid12(this, this::bootSystem);

        mTextureView.setOnTouchListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestHighRefreshDisplayMode();
        if (mRootView != null) {
            mRootView.removeCallbacks(mRefreshWatcher);
            mRootView.postDelayed(mRefreshWatcher, 1000);
            startVsyncPump();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopVsyncPump();
        if (mRootView != null) {
            mRootView.removeCallbacks(mRefreshWatcher);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState: " + savedInstanceState);

        finish();
        RomManager.reboot(this);
    }

    private void bootSystem() {
        boolean romExist = RomManager.romExist(this);
        boolean factoryRomUpdated = RomManager.needsUpgrade(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);

        boolean shouldExtractRom = !romExist || forceInstall || (!use3rdRom && factoryRomUpdated);

        if (shouldExtractRom) {
            Log.i(TAG, "extracting rom...");

            showTipsForFirstBoot();

            new Thread(() -> {
                mIsExtracting.set(true);
                RomManager.extractRootfs(getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                mIsExtracting.set(false);

                RomManager.initRootfs(getApplicationContext());

                runOnUiThread(() -> {
                    mRootView.addView(mTextureView, 0);
                    showBootingProcedure();
                });
            }, "extract-rom").start();
        } else {
            mRootView.addView(mTextureView, 0);
            showBootingProcedure();
        }
    }

    private void showTipsForFirstBoot() {
        mLoadingText.setText(R.string.extracting_tips);
        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips);
            }
        }, 5000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips2);
            }
        }, 10 * 1000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips3);
            }
        }, 15 * 1000);
    }

    private void showBootingProcedure() {
        mLoadingText.setVisibility(View.GONE);
        mBootLogView.setVisibility(View.VISIBLE);
        new Thread(() -> {
            boolean success = false;
            try {
                success = TwoyiStatusManager.getInstance().waitBoot(15, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }

            if (!success) {
                LogEvents.trackBootFailure(getApplicationContext());
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), R.string.boot_failed, Toast.LENGTH_SHORT).show());

                SystemClock.sleep(3000);

                finish();
                System.exit(0);
                return;
            }

            runOnUiThread(() -> {
                mLoadingView.stopAnimation();
                mLoadingLayout.setVisibility(View.GONE);
            });
        }, "waiting-boot").start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            NavUtils.hideNavigation(getWindow());
            requestHighRefreshDisplayMode();
            startVsyncPump();
        }
        else {
            stopVsyncPump();
        }

        TwoyiStatusManager.getInstance().updateVisibility(hasFocus);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Renderer.handleTouch(event);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // TODO: 2021/10/26 Add Volume control
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        Renderer.sendKeycode(KeyEvent.KEYCODE_HOME);
    }

    private static int clampFps(int fps) {
        if (fps < 30) return 60;
        if (fps > 240) return 240;
        return fps;
    }

    /**
     * Requests a high refresh Display.Mode (prefers ~120Hz if available).
     * This does NOT rely on API 30 methods, so it compiles on older compileSdk.
     */
    private void requestHighRefreshDisplayMode() {
        try {
            Display display = getWindowManager().getDefaultDisplay();
            Display.Mode[] modes = display.getSupportedModes();

            Display.Mode best = null;

            // Prefer 120-ish first
            for (Display.Mode m : modes) {
                if (m.getRefreshRate() >= 119.5f) {
                    if (best == null) best = m;
                    else {
                        int a0 = best.getPhysicalWidth() * best.getPhysicalHeight();
                        int a1 = m.getPhysicalWidth() * m.getPhysicalHeight();
                        if (a1 > a0) best = m;
                    }
                }
            }

            // If no 120Hz, pick the highest refresh available
            if (best == null) {
                for (Display.Mode m : modes) {
                    if (best == null || m.getRefreshRate() > best.getRefreshRate()) {
                        best = m;
                    }
                }
            }

            if (best != null) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.preferredDisplayModeId = best.getModeId();
                getWindow().setAttributes(lp);
                Log.i(TAG, "Requested display mode: " +
                        best.getPhysicalWidth() + "x" + best.getPhysicalHeight() +
                        " @" + best.getRefreshRate() + " (id=" + best.getModeId() + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestHighRefreshDisplayMode failed", t);
        }
    }
}
