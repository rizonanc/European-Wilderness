package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.List;

@FunctionalInterface
public interface BatchTensorFunction {
   List<FloatTensor> apply(List<int[]> var1, List<List<FloatTensor>> var2);
}
