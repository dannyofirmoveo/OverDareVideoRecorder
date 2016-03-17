package com.dannyofir.www.overdarevideorecorder;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.PowerManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.VideoView;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.http.HttpRequest;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3Client;
import com.jmolsmobile.landscapevideocapture.VideoCaptureActivity;
import com.jmolsmobile.landscapevideocapture.configuration.CaptureConfiguration;
import com.jmolsmobile.landscapevideocapture.configuration.PredefinedCaptureConfigurations;
import com.jmolsmobile.landscapevideocapture.recorder.VideoRecorder;
import com.netcompss.ffmpeg4android.CommandValidationException;
import com.netcompss.ffmpeg4android.GeneralUtils;
import com.netcompss.ffmpeg4android.Prefs;
import com.netcompss.loader.LoadJNI;

import java.io.File;

// The problem I had was understanding where all the ffmpeg files need to go. Once I got all the folders and files inorder
// it is very simple.

public class MainActivity extends AppCompatActivity {

    private int maxVideoDuration = 10;
    private int maxVideoSize = 50;

    private String filepath;

    String workFolder = null;
    String videoFolder = null;
    String vkLogPath = null;
    private boolean commandValidationFailedFlag = false;

    private VideoView videoViewMain;

    private TransferUtility transferUtility;
    private AmazonS3Client amazonS3Client;

    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.hide();


        Intent intent = new Intent(this, VideoCaptureActivity.class);

        CaptureConfiguration config = new CaptureConfiguration(PredefinedCaptureConfigurations.CaptureResolution.RES_1080P,
                PredefinedCaptureConfigurations.CaptureQuality.HIGH, maxVideoDuration, maxVideoSize);


        intent.putExtra(VideoCaptureActivity.EXTRA_CAPTURE_CONFIGURATION, config);
        intent.putExtra(VideoCaptureActivity.EXTRA_OUTPUT_FILENAME, "");
        startActivityForResult(intent, 101);

        Log.i(Prefs.TAG, getString(R.string.app_name) + " version: " + GeneralUtils.getVersionName(getApplicationContext()));
        workFolder = getApplicationContext().getFilesDir().getAbsolutePath() + "/";
        Log.i(Prefs.TAG, "workFolder (license and logs location) path: " + workFolder);
        vkLogPath = workFolder + "vk.log";
        Log.i(Prefs.TAG, "vk log (native log) path: " + vkLogPath);

        GeneralUtils.copyLicenseFromAssetsToSDIfNeeded(this, workFolder);

