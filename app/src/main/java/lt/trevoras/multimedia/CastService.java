package lt.trevoras.multimedia;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * TFT mirror servisas. UI paliekamas Trevoro, o video kelias atkurtas
 * pagal originalios com.deepwei.electricbicycle / Duowei programėlės elgesį.
 */
public class CastService extends Service {

    private static final String CHANNEL = "trevoras_cast";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int MAX_CHUNK = 65500;

    private MediaProjection projection;
    private ScreenMirrorCast mirrorCast;
    private volatile boolean running;

    private final MediaProjection.Callback projectionCallback =
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopProjection();
                    stopSelf();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL,
                    "TREVORAS projekcija",
                    NotificationManager.IMPORTANCE_LOW
            ));
        }

        Notification notification;
        if (Build.VERSION.SDK_INT >= 26) {
            notification = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("TREVORAS")
                    .setContentText("Ekrano projekcija aktyvi")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("TREVORAS")
                    .setContentText("Ekrano projekcija aktyvi")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .build();
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(42, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(42, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");

        if (resultCode != Activity.RESULT_OK || data == null ||
                !TrevorasWssClient.isConnected()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startProjection(resultCode, data);
        } catch (Throwable t) {
            stopProjection();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent data) throws Exception {
        stopProjection();

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("MediaProjectionManager unavailable");
        }

        projection = manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            throw new IllegalStateException("MediaProjection unavailable");
        }

        // Android 14+ reikalauja callback prieš VirtualDisplay kūrimą.
        projection.registerCallback(
                projectionCallback,
                new Handler(Looper.getMainLooper())
        );

        running = true;

        mirrorCast = new ScreenMirrorCast(WIDTH, HEIGHT, new CastBase.Callback() {
            @Override
            public void onFrame(byte[] frame) {
                if (running) {
                    sendFrameLikeOriginal(frame);
                }
            }

            @Override
            public void onError(Throwable error) {
                stopSelf();
            }
        });

        mirrorCast.startMirrorCast(projection);
    }

    /**
     * Originalo UDP fragmentavimo schema:
     * pirmas fragmentas neša viso frame dydį 24 bitais,
     * kiti fragmentai - savo payload dydį ir fragmento indeksą.
     */
    private void sendFrameLikeOriginal(byte[] frame) {
        if (frame == null || frame.length == 0 || frame.length > 0xFFFFFF) {
            return;
        }

        int offset = 0;
        int fragmentIndex = 0;

        while (running && offset < frame.length) {
            int chunk = Math.min(MAX_CHUNK, frame.length - offset);
            byte[] packet = new byte[chunk + 4];

            if (fragmentIndex == 0) {
                int total = frame.length;
                packet[0] = (byte) (total & 0xFF);
                packet[1] = (byte) ((total >>> 8) & 0xFF);
                packet[2] = (byte) ((total >>> 16) & 0xFF);
                packet[3] = 0;
            } else {
                packet[0] = (byte) (chunk & 0xFF);
                packet[1] = (byte) ((chunk >>> 8) & 0xFF);
                packet[2] = 0;
                packet[3] = (byte) (fragmentIndex & 0xFF);
            }

            System.arraycopy(frame, offset, packet, 4, chunk);

            if (!TrevorasWssClient.sendVideoPacketViaActiveSession(packet)) {
                return;
            }

            offset += chunk;
            fragmentIndex++;
        }
    }

    private void stopProjection() {
        running = false;

        if (mirrorCast != null) {
            mirrorCast.stop();
            mirrorCast = null;
        }

        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback);
            } catch (Throwable ignored) {
            }

            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
            projection = null;
        }
    }

    @Override
    public void onDestroy() {
        stopProjection();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
