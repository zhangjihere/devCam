package com.devcam;


import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    public final static String APP_TAG = "SettingActivity";

    static final String SHOW_EXPOSURE_TIME = "SHOW_EXPOSURE_TIME";
    static final String SHOW_APERTURE = "SHOW_APERTURE";
    static final String SHOW_SENSITIVITY = "SHOW_SENSITIVITY";
    static final String SHOW_FOCUS_DISTANCE = "SHOW_FOCUS_DISTANCE";
    static final String SHOW_FOCAL_LENGTH = "SHOW_FOCAL_LENGTH";
    static final String USE_DELAY_KEY = "USE_DELAY";
    static final String CAMERA_DEVICE_KEY = "CAMERA_DEVICE";
    static final String BURST_BATCH_KEY = "BURST_BATCH";

    Button mOKbutton;
    CheckBox mExposureTimeBox;
    CheckBox mApertureBox;
    CheckBox mSensitivityBox;
    CheckBox mFocusDistanceBox;
    CheckBox mFocalLengthBox;
    Switch mSwitch;
    RadioGroup mCameraDevice;
    Switch mBatchBurstSwitch;

    int presetCameraDeviceCheckedRadio;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(APP_TAG, "SettingsActivity Created.");

        // Hide the action bar so the activity gets the full screen
        getActionBar().hide();

        // Set the correct layout for this Activity
        setContentView(R.layout.settings_layout);

        mExposureTimeBox = findViewById(R.id.exposureTimeCheckBox);
        mApertureBox = findViewById(R.id.apertureCheckBox);
        mSensitivityBox = findViewById(R.id.sensitivityCheckBox);
        mFocusDistanceBox = findViewById(R.id.focusDistanceCheckBox);
        mFocalLengthBox = findViewById(R.id.focalLengthCheckBox);
        mSwitch = findViewById(R.id.delaySwitch);
        mCameraDevice = findViewById(R.id.CameraDevice);
        mBatchBurstSwitch = findViewById(R.id.BurstBatch_Switch);

        SharedPreferences settings = getSharedPreferences(MainDevCamActivity.class.getName(), Context.MODE_MULTI_PROCESS);

        mExposureTimeBox.setChecked(settings.getBoolean(SHOW_EXPOSURE_TIME, true));
        mApertureBox.setChecked(settings.getBoolean(SHOW_APERTURE, false)); // this is often fixed
        mSensitivityBox.setChecked(settings.getBoolean(SHOW_SENSITIVITY, true));
        mFocusDistanceBox.setChecked(settings.getBoolean(SHOW_FOCUS_DISTANCE, true));
        mFocalLengthBox.setChecked(settings.getBoolean(SHOW_FOCAL_LENGTH, false)); // often fixed
        mSwitch.setChecked(settings.getBoolean(USE_DELAY_KEY, false));
        mBatchBurstSwitch.setChecked(settings.getBoolean(BURST_BATCH_KEY, false));
        if (settings.getInt(CAMERA_DEVICE_KEY, CameraMetadata.LENS_FACING_BACK) == CameraMetadata.LENS_FACING_BACK) {
            mCameraDevice.check(R.id.BackCamera);
        } else {
            mCameraDevice.check(R.id.FrontCamera);
        }
        presetCameraDeviceCheckedRadio = mCameraDevice.getCheckedRadioButtonId();

        // Set up the "OK" Button to send settings back to main function
        mOKbutton = findViewById(R.id.okSettingsButton);
        mOKbutton.setOnClickListener(v -> {
            SharedPreferences.Editor editor = getSharedPreferences(MainDevCamActivity.class.getName(), Context.MODE_MULTI_PROCESS).edit();
            editor.putBoolean(SHOW_EXPOSURE_TIME, mExposureTimeBox.isChecked());
            editor.putBoolean(SHOW_APERTURE, mApertureBox.isChecked());
            editor.putBoolean(SHOW_SENSITIVITY, mSensitivityBox.isChecked());
            editor.putBoolean(SHOW_FOCAL_LENGTH, mFocalLengthBox.isChecked());
            editor.putBoolean(SHOW_FOCUS_DISTANCE, mFocusDistanceBox.isChecked());
            editor.putBoolean(USE_DELAY_KEY, mSwitch.isChecked());
            editor.putBoolean(BURST_BATCH_KEY, mBatchBurstSwitch.isChecked());
            final int newCameraDeviceCheckedRadio = mCameraDevice.getCheckedRadioButtonId();
            if (newCameraDeviceCheckedRadio == R.id.BackCamera) {
                editor.putInt(CAMERA_DEVICE_KEY, CameraMetadata.LENS_FACING_BACK);
            } else {
                editor.putInt(CAMERA_DEVICE_KEY, CameraMetadata.LENS_FACING_FRONT);
            }
            if (presetCameraDeviceCheckedRadio != newCameraDeviceCheckedRadio) {
                Toast.makeText(this, "Restart APP to Enable Camera Changed!", Toast.LENGTH_LONG).show();
            }
            editor.apply();
            finish();
        });
    }

}