        videoFolder = String.valueOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)) + "/";

        progressBar = (ProgressBar) findViewById(R.id.progressBarUpload);
        progressBar.setVisibility(View.INVISIBLE);


    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.i(Prefs.TAG, "Main on resume handling log copy in case of a crash");
        String workFolder = getApplicationContext().getFilesDir() + "/";
        String vkLogPath = workFolder + "vk.log";
        GeneralUtils.copyFileToFolder(vkLogPath, videoFolder);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {


        if (resultCode == Activity.RESULT_OK) {
            filepath = data.getStringExtra(VideoCaptureActivity.EXTRA_OUTPUT_FILENAME);

            File file = new File(filepath);
            videoFolder = file.getParent();

            new TranscodingBackground(this).execute();

//            statusMessage = String.format(getString(R.string.status_capturesuccess), filename);
        } else if (resultCode == Activity.RESULT_CANCELED) {
            filepath = null;
//            statusMessage = getString(R.string.status_capturecancelled);
        } else if (resultCode == VideoCaptureActivity.RESULT_ERROR) {
            filepath = null;
//            statusMessage = getString(R.string.status_capturefailed);
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    public void buttonUploadVideo_onClick(View view) {

        credentialsProvider();

        setTransferUtility();

        setFileToUpload();


    }

    // FOR AMAZON WEB SERVICES:

    public void credentialsProvider(){

        // Initialize the Amazon Cognito credentials provider
        CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(
                getApplicationContext(),
                "us-east-1:ffa41a0d-f4fb-425a-b23b-31c14476f95f", // Identity Pool ID
                Regions.US_EAST_1 // Region
        );

        setAmazonS3Client(credentialsProvider);
    }

    public void setTransferUtility(){

        transferUtility = new TransferUtility(amazonS3Client, getApplicationContext());
    }

    public void setAmazonS3Client(CognitoCachingCredentialsProvider credentialsProvider){

        // Create an S3 client
        amazonS3Client = new AmazonS3Client(credentialsProvider);

        // Set the region of your S3 bucket
        amazonS3Client.setRegion(Region.getRegion(Regions.EU_CENTRAL_1));

    }

    public void setFileToUpload(){

        File fileToUpload = new File(videoFolder + "/newVideo.mp4");

        TransferObserver transferObserver = transferUtility.upload(
                "overdare",     /* The bucket to upload to */
                "newVideo.mp4",    /* The key for the uploaded object */
                fileToUpload       /* The file where the data to upload exists */
        );


        progressBar.setVisibility(View.VISIBLE);

        transferObserverListener(transferObserver);

    }

    public void transferObserverListener(TransferObserver transferObserver){

        transferObserver.setTransferListener(new TransferListener() {

            @Override
            public void onStateChanged(int id, TransferState state) {
                Log.e("statechange", state + "");
                if(state == TransferState.COMPLETED){
                    progressBar.setVisibility(View.INVISIBLE);
                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                int percentage = (int) (bytesCurrent / bytesTotal * 100);
                Log.e("percentage", percentage + "");
                progressBar.setProgress(percentage);
            }

            @Override
            public void onError(int id, Exception ex) {
                Log.e("error", "error");
            }

        });
    }

    // ALL ABOVE IS FOR AMAZON WEB SERVICES

    public class TranscodingBackground extends AsyncTask<String, Integer, Integer>
    {

        ProgressDialog progressDialog;
        Activity activity;

        public TranscodingBackground(Activity activity) {
            this.activity = activity;
        }

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(activity);
            progressDialog.setMessage("FFmpeg4Android Transcoding in progress...");
            progressDialog.show();
        }

        protected Integer doInBackground(String... paths) {
            Log.i(Prefs.TAG, "doInBackground started...");

            // delete previous log
            GeneralUtils.deleteFileUtil(videoFolder + "/vk.log");

            PowerManager powerManager = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VK_LOCK");
            Log.d(Prefs.TAG, "Acquire wake lock");
            wakeLock.acquire();

            String[] commandStr = {"ffmpeg","-y" ,"-i", filepath,"-strict","experimental", "-vf", "movie=" + videoFolder + "/watermark.png [watermark]; [in][watermark] overlay=main_w-overlay_w-10:10 [out]","-s", "320x240","-r", "30", "-b", "15496k", "-vcodec", "mpeg4","-ab", "48000", "-ac", "2", "-ar", "22050", videoFolder + "/newVideo.mp4"};

            ///////////// Set Command using code (overriding the UI EditText) /////
            //String commandStr = "ffmpeg -y -i /sdcard/videokit/in.mp4 -strict experimental -s 320x240 -r 30 -aspect 3:4 -ab 48000 -ac 2 -ar 22050 -vcodec mpeg4 -b 2097152 /sdcard/videokit/out.mp4";
            //String[] complexCommand = {"ffmpeg", "-y" ,"-i", "/sdcard/videokit/in.mp4","-strict","experimental","-s", "160x120","-r","25", "-vcodec", "mpeg4", "-b", "150k", "-ab","48000", "-ac", "2", "-ar", "22050", "/sdcard/videokit/out.mp4"};
            ///////////////////////////////////////////////////////////////////////


            LoadJNI vk = new LoadJNI();
            try {

                // complex command
                //vk.run(complexCommand, workFolder, getApplicationContext());

                vk.run(commandStr, workFolder, getApplicationContext());

                // running without command validation
                //vk.run(complexCommand, workFolder, getApplicationContext(), false);

                // copying vk.log (internal native log) to the videokit folder
                GeneralUtils.copyFileToFolder(vkLogPath, videoFolder);

//			} catch (CommandValidationException e) {
//					Log.e(Prefs.TAG, "vk run exeption.", e);
//					commandValidationFailedFlag = true;

            } catch (Throwable e) {
                Log.e(Prefs.TAG, "vk run exeption.", e);
            }
            finally {
                if (wakeLock.isHeld())
                    wakeLock.release();
                else{
                    Log.i(Prefs.TAG, "Wake lock is already released, doing nothing");
                }
            }
            Log.i(Prefs.TAG, "doInBackground finished");
            return Integer.valueOf(0);
        }

        protected void onProgressUpdate(Integer... progress) {
        }

        @Override
        protected void onCancelled() {
            Log.i(Prefs.TAG, "onCancelled");
            //progressDialog.dismiss();
            super.onCancelled();
        }


        @Override
        protected void onPostExecute(Integer result) {
            Log.i(Prefs.TAG, "onPostExecute");
            progressDialog.dismiss();
            super.onPostExecute(result);

            // finished Toast
            String rc = null;
            if (commandValidationFailedFlag) {
                rc = "Command Vaidation Failed";
            }
            else {
                rc = GeneralUtils.getReturnCodeFromLog(vkLogPath);
            }
            final String status = rc;

            // Maybe this needs to change, check if there are further problems

            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(MainActivity.this, status, Toast.LENGTH_LONG).show();
                    if (status.equals("Transcoding Status: Failed")) {
                        Toast.makeText(MainActivity.this, "Check: " + vkLogPath + " for more information.", Toast.LENGTH_LONG).show();
                    }
                }
            });

            MediaController mediaController = new MediaController(MainActivity.this);
            mediaController.setAnchorView(videoViewMain);

            videoViewMain = (VideoView) findViewById(R.id.videoViewMain);
            videoViewMain.setVideoPath(videoFolder + "/newVideo.mp4");
            videoViewMain.setMediaController(mediaController);
            videoViewMain.start();

        }

    }

}
