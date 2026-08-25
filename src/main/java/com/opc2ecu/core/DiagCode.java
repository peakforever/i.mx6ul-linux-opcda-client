package com.opc2ecu.core;

/**
 * Fine-grained connection-failure attribution codes for OPC DA remote access.
 *
 * <p>Each code maps a failure signature (COM HRESULT or behavioural pattern)
 * to an actionable verdict + fix hint. This is the program-side counterpart of
 * the troubleshooting table in docs/opc-server-windows-deploy.md section 6:
 * instead of "check network/firewall/config", the driver states what it
 * detected and what to do about it.
 */
public enum DiagCode {

    /** No attribution possible / unexpected failure. */
    UNKNOWN(null, "generic", "Unexpected failure", "Inspect the full stack trace in debug logs."),

    /** 0x80070005 E_ACCESSDENIED: DCOM launch/activation/access permission or credentials. */
    DCOM_ACCESS_DENIED(
            0x80070005, "dcom",
            "DCOM launch/activation/access denied, or account/password rejected by the OPC server host",
            "mmc comexp.msc /32 -> OPCEnum and the OPC server component -> Launch and Activation / Access "
                    + "permissions must include the account; verify domain/user/password (non-empty password, "
                    + "classic local-account model)."),

    /** 0x800706BA RPC_S_SERVER_UNAVAILABLE: RPC endpoint unreachable. */
    RPC_SERVER_UNAVAILABLE(
            0x800706BA, "rpc",
            "RPC server unavailable: OPCEnum/OPC server service not running, or TCP 135 blocked",
            "Start the OPC server application (and OPCEnum service); allow inbound TCP 135 from the ECU IP."),

    /** 0x800706BF RPC_S_ENDPOINT_NOT_FOUND: dynamic RPC endpoint refused. */
    RPC_ENDPOINT_NOT_FOUND(
            0x800706BF, "rpc",
            "RPC dynamic endpoint not reachable: the DCOM business-channel port range is blocked",
            "Allow inbound TCP 49152-65535 from the ECU IP, or fix the RPC port range in the registry "
                    + "(see docs/opc-server-windows-deploy.md section 2, option B)."),

    /** 0x800706D3 RPC_S_SERVER_TOO_BUSY. */
    RPC_SERVER_TOO_BUSY(
            0x800706D3, "rpc",
            "RPC server too busy: the OPC host is overloaded or transiently saturated",
            "Retry later; check load on the Windows host if it persists."),

    /** 0x800706BE RPC_S_CALL_FAILED: RPC call failed (often firewall/network mid-flight drop). */
    RPC_CALL_FAILED(
            0x800706BE, "rpc",
            "RPC call failed: connection was cut mid-call (network drop or firewall interference)",
            "Check stability of the link between ECU and the Windows host; verify RPC dynamic ports are allowed."),

    /** 0x80040154 REGDB_E_CLASSNOTREG: ProgID/CLSID not registered. */
    CLASS_NOT_REGISTERED(
            0x80040154, "registry",
            "OPC server ProgID/CLSID is not registered on the target host",
            "Verify progId/clsid in the config; confirm the server is installed and visible in the 32-bit "
                    + "component view (mmc comexp.msc /32)."),

    /**
     * Behavioural attribution: the connect attempt hung until the RPC socket
     * timeout. Typical cause is NTLM authentication failing (wrong password,
     * wrong domain, guest model) or a black-hole firewall that drops instead
     * of rejecting.
     */
    AUTH_OR_NETWORK_TIMEOUT(
            null, "auth",
            "Connect hung until socket timeout: NTLM authentication failure or black-hole firewall "
                    + "(packets dropped, not refused)",
            "Verify domain/user/password first (NTLMv2, non-empty password, classic local-account model); "
                    + "then check whether the Windows firewall drops rather than rejects."),

    /** Catch-all for failures with no recognisable signature. */
    GENERIC_CONNECTION(
            null, "generic",
            "Connection failed without a recognisable COM error code",
            "Capture the [ERROR] message and debug log; correlate with docs/opc-server-windows-deploy.md "
                    + "section 6 troubleshooting table.");

    private final Integer hresult;
    private final String layer;
    private final String detail;
    private final String hint;

    DiagCode(final Integer hresult, final String layer, final String detail, final String hint) {
        this.hresult = hresult;
        this.layer = layer;
        this.detail = detail;
        this.hint = hint;
    }

    /** Machine-readable stable code, e.g. {@code OPC_E_DCOM_ACCESS_DENIED}. */
    public String code() {
        return "OPC_E_" + name();
    }

    public Integer hresult() {
        return hresult;
    }

    public String layer() {
        return layer;
    }

    public String detail() {
        return detail;
    }

    public String hint() {
        return hint;
    }

    /**
     * Maps a COM HRESULT (or a bare Win32 error code) to the most specific
     * known attribution. J-Interop surfaces COM failures sometimes as the full
     * HRESULT (0x80070005) and sometimes as the bare Win32 code (0x00000005),
     * so both forms are matched via the low 16 bits.
     */
    public static DiagCode fromHresult(final int hresult) {
        for (final DiagCode code : values()) {
            if (code.hresult == null) {
                continue;
            }
            if (code.hresult == hresult || (code.hresult & 0xFFFF) == hresult) {
                return code;
            }
        }
        return UNKNOWN;
    }

    /** Attribution for a connect attempt that hung until the socket timeout. */
    public static DiagCode timeout() {
        return AUTH_OR_NETWORK_TIMEOUT;
    }
}
