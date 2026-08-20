package com.taiji.opc2ecu.core;

import java.util.Calendar;

public final class OpcReadValue {
    private final String itemId;
    private final Object value;
    private final int quality;
    private final Calendar timestamp;

    public OpcReadValue(
            final String itemId, final Object value, final int quality, final Calendar timestamp) {
        this.itemId = itemId;
        this.value = value;
        this.quality = quality;
        this.timestamp = timestamp;
    }

    public String getItemId() { return itemId; }
    public Object getValue() { return value; }
    public int getQuality() { return quality; }
    public Calendar getTimestamp() { return timestamp; }
}
