package com.puzzlebot.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.nio.ByteBuffer;

public class PuzzleBotService extends AccessibilityService {

    private static final String TAG = "PuzzleBotService";

    // ── State ────────────────────────────────────────────────────────────────
    private static PuzzleBotService instance;
    private boolean isRunning = false;
    private Handler mainHandler;

    // ── Screen Capture ───────────────────────────────────────────────────────
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    // ── Screen Size ──────────────────────────────────────────────────────────
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    // ── Callback ke UI ───────────────────────────────────────────────────────
    public interface BotCallback {
        void onLog(String message);
        void onStatusChange(boolean running);
        void onSuccess();
    }
    private static BotCallback callback;

    // =========================================================================
    //  LIFECYCLE
    // =========================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());

        // Ambil ukuran layar
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        screenWidth   = metrics.widthPixels;
        screenHeight  = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        log("Service aktif. Layar: " + screenWidth + "x" + screenHeight);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopBot();
        instance = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Tidak digunakan — bot dijalankan manual via tombol
    }

    @Override
    public void onInterrupt() {
        stopBot();
    }

    // =========================================================================
    //  PUBLIC API — dipanggil dari MainActivity
    // =========================================================================

    public static PuzzleBotService getInstance() {
        return instance;
    }

    public static void setCallback(BotCallback cb) {
        callback = cb;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /** Mulai bot dengan MediaProjection token dari MainActivity */
    public void startBot(MediaProjection projection) {
        if (isRunning) return;
        this.mediaProjection = projection;
        isRunning = true;

        if (callback != null) callback.onStatusChange(true);
        log("Bot dimulai. Menginisialisasi capture layar...");

        setupImageReader();
        runBotLoop();
    }

    public void stopBot() {
        isRunning = false;
        releaseCapture();
        if (callback != null) callback.onStatusChange(false);
        log("Bot dihentikan.");
    }

    // =========================================================================
    //  SCREEN CAPTURE SETUP
    // =========================================================================

    private void setupImageReader() {
        try {
            // ImageReader untuk menangkap frame layar
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888,
                2  // maxImages buffer
            );

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "PuzzleBotCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, null
            );

            log("Screen capture siap.");
        } catch (Exception e) {
            log("ERROR setup capture: " + e.getMessage());
        }
    }

    private void releaseCapture() {
        try {
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader    != null) { imageReader.close();      imageReader    = null; }
            if (mediaProjection!= null) { mediaProjection.stop();   mediaProjection= null; }
        } catch (Exception e) {
            Log.e(TAG, "releaseCapture error", e);
        }
    }

    // =========================================================================
    //  BOT LOOP — berjalan di background thread
    // =========================================================================

    private void runBotLoop() {
        new Thread(() -> {
            log("Loop bot berjalan. Menunggu puzzle muncul...");

            // Tunggu sebentar agar ImageReader siap
            sleep(500);

            int attempts = 0;
            int maxAttempts = 20;

            while (isRunning && attempts < maxAttempts) {
                attempts++;
                log("── Percobaan #" + attempts + " ──");

                try {
                    // ── 1. Ambil screenshot ──────────────────────────────
                    Bitmap screen = captureScreen();
                    if (screen == null) {
                        log("Screenshot gagal, coba lagi...");
                        sleep(300);
                        continue;
                    }

                    int w = screen.getWidth();
                    int h = screen.getHeight();

                    // ── 2. Deteksi puzzle (cari slider button oranye) ────
                    int[] sliderPos = findOrangeSlider(screen, w, h);
                    if (sliderPos == null) {
                        log("Slider oranye tidak ditemukan. Menunggu puzzle...");
                        screen.recycle();
                        sleep(300);
                        continue;
                    }

                    int sliderX = sliderPos[0];
                    int sliderY = sliderPos[1];
                    log("Slider ditemukan di: (" + sliderX + ", " + sliderY + ")");

                    // ── 3. Deteksi posisi lubang ─────────────────────────
                    int[] holePos = findHolePosition(screen, w, h);
                    if (holePos == null) {
                        log("Lubang tidak terdeteksi.");
                        screen.recycle();
                        sleep(300);
                        continue;
                    }

                    int holeX = holePos[0];
                    int holeY = holePos[1];
                    log("Lubang terdeteksi di X=" + holeX);

                    screen.recycle();

                    // ── 4. Hitung target X slider ────────────────────────
                    // Slider perlu digeser sejauh holeX - posisi piece awal
                    // Piece awal biasanya di kiri gambar puzzle
                    int targetSliderX = holeX;

                    log("Menggeser slider: " + sliderX + " → " + targetSliderX);

                    // ── 5. Eksekusi swipe INSTAN ─────────────────────────
                    performSwipe(sliderX, sliderY, targetSliderX, sliderY);

                    log("✅ Swipe selesai!");

                    // Tunggu sebentar lalu cek hasilnya
                    sleep(500);

                    // Cek apakah puzzle sudah selesai (tidak ada slider lagi)
                    Bitmap checkScreen = captureScreen();
                    if (checkScreen != null) {
                        int[] checkSlider = findOrangeSlider(checkScreen, checkScreen.getWidth(), checkScreen.getHeight());
                        checkScreen.recycle();

                        if (checkSlider == null) {
                            log("🎉 Puzzle berhasil diselesaikan!");
                            if (callback != null) {
                                mainHandler.post(() -> callback.onSuccess());
                            }
                            isRunning = false;
                            break;
                        }
                    }

                } catch (Exception e) {
                    log("Error: " + e.getMessage());
                    sleep(300);
                }
            }

            if (attempts >= maxAttempts) {
                log("⚠ Batas percobaan tercapai.");
            }

            isRunning = false;
            if (callback != null) {
                mainHandler.post(() -> callback.onStatusChange(false));
            }

        }, "BotLoopThread").start();
    }

    // =========================================================================
    //  DETEKSI GAMBAR — tanpa OpenCV, pakai pixel analysis (cepat ~20-50ms)
    // =========================================================================

    /**
     * Cari tombol slider oranye di layar.
     * Strategi: scan piksel untuk menemukan area warna oranye dominan (#FF5722 / #FF6D00).
     *
     * Return: [centerX, centerY] tombol slider, atau null jika tidak ada.
     */
    private int[] findOrangeSlider(Bitmap bmp, int w, int h) {
        // Scan di bagian bawah layar (slider biasanya di 60-90% tinggi layar)
        int scanTop    = (int)(h * 0.60);
        int scanBottom = (int)(h * 0.95);
        int scanStep   = 4; // Scan setiap 4 piksel untuk kecepatan

        int bestX = -1, bestY = -1;
        int bestCount = 0;

        // Geser window 60x60 mencari cluster oranye terbesar
        int windowSize = 60;

        for (int y = scanTop; y < scanBottom - windowSize; y += scanStep) {
            for (int x = 0; x < w - windowSize; x += scanStep) {
                int count = countOrangePixels(bmp, x, y, windowSize, windowSize);
                if (count > bestCount) {
                    bestCount = count;
                    bestX = x + windowSize / 2;
                    bestY = y + windowSize / 2;
                }
            }
        }

        // Minimum threshold: setidaknya 30% area window harus oranye
        int minCount = (windowSize / scanStep) * (windowSize / scanStep) * 30 / 100;

        if (bestCount < minCount) return null;
        return new int[]{bestX, bestY};
    }

    /**
     * Hitung jumlah piksel oranye dalam area tertentu.
     */
    private int countOrangePixels(Bitmap bmp, int startX, int startY, int width, int height) {
        int count = 0;
        int step = 3;
        for (int y = startY; y < startY + height; y += step) {
            for (int x = startX; x < startX + width; x += step) {
                if (x < bmp.getWidth() && y < bmp.getHeight()) {
                    int pixel = bmp.getPixel(x, y);
                    if (isOrangePixel(pixel)) count++;
                }
            }
        }
        return count;
    }

    /**
     * Apakah piksel ini warna oranye (slider button)?
     * Oranye: R tinggi, G sedang, B rendah.
     */
    private boolean isOrangePixel(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8)  & 0xFF;
        int b =  pixel        & 0xFF;
        return (r > 180) && (g > 60 && g < 160) && (b < 80);
    }

    /**
     * Deteksi posisi lubang puzzle.
     *
     * Strategi: Lubang puzzle adalah area GELAP / berbeda warna di tengah
     * gambar yang bentuknya seperti puzzle piece outline.
     *
     * Kita scan area gambar (bagian atas layar) untuk menemukan zona
     * yang memiliki edge density tinggi (kontur puzzle piece) dengan
     * area gelap di sekitarnya.
     *
     * Return: [centerX, centerY] lubang, atau null jika tidak ditemukan.
     */
    private int[] findHolePosition(Bitmap bmp, int w, int h) {
        // Puzzle biasanya di bagian atas-tengah layar (20-60% tinggi)
        int puzzleTop    = (int)(h * 0.15);
        int puzzleBottom = (int)(h * 0.60);

        // Piece awal biasanya di 0-20% lebar layar (sudut kiri)
        // Kita skip area ini dan cari lubang di sisanya
        int searchFromX  = (int)(w * 0.20);

        int bestX = -1, bestY = -1;
        double bestScore = 0;

        int windowW = (int)(w * 0.12); // ~12% lebar layar per window
        int windowH = (int)(h * 0.15); // ~15% tinggi layar per window
        int step = 8;

        for (int y = puzzleTop; y < puzzleBottom - windowH; y += step) {
            for (int x = searchFromX; x < w - windowW; x += step) {
                double score = calcHoleScore(bmp, x, y, windowW, windowH);
                if (score > bestScore) {
                    bestScore = score;
                    bestX = x + windowW / 2;
                    bestY = y + windowH / 2;
                }
            }
        }

        if (bestScore < 0.15 || bestX < 0) return null;
        return new int[]{bestX, bestY};
    }

    /**
     * Hitung "skor lubang" untuk sebuah window area.
     * Lubang puzzle ciri-cirinya:
     * - Area lebih gelap dari sekitarnya (shadow/overlay)
     * - Memiliki variasi warna yang rendah (uniform dark area)
     * - Dikelilingi tepi gambar yang kontras
     */
    private double calcHoleScore(Bitmap bmp, int startX, int startY, int width, int height) {
        long totalDark = 0;
        long totalPixels = 0;
        int step = 4;

        for (int y = startY; y < startY + height; y += step) {
            for (int x = startX; x < startX + width; x += step) {
                if (x >= bmp.getWidth() || y >= bmp.getHeight()) continue;
                int pixel = bmp.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8)  & 0xFF;
                int b =  pixel        & 0xFF;
                int brightness = (r + g + b) / 3;

                // Piksel gelap (brightness < 100) atau abu-abu transparan
                if (brightness < 100 || isGrayish(r, g, b)) {
                    totalDark++;
                }
                totalPixels++;
            }
        }

        if (totalPixels == 0) return 0;
        return (double) totalDark / totalPixels;
    }

    private boolean isGrayish(int r, int g, int b) {
        int avg = (r + g + b) / 3;
        int maxDiff = Math.max(Math.abs(r - avg), Math.max(Math.abs(g - avg), Math.abs(b - avg)));
        return maxDiff < 20 && avg > 80 && avg < 180; // abu-abu medium
    }

    // =========================================================================
    //  CAPTURE SCREEN
    // =========================================================================

    private Bitmap captureScreen() {
        if (imageReader == null) return null;
        try {
            Image image = imageReader.acquireLatestImage();
            if (image == null) {
                sleep(100);
                image = imageReader.acquireLatestImage();
                if (image == null) return null;
            }

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride  = planes[0].getPixelStride();
            int rowStride    = planes[0].getRowStride();
            int rowPadding   = rowStride - pixelStride * screenWidth;

            Bitmap bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            );
            bmp.copyPixelsFromBuffer(buffer);
            image.close();

            // Crop ke ukuran layar tepat jika ada padding
            if (bmp.getWidth() != screenWidth) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight);
                bmp.recycle();
                return cropped;
            }
            return bmp;

        } catch (Exception e) {
            Log.e(TAG, "captureScreen error", e);
            return null;
        }
    }

    // =========================================================================
    //  GESTURE — Swipe via AccessibilityService (NO ROOT NEEDED)
    // =========================================================================

    /**
     * Eksekusi swipe menggunakan AccessibilityService.dispatchGesture().
     * Ini adalah cara resmi Android tanpa root.
     * duration=1ms → praktis instan.
     */
    private void performSwipe(int x1, int y1, int x2, int y2) {
        try {
            Path swipePath = new Path();
            swipePath.moveTo(x1, y1);
            swipePath.lineTo(x2, y2);

            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                    swipePath,
                    0,    // startTime (ms)
                    1     // duration (ms) → instan
                );

            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    log("Gesture selesai dieksekusi.");
                }
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    log("⚠ Gesture dibatalkan sistem.");
                }
            }, mainHandler);

        } catch (Exception e) {
            log("performSwipe error: " + e.getMessage());
        }
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private void log(String msg) {
        Log.d(TAG, msg);
        if (callback != null) {
            mainHandler.post(() -> callback.onLog(msg));
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
