package com.limelight.binding.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private AudioTrack track;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    public static int MoonAudioSessionID;
    public static long MoonAudioStartTS;

    @SuppressLint("NewApi")
    public int createMoonAudioSessionID(){
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        MoonAudioSessionID = audioManager.generateAudioSessionId();
        MoonAudioStartTS = System.nanoTime();

        return MoonAudioSessionID;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        createMoonAudioSessionID();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return new AudioTrack(AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM);
        }
        else {
            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME);
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build();

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // Use FLAG_LOW_LATENCY on L through N
                if (lowLatency) {
                    attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                LimeLog.info("Audio track format: "+format);
                LimeLog.info("Audio buffer size: "+bufferSize);
                LimeLog.info("Audio track attributes: "+attributesBuilder.build());
                attributesBuilder.setFlags(AudioAttributes.FLAG_HW_AV_SYNC)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE) // this may not be necessary
                        .setUsage(AudioAttributes.USAGE_GAME);
                AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                        .setAudioFormat(format)
                        .setAudioAttributes(attributesBuilder.build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferSize)
                        .setSessionId(MoonAudioSessionID);

                // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
                if (lowLatency) {
                    trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                }

                return trackBuilder.build();
            }
            else {
                return new AudioTrack(attributesBuilder.build(),
                        format,
                        bufferSize,
                        AudioTrack.MODE_STREAM,
                        MoonAudioSessionID);
            }
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                    // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                    // in 5.0, so just hardcode the constant so we can work on Lollipop.
                    channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                }
                else {
                    // On KitKat and lower, creation of the AudioTrack will fail if we specify
                    // CHANNEL_OUT_SIDE_LEFT or CHANNEL_OUT_SIDE_RIGHT. That leaves us with
                    // the old CHANNEL_OUT_7POINT1 which uses left-of-center and right-of-center
                    // speakers instead of side-left and side-right. This non-standard layout
                    // is probably not what the user wants, but we don't really have a choice.
                    channelConfig = AudioFormat.CHANNEL_OUT_7POINT1;
                }
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                LimeLog.info("Actual Audio sessionid:      "+track.getAudioSessionId());
                LimeLog.info("MoonAudioSessionID variable: "+MoonAudioSessionID);

                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        return 0;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Convert short array to byte array (assuming 16-bit PCM)
        ByteBuffer byteBuffer = ByteBuffer.allocate(audioData.length * 2);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : audioData) {
            byteBuffer.putShort(sample);
        }
        byteBuffer.flip();

        // Write the audio data to the AudioTrack with timestamp
        track.write(byteBuffer, byteBuffer.remaining(), AudioTrack.WRITE_NON_BLOCKING, System.nanoTime() - MoonAudioStartTS);

        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack is buffering for us.
        //if (MoonBridge.getPendingAudioDuration() < 40) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to be able to bound
            // latency at 40 ms in that situation.
            //track.write(audioData, 0, audioData.length);
        //}
        //else {
            //LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
        //}
    }

    @Override
    public void start() {
        if (enableAudioFx) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
    }
}
