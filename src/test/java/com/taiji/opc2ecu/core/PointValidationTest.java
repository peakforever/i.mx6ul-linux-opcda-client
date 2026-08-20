package com.taiji.opc2ecu.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PointValidationTest {
    @Test public void acceptsEverySpecifiedNumericVarType() {
        final int[] types = { 2, 3, 4, 5, 6, 16, 17, 18, 19, 20, 21 };
        for (final int type : types) {
            assertTrue("Expected numeric VARTYPE " + type,
                    PointValidation.isNumericVarType(type));
        }
    }

    @Test public void rejectsSpecifiedNonNumericScalarTypes() {
        final int[] types = { 0, 1, 7, 8, 9, 10, 11, 12, 13, 14, 22, 23 };
        for (final int type : types) {
            assertFalse("Expected non-numeric VARTYPE " + type,
                    PointValidation.isNumericVarType(type));
        }
    }

    @Test public void rejectsArraysEvenWhenElementTypeIsNumeric() {
        final int[] types = { 8194, 8195, 8196, 8197, 8208, 8209, 8210, 8211, 8212, 8213 };
        for (final int type : types) {
            assertFalse("Expected array VARTYPE to be non-numeric " + type,
                    PointValidation.isNumericVarType(type));
        }
    }

    @Test public void treatsVarTypeAsUnsignedSixteenBitValue() {
        assertTrue(PointValidation.isNumericVarType(0x10002));
        assertFalse(PointValidation.isNumericVarType(0x12003));
    }

    @Test public void readableNumericValidationPassesBothPredicates() {
        final PointValidation validation = new PointValidation("a", true, 5);
        assertTrue(validation.isReadable());
        assertTrue(validation.isNumeric());
    }
}
