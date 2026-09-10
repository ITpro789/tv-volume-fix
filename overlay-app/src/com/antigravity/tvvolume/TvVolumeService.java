package com.antigravity.tvvolume;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class TvVolumeService extends Service {
    private static final String TAG = "TvVolumeOverlay";
    private static final String CHANNEL_ID = "tv_volume_service_channel";
    private static final int PORT = 49200;

    private WindowManager mWindowManager;
    private LinearLayout mRootView;
    private TextView mTitleText;
    private TextView mVolumeNumberText;
    private ProgressBar mProgressBar;

    private Handler mHandler;
    private Runnable mHideRunnable;
    private ServerSocket mServerSocket;
    private Thread mServerThread;
    private boolean mRunning = true;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creating TvVolumeService...");

        mHandler = new Handler(Looper.getMainLooper());
        mHideRunnable = new Runnable() {
            @Override
            public void run() {
                if (mRootView != null) {
                    mRootView.animate()
                            .alpha(0f)
                            .setDuration(250)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    mRootView.setVisibility(View.GONE);
                                }
                            })
                            .start();
                }
            }
        };

        createNotificationChannel();
        startForeground(101, buildNotification());

        initOverlayView();
        registerVolumeBroadcastReceiver();
        startSocketServer();
    }

    private void registerVolumeBroadcastReceiver() {
        android.content.IntentFilter filter = new android.content.IntentFilter("com.antigravity.tvvolume.UPDATE_VOLUME");
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null) {
                    int vol = intent.getIntExtra("volume", -1);
                    boolean mute = intent.getBooleanExtra("mute", false);
                    if (vol >= 0 || mute) {
                        updateVolume(vol, mute);
                    }
                }
            }
        }, filter);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Volume Overlay Service",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Shows on-screen volume overlay");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setContentTitle("TV Volume Overlay")
                .setContentText("Active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void initOverlayView() {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Root container: Dark rounded card (compact, half size)
        mRootView = new LinearLayout(this);
        mRootView.setOrientation(LinearLayout.VERTICAL);
        mRootView.setGravity(Gravity.CENTER_HORIZONTAL);
        mRootView.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EE181818")); // 93% black/charcoal
        bg.setCornerRadius(dpToPx(12));
        bg.setStroke(dpToPx(1), Color.parseColor("#33FFFFFF")); // Subtle light glass border
        mRootView.setBackground(bg);

        // Row with Speaker icon and Volume Number
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        mTitleText = new TextView(this);
        mTitleText.setText("VOL  ");
        mTitleText.setTextColor(Color.parseColor("#AAFFFFFF")); // Subtle white
        mTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mTitleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(mTitleText);

        mVolumeNumberText = new TextView(this);
        mVolumeNumberText.setText("00");
        mVolumeNumberText.setTextColor(Color.parseColor("#FFFFFF")); // Bright white
        mVolumeNumberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        mVolumeNumberText.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        row.addView(mVolumeNumberText);

        mRootView.addView(row);

        // Progress bar below numbers
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                dpToPx(100), dpToPx(4));
        pbParams.topMargin = dpToPx(6);
        mProgressBar.setLayoutParams(pbParams);
        mProgressBar.setMax(100);
        mProgressBar.setProgress(0);

        mProgressBar.setProgressDrawable(createProgressDrawable());
        mRootView.addView(mProgressBar);

        // Layout parameters: Tucked tightly in Top-Right corner
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dpToPx(16); // 16dp from right edge
        params.y = dpToPx(16); // 16dp from top edge

        mRootView.setVisibility(View.GONE);
        mRootView.setAlpha(0f);

        try {
            mWindowManager.addView(mRootView, params);
            Log.i(TAG, "Overlay view added to WindowManager successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlay view: " + e.getMessage(), e);
        }
    }

    private android.graphics.drawable.Drawable createProgressDrawable() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#33FFFFFF"));
        bg.setCornerRadius(dpToPx(2));

        GradientDrawable progress = new GradientDrawable();
        progress.setColor(Color.parseColor("#00E5FF")); // Vibrant cyan accent
        progress.setCornerRadius(dpToPx(2));

        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                progress, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);

        android.graphics.drawable.Drawable[] layers = new android.graphics.drawable.Drawable[]{bg, clip};
        android.graphics.drawable.LayerDrawable layerDrawable = new android.graphics.drawable.LayerDrawable(layers);
        layerDrawable.setId(0, android.R.id.background);
        layerDrawable.setId(1, android.R.id.progress);
        return layerDrawable;
    }

    public void updateVolume(final int volume, final boolean isMute) {
        Log.i(TAG, "updateVolume: vol=" + volume + ", mute=" + isMute);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mRootView == null) return;

                if (isMute) {
                    mTitleText.setText("MUTE  ");
                    mVolumeNumberText.setText("--");
                    mProgressBar.setProgress(0);
                } else {
                    mTitleText.setText("VOL  ");
                    mVolumeNumberText.setText(String.format("%02d", volume));
                    mProgressBar.setProgress(Math.max(0, Math.min(100, volume)));
                }

                mHandler.removeCallbacks(mHideRunnable);

                if (mRootView.getVisibility() != View.VISIBLE) {
                    mRootView.setVisibility(View.VISIBLE);
                }
                mRootView.animate().alpha(1.0f).setDuration(120).start();

                // Stay visible for 2.0 seconds after last press, then smoothly fade out
                mHandler.postDelayed(mHideRunnable, 2000);
            }
        });
    }

    private void startSocketServer() {
        mServerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerSocket = new ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"));
                    Log.i(TAG, "Socket server listening on 127.0.0.1:" + PORT);

                    while (mRunning && !mServerSocket.isClosed()) {
                        Socket socket = mServerSocket.accept();
                        handleClient(socket);
                    }
                } catch (Exception e) {
                    if (mRunning) {
                        Log.e(TAG, "Socket server error: " + e.getMessage(), e);
                    }
                }
            }
        });
        mServerThread.setDaemon(true);
        mServerThread.start();
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Expected format: "61" or "MUTE" or "VOL:61"
                if ("MUTE".equalsIgnoreCase(line)) {
                    updateVolume(0, true);
                } else {
                    if (line.startsWith("VOL:")) {
                        line = line.substring(4).trim();
                    }
                    try {
                        int vol = Integer.parseInt(line);
                        updateVolume(vol, false);
                    } catch (NumberFormatException ignored) {}
                }
            }
            socket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error handling client connection: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("volume")) {
            int vol = intent.getIntExtra("volume", 0);
            boolean mute = intent.getBooleanExtra("mute", false);
            updateVolume(vol, mute);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        try {
            if (mServerSocket != null) mServerSocket.close();
        } catch (Exception ignored) {}

        if (mRootView != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mRootView);
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
