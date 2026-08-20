package com.taiji.opc2ecu.core;

import static org.junit.Assert.assertEquals;

import java.io.StringWriter;

import org.junit.Test;

public class JsonWriterTest {
    @Test
    public void escapesQuotesAndBackslashes() {
        assertEquals("a\\\"b\\\\c", JsonWriter.escape("a\"b\\c"));
    }

    @Test
    public void escapesNamedControlCharacters() {
        assertEquals("\\b\\f\\n\\r\\t", JsonWriter.escape("\b\f\n\r\t"));
    }

    @Test
    public void escapesOtherControlCharactersAsUnicode() {
        assertEquals("\\u0001", JsonWriter.escape("\u0001"));
    }

    @Test
    public void writesNullWithoutQuotes() throws Exception {
        final StringWriter writer = new StringWriter();
        JsonWriter.writeField(writer, "value", null, 1, false, true);
        assertEquals("  \"value\": null\n", writer.toString());
    }

    @Test
    public void writesIndentedQuotedFieldWithComma() throws Exception {
        final StringWriter writer = new StringWriter();
        JsonWriter.writeField(writer, "name", "x", 2, true, true);
        assertEquals("    \"name\": \"x\",\n", writer.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeIndent() throws Exception {
        JsonWriter.writeField(new StringWriter(), "x", "y", -1, false, true);
    }
}
