package com.taiji.opc2ecu.core;

import java.io.IOException;
import java.io.Writer;

/** Small dependency-free JSON helper suitable for the compact gateway runtime. */
public final class JsonWriter {
    private JsonWriter() {
    }

    public static void writeField(
            final Writer writer,
            final String name,
            final String value,
            final int indent,
            final boolean comma,
            final boolean quoted) throws IOException {
        if (indent < 0) {
            throw new IllegalArgumentException("indent must not be negative");
        }
        for (int i = 0; i < indent; i++) {
            writer.write("  ");
        }
        writer.write('"');
        writer.write(escape(name));
        writer.write("\": ");
        if (value == null) {
            writer.write("null");
        } else if (quoted) {
            writer.write('"');
            writer.write(escape(value));
            writer.write('"');
        } else {
            writer.write(value);
        }
        writer.write(comma ? ",\n" : "\n");
    }

    public static String escape(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("JSON text must not be null");
        }
        final StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }
}
