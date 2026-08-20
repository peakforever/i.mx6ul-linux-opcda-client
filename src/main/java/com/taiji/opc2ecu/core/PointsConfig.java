package com.taiji.opc2ecu.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Immutable collection and UDP configuration loaded from points.json. */
public final class PointsConfig {
    private final int periodMillis;
    private final String udpHost;
    private final int udpPort;
    private final Charset md5Charset;
    private final List<String> items;

    private PointsConfig(
            final int periodMillis, final String udpHost, final int udpPort,
            final Charset md5Charset, final List<String> items) {
        this.periodMillis = periodMillis;
        this.udpHost = udpHost;
        this.udpPort = udpPort;
        this.md5Charset = md5Charset;
        this.items = Collections.unmodifiableList(new ArrayList<String>(items));
    }

    public static PointsConfig load(final String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("points.json path must not be empty");
        }
        return fromJson(new ObjectMapper().readTree(new File(path)));
    }

    static PointsConfig fromJson(final JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("points.json root must be an object");
        }
        final int period = requiredInt(root, "periodMillis");
        if (period <= 0) {
            throw new IllegalArgumentException("periodMillis must be greater than zero");
        }
        final JsonNode udp = root.get("udp");
        if (udp == null || !udp.isObject()) {
            throw new IllegalArgumentException("udp object is required");
        }
        final String host = requiredText(udp, "host");
        final int port = requiredInt(udp, "port");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("udp.port must be between 1 and 65535");
        }
        final String charsetName = optionalText(udp, "md5Charset", "US-ASCII");
        final Charset charset;
        if ("US-ASCII".equalsIgnoreCase(charsetName)) {
            charset = StandardCharsets.US_ASCII;
        } else if ("GBK".equalsIgnoreCase(charsetName)) {
            charset = Charset.forName("GBK");
        } else {
            throw new IllegalArgumentException("udp.md5Charset must be US-ASCII or GBK");
        }
        final JsonNode itemNodes = root.get("items");
        if (itemNodes == null || !itemNodes.isArray() || itemNodes.size() == 0) {
            throw new IllegalArgumentException("items must be a non-empty array");
        }
        final List<String> items = new ArrayList<String>();
        final Set<String> unique = new HashSet<String>();
        final Iterator<JsonNode> iterator = itemNodes.elements();
        while (iterator.hasNext()) {
            final JsonNode item = iterator.next();
            if (!item.isTextual() || item.asText().trim().isEmpty()) {
                throw new IllegalArgumentException("items must contain non-empty strings");
            }
            final String value = item.asText().trim();
            if (!unique.add(value)) {
                throw new IllegalArgumentException("items contains duplicate entry: " + value);
            }
            items.add(value);
        }
        return new PointsConfig(period, host, port, charset, items);
    }

    private static int requiredInt(final JsonNode parent, final String name) {
        final JsonNode value = parent.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return value.intValue();
    }

    private static String requiredText(final JsonNode parent, final String name) {
        final JsonNode value = parent.get(name);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return value.asText().trim();
    }

    private static String optionalText(
            final JsonNode parent, final String name, final String defaultValue) {
        return parent.has(name) ? requiredText(parent, name) : defaultValue;
    }

    public int getPeriodMillis() { return periodMillis; }
    public String getUdpHost() { return udpHost; }
    public int getUdpPort() { return udpPort; }
    public Charset getMd5Charset() { return md5Charset; }
    public List<String> getItems() { return items; }
}
