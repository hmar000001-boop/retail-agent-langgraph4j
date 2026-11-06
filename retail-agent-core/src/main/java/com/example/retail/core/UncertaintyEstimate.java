package com.example.retail.core;

import java.io.Serializable;

/** Uncertainty is carried with provenance; a raw LLM self-confidence is explicitly not calibrated. */
public record UncertaintyEstimate(double probabilityCorrect, String provenance,
                                  boolean calibrated, String calibrationVersion,
                                  int calibrationSampleCount) implements Serializable {
    public UncertaintyEstimate {
        if (!Double.isFinite(probabilityCorrect) || probabilityCorrect < 0 || probabilityCorrect > 1) throw new IllegalArgumentException("probability must be in [0,1]");
        provenance = provenance == null ? "unknown" : provenance;
        calibrationVersion = calibrationVersion == null ? "none" : calibrationVersion;
    }

    public static UncertaintyEstimate uncalibratedModelEstimate(double value) {
        return new UncertaintyEstimate(value, "LLM self-reported confidence", false, "none", 0);
    }

    public static UncertaintyEstimate unknown(String why) {
        return new UncertaintyEstimate(0.0, why, false, "none", 0);
    }
}
