package com.dji.rexy;
import android.app.Activity;
import android.graphics.Bitmap;
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
import com.dji.rexy.R;
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

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.pytorch.Module;

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
    private FlightCommandUI UI_commands;
    private Handler handler;
    enum states {Floor, Takeoff, Land, Forward, Backward, Yaw_R, Yaw_L,Right, Left,
                        Up, Down, Emergency, Hover}
    private states state = states.Floor;
    private LogCustom log;
    private Timer timer;

    // Speech2Text params
    private Module module = null;
    private final static int REQUEST_RECORD_AUDIO = 13;
    private final static int AUDIO_LEN_IN_SECOND = 4;
    private final static int SAMPLE_RATE = 16000;
    private final static int RECORDING_LENGTH = SAMPLE_RATE * AUDIO_LEN_IN_SECOND;
    private int mStart = 1;
    private HandlerThread mTimerThread;
    private Handler mTimerHandler;
    private SpeechRecognition speech_utils;
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

    static {
        System.loadLibrary("opencv_java4");
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        initListeners();
        initParams();
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

    private void initParams(){
        // create log instance
        log = new LogCustom(getExternalFilesDir("LOG"));
        // create Flight Controller instance wrapped with FPV-API
        FPVcontrol = new FlightCommandsAPI(log, bat_status);
        // A UI class for flight commands.
        UI_commands = new FlightCommandUI(this.log, this.info, this.FPVcontrol);
        speech_utils = new SpeechRecognition(getApplicationContext());
        // set a new timer for updating the Log each 1 second
        handler = new Handler();
        TimerTask t = new TimerTask() {
            @Override
            public void run() {
                log.write();
            }
        };
        timer = new Timer();
        timer.schedule(t, 0, 100);
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

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {
            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
            }
        };
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
                UI_commands.takeoff();
                break;

            case R.id.land_button:
                UI_commands.land();
                break;

            case R.id.forward_button:
                UI_commands.forward();
                break;

            case R.id.backward_button:
                UI_commands.backward();
                break;

            case R.id.yaw_left_button:
                UI_commands.yaw_left();
                break;

            case R.id.yaw_right_button:
                UI_commands.yaw_right();
                break;

            case R.id.turn_left_button:
                UI_commands.turn_left();
                break;

            case R.id.turn_right_button:
                UI_commands.turn_right();
                break;

            case R.id.up_button:
                UI_commands.up();
                break;

            case R.id.down_button:
                UI_commands.down();
                break;

            case R.id.stop_button:
                UI_commands.stop();
                break;

            case R.id.save_button:
                this.save_log();
                break;

            case R.id.record_button:
                this.record();
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

    private void showTranslationResult(String result) {
        voice_command_view.setText(new String(result));
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

        record.stop();
        record.release();
        stopTimerThread();

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
        final String result = speech_utils.recognize(floatInputBuffer);
        log.setDebug("Result is ready");

        // perform the desired command
        boolean command_executed = this.voice_command_execute(result);
        if (command_executed){
            showToast("Command executed successfully!");
        }
        else{
            showToast("Command failed / Command not clear!");
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showTranslationResult(result);
                record_button.setEnabled(true);
                record_button.setText("Record");
            }
        });
    }


    private boolean voice_command_execute(String command){
        /*
            This method parse the voice command.
            If the command key is -1, it will return false as the
            command isn't clear.
            Else, it will perform the desired command and will return
            true if the command executed successfully.
         */
        // parse the predicted voice command.
        int command_key = speech_utils.parseCommand(command);
        // perform the command:
        switch (command_key){
            case -1:
                // return false, the command isn't clear!
                return false;
            case 0:
                // Takeoff
                UI_commands.takeoff();
                break;
            case 1:
                // Land
                UI_commands.land();
                break;
            case 2:
                // Forward
                UI_commands.forward();
                break;
            case 3:
                // Backward
                UI_commands.backward();
                break;
            case 4:
                // Turn left
                UI_commands.turn_left();
                break;
            case 5:
                // Turn right
                UI_commands.turn_right();
                break;
            case 6:
                // Yaw left
                UI_commands.yaw_left();
                break;
            case 7:
                // Yaw right
                UI_commands.yaw_right();
                break;
            case 8:
                // Up
                UI_commands.up();
                break;
            case 9:
                // Down
                UI_commands.down();
                break;
            case 10:
                // Stop
                UI_commands.stop();
                break;
            case 11:
                UI_commands.speedUp();
                break;
            case 12:
                UI_commands.slowDown();
                break;
            default:
                // Command not clear!
                return false;
        }
        return true;
    }


    private void save_log(){
        timer.cancel();
        log.close();
        showToast("Log Saved!");
    }

    private void record(){
        record_button.setText(String.format("Listening - %ds left", AUDIO_LEN_IN_SECOND));
        record_button.setEnabled(false);
        Thread thread = new Thread(MainActivity.this);
        thread.start();
        mTimerThread = new HandlerThread("Timer");
        mTimerThread.start();
        mTimerHandler = new Handler(mTimerThread.getLooper());
        mTimerHandler.postDelayed(mRunnable, 1000);
    }



}
