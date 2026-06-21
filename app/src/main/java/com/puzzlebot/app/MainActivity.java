package com.puzzlebot.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQUEST_MEDIA_PROJECTION = 100;
    private static final int REQUEST_ACCESSIBILITY    = 101;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;

    private Button btnStart, btnStop, btnPermission;
    private TextView tvLog, tvStatus;
    private ScrollView scrollLog;

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager)
            getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        bindViews();
        setupButtons();
        checkPermissionsOnStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();

        // Sambungkan callback jika service sudah aktif
        PuzzleBotService svc = PuzzleBotService.getInstance();
        if (svc != null) {
            PuzzleBotService.setCallback(makeBotCallback());
            updateBotButtons(svc.isRunning());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                addLog("Izin screen capture diberikan ✓");
                doStartBot();
            } else {
                addLog("❌ Izin screen capture ditolak.");
                Toast.makeText(this, "Izin diperlukan untuk menjalankan bot!", Toast.LENGTH_LONG).show();
            }
        }
    }

    // =========================================================================
    //  UI SETUP
    // =========================================================================

    private void bindViews() {
        btnStart      = findViewById(R.id.btnStart);
        btnStop       = findViewById(R.id.btnStop);
        btnPermission = findViewById(R.id.btnPermission);
        tvLog         = findViewById(R.id.tvLog);
        tvStatus      = findViewById(R.id.tvStatus);
        scrollLog     = findViewById(R.id.scrollLog);
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v  -> onStopClicked());
        btnPermission.setOnClickListener(v -> openAccessibilitySettings());
    }

    private void checkPermissionsOnStart() {
        addLog("Selamat datang di Puzzle Bot!");
        addLog("Langkah setup:");
        addLog("1. Aktifkan Accessibility Service (tekan tombol di bawah)");
        addLog("2. Tekan START BOT saat puzzle CAPTCHA muncul");
        updatePermissionStatus();
    }

    private void updatePermissionStatus() {
        boolean hasAccess = isAccessibilityEnabled();
        if (hasAccess) {
            tvStatus.setText("✅ Accessibility Service: AKTIF");
            tvStatus.setTextColor(0xFF2ECC71);
            btnPermission.setText("✓ Accessibility Aktif");
            btnPermission.setEnabled(false);
            btnStart.setEnabled(true);
        } else {
            tvStatus.setText("⚠ Accessibility Service: BELUM AKTIF");
            tvStatus.setTextColor(0xFFE74C3C);
            btnPermission.setText("Aktifkan Accessibility Service");
            btnPermission.setEnabled(true);
            btnStart.setEnabled(false);
        }
    }

    private void updateBotButtons(boolean running) {
        btnStart.setEnabled(!running && isAccessibilityEnabled());
        btnStop.setEnabled(running);
        btnStart.setAlpha(running ? 0.5f : 1.0f);
    }

    // =========================================================================
    //  BUTTON HANDLERS
    // =========================================================================

    private void onStartClicked() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Aktifkan Accessibility Service dulu!", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }

        addLog("─────────────────────────────");
        addLog("Meminta izin screen capture...");

        // Minta izin MediaProjection (screen capture)
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION);
    }

    private void onStopClicked() {
        PuzzleBotService svc = PuzzleBotService.getInstance();
        if (svc != null) {
            svc.stopBot();
        }
        updateBotButtons(false);
        addLog("Bot dihentikan oleh pengguna.");
    }

    private void openAccessibilitySettings() {
        addLog("Membuka pengaturan Accessibility...");
        addLog("Cari 'Puzzle Bot' dan aktifkan.");
        startActivityForResult(
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            REQUEST_ACCESSIBILITY
        );
    }

    // =========================================================================
    //  BOT START
    // =========================================================================

    private void doStartBot() {
        PuzzleBotService svc = PuzzleBotService.getInstance();
        if (svc == null) {
            addLog("❌ Service tidak aktif. Pastikan Accessibility Service sudah diaktifkan.");
            Toast.makeText(this, "Aktifkan Accessibility Service!", Toast.LENGTH_LONG).show();
            return;
        }

        PuzzleBotService.setCallback(makeBotCallback());
        svc.startBot(mediaProjection);
        updateBotButtons(true);
        addLog("🤖 Bot dimulai! Menunggu puzzle CAPTCHA...");
    }

    // =========================================================================
    //  BOT CALLBACK
    // =========================================================================

    private PuzzleBotService.BotCallback makeBotCallback() {
        return new PuzzleBotService.BotCallback() {
            @Override
            public void onLog(String message) {
                runOnUiThread(() -> addLog(message));
            }

            @Override
            public void onStatusChange(boolean running) {
                runOnUiThread(() -> {
                    updateBotButtons(running);
                    if (!running) addLog("── Bot berhenti ──");
                });
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    addLog("🎉 PUZZLE BERHASIL DISELESAIKAN!");
                    Toast.makeText(MainActivity.this,
                        "Puzzle selesai!", Toast.LENGTH_SHORT).show();
                    updateBotButtons(false);
                });
            }
        };
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private boolean isAccessibilityEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager)
                getSystemService(Context.ACCESSIBILITY_SERVICE);

            // Cek via Settings string untuk service spesifik ini
            String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );

            String packageName = getPackageName();
            return enabledServices != null &&
                   enabledServices.contains(packageName + "/" + packageName + ".PuzzleBotService");

        } catch (Exception e) {
            return false;
        }
    }

    private void addLog(String message) {
        String current = tvLog.getText().toString();
        String newText = current + "\n" + message;

        // Batasi log agar tidak terlalu panjang
        String[] lines = newText.split("\n");
        if (lines.length > 80) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 80; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            newText = sb.toString();
        }

        tvLog.setText(newText);

        // Auto scroll ke bawah
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
