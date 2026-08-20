package com.taiji.opc2ecu.core;

/** Result of validating one configured OPC item before collection starts. */
public final class PointValidation {
    private final String itemId;
    private final boolean readable;
    private final int canonicalDataType;

    public PointValidation(
            final String itemId, final boolean readable, final int canonicalDataType) {
        if (itemId == null || itemId.trim().isEmpty()) {
            throw new IllegalArgumentException("itemId must not be empty");
        }
        this.itemId = itemId;
        this.readable = readable;
        this.canonicalDataType = canonicalDataType & 0xffff;
    }

    public String getItemId() { return itemId; }
    public boolean isReadable() { return readable; }
    public int getCanonicalDataType() { return canonicalDataType; }

    public boolean isNumeric() {
        return isNumericVarType(canonicalDataType);
    }

    /** Numeric scalar VARTYPE values accepted by the OPC2ECU double-value protocol. */
    public static boolean isNumericVarType(final int varType) {
        switch (varType & 0xffff) {
            case 2:  // VT_I2
            case 3:  // VT_I4
            case 4:  // VT_R4
            case 5:  // VT_R8
            case 6:  // VT_CY
            case 16: // VT_I1
            case 17: // VT_UI1
            case 18: // VT_UI2
            case 19: // VT_UI4
            case 20: // VT_I8
            case 21: // VT_UI8
                return true;
            default:
                return false;
        }
    }
}
