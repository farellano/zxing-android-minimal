/*
 * Copyright (C) 2008 ZXing authors
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

package com.google.zxing.client.android;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.views.ScanResultLayout;
import com.ticktbox.ticktBoxAPI.api.models.EventTheater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {

    enum CAMERA_STATE {
        STOPPED,
        RUNNING
    }

    public static final String EVENT = "event";
    private EventTheater event;

    private static final String TAG = CaptureActivity.class.getSimpleName();


    private CameraManager cameraManager;
    private CaptureActivityHandler handler;
    private Result savedResultToShow;
    private ViewfinderView viewfinderView;
    private boolean hasSurface;
    private Collection<BarcodeFormat> decodeFormats;
    private Map<DecodeHintType,?> decodeHints;
    private String characterSet;
    private InactivityTimer inactivityTimer;
    private BeepManager beepManager;
    private AmbientLightManager ambientLightManager;
    private Button buttonVerify;

    private CAMERA_STATE camera_state = CAMERA_STATE.STOPPED;

    ViewfinderView getViewfinderView() {
      return viewfinderView;
    }

    public Handler getHandler() {
      return handler;
    }

    CameraManager getCameraManager() {
      return cameraManager;
    }

    ScanResultLayout scanResultLayout;

    @Override
    public void onCreate(Bundle icicle) {
      super.onCreate(icicle);

      Window window = getWindow();
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      setContentView(R.layout.zxing_capture);
      buttonVerify = (Button) findViewById(R.id.button_verify);
      buttonVerify.setOnClickListener(new View.OnClickListener() {
          @Override
          public void onClick(View view) {
              if (camera_state == CAMERA_STATE.STOPPED){
                  cameraManager.startPreview();
                  camera_state = CAMERA_STATE.RUNNING;
                  buttonVerify.setText("STOP");
                  buttonVerify.setBackgroundResource(R.drawable.btn_yellow);
              }else{
                  cameraManager.stopPreview();
                  camera_state = CAMERA_STATE.STOPPED;
                  buttonVerify.setText("RESUME");
                  buttonVerify.setBackgroundResource(R.drawable.btn_confirm);
              }
          }
      });

        scanResultLayout = (ScanResultLayout) findViewById(R.id.zxing_capture_layout_scan_result);
      event = getIntent().getParcelableExtra(EVENT);

      hasSurface = false;
      inactivityTimer = new InactivityTimer(this);
      beepManager = new BeepManager(this);
      ambientLightManager = new AmbientLightManager(this);
      PreferenceManager.setDefaultValues(this, R.xml.zxing_preferences, false);
    }

    @Override
    protected void onResume() {
      super.onResume();

      // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
      // want to open the camera driver and measure the screen size if we're going to show the help on
      // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
      // off screen.
      cameraManager = new CameraManager(getApplication());

      viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
      viewfinderView.setCameraManager(cameraManager);

      handler = null;

      SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      if (hasSurface) {
        // The activity was paused but not stopped, so the surface still exists. Therefore
        // surfaceCreated() won't be called, so init the camera here.
        initCamera(surfaceHolder);
      } else {
        // Install the callback and wait for surfaceCreated() to init the camera.
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
      }

      beepManager.updatePrefs();
      ambientLightManager.start(cameraManager);

      inactivityTimer.onResume();

      Intent intent = getIntent();

      decodeFormats = null;
      characterSet = null;

      if (intent != null) {

        String action = intent.getAction();

        if (Intents.Scan.ACTION.equals(action)) {

          // Scan the formats the intent requested, and return the result to the calling activity.
          decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);
          decodeHints = DecodeHintManager.parseDecodeHints(intent);

          if (intent.hasExtra(Intents.Scan.WIDTH) && intent.hasExtra(Intents.Scan.HEIGHT)) {
            int width = intent.getIntExtra(Intents.Scan.WIDTH, 0);
            int height = intent.getIntExtra(Intents.Scan.HEIGHT, 0);
            if (width > 0 && height > 0) {
              cameraManager.setManualFramingRect(width, height);
            }
          }
        }

        characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);

      }
    }

    @Override
    protected void onPause() {
      if (handler != null) {
        handler.quitSynchronously();
        handler = null;
      }
      inactivityTimer.onPause();
      ambientLightManager.stop();
      cameraManager.closeDriver();
      if (!hasSurface) {
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        surfaceHolder.removeCallback(this);
      }
      super.onPause();
    }

    @Override
    protected void onDestroy() {
      inactivityTimer.shutdown();
      super.onDestroy();
    }

    private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
      // Bitmap isn't used yet -- will be used soon
      if (handler == null) {
        savedResultToShow = result;
      } else {
        if (result != null) {
          savedResultToShow = result;
        }
        if (savedResultToShow != null) {
          Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
          handler.sendMessage(message);
        }
        savedResultToShow = null;
      }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
      if (holder == null) {
        Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
      }
      if (!hasSurface) {
        hasSurface = true;
        initCamera(holder);
      }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
      hasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    /**
     * A valid barcode has been found, so give an indication of success and show the results.
     *
     * @param rawResult The contents of the barcode.
     * @param barcode   A greyscale bitmap of the camera data which was decoded.
     */
    public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
        scanResultLayout.setScanResult(ScanResultLayout.SCAN_RESULT.VALID);
        handler.sendEmptyMessage(R.id.restart_preview);
    }

    private boolean eventIsFull(){
        int usedSeats = Integer.parseInt(event.getUsed_seats());
        int availableSeats = Integer.parseInt(event.getAvailable_seats());
        return usedSeats == availableSeats;
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
      if (surfaceHolder == null) {
        throw new IllegalStateException("No SurfaceHolder provided");
      }
      if (cameraManager.isOpen()) {
        Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
        return;
      }
      try {
        cameraManager.openDriver(surfaceHolder);
        // Creating the handler starts the preview, which can also throw a RuntimeException.
        if (handler == null) {
          handler = new CaptureActivityHandler(this, decodeFormats, decodeHints, characterSet, cameraManager);
        }
        decodeOrStoreSavedBitmap(null, null);
      } catch (IOException ioe) {
        Log.w(TAG, ioe);
        displayFrameworkBugMessageAndExit();
      } catch (RuntimeException e) {
        // Barcode Scanner has seen crashes in the wild of this variety:
        // java.?lang.?RuntimeException: Fail to connect to camera service
        Log.w(TAG, "Unexpected error initializing camera", e);
        displayFrameworkBugMessageAndExit();
      }
    }

    private void displayFrameworkBugMessageAndExit() {
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setTitle(getString(R.string.zxing_app_name));
      builder.setMessage(getString(R.string.msg_camera_framework_bug));
      builder.setPositiveButton(R.string.button_ok, new FinishListener(this));
      builder.setOnCancelListener(new FinishListener(this));
      builder.show();
    }

    public void drawViewfinder() {
      viewfinderView.drawViewfinder();
    }

}
