package lt.trevoras.multimedia;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Funkcinis originalios Duowei CastBase atkūrimas pagal APK elgesį.
 * Sąmoningai nenaudojame papildomų profilio/lygio nustatymų ir
 * neperformatuojame MediaCodec H.264 srauto, kad elgesys būtų kuo arčiau originalo.
 */
public abstract class CastBase {

    public interface Callback {
        void onFrame(byte[] frame);
        void onError(Throwable error);
    }

    protected final int width;
    protected final int height;
    protected final Callback callback;

    protected MediaCodec codec;
    protected Surface inputSurface;
    protected Thread encoderThread;
    protected volatile boolean encoding;

    private byte[] codecConfig;

    protected CastBase(int width, int height, Callback callback) {
        this.width = width;
        this.height = height;
        this.callback = callback;
    }

    protected void initCodec() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_400_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger("bitrate-mode", 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        codec = MediaCodec.createEncoderByType("video/avc");
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();
    }

    protected void startEncoderLoop() {
        encoding = true;
        encoderThread = new Thread(this::encoderLoop, "Trevoras-OriginalCastBase");
        encoderThread.start();
    }

    private void encoderLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (encoding && codec != null) {
                int index = codec.dequeueOutputBuffer(info, 10_000);

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                }

                if (index < 0) {
                    continue;
                }

                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);

                    byte[] data = new byte[info.size];
                    buffer.get(data);

                    // Originalo logika sprendžia NAL tipą iš 5-o baito:
                    // 00 00 00 01 [NAL header].
                    int nalType = data.length > 4 ? (data[4] & 0x1F) : -1;
                    boolean codecConfigFlag =
                            (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean keyFrameFlag =
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                    // SPS/PPS saugome kaip vieną MediaCodec config bloką.
                    if (codecConfigFlag || nalType == 7 || nalType == 8) {
                        codecConfig = data;
                    } else {
                        boolean idr = nalType == 5 || keyFrameFlag;
                        if (idr && codecConfig != null && codecConfig.length > 0) {
                            callback.onFrame(concat(codecConfig, data));
                        } else {
                            callback.onFrame(data);
                        }
                    }
                }

                codec.releaseOutputBuffer(index, false);
            }
        } catch (Throwable t) {
            if (encoding && callback != null) {
                callback.onError(t);
            }
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public void stop() {
        encoding = false;

        if (encoderThread != null) {
            encoderThread.interrupt();
            encoderThread = null;
        }

        try {
            if (codec != null) {
                codec.stop();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (codec != null) {
                codec.release();
            }
        } catch (Throwable ignored) {
        }

        codec = null;
        inputSurface = null;
        codecConfig = null;
    }
}
