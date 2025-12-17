package com.benkesmith.realtimeaudio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;

@SuppressWarnings("MissingPermission")
public class RealtimeAudio extends CordovaPlugin {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_MS = 20;

    private AudioRecord recorder;
    private Thread audioThread;
    private boolean running = false;

    private OkHttpClient wsClient;
    private WebSocket webSocket;

    private CallbackContext callback;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {

        android.util.Log.d("RealtimeAudio", "execute() called, action=" + action);

        if ("start".equals(action)) {

            // ✅ MUST be first
            this.callback = callbackContext;

            try {
                JSONObject opts = args.getJSONObject(0);
                String apiKey = opts.getString("apiKey");
                String instruction = opts.optString("instruction", null);
                String language = opts.optString("language", null);

                startRecording(apiKey, instruction, language);
                return true;

            } catch (Exception e) {
                callbackContext.error(e.getMessage());
                return true;
            }
        }

        if ("stop".equals(action)) {
            stopRecording();
            return true;
        }

        return false;
    }


    private String escape(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String buildInstructionWrapper(String userInstruction) {

        if (userInstruction == null || userInstruction.isEmpty()) {
            return null;
        }

        return
                "You are a speech information extractor. "
                        + userInstruction + " "
                        + "You MUST return ONLY valid JSON in this exact format: "
                        + "{ \"response\": \"VALUE\" }. "
                        + "If no relevant information can be extracted, return: "
                        + "{ \"response\": \"???\" }. "
                        + "Do not include explanations, comments, or extra text.";
    }

    private void startRecording(String apiKey, String instruction, String language) {
        if (running) return;

        wsClient = new OkHttpClient();

        Request request = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=gpt-realtime-mini")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                sendStatus("{\"type\":\"status\",\"value\":\"ws_open\"}");

                String wrappedInstruction = buildInstructionWrapper(instruction);

                String sessionUpdate =
                        "{"
                                + "\"type\":\"session.update\","
                                + "\"session\":{"
                                +   "\"modalities\":[\"text\"],"
                                +   "\"input_audio_transcription\":{"
                                +     "\"model\":\"gpt-4o-transcribe\"";

                                if (language != null && !language.isEmpty()) {
                                    sessionUpdate += ",\"language\":\"" + escape(language) + "\"";
                                }

                                sessionUpdate +=
                                        "},"
                                + "\"turn_detection\":{"
                                +     "\"type\":\"server_vad\","
                                +     "\"create_response\":false"
                                +   "}";

                if (wrappedInstruction != null) {
                    sessionUpdate += ",\"instructions\":\"" + escape(wrappedInstruction) + "\"";
                }

                sessionUpdate += "}}";

                webSocket.send(sessionUpdate);

                webSocket.send(
                        "{"
                                + "\"type\":\"session.update\","
                                + "\"session\":{"
                                +   "\"modalities\":[\"text\"],"
                                +   "\"input_audio_transcription\":{"
                                +     "\"model\":\"gpt-4o-transcribe\""
                                +   "},"
                                +   "\"turn_detection\":{"
                                +     "\"type\":\"server_vad\","
                                +     "\"create_response\":false"
                                +   "}"
                                + "}"
                                + "}"
                );

            }

            @Override
            public void onMessage(WebSocket ws, String text) {

                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("type");

                    // Forward everything to JS (optional but useful)
                    PluginResult pr = new PluginResult(PluginResult.Status.OK, text);
                    pr.setKeepCallback(true);
                    callback.sendPluginResult(pr);

                    // 🔑 THIS is where response.create belongs
                    if ("conversation.item.input_audio_transcription.completed".equals(type)) {

                        webSocket.send(
                                "{"
                                        + "\"type\":\"response.create\","
                                        + "\"response\":{"
                                        +   "\"modalities\":[\"text\"]"
                                        + "}"
                                        + "}"
                        );
                    }

                } catch (Exception e) {
                    // swallow or log
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                callback.error("WebSocket error: " + t.getMessage());
            }
        });

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
                if (read > 0 && webSocket != null) {

                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);

                    String b64 = Base64.encodeToString(chunk, Base64.NO_WRAP);

                    String json = "{"
                            + "\"type\":\"input_audio_buffer.append\","
                            + "\"audio\":\"" + b64 + "\""
                            + "}";

                    webSocket.send(json);
                }
            }
        });

        audioThread.start();

        if (callback != null) {
            PluginResult pr = new PluginResult(PluginResult.Status.NO_RESULT);
            pr.setKeepCallback(true);
            callback.sendPluginResult(pr);
        }
    }

    private void stopRecording() {
        running = false;

        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
        }

        if (webSocket != null) {
            webSocket.send("{\"type\":\"input_audio_buffer.commit\"}");
            webSocket.close(1000, "normal");
            webSocket = null;
        }
    }

    private void sendStatus(String json) {
        if (callback != null) {
            PluginResult result = new PluginResult(
                    PluginResult.Status.OK,
                    json
            );
            result.setKeepCallback(true);
            callback.sendPluginResult(result);
        }
    }
}
