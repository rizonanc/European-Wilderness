package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Arrays;
import java.util.HashSet;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;

/** Validate external tensor boundaries; no full production tensor scan on each Run. */
final class PipelineChecks {
    static final class Invalid extends IllegalArgumentException {
        final Code code;

        Invalid(Code code, String message) {
            super(message);
            this.code = code;
        }
    }

    static Invalid invalid(Code code, String message) {
        var failure = new Invalid(code, message);
        failed(code, "TENSOR_VALIDATION", failure);
        return failure;
    }

    static void inputs(Object[][] inputs) {
        if (inputs == null) throw invalid(Code.INF_INPUT, "Missing input table");
        var names = new HashSet<String>();
        for (var input : inputs) {
            if (input == null
                    || input.length != 3
                    || !(input[0] instanceof String name)
                    || !names.add(name)
                    || !(input[1] instanceof float[] data)
                    || !(input[2] instanceof long[] shape))
                throw invalid(Code.INF_INPUT, "Malformed/duplicate model input");
            long count = 1;
            try {
                for (long size : shape) {
                    if (size < 0) throw invalid(Code.INF_INPUT, "Unresolved dynamic input dimension");
                    count = Math.multiplyExact(count, size);
                }
                Math.multiplyExact(data.length, Float.BYTES);
            } catch (ArithmeticException overflow) {
                throw invalid(Code.INF_INPUT, "Model input size overflow");
            }
            if (count != data.length)
                throw invalid(
                        Code.INF_INPUT,
                        "Tensor shape/data mismatch: "
                                + name
                                + " (expected shape "
                                + Arrays.toString(shape)
                                + " = "
                                + count
                                + " elements, actual array length "
                                + data.length
                                + ")");
        }
    }

    static void output(float[] values, int expected, String stage) {
        if (values == null || values.length != expected)
            throw invalid(Code.INF_OUTPUT, stage + " output must have " + expected + " values");
        if (DiagnosticErrors.debug()) {
            for (float value : values) finite(value, stage);
        } else if (values.length > 0) {
            for (int i = 0; i < Math.min(64, values.length); i++)
                finite(
                        values[(int) ((long) i * (values.length - 1) / Math.max(1, Math.min(64, values.length) - 1))],
                        stage);
        }
    }

    static void finite(float value, String stage) {
        if (!Float.isFinite(value)) throw invalid(Code.INF_NONFINITE, "Non-finite value at " + stage);
    }
}
