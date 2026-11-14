package com.github.xandergos.terraindiffusionmc.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;

class PipelineChecksTest {
    @Test void invalidShapeIsExplicitAndNeverConvertedToEmptyTerrain(){
        var failure=assertThrows(PipelineChecks.Invalid.class,()->PipelineChecks.inputs(new Object[][]{{"x",new float[3],new long[]{2,2}}}));
        assertEquals(DiagnosticErrors.Code.INF_INPUT,failure.code);
        assertThrows(PipelineChecks.Invalid.class,()->PipelineChecks.inputs(new Object[][]{{"x",new float[3],new long[]{-1}}}));
        assertThrows(PipelineChecks.Invalid.class,()->PipelineChecks.inputs(new Object[][]{{"x",new float[0],new long[]{Long.MAX_VALUE,2}}}));
        assertDoesNotThrow(()->PipelineChecks.inputs(new Object[][]{{"x",new float[4],new long[]{2,2}}}));
    }

    @Test void variableBatchBufferLongerThanShapeIsRejectedWithExactFieldMessage(){
        // Phase 4.2 regression: latentBatch with a smaller batch after a larger one reused
        // a longer buffer — the production world-creation failure. Reproduces the field
        // error exactly and requires the enriched diagnosis (expected shape/count + actual length).
        var failure=assertThrows(PipelineChecks.Invalid.class,()->PipelineChecks.inputs(
            new Object[][]{{"x",new float[4*5*64*64],new long[]{2,5,64,64}}}));
        assertEquals(DiagnosticErrors.Code.INF_INPUT,failure.code);
        assertTrue(failure.getMessage().contains("Tensor shape/data mismatch: x"),failure.getMessage());
        assertTrue(failure.getMessage().contains("[2, 5, 64, 64]"),failure.getMessage());
        assertTrue(failure.getMessage().contains("= 40960 elements"),failure.getMessage());
        assertTrue(failure.getMessage().contains("actual array length 81920"),failure.getMessage());
    }

    @Test void exactLengthMatchesShapeProduct() {
        // The valid remainder cases (batch 1..3) must keep passing: exact length is accepted.
        for(int batch=1;batch<=4;batch++) {
            int b=batch,expected=batch*5*64*64;
            assertDoesNotThrow(()->PipelineChecks.inputs(
                new Object[][]{{"x",new float[expected],new long[]{b,5,64,64}}}));
        }
    }
    @Test void wrongOutputAndNonFiniteCriticalValuesHaveDifferentCodes(){
        assertEquals(DiagnosticErrors.Code.INF_OUTPUT,assertThrows(PipelineChecks.Invalid.class,()->PipelineChecks.output(new float[3],4,"decoder")).code);
        for(float f:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
            assertEquals(DiagnosticErrors.Code.INF_NONFINITE,assertThrows(PipelineChecks.Invalid.class,()->PipelineChecks.finite(f,"height")).code);
        assertDoesNotThrow(()->PipelineChecks.output(new float[]{0,-0.0f,1,2},4,"decoder"));
    }
}
