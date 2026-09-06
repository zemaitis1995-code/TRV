package lt.trevoras.multimedia;

import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;

/** Funkcinis originalios ScreenMirrorCast mirror kelio atkūrimas. */
public class ScreenMirrorCast extends CastBase {

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;

    public ScreenMirrorCast(int width, int height, Callback callback) {
        super(width, height, callback);
    }

    public void startMirrorCast(MediaProjection projection) throws Exception {
        this.projection = projection;

        initCodec();

        // Originaliame APK: "MyVirtualDisplay", densityDpi=1, flags=19.
        virtualDisplay = projection.createVirtualDisplay(
                "MyVirtualDisplay",
                width,
                height,
                1,
                19,
                inputSurface,
                null,
                null
        );

        startEncoderLoop();
    }

    @Override
    public void stop() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
            }
        } catch (Throwable ignored) {
        }
        virtualDisplay = null;

        super.stop();
    }
}
