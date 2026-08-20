package com.taiji.opc2ecu.poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.taiji.opc2ecu.core.OpcDaClient;
import com.taiji.opc2ecu.core.OpcDaException;
import com.taiji.opc2ecu.core.OpcDataCallback;
import com.taiji.opc2ecu.core.OpcReadValue;
import com.taiji.opc2ecu.core.PointValidation;
import com.taiji.opc2ecu.core.ProbeConfig;

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

    @Test public void precheckAllPassReturnsZeroAndMachineReadableReport() throws Exception {
        final FakeClient client = new FakeClient(Arrays.asList(
                new PointValidation("a", true, 3), new PointValidation("b", true, 5)));
        final CapturedRun run = capturePrecheck(client);
        assertEquals(0, run.exitCode);
        assertTrue(run.stdout.contains("[PRECHECK 1/2] item=a result=PASS reason=ok"));
        assertTrue(run.stdout.contains("[PRECHECK] summary passed=2 failed=0"));
    }

    @Test public void precheckUnreadablePointReturnsFour() throws Exception {
        final FakeClient client = new FakeClient(Arrays.asList(
                new PointValidation("a", false, 0), new PointValidation("b", true, 3)));
        final CapturedRun run = capturePrecheck(client);
        assertEquals(4, run.exitCode);
        assertTrue(run.stdout.contains("item=a result=FAIL reason=not-readable"));
        assertTrue(run.stdout.contains("summary passed=1 failed=1"));
    }

    @Test public void precheckNonNumericPointReturnsFour() throws Exception {
        final FakeClient client = new FakeClient(Collections.singletonList(
                new PointValidation("a", true, 8)));
        final CapturedRun run = capturePrecheck(client);
        assertEquals(4, run.exitCode);
        assertTrue(run.stdout.contains("item=a result=FAIL reason=non-numeric"));
    }

    @Test public void precheckConnectionFailureReturnsThreeWithFirewallHint() throws Exception {
        final FakeClient client = new FakeClient(Collections.<PointValidation>emptyList());
        client.connectFailure = new OpcDaException(OpcDaException.Kind.CONNECTION, "offline");
        final CapturedRun run = capturePrecheck(client);
        assertEquals(3, run.exitCode);
        assertTrue(run.stderr.contains("TCP 135"));
    }

    @Test public void malformedPointsReturnsTwoBeforeClientOrUdpCreation() throws Exception {
        final Path config = writeConfig();
        final Path points = Files.createTempFile("opcda-bad-points", ".json");
        Files.write(points, "{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
        final FakeProvider provider = new FakeProvider(new FakeClient(Collections.<PointValidation>emptyList()));
        try {
            final CapturedRun run = capture(
                    new String[] { "--precheck-points", points.toString(), config.toString() },
                    "secret", provider);
            assertEquals(2, run.exitCode);
            assertEquals(0, provider.createCount);
        } finally {
            Files.deleteIfExists(points);Files.deleteIfExists(config);
        }
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
        return capture(args, password, null);
    }

    private static CapturedRun capture(
            final String[] args, final String password, final FakeProvider provider) throws Exception {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, "UTF-8"));
            System.setErr(new PrintStream(stderr, true, "UTF-8"));
            final int exitCode = provider == null
                    ? OpcDaProbe.run(args, password) : OpcDaProbe.run(args, password, provider);
            return new CapturedRun(
                    exitCode,
                    stdout.toString("UTF-8"),
                    stderr.toString("UTF-8"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static CapturedRun capturePrecheck(final FakeClient client) throws Exception {
        final Path config = writeConfig();
        final Path points = Files.createTempFile("opcda-points", ".json");
        Files.write(points, ("{\"periodMillis\":1000,\"udp\":{\"host\":\"invalid.example\","
                + "\"port\":9999},\"items\":[\"a\",\"b\"]}").getBytes(StandardCharsets.UTF_8));
        if (client.validations.size() == 1) {
            Files.write(points, ("{\"periodMillis\":1000,\"udp\":{\"host\":\"invalid.example\","
                    + "\"port\":9999},\"items\":[\"a\"]}").getBytes(StandardCharsets.UTF_8));
        }
        try {
            return capture(new String[] { "--precheck-points", points.toString(), config.toString() },
                    "secret", new FakeProvider(client));
        } finally {
            Files.deleteIfExists(points);Files.deleteIfExists(config);
        }
    }

    private static final class FakeProvider implements OpcDaProbe.ClientProvider {
        private final FakeClient client;private int createCount;
        private FakeProvider(final FakeClient client){this.client=client;}
        @Override public OpcDaClient create(final ProbeConfig config, final String outputPath){createCount++;return client;}
    }

    private static final class FakeClient implements OpcDaClient {
        private final List<PointValidation> validations;private Exception connectFailure;
        private FakeClient(final List<PointValidation> validations){this.validations=validations;}
        @Override public void connect() throws Exception{if(connectFailure!=null){throw connectFailure;}}
        @Override public void disconnect(){}
        @Override public boolean isConnected(){return true;}
        @Override public List<String> browseItems(){return Collections.emptyList();}
        @Override public int exportCatalog(){return 0;}
        @Override public List<PointValidation> validateItems(final List<String> itemIds){return validations;}
        @Override public OpcReadValue readItem(final String itemId){return null;}
        @Override public void bindSyncRead(final OpcDataCallback callback){}
        @Override public void unbind(){}
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
