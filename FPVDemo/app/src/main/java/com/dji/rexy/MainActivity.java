package com.dji.rexy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


//import com.dji.FPVDemo.R;
import com.dji.rexy.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.util.Timer;
import java.util.TimerTask;

import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.useraccount.UserAccountManager;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;

public class MainActivity extends Activity implements SurfaceTextureListener, OnClickListener, Runnable {

    private static final String TAG = MainActivity.class.getName();
    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;
    // Codec for video live view
    protected DJICodecManager mCodecManager = null;
    protected TextureView mVideoSurface = null;
    private Button forward_button, backward_button, turn_left_button, turn_right_button, land_button,
            takeoff_button, save_button, stop_button, yaw_right_button, yaw_left_button, up_button,
            down_button, record_button;
    private TextView info, bat_status, voice_command_view;
    private FlightCommandsAPI FPVcontrol;
    private Handler handler;
    private enum states {Floor, Takeoff, Land, Forward, Backward, Yaw_R, Yaw_L,Right, Left,
                        Up, Down, Emergency, Hover}
    private states state = states.Floor;
    private LogCustom log;
    private Timer timer;

    // Speech2Text params
    private Module module = null;
    private final static int REQUEST_RECORD_AUDIO = 13;
    private final static int AUDIO_LEN_IN_SECOND = 6;
    private final static int SAMPLE_RATE = 16000;
    private final static int RECORDING_LENGTH = SAMPLE_RATE * AUDIO_LEN_IN_SECOND;
    private int mStart = 1;
    private HandlerThread mTimerThread;
    private Handler mTimerHandler;
    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTimerHandler.postDelayed(mRunnable, 1000);

            MainActivity.this.runOnUiThread(
                    () -> {
                        record_button.setText(String.format("Listening - %ds left", AUDIO_LEN_IN_SECOND - mStart));
                        mStart += 1;
                    });
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler();
        initUI();
        initListeners();

