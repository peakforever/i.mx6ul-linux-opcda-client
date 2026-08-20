package com.taiji.opc2ecu.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReconnectPolicyTest {
    private final ReconnectPolicy policy = new ReconnectPolicy(1000L, 30000L, 3);

    @Test
    public void doublesDelayByAttempt() {
        assertEquals(1000L, policy.delayMillis(1, 0.5d));
        assertEquals(2000L, policy.delayMillis(2, 0.5d));
        assertEquals(4000L, policy.delayMillis(3, 0.5d));
    }

    @Test
    public void capsBaseDelay() {
        assertEquals(30000L, policy.delayMillis(30, 0.5d));
    }

    @Test
    public void capsFinalDelayAfterJitter() {
        assertEquals(30000L, policy.delayMillis(30, 1.0d));
    }

    @Test
    public void appliesMinusTwentyPercentJitter() {
        assertEquals(800L, policy.delayMillis(1, 0.0d));
    }

    @Test
    public void appliesPlusTwentyPercentJitter() {
        assertEquals(1200L, policy.delayMillis(1, 1.0d));
    }

    @Test
    public void enforcesFiniteAttempts() {
        assertTrue(policy.allowsAttempt(3));
        assertFalse(policy.allowsAttempt(4));
    }

    @Test
    public void zeroMaxAttemptsIsUnlimited() {
        assertTrue(new ReconnectPolicy(1L, 2L, 0).allowsAttempt(Integer.MAX_VALUE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidJitterUnit() {
        policy.delayMillis(1, 1.1d);
    }
}
