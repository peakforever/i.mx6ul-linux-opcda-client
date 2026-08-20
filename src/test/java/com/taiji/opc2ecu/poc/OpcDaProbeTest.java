package com.taiji.opc2ecu.poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class OpcDaProbeTest {
    @Test
    public void selfTestProtocolRemainsAvailable() throws Exception {
        final CapturedRun run = capture(new String[] { "--self-test-protocol" }, null);
        assertEquals(0, run.exitCode);
        assertTrue(run.stdout.contains("[RESULT] OPC2ECU protocol codec self-test succeeded."));
    }

    @Test
    public void checkConfigSucceedsWithoutLoggingCredentials() throws Exception {
        final Path config = writeConfig();
        try {
            final CapturedRun run = capture(
                    new String[] { "--check-config", config.toString() }, "top-secret-password");
            assertEquals(0, run.exitCode);
            assertTrue(run.stdout.contains("[CONFIG]"));
            assertTrue(run.stdout.contains("user=<redacted>"));
            assertFalse(run.stdout.contains("opcuser"));
            assertFalse(run.stdout.contains("EXAMPLE"));
            assertFalse(run.stdout.contains("top-secret-password"));
            assertFalse(run.stderr.contains("top-secret-password"));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    public void checkConfigWithoutPasswordReturnsExitCodeTwo() throws Exception {
        final Path config = writeConfig();
        try {
            final CapturedRun run = capture(
                    new String[] { "--check-config", config.toString() }, null);
            assertEquals(2, run.exitCode);
            assertTrue(run.stderr.contains("OPC_PASSWORD"));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    public void missingConfigFileReturnsExitCodeTwo() throws Exception {
        final CapturedRun run = capture(
                new String[] { "--check-config", "/path/that/does/not/exist.properties" },
                "secret");
        assertEquals(2, run.exitCode);
        assertTrue(run.stderr.contains("configuration file"));
    }

    private static Path writeConfig() throws Exception {
        final Path path = Files.createTempFile("opcda-probe-test", ".properties");
        final String text = "host=192.0.2.1\n"
                + "domain=EXAMPLE\n"
                + "user=opcuser\n"
                + "progId=Example.OPC.1\n"
                + "clsid=00000000-0000-0000-0000-000000000000\n"
                + "itemId=Group.Item\n";
        Files.write(path, text.getBytes(StandardCharsets.ISO_8859_1));
        return path;
    }

    private static CapturedRun capture(final String[] args, final String password) throws Exception {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, "UTF-8"));
            System.setErr(new PrintStream(stderr, true, "UTF-8"));
            final int exitCode = OpcDaProbe.run(args, password);
            return new CapturedRun(
                    exitCode,
                    stdout.toString("UTF-8"),
                    stderr.toString("UTF-8"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static final class CapturedRun {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CapturedRun(final int exitCode, final String stdout, final String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
