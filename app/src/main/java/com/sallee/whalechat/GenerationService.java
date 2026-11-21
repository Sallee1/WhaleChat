package com.sallee.whalechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * 回复生成期间的前台服务。
 *
 * <p>chat-demo 是直接在页面里向 api.deepseek.com 发起流式请求的，App 一进后台，
 * Android 就会把进程降级：轻则限制网络 / 冻结，重则被 OEM 直接回收，流式连接
 * 随之断掉。这里在「有请求在飞」的这段时间里跑一个 dataSync 前台服务，把进程维持
 * 在前台级别，并持一个 CPU 唤醒锁，让屏幕熄灭时读取流也不会被挂起。
 *
 * <p>页面侧由 whalechat/ui.js 里的 fetch 钩子负责 begin/end；
 * 15 分钟没收到结束信号就自动收掉，避免通知栏残留。
 */
public class GenerationService extends Service {

    private static final String CHANNEL_ID = "generation";
    private static final int NOTIFICATION_ID = 1001;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long WATCHDOG_TIMEOUT_MS = 15 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = this::stopSelf;
    private PowerManager.WakeLock wakeLock;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, GenerationService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, GenerationService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhaleChat:generation");
            wakeLock.setReferenceCounted(false);
            try {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            } catch (RuntimeException ignored) {
            }
        }
        handler.postDelayed(watchdog, WATCHDOG_TIMEOUT_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 没有 WebView 就没有生成，系统重启服务没有意义
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.generation_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.generation_channel_description));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_generation)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.generation_notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }
}
