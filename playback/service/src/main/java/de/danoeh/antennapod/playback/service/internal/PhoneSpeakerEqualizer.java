package de.danoeh.antennapod.playback.service.internal;

import android.media.audiofx.Equalizer;
import android.util.Log;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

/**
 * Manages a playback {@link Equalizer} that applies two optional, fixed tuning curves:
 * <ul>
 *     <li>Phone-speaker tuning: rolls off deep bass, keeps low-mid voice body, lifts presence.
 *         Only while playing through the built-in speaker.</li>
 *     <li>Reduce harshness: rolls off harsh highs and sibilance to make scratchy / bad-mic
 *         recordings less fatiguing. Applies on all outputs, strength configurable.</li>
 * </ul>
 * The curves are additive; the effect is only enabled when at least one applies.
 */
public class PhoneSpeakerEqualizer {
    private static final String TAG = "PhoneSpeakerEq";

    private Equalizer equalizer;
    private int sessionId = 0;
    private boolean onSpeaker = true;

    /**
     * (Re)creates the equalizer for the given audio session. Safe to call repeatedly.
     */
    public void init(int audioSessionId) {
        release();
        sessionId = audioSessionId;
        reconfigure();
    }

    /**
     * Updates whether audio is currently routed to the built-in speaker.
     */
    public void setOnSpeaker(boolean onSpeaker) {
        this.onSpeaker = onSpeaker;
        reconfigure();
    }

    /**
     * Re-reads the preferences and applies them. Call when a tuning preference changes.
     */
    public void reconfigure() {
        // Session id 0 is the global output mix; only attach to a real per-player session.
        if (sessionId <= 0) {
            return;
        }
        if (!isAnyTuningEnabled()) {
            release();
            return;
        }
        if (equalizer == null) {
            try {
                equalizer = new Equalizer(0, sessionId);
            } catch (Exception e) {
                Log.d(TAG, "Unable to create equalizer: " + e.getMessage());
                equalizer = null;
                return;
            }
        }
        applyBands(equalizer);
        try {
            equalizer.setEnabled(true);
        } catch (Exception e) {
            Log.d(TAG, "Unable to enable equalizer: " + e.getMessage());
        }
    }

    public void release() {
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception e) {
                Log.d(TAG, "Unable to release equalizer: " + e.getMessage());
            }
            equalizer = null;
        }
    }

    private boolean isAnyTuningEnabled() {
        return (UserPreferences.isPhoneSpeakerTuningEnabled() && onSpeaker)
                || UserPreferences.isReduceHarshnessEnabled();
    }

    private void applyBands(Equalizer eq) {
        boolean speaker = UserPreferences.isPhoneSpeakerTuningEnabled() && onSpeaker;
        boolean harshness = UserPreferences.isReduceHarshnessEnabled();
        short bandCount = eq.getNumberOfBands();
        short[] range = eq.getBandLevelRange();
        short minLevel = range[0];
        short maxLevel = range[1];
        for (short band = 0; band < bandCount; band++) {
            int centerHz = eq.getCenterFreq(band) / 1000; // center frequency is reported in milliHz
            int targetMillibel = 0;
            if (speaker) {
                if (centerHz < 120) {
                    targetMillibel += -350;     // trim only the sub-bass the speaker can't produce
                } else if (centerHz < 1500) {
                    targetMillibel += 150;      // fill low-mid / midrange body (avoids a hollow sound)
                } else if (centerHz <= 4000) {
                    targetMillibel += 300;      // lift vocal presence for clarity
                } else if (centerHz <= 8000) {
                    targetMillibel += 150;      // add upper-mid articulation (avoids a muffled sound)
                }
                // leave the extreme highs flat; small speakers already roll them off
            }
            if (harshness) {
                int strength = Math.max(1, Math.min(4, UserPreferences.getReduceHarshnessStrength()));
                if (centerHz >= 5000 && centerHz <= 8000) {
                    targetMillibel += -200 * strength;  // dip sibilance range
                } else if (centerHz > 8000) {
                    targetMillibel += -350 * strength;  // roll off scratchy / hissy highs
                }
            }
            short level = (short) Math.max(minLevel, Math.min(maxLevel, targetMillibel));
            try {
                eq.setBandLevel(band, level);
            } catch (Exception e) {
                Log.d(TAG, "Unable to set band level: " + e.getMessage());
            }
        }
    }
}
