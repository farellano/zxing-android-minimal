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
import com.google.zxing.interfaces.OnSlidingMenuOptionSelected;
import com.google.zxing.views.ScanResultLayout;
import com.google.zxing.widgets.HowToDialogFragment;
import com.google.zxing.widgets.MultiDirectionSlidingDrawer;
import com.ticktbox.ticktBoxAPI.api.TicktBoxAPI;
import com.ticktbox.ticktBoxAPI.api.models.EventTheater;
import com.ticktbox.ticktBoxAPI.api.models.Pass;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

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
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback, OnSlidingMenuOptionSelected {

    private static final String TAG = CaptureActivity.class.getSimpleName();
    private ImageView drawerHandle;

    @Override
    public void onSlidingMenuOptionSelected(String optionTag) {
        if (optionTag.equalsIgnoreCase("how_to")){
            displayHowToDialog();
        }else if (optionTag.equals("contact")){
        }else if (optionTag.equals("log_out")){

        }
    }

    private void displayHowToDialog() {
        Bundle args = new Bundle();
        HowToDialogFragment informationDialogFragment = (HowToDialogFragment) HowToDialogFragment.instantiate(this, HowToDialogFragment.class.getName(), args);
        informationDialogFragment.showDialog(getFragmentManager());
    }

    private enum CAMERA_STATE {
        STOPPED,
        RUNNING
    }

    public static final String MANUAL_PASSES = "manual_passes";
    public static final String EVENT = "event";
    private EventTheater event;


    private Pass currentPass;
    private int manualPasses = 0;

    private Button buttonVerify;
    private TextView textViewVerifiedSeats;
    private TextView textViewSeats;
    private LinearLayout buttonPlusSeat;
    private LinearLayout buttonMinusSeat;

    private Button buttonImDone;
    private Button buttonBack;

    private CAMERA_STATE camera_state = CAMERA_STATE.STOPPED;
    ScanResultLayout scanResultLayout;

    PassTask passTask;

    // This is the interval time for update the available seats number.
    private static final int UPDATING_TIME = 3000;
    private boolean UPDATING_SEATS = false;
    private UpdateSeatsTasks updateSeatsTasks = new UpdateSeatsTasks();

    private Handler mHandlerUpdateSeats = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            EventTheater updatedEvent = msg.getData().getParcelable(EVENT);
            if (updatedEvent != null){
                event = updatedEvent;
                textViewVerifiedSeats.setText(String.format(getString(R.string.verified_seats),event.getUsed_seats()));
            }
        }
    };

    private AddManualPassTask addManualPassTask;
    private MinusManualPassTask minusManualPassTask;

    private SoundPool soundPool;
    private int idAccept, idDeny;

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

    ViewfinderView getViewfinderView() {
        return viewfinderView;
    }

    public Handler getHandler() {
        return handler;
    }

    CameraManager getCameraManager() {
        return cameraManager;
    }

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
                    camera_state = CAMERA_STATE.RUNNING;
                    buttonVerify.setText("STOP");
                    buttonVerify.setBackgroundResource(R.drawable.btn_yellow);
                }else{
                    camera_state = CAMERA_STATE.STOPPED;
                    buttonVerify.setText("RESUME");
                    buttonVerify.setBackgroundResource(R.drawable.btn_confirm);
                }
            }
        });
        buttonPlusSeat = (LinearLayout) findViewById(R.id.zxing_capture_button_plus_seat);
        buttonPlusSeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!eventIsFull())
                    addSeat();
                else
                    scanResultLayout.setScanResult(ScanResultLayout.SCAN_RESULT.FULL);
            }
        });
        buttonMinusSeat = (LinearLayout) findViewById(R.id.zxing_capture_button_minus_seat);
        buttonMinusSeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                removeSeat();
            }
        });
        textViewVerifiedSeats = (TextView) findViewById(R.id.zxing_capture_textview_veriried_seats);
        textViewSeats = (TextView) findViewById(R.id.zxing_capture_textview_seats);

        scanResultLayout = (ScanResultLayout) findViewById(R.id.zxing_capture_layout_scan_result);
        event = getIntent().getParcelableExtra(EVENT);
        textViewSeats.setText(String.format(getString(R.string.number_of_seats),event.getTotal_seats()));

        buttonImDone = (Button) findViewById(R.id.zxing_capture_button_im_done);
        buttonImDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent();
                intent.putExtra(EVENT,event);
                intent.putExtra(MANUAL_PASSES,manualPasses);
                setResult(RESULT_OK,intent);
                finish();
            }
        });
        buttonBack = (Button) findViewById(R.id.zxing_capture_button_go_back);
        buttonBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });


        MultiDirectionSlidingDrawer drawer = (MultiDirectionSlidingDrawer) findViewById(R.id.capture_drawer);
        drawer.setOnSlidingMenuOptionSelectedlistener(this);

        drawerHandle = (ImageView) drawer.findViewById(R.id.handle_drawer_capture);

        drawer.setOnDrawerOpenListener(new MultiDirectionSlidingDrawer.OnDrawerOpenListener() {
            @Override
            public void onDrawerOpened() {
                drawerHandle.setImageResource(R.drawable.ic_handle_white_close);
            }
        });

        drawer.setOnDrawerCloseListener(new MultiDirectionSlidingDrawer.OnDrawerCloseListener() {
            @Override
            public void onDrawerClosed() {
                drawerHandle.setImageResource(R.drawable.ic_handle_white);
            }
        });

        hasSurface = false;
        inactivityTimer = new InactivityTimer(this);
        beepManager = new BeepManager(this);
        ambientLightManager = new AmbientLightManager(this);
        PreferenceManager.setDefaultValues(this, R.xml.zxing_preferences, false);

        soundPool = new SoundPool( 2, AudioManager.STREAM_MUSIC, 0);
        idAccept    = soundPool.load(this, R.raw.accept, 0);
        idDeny      = soundPool.load(this, R.raw.deny, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSeatsTasks.start();

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
        UPDATING_SEATS = false;
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
        inactivityTimer.onActivity();
        if (camera_state == CAMERA_STATE.RUNNING) {
            if (eventIsFull()) {
                scanResultLayout.setScanResult(ScanResultLayout.SCAN_RESULT.FULL);
                playSound(idDeny);
            } else if (passTask == null || passTask.getStatus() == AsyncTask.Status.FINISHED){
                passTask = new PassTask();
                passTask.execute(rawResult.getText());
            }
            handler.sendEmptyMessage(R.id.restart_preview);
        }
    }

    private boolean eventIsFull(){
        int usedSeats = Integer.parseInt(event.getUsed_seats());
        int availableSeats = Integer.parseInt(event.getAvailable_seats());
        return usedSeats == availableSeats;
    }

    private void playSound(int idSound){
        soundPool.play(idSound, 1, 1, 1, 0, 1);
    }

    private void addSeat(){
        if (eventIsFull()){
            scanResultLayout.setScanResult(ScanResultLayout.SCAN_RESULT.VALID);
        }else{
            manualPasses++;
            addManualPassTask = new AddManualPassTask();
            addManualPassTask.execute();
        }
    }

    private void removeSeat(){
        minusManualPassTask = new MinusManualPassTask();
        minusManualPassTask.execute();
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

    private class PassTask extends AsyncTask<String,Void,Pass>{

        ProgressDialog dialog = ProgressDialog.show(CaptureActivity.this, "",
                "Verifying pass, please wait...", true);

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            dialog.show();
        }

        @Override
        protected Pass doInBackground(String... voids) {
            return TicktBoxAPI.getInstance().pass(voids[0],event.getEventTheater_id());
        }

        @Override
        protected void onPostExecute(Pass pass) {
            super.onPostExecute(pass);
            dialog.dismiss();
            if (pass == null){
                scanResultLayout.setScanResult(ScanResultLayout.SCAN_RESULT.INVALID);
                playSound(idDeny);
            }else if (pass.getUsed().equals("0") && pass.getTheaterEventId().equals(event.getEventTheater_id())){
                new VerifyTask().execute(pass);
                scanResultLayout.setScanResult(ScanResultLayout.SCAN_RESULT.VALID);
                playSound(idAccept);
            }else{
                scanResultLayout.setScanResult(ScanResultLayout.SCAN_RESULT.USED);
                playSound(idDeny);
            }
        }
    }

    private class VerifyTask extends AsyncTask<Pass,Void,Pass>{

        @Override
        protected Pass doInBackground(Pass... passes) {
            return TicktBoxAPI.getInstance().verify(passes[0].getId(),passes[0].getGuests());
        }

        @Override
        protected void onPostExecute(Pass pass) {
            super.onPostExecute(pass);
            if (pass != null){
                currentPass = pass;
            }
        }
    }

    private class AddManualPassTask extends AsyncTask<Void,Void,JSONArray>{

        @Override
        protected JSONArray doInBackground(Void... voids) {
            return TicktBoxAPI.getInstance().manualPass(event.getEventTheater_id(), "plus");
        }

        @Override
        protected void onPostExecute(JSONArray jsonObject) {
            super.onPostExecute(jsonObject);
            if (jsonObject !=  null && jsonObject.length() > 0){
                event.setUsed_seats(jsonObject.optJSONObject(0).optString("seats_used"));
                textViewVerifiedSeats.setText(String.format(getString(R.string.verified_seats),event.getUsed_seats()));
            }
        }
    }

    private class MinusManualPassTask extends AsyncTask<Void,Void,JSONArray>{

        @Override
        protected JSONArray doInBackground(Void... voids) {
            return TicktBoxAPI.getInstance().manualPass(event.getEventTheater_id(), "minus");
        }

        @Override
        protected void onPostExecute(JSONArray jsonObject) {
            super.onPostExecute(jsonObject);
            if (jsonObject !=  null && jsonObject.length() > 0){
                event.setUsed_seats(jsonObject.optJSONObject(0).optString("seats_used"));
                textViewVerifiedSeats.setText(String.format(getString(R.string.verified_seats),event.getUsed_seats()));
            }
        }
    }

    private class UpdateSeatsTasks extends Thread{

        @Override
        public void run() {
            try{
                UPDATING_SEATS = true;
                while (UPDATING_SEATS){
                    for (EventTheater evt : TicktBoxAPI.getInstance().theaterEvents()){
                        if (evt.getEventTheater_id().equals(event.getEventTheater_id())){
                            Message message = new Message();
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(EVENT,evt);
                            message.setData(bundle);
                            mHandlerUpdateSeats.sendMessage(message);
                            break;
                        }
                    }
                    sleep(UPDATING_TIME);
                }
            }catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }
}
