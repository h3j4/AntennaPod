package de.danoeh.antennapod.playback.service.internal;

import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.util.Log;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

/**
 * Applies a broadband compressor + limiter (Android {@link DynamicsProcessing}) to even out
 * loud and quiet speech, similar to the adaptive leveling used by podcast-mastering tools.
 * Requires Android 9 (API 28) or newer; on older versions it is a no-op.
 */
public class SpeechCompressor {
    private static final String TAG = "SpeechCompressor";

    private DynamicsProcessing dynamicsProcessing;
    private int sessionId = 0;

    /**
     * (Re)creates the effect for the given audio session. Safe to call repeatedly.
     */
    public void init(int audioSessionId) {
        release();
        sessionId = audioSessionId;
        // Session id 0 is the global output mix; only attach to a real per-player session.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                || audioSessionId <= 0
                || !UserPreferences.isVoiceLevelingEnabled()) {
            return;
        }
        try {
            int channelCount = 2;
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channelCount,
                    false, 1,   // pre-EQ unused
                    true, 1,    // one multiband-compressor band (used as broadband)
                    false, 1,   // post-EQ unused
                    true)       // limiter in use
                    .build();
            dynamicsProcessing = new DynamicsProcessing(0, audioSessionId, config);

            // Gentle broadband compression tuned for speech, with makeup gain.
            DynamicsProcessing.MbcBand mbcBand = new DynamicsProcessing.MbcBand(
                    true,       // enabled
                    20000f,     // cutoff frequency (single full-range band)
                    10f,        // attack time (ms)
                    150f,       // release time (ms)
                    3f,         // compression ratio
                    -24f,       // threshold (dB)
                    6f,         // knee width (dB)
                    -80f,       // noise gate threshold (dB)
                    1f,         // expander ratio (1 = off)
                    0f,         // pre gain (dB)
                    8f);        // post / makeup gain (dB)
            dynamicsProcessing.setMbcBandAllChannelsTo(0, mbcBand);

            // Limiter to catch peaks introduced by the makeup gain.
            DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(
                    true,       // in use
                    true,       // enabled
                    0,          // link group
                    1f,         // attack time (ms)
                    60f,        // release time (ms)
                    10f,        // ratio
                    -1f,        // threshold (dB)
                    0f);        // post gain (dB)
            dynamicsProcessing.setLimiterAllChannelsTo(limiter);

            dynamicsProcessing.setEnabled(true);
        } catch (Exception e) {
            Log.d(TAG, "Unable to create dynamics processing: " + e.getMessage());
            dynamicsProcessing = null;
        }
    }

    public void release() {
        if (dynamicsProcessing != null) {
            try {
                dynamicsProcessing.release();
            } catch (Exception e) {
                Log.d(TAG, "Unable to release dynamics processing: " + e.getMessage());
            }
            dynamicsProcessing = null;
        }
    }
}
