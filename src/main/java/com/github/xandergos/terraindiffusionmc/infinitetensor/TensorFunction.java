package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.List;

@FunctionalInterface
public interface TensorFunction {
   FloatTensor apply(int[] var1, List<FloatTensor> var2);
}
