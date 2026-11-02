// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.stalls;

import dev.jfrq.core.model.ThreadRef;

/**
 * Another thread the sampler saw running while a stall with no recorded holder lasted: a
 * correlation in time, never a holder. JFR records who parked, not who held what they parked
 * on, so this is the nearest the recording comes to naming one.
 *
 * @param thread        the other thread
 * @param javaSamples   its samples executing Java inside the stall, none of them idle
 * @param nativeSamples its samples in native code inside the stall, none of them idle
 * @param frame         the innermost frame most of those samples had, without its line
 * @param frameSamples  how many of them had it
 * @param site          under {@code --app}, the site that frame's samples name; {@code null} otherwise
 */
public record Meanwhile(ThreadRef thread, int javaSamples, int nativeSamples, String frame, int frameSamples,
                        String site) {

    /** What both renderers put above these threads: they are not a holder, and must not read as one. */
    public static final String LABEL = "seen running meanwhile (a correlation, not a recorded holder)";

    public int samples() {
        return javaSamples + nativeSamples;
    }

    /**
     * The line both renderers print: {@code AppKit Thread: 7 native samples, all in
     * sun.java2d.metal.MTLLayer.blitTexture}, with the site under {@code --app}.
     */
    public String describe() {
        final StringBuilder sb = new StringBuilder(thread.name()).append(": ");
        if (javaSamples > 0) {
            sb.append(javaSamples).append(" Java");
        }
        if (nativeSamples > 0) {
            sb.append(javaSamples > 0 ? " and " : "").append(nativeSamples).append(" native");
        }
        sb.append(samples() == 1 ? " sample in "
                : frameSamples == samples() ? " samples, all in " : " samples, " + frameSamples + " of them in ")
                .append(frame);
        if (site != null && !site.equals(frame)) {
            sb.append(", site ").append(site);
        }
        return sb.toString();
    }
}