        // create log instance
        log = new LogCustom(getExternalFilesDir("LOG"));
        // create Flight Controller instance wrapped with FPV-API
        FPVcontrol = new FlightCommandsAPI(log, bat_status);
        // set a new timer for updating the Log each 1 second
        TimerTask t = new TimerTask() {
            @Override
            public void run() {
                log.write();
            }
        };
        timer = new Timer();
        timer.schedule(t, 0, 100);


        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {
            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
            }
        };

        requestMicrophonePermission();
    }

    protected void onProductChange() {
        initPreviewer();
        loginAccount();
    }

    private void loginAccount() {

        UserAccountManager.getInstance().logIntoDJIUserAccount(this,
                new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                    @Override
                    public void onSuccess(final UserAccountState userAccountState) {
                        Log.e(TAG, "Login Success");
                    }

                    @Override
                    public void onFailure(DJIError error) {
                        showToast("Login Error:"
                                + error.getDescription());
                    }
                });
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        initPreviewer();
        onProductChange();

        if (mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view) {
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();
        stopTimerThread();
        super.onDestroy();
    }

    private void initUI() {
        // init mVideoSurface
        mVideoSurface = findViewById(R.id.video_previewer_surface);
        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }
        info = findViewById(R.id.status);
        info.setText(new String("Floor"));
        bat_status = findViewById(R.id.Battery_status);
        bat_status.setText(new String("100"));
        forward_button = findViewById(R.id.forward_button);
        backward_button = findViewById(R.id.backward_button);
        yaw_left_button = findViewById(R.id.yaw_left_button);
        yaw_right_button = findViewById(R.id.yaw_right_button);
        turn_left_button = findViewById(R.id.turn_left_button);
        turn_right_button = findViewById(R.id.turn_right_button);
        land_button = findViewById(R.id.land_button);
        takeoff_button = findViewById(R.id.take_off_button);
        up_button = findViewById(R.id.up_button);
        down_button = findViewById(R.id.down_button);
        save_button = findViewById(R.id.save_button);
        stop_button = findViewById(R.id.stop_button);
        record_button = findViewById(R.id.record_button);
        voice_command_view = findViewById(R.id.command_text);
    }

    private void initListeners() {
        takeoff_button.setOnClickListener(this);
        land_button.setOnClickListener(this);
        forward_button.setOnClickListener(this);
        backward_button.setOnClickListener(this);
        turn_right_button.setOnClickListener(this);
        turn_left_button.setOnClickListener(this);
        save_button.setOnClickListener(this);
        stop_button.setOnClickListener(this);
        yaw_left_button.setOnClickListener(this);
        yaw_right_button.setOnClickListener(this);
        up_button.setOnClickListener(this);
        down_button.setOnClickListener(this);
        record_button.setOnClickListener(this);
    }


    private void initPreviewer() {

        BaseProduct product = FPVDemoApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {
            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
            }
        }
    }

    private void uninitPreviewer() {
        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            // Reset the callback
            VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(null);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG, "onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onClick(View v) {
        /*
            Main UI method, an onClick listener for all UI buttons.
            Updates the current state.
            Send commands to the drone using the interface class FPVcontrol.
         */
        switch (v.getId()) {
            case R.id.take_off_button:
                if (state == states.Floor) {
                    state = states.Takeoff;
                    info.setText(new String("Takeoff"));
                    log.setMode("Takeoff");
                    FPVcontrol.takeoff();
                    state = states.Hover;
                    info.setText(new String("Hover"));
                    log.setMode("Hover");
                }
                break;

            case R.id.land_button:
                if (state == states.Hover) {
                    state = states.Land;
                    info.setText(new String("Landing"));
                    log.setMode("Land");
                    FPVcontrol.land();
                    state = states.Floor;
                    info.setText(new String("Floor"));
                    log.setMode("Floor");
                }
                break;

            case R.id.forward_button:
                if (state != states.Floor){
                    state = states.Forward;
                    info.setText(new String("Forward"));
                    log.setMode("Forward");
                    FPVcontrol.set_pitch((float) 0.5, "Forward");
                }
                break;

            case R.id.backward_button:
                if (state != states.Floor){
                    state = states.Backward;
                    info.setText(new String("Backward"));
                    log.setMode("Backward");
                    FPVcontrol.set_pitch((float) -0.5, "Backward");
                }
                break;

            case R.id.yaw_left_button:
                if (state != states.Floor){
                    state = states.Yaw_L;
                    info.setText(new String("Yaw left"));
                    log.setMode("Yaw Left");
                    FPVcontrol.set_yaw((float) -5, "Yaw Left");
                }
                break;

            case R.id.yaw_right_button:
                if (state != states.Floor){
                    state = states.Yaw_R;
                    info.setText(new String("Yaw right"));
                    log.setMode("Yaw Right");
                    FPVcontrol.set_yaw((float) 5, "Yaw Right");
                }
                break;

            case R.id.turn_left_button:
                if (state != states.Floor){
                    state = states.Left;
                    info.setText(new String("Left"));
                    log.setMode("Left");
                    FPVcontrol.set_roll((float) -0.2, "Left");
                }
                break;

            case R.id.turn_right_button:
                if (state != states.Floor){
                    state = states.Right;
                    info.setText(new String("Right"));
                    log.setMode("Right");
                    FPVcontrol.set_roll((float) 0.2, "Right");
                }
                break;

            case R.id.up_button:
                if (state != states.Floor){
                    state = states.Up;
                    info.setText(new String("Up"));
                    log.setMode("Up");
                    FPVcontrol.set_throttle((float) 0.1, "Up");
                }
                break;

            case R.id.down_button:
                if (state != states.Floor){
                    state = states.Down;
                    info.setText(new String("Down"));
                    log.setMode("Down");
                    FPVcontrol.set_throttle((float) -0.1, "Down");
                }
                break;

            case R.id.stop_button:
                state = states.Hover;
                info.setText(new String("Hover"));
                log.setMode("Hover");
                FPVcontrol.stayOnPlace();
                break;

            case R.id.save_button:
                timer.cancel();
                log.close();
                showToast("Log Saved!");
                break;

            case R.id.record_button:
                record_button.setText(String.format("Listening - %ds left", AUDIO_LEN_IN_SECOND));
                record_button.setEnabled(false);
                Thread thread = new Thread(MainActivity.this);
                thread.start();
                mTimerThread = new HandlerThread("Timer");
                mTimerThread.start();
                mTimerHandler = new Handler(mTimerThread.getLooper());
                mTimerHandler.postDelayed(mRunnable, 1000);
                break;

            default:
                break;
        }
    }

    // Speech2Text methods
    protected void stopTimerThread() {
        mTimerThread.quitSafely();
        try {
            mTimerThread.join();
            mTimerThread = null;
            mTimerHandler = null;
            mStart = 1;
        } catch (InterruptedException e) {
            Log.e(TAG, "Error on stopping background thread", e);
        }
    }

    private void requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    private String assetFilePath(Context context, String assetName) {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, assetName + ": " + e.getLocalizedMessage());
        }
        return null;
    }

    private void showTranslationResult(String result) {
        voice_command_view.setText(result);
    }

    private String recognize(float[] floatInputBuffer)  {
        if (module == null) {
            module = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "wav2vec2.ptl"));
            showToast("Model loaded successfully");
        }

        double wav2vecinput[] = new double[RECORDING_LENGTH];
        for (int n = 0; n < RECORDING_LENGTH; n++)
            wav2vecinput[n] = floatInputBuffer[n];

        FloatBuffer inTensorBuffer = Tensor.allocateFloatBuffer(RECORDING_LENGTH);
        for (double val : wav2vecinput)
            inTensorBuffer.put((float)val);

        Tensor inTensor = Tensor.fromBlob(inTensorBuffer, new long[]{1, RECORDING_LENGTH});
        showToast("created tensor");
        final String result = module.forward(IValue.from(inTensor)).toStr();
        showToast("generated result");
        return result;
    }

    @Override
    public void run() {
        log.setDebug("RUN speech");
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!");
            return;
        }
        log.setDebug("passed first IF statement");
        record.startRecording();
        log.setDebug("Started recording");
        long shortsRead = 0;
        int recordingOffset = 0;
        short[] audioBuffer = new short[bufferSize / 2];
        short[] recordingBuffer = new short[RECORDING_LENGTH];

        while (shortsRead < RECORDING_LENGTH) {
            int numberOfShort = record.read(audioBuffer, 0, audioBuffer.length);
            shortsRead += numberOfShort;
            System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, numberOfShort);
            recordingOffset += numberOfShort;
        }
        log.setDebug("Finished recording");
        record.stop();
        record.release();
        log.setDebug("Stopped recording");
        stopTimerThread();
        log.setDebug("Stopped Timer");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                record_button.setText("Recognizing...");
            }
        });

        float[] floatInputBuffer = new float[RECORDING_LENGTH];

        // feed in float values between -1.0f and 1.0f by dividing the signed 16-bit inputs.
        for (int i = 0; i < RECORDING_LENGTH; ++i) {
            floatInputBuffer[i] = recordingBuffer[i] / (float)Short.MAX_VALUE;
        }
        showToast("Start recognition");
        final String result = recognize(floatInputBuffer);
        log.setDebug("Result is ready");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showTranslationResult(result);
                record_button.setEnabled(true);
                record_button.setText("Start");
            }
        });
    }
}
