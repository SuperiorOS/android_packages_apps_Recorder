/*
 * Copyright (C) 2013 The CyanogenMod Project
 * Copyright (C) 2016 Kevin Crimi (kcrimi)
 * Copyright (C) 2017-2018 The LineageOS Project
 * Copyright (C) 2020 Bootleggers ROM
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
package org.lineageos.recorder.screen;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.lineageos.recorder.R;
import org.lineageos.recorder.RecorderActivity;
import org.lineageos.recorder.utils.LastRecordHelper;
import org.lineageos.recorder.utils.MediaProviderHelper;
import org.lineageos.recorder.utils.Utils;

import java.nio.ByteBuffer;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class ScreencastService extends Service implements MediaProviderHelper.OnContentWritten {
    private static final String LOGTAG = "ScreencastService";

    private static final String SCREENCAST_NOTIFICATION_CHANNEL =
            "screencast_notification_channel";

    private static final String EXTRA_RESULT_CODE = "extra_resultCode";
    private static final String EXTRA_DATA = "extra_data";
    private static final String EXTRA_AUDIO_SOURCE = "extra_audioSource";

    private static final String ACTION_START_SCREENCAST =
            "org.lineageos.recorder.screen.ACTION_START_SCREENCAST";
    public static final String ACTION_STOP_SCREENCAST =
            "org.lineageos.recorder.screen.ACTION_STOP_SCREENCAST";
    public static final String ACTION_TOGGLE_SCREENCAST =
            "org.lineageos.recorder.screen.ACTION_TOGGLE_SCREENCAST";
    private static final String ACTION_SCAN =
            "org.lineageos.recorder.server.display.SCAN";
    private static final String ACTION_STOP_SCAN =
            "org.lineageos.recorder.server.display.STOP_SCAN";

    private static final int TOTAL_NUM_TRACKS = 1;
    private static final int SAMPLES_PER_FRAME = 1024;
    private static final int VIDEO_BIT_RATE = 5000000;
    private static final int VIDEO_FRAME_RATE = 48;
    private static final int AUDIO_BIT_RATE = 128000;
    private static final int AUDIO_SAMPLE_RATE = 44100;

    public static final int NOTIFICATION_ID = 61;
    private long mStartTime;
    private Timer mTimer;
    private NotificationCompat.Builder mBuilder;
    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private MediaMuxer mMuxer;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;
    private NotificationManager mNotificationManager;
    private int mAudioSource;
    private int mAudioBufferBytes;
    private AudioRecord mInternalAudio;
    private boolean mMuxerStarted = false;
    private boolean mAudioRecording;
    private boolean mAudioEncoding;
    private boolean mVideoEncoding;
    private boolean mVideoRecording;
    private File mPath;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private final Object mMuxerLock = new Object();
    private final Object mAudioEncoderLock = new Object();
    private final Object mWriteVideoLock = new Object();
    private final Object mWriteAudioLock = new Object();
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_BACKGROUND.equals(action) ||
                    Intent.ACTION_SHUTDOWN.equals(action)) {
                stopCasting();
            }
        }
    };

    public static Intent getStartIntent(Context context, int resultCode, Intent data,
                                        int audioSource) {
        return new Intent(context, ScreencastService.class)
                .setAction(ACTION_START_SCREENCAST)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_AUDIO_SOURCE, audioSource);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();
        if (action == null) {
            return START_NOT_STICKY;
        }

        switch (action) {
            case ACTION_SCAN:
            case ACTION_STOP_SCAN:
                return START_STICKY;
            case ACTION_START_SCREENCAST:
                return startScreencasting(intent);
            case ACTION_STOP_SCREENCAST:
                stopCasting();
                return START_STICKY;
            case ACTION_TOGGLE_SCREENCAST:
                if (mVideoRecording) {
                    stopCasting();
                    return START_STICKY;
                }
                return startScreencasting(intent);
            default:
                return START_NOT_STICKY;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Prepare all the output metadata
        String videoDate = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                .format(new Date());
        // the directory which holds all recording files
        mPath = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "ScreenRecords/ScreenRecord-" + videoDate + ".mp4");

        File recordingDir = mPath.getParentFile();
        if (recordingDir == null) {
            throw new SecurityException("Cannot access scoped Movies/ScreenRecords directory");
        }
        //noinspection ResultOfMethodCallIgnored
        recordingDir.mkdirs();
        if (!(recordingDir.exists() && recordingDir.canWrite())) {
            throw new SecurityException("Cannot write to " + recordingDir);
        }

        mMediaProjectionManager = getSystemService(MediaProjectionManager.class);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mBroadcastReceiver, filter);

        mNotificationManager = getSystemService(NotificationManager.class);

        if (mNotificationManager == null || mNotificationManager.getNotificationChannel(
                SCREENCAST_NOTIFICATION_CHANNEL) != null) {
            return;
        }

        mVideoRecording = false;

        CharSequence name = getString(R.string.screen_channel_title);
        String description = getString(R.string.screen_channel_desc);
        NotificationChannel notificationChannel =
                new NotificationChannel(SCREENCAST_NOTIFICATION_CHANNEL,
                        name, NotificationManager.IMPORTANCE_LOW);
        notificationChannel.setDescription(description);
        mNotificationManager.createNotificationChannel(notificationChannel);
    }

    @Override
    public void onDestroy() {
        stopCasting();
        unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }

    @Override
    public void onContentWritten(@Nullable String uri) {
        stopForeground(true);
        if (uri != null) {
            sendShareNotification(uri);
        }
    }

    private int startScreencasting(Intent intent) {
        if (hasNoAvailableSpace()) {
            Toast.makeText(this, R.string.screen_insufficient_storage,
                    Toast.LENGTH_LONG).show();
            return START_NOT_STICKY;
        }

        mStartTime = SystemClock.elapsedRealtime();
        mBuilder = createNotificationBuilder();
        mTimer = new Timer();
        mTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateNotification();
            }
        }, 100, 1000);

        Utils.setStatus(getApplicationContext(), Utils.PREF_RECORDING_SCREEN);

        startForeground(NOTIFICATION_ID, mBuilder.build());

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        mAudioSource = Utils.getAudioRecordingSource(this);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data != null) {
            mMediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
            new Thread(this::startRecording).start();
        }
        return START_STICKY;
    }

    private void startRecording() {
        try {
            Log.d(LOGTAG, "Writing video output to: " + mPath.getAbsolutePath());

            // Set initial resources
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager wm = getSystemService(WindowManager.class);
            wm.getDefaultDisplay().getRealMetrics(metrics);
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            mMediaRecorder = new MediaRecorder();

            // Reving up those recorders
            switch (mAudioSource) {
            	case 1:
            		mVideoBufferInfo = new MediaCodec.BufferInfo();
                    mAudioBufferBytes =  AudioRecord.getMinBufferSize(
                        AUDIO_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                    // Preparing video encoder
            		MediaFormat videoFormat = MediaFormat.createVideoFormat("video/avc", screenWidth, screenHeight);
            		videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                		MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            		videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE);
            		videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
            		videoFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, VIDEO_FRAME_RATE);
            		videoFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / VIDEO_FRAME_RATE);
            		videoFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            		videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                    mVideoEncoder = MediaCodec.createEncoderByType("video/avc");
                    mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    // Preparing audio encoder
                    MediaFormat mAudioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", AUDIO_SAMPLE_RATE, TOTAL_NUM_TRACKS);
                    mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                    mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
                    mAudioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                    mAudioEncoder.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    int iMinBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    int bufferSize = SAMPLES_PER_FRAME * VIDEO_FRAME_RATE;
                    if (bufferSize < iMinBufferSize)
                        bufferSize = ((iMinBufferSize / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;
            		mMuxer = new MediaMuxer(mPath.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    // Preparing internal recorder
            		AudioPlaybackCaptureConfiguration internalAudioConfig = 
            		new AudioPlaybackCaptureConfiguration.Builder(mMediaProjection)
		                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
		                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
		                .addMatchingUsage(AudioAttributes.USAGE_GAME)
    					.build();
                    mInternalAudio = new AudioRecord.Builder()
                        .setAudioFormat(
                            new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(AUDIO_SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                        .setAudioPlaybackCaptureConfig(internalAudioConfig)
                        .build();
                    mInputSurface = mVideoEncoder.createInputSurface();
                    break;

	            default:
                    if (mAudioSource == 2) mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
		            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
		            mMediaRecorder.setVideoSize(screenWidth, screenHeight);
		            mMediaRecorder.setVideoFrameRate(VIDEO_FRAME_RATE);
		            mMediaRecorder.setVideoEncodingBitRate(VIDEO_BIT_RATE);
		            if (mAudioSource == 2) {
		                mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		                mMediaRecorder.setAudioChannels(TOTAL_NUM_TRACKS);
		                mMediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
		                mMediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE);
	                }
		            mMediaRecorder.setOutputFile(mPath);
		            mMediaRecorder.prepare();
                    mInputSurface = mMediaRecorder.getSurface();
	                break;
            }

            // Create surface
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "Recording Display",
                    screenWidth,
                    screenHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mInputSurface,
                    null,
                    null);

            // Let's get ready to record now
            switch (mAudioSource) {
                case 1:
                    mVideoRecording = true;
                    // Start the encoders
                    mVideoEncoder.start();
                    new Thread(new VideoEncoderTask(), "VideoEncoderTask").start();
                    mAudioEncoder.start();
                    new Thread(new AudioEncoderTask(), "AudioEncoderTask").start();
                    mInternalAudio.startRecording();
                    mAudioRecording = true;
                    new Thread(new AudioRecorderTask(), "AudioRecorderTask").start();
                    break;

                default:
                    mVideoRecording = true;
                    mMediaRecorder.start();
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private boolean hasNoAvailableSpace() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long bytesAvailable = stat.getBlockSizeLong() * stat.getBlockCountLong();
        long megAvailable = bytesAvailable / 1048576;
        return megAvailable < 100;
    }

    private void updateNotification() {
        long timeElapsed = SystemClock.elapsedRealtime() - mStartTime;
        mBuilder.setContentText(getString(R.string.screen_notification_message,
                DateUtils.formatElapsedTime(timeElapsed / 1000)));
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    private void stopRecording() {
        switch (mAudioSource) {
            case 1:
                mAudioRecording = false;
                mAudioEncoding = false;
                mVideoEncoding = false;
                break;

            default:
                mMediaRecorder.stop();
                mMediaRecorder.release();
                mMediaRecorder = null;
                mMediaProjection.stop();
                mMediaProjection = null;
                mInputSurface.release();
                mVirtualDisplay.release();
                break;
        }
        mVideoRecording = false;
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
        MediaProviderHelper.addVideoToContentProvider(getContentResolver(), mPath, this);
    }

    private void stopCasting() {
        Utils.setStatus(getApplicationContext(), Utils.PREF_RECORDING_NOTHING);
        stopRecording();

        if (hasNoAvailableSpace()) {
            Toast.makeText(this, R.string.screen_not_enough_storage, Toast.LENGTH_LONG).show();
        }
    }

    private NotificationCompat.Builder createNotificationBuilder() {
        Intent intent = new Intent(this, RecorderActivity.class);
        Intent stopRecordingIntent = new Intent(ACTION_STOP_SCREENCAST);
        stopRecordingIntent.setClass(this, ScreencastService.class);

        return new NotificationCompat.Builder(this, SCREENCAST_NOTIFICATION_CHANNEL)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification_screen)
                .setContentTitle(getString(R.string.screen_notification_title))
                .setContentText(getString(R.string.screen_notification_message))
                .setContentIntent(PendingIntent.getActivity(this, 0, intent, 0))
                .addAction(R.drawable.ic_stop, getString(R.string.stop),
                        PendingIntent.getService(this, 0, stopRecordingIntent, 0));
    }

    private void sendShareNotification(String recordingFilePath) {
        mBuilder = createShareNotificationBuilder(recordingFilePath);
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    private NotificationCompat.Builder createShareNotificationBuilder(String uriStr) {
        Uri uri = Uri.parse(uriStr);
        Intent intent = new Intent(this, RecorderActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, 0);

        PendingIntent playPIntent = PendingIntent.getActivity(this, 0,
                LastRecordHelper.getOpenIntent(uri, "video/mp4"),
                PendingIntent.FLAG_CANCEL_CURRENT);
        PendingIntent sharePIntent = PendingIntent.getActivity(this, 0,
                LastRecordHelper.getShareIntent(uri, "video/mp4"),
                PendingIntent.FLAG_CANCEL_CURRENT);
        PendingIntent deletePIntent = PendingIntent.getActivity(this, 0,
                LastRecordHelper.getDeleteIntent(this, false),
                PendingIntent.FLAG_CANCEL_CURRENT);

        long timeElapsed = SystemClock.elapsedRealtime() - mStartTime;
        LastRecordHelper.setLastItem(this, uriStr, timeElapsed, false);

        return new NotificationCompat.Builder(this, SCREENCAST_NOTIFICATION_CHANNEL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.ic_notification_screen)
                .setContentTitle(getString(R.string.screen_notification_message_done))
                .setContentText(getString(R.string.screen_notification_message,
                        DateUtils.formatElapsedTime(timeElapsed / 1000)))
                .addAction(R.drawable.ic_play, getString(R.string.play), playPIntent)
                .addAction(R.drawable.ic_share, getString(R.string.share), sharePIntent)
                .addAction(R.drawable.ic_delete, getString(R.string.delete), deletePIntent)
                .setContentIntent(pi);
    }

    private class AudioRecorderTask implements Runnable {
        ByteBuffer inputBuffer;
        int readResult;

        @Override
        public void run() {
            long audioPresentationTimeNs;
            byte[] mTempBuffer = new byte[SAMPLES_PER_FRAME];
            while (mAudioRecording) {
                audioPresentationTimeNs = System.nanoTime();
                readResult = mInternalAudio.read(mTempBuffer, 0, SAMPLES_PER_FRAME);
                if(readResult == AudioRecord.ERROR_BAD_VALUE || readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                    continue;
                }
                // send current frame data to encoder
                try {
                    synchronized (mAudioEncoderLock) {
                        if (mAudioEncoding) {
                            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
                            if (inputBufferIndex >= 0) {
                                inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex);
                                inputBuffer.clear();
                                inputBuffer.put(mTempBuffer);

                                mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, mTempBuffer.length, audioPresentationTimeNs / 1000, 0);
                            }
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            // finished recording -> send it to the encoder
            audioPresentationTimeNs = System.nanoTime();
            readResult = mInternalAudio.read(mTempBuffer, 0, SAMPLES_PER_FRAME);
            if (readResult == AudioRecord.ERROR_BAD_VALUE
                || readResult == AudioRecord.ERROR_INVALID_OPERATION)
            // send current frame data to encoder
            try {
                synchronized (mAudioEncoderLock) {
                    if (mAudioEncoding) {
                        int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(-1);
                        if (inputBufferIndex >= 0) {
                            inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex);
                            inputBuffer.clear();
                            inputBuffer.put(mTempBuffer);
                            mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, mTempBuffer.length, audioPresentationTimeNs / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
            mInternalAudio.stop();
            mInternalAudio.release();
            mInternalAudio = null;
        }
    }

    // Encoders tasks to do both screen capture and audio recording
    private class VideoEncoderTask implements Runnable {
        private MediaCodec.BufferInfo videoBufferInfo;

        @Override
        public void run(){
            mVideoEncoding = true;
            videoTrackIndex = -1;
            videoBufferInfo = new MediaCodec.BufferInfo();
            while(mVideoEncoding){
                int bufferIndex = mVideoEncoder.dequeueOutputBuffer(videoBufferInfo, 10);
                if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // nothing available yet
                } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // should happen before receiving buffers, and should only happen once
                    if (videoTrackIndex >= 0) {
                        throw new RuntimeException("format changed twice");
                    }
                    synchronized (mMuxerLock) {
                        videoTrackIndex = mMuxer.addTrack(mVideoEncoder.getOutputFormat());

                        if (!mMuxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                            mMuxer.start();
                            mMuxerStarted = true;
                        }
                    }
                } else if (bufferIndex < 0) {
                    // not sure what's going on, ignore it
                } else {
                    ByteBuffer videoData = mVideoEncoder.getOutputBuffer(bufferIndex);
                    if (videoData == null) {
                        throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
                    }
                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        videoBufferInfo.size = 0;
                    }
                    if (videoBufferInfo.size != 0) {
                        if (mMuxerStarted) {
                            videoData.position(videoBufferInfo.offset);
                            videoData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                            synchronized (mWriteVideoLock) {
                                if (mMuxerStarted) {
                                    mMuxer.writeSampleData(videoTrackIndex, videoData, videoBufferInfo);
                                }
                            }
                        } else {
                            // muxer not started
                        }
                    }
                    mVideoEncoder.releaseOutputBuffer(bufferIndex, false);
                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        mVideoEncoding = false;
                        break;
                    }
                }
            }
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;

            if (mInputSurface != null) {
                mInputSurface.release();
                mInputSurface = null;
            }
            if (mMediaProjection != null) {
                mMediaProjection.stop();
                mMediaProjection = null;
            }
            synchronized (mWriteAudioLock) {
                synchronized (mMuxerLock) {
                    if (mMuxer != null) {
                        if (mMuxerStarted) {
                            mMuxer.stop();
                        }
                        mMuxer.release();
                        mMuxer = null;
                        mMuxerStarted = false;
                    }
                }
            }
        }
    }

    private class AudioEncoderTask implements Runnable {
        private MediaCodec.BufferInfo audioBufferInfo;

        @Override
        public void run(){
            mAudioEncoding = true;
            audioTrackIndex = -1;
            audioBufferInfo = new MediaCodec.BufferInfo();
            while(mAudioEncoding){
                int bufferIndex = mAudioEncoder.dequeueOutputBuffer(audioBufferInfo, 10);
                if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // should happen before receiving buffers, and should only happen once
                    if (audioTrackIndex >= 0) {
                        throw new RuntimeException("format changed twice");
                    }
                    synchronized (mMuxerLock) {
                        audioTrackIndex = mMuxer.addTrack(mAudioEncoder.getOutputFormat());

                        if (!mMuxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                            mMuxer.start();
                            mMuxerStarted = true;
                        }
                    }
                } else if (bufferIndex < 0) {
                    // let's ignore it
                } else {
                    if (mMuxerStarted && audioTrackIndex >= 0) {
                        ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(bufferIndex);
                        if (encodedData == null) {
                            throw new RuntimeException("encoderOutputBuffer " + bufferIndex + " was null");
                        }
                        if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // The codec config data was pulled out and fed to the muxer when we got
                            // the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
                            audioBufferInfo.size = 0;
                        }
                        if (audioBufferInfo.size != 0) {
                            if (mMuxerStarted) {
                                // adjust the ByteBuffer values to match BufferInfo (not needed?)
                                encodedData.position(audioBufferInfo.offset);
                                encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size);
                                synchronized (mWriteAudioLock) {
                                    if (mMuxerStarted) {
                                        mMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo);
                                    }
                                }
                            }
                        }
                        mAudioEncoder.releaseOutputBuffer(bufferIndex, false);
                        if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            // reached EOS
                            mAudioEncoding = false;
                            break;
                        }
                    }
                }
            }

            synchronized (mAudioEncoderLock) {
                mAudioEncoder.stop();
                mAudioEncoder.release();
                mAudioEncoder = null;
            }

            synchronized (mWriteVideoLock) {
                synchronized (mMuxerLock) {
                    if (mMuxer != null) {
                        if (mMuxerStarted) {
                            mMuxer.stop();
                        }
                        mMuxer.release();
                        mMuxer = null;
                        mMuxerStarted = false;
                    }
                }
            }
        }
    }
}
