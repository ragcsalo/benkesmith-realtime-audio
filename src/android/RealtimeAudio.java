package com.benkesmith.realtimeaudio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.apache.cordova.*;
import org.json.JSONArray;

import android.util.Base64;

public class RealtimeAudio extends CordovaPlugin {

    private AudioRecord recorder;
    private Thread audioThread;
    private boolean running = false;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_MS = 20;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callback) {
        if ("start".equals(action)) {
            startRecording(callback);
            return true;
        }
        if ("stop".equals(action)) {
            stopRecording();
            return true;
        }
        return false;
    }

    private void startRecording(CallbackContext callback) {
        if (running) return;

        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        recorder.startRecording();
        running = true;

        int bytesPerChunk = SAMPLE_RATE * 2 * CHUNK_MS / 1000;
        byte[] buffer = new byte[bytesPerChunk];

        audioThread = new Thread(() -> {
            while (running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    byte[] chunk = new byte[read];
					System.arraycopy(buffer, 0, chunk, 0, read);
					String b64 = Base64.encodeToString(chunk, Base64.NO_WRAP);

                    PluginResult result = new PluginResult(
                            PluginResult.Status.OK,
                            b64
                    );
                    result.setKeepCallback(true);
                    callback.sendPluginResult(result);
                }
            }
        });

        audioThread.start();

        PluginResult ready = new PluginResult(PluginResult.Status.NO_RESULT);
        ready.setKeepCallback(true);
        callback.sendPluginResult(ready);
    }

    private void stopRecording() {
        running = false;
        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
        }
    }
}
