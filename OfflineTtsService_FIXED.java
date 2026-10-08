package com.leyhqalha.leyh;

import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline Kareem Medium TTS.
 * Runs in a separate Android process so a native Sherpa failure cannot close
 * the main WebView process.
 */
public final class OfflineTtsService extends Service {
    private static final String ASSET_BASE = "tts-model/vits-piper-ar_JO-kareem-medium";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OfflineTts tts;
    private MediaPlayer player;
    private volatile int activeRequest = 0;

    @Override public void onCreate() {
        super.onCreate();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String command = intent.getStringExtra(OfflineTtsBridge.EXTRA_COMMAND);
        int req = intent.getIntExtra(OfflineTtsBridge.EXTRA_REQUEST, 0);

        if ("stop".equals(command)) {
            activeRequest = req;
            stopPlayback();
            return START_NOT_STICKY;
        }
        if ("pause".equals(command)) {
            if (player != null) { try { player.pause(); } catch (Throwable ignored) {} }
            return START_NOT_STICKY;
        }
        if ("resume".equals(command)) {
            if (player != null) { try { player.start(); } catch (Throwable ignored) {} }
            return START_NOT_STICKY;
        }
        if ("speak".equals(command)) {
            String text = intent.getStringExtra(OfflineTtsBridge.EXTRA_TEXT);
            float speed = intent.getFloatExtra(OfflineTtsBridge.EXTRA_SPEED, 0.9f);
            activeRequest = req;
            stopPlayback();
            executor.execute(() -> generateAndPlay(text, speed, req));
        }
        return START_NOT_STICKY;
    }

    private void generateAndPlay(String text, float speed, int req) {
        try {
            if (text == null || text.trim().isEmpty()) throw new Exception("النص فارغ");
            OfflineTts engine = getTts();
            GeneratedAudio audio = engine.generate(text, 0,
                Math.max(0.7f, Math.min(1.25f, speed)));
            if (req != activeRequest) return;

            File out = new File(getCacheDir(), "leyh_kareem_" + req + ".wav");
            if (!audio.save(out.getAbsolutePath())) {
                throw new Exception("تعذر إنشاء ملف صوت كريم");
            }
            play(out, req);
        } catch (Throwable e) {
            if (req == activeRequest) {
                sendError(req, e.getMessage() == null ? "تعذر تشغيل كريم" : e.getMessage());
            }
        }
    }

    private synchronized OfflineTts getTts() throws Exception {
        if (tts != null) return tts;

        // The Android AAR constructor uses AssetManager, so the model paths
        // must be paths inside APK assets, not filesystem paths.
        String modelPath = ASSET_BASE + "/ar_JO-kareem-medium.onnx";
        String tokensPath = ASSET_BASE + "/tokens.txt";
        // Sherpa's Android engine copies espeak-ng-data from APK assets to a
        // real filesystem directory before creating OfflineTts. The native
        // VITS config validates phontab/phonindex/phondata as real files.
        File modelDataDir = new File(getExternalFilesDir(null), ASSET_BASE + "/espeak-ng-data");
        copyAssetTree(ASSET_BASE + "/espeak-ng-data", modelDataDir);
        if (!new File(modelDataDir, "phontab").isFile()
                || !new File(modelDataDir, "phonindex").isFile()
                || !new File(modelDataDir, "phondata").isFile()) {
            throw new IOException("بيانات espeak-ng-data غير مكتملة");
        }
        String dataDir = modelDataDir.getAbsolutePath();

        // Use the Java setter API exposed by the Android AAR.
        // This avoids the Builder API mismatch that caused the v38 compile failure.
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(modelPath);
        vits.setTokens(tokensPath);
        vits.setDataDir(dataDir);

        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(1);
        model.setDebug(false);
        model.setProvider("cpu");

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setMaxNumSentences(1);

        tts = new OfflineTts(getAssets(), config);
        return tts;
    }

    private void copyAssetTree(String assetPath, File target) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            target.getParentFile().mkdirs();
            if (!target.exists() || target.length() == 0) {
                try (InputStream in = getAssets().open(assetPath);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
            return;
        }

        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("تعذر إنشاء مجلد نموذج كريم");
        }
        for (String child : children) {
            copyAssetTree(assetPath + "/" + child, new File(target, child));
        }
    }

    private void play(File file, int req) {
        mainHandler.post(() -> {
            if (req != activeRequest) return;
            stopPlayback();
            try {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
                player.setDataSource(file.getAbsolutePath());
                player.setOnCompletionListener(mp -> {
                    try { mp.release(); } catch (Throwable ignored) {}
                    player = null;
                    sendDone(req);
                    try { file.delete(); } catch (Throwable ignored) {}
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    try { mp.release(); } catch (Throwable ignored) {}
                    player = null;
                    sendError(req, "تعذر تشغيل ملف كريم الصوتي");
                    try { file.delete(); } catch (Throwable ignored) {}
                    return true;
                });
                player.prepare();
                player.start();
            } catch (Throwable e) {
                if (player != null) { try { player.release(); } catch (Throwable ignored) {} player = null; }
                sendError(req, e.getMessage() == null ? "تعذر تشغيل الصوت" : e.getMessage());
                try { file.delete(); } catch (Throwable ignored) {}
            }
        });
    }

    private synchronized void stopPlayback() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    private void sendDone(int req) {
        Intent i = new Intent(OfflineTtsBridge.ACTION_DONE).setPackage(getPackageName());
        i.putExtra(OfflineTtsBridge.EXTRA_REQUEST, req);
        sendBroadcast(i);
    }

    private void sendError(int req, String message) {
        Intent i = new Intent(OfflineTtsBridge.ACTION_ERROR).setPackage(getPackageName());
        i.putExtra(OfflineTtsBridge.EXTRA_REQUEST, req);
        i.putExtra(OfflineTtsBridge.EXTRA_MESSAGE, message);
        sendBroadcast(i);
    }

    @Override public void onDestroy() {
        stopPlayback();
        executor.shutdownNow();
        try { if (tts != null) tts.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
