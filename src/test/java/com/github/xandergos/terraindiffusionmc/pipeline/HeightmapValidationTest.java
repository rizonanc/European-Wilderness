package com.github.xandergos.terraindiffusionmc.pipeline;

import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeightmapValidationTest {
    private static Object assemble(float[] height,short[] biome,float[] climate) throws Exception {
        var method=LocalTerrainProvider.class.getDeclaredMethod("buildHeightmapData",float[].class,short[].class,int.class,int.class,float[].class);
        method.setAccessible(true);
        try{return method.invoke(null,height,biome,1,2,climate);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException cause)throw cause;throw failure;}
    }
    @Test void invalidDimensionsAndNonFiniteHeightCannotBecomeSuccessfulTerrain(){
        assertThrows(IllegalArgumentException.class,()->assemble(new float[1],new short[2],null));
        assertThrows(IllegalArgumentException.class,()->assemble(new float[2],new short[2],new float[1]));
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
            assertThrows(PipelineChecks.Invalid.class,()->assemble(new float[]{bad,1},new short[2],null));
    }
    @Test void validHeightQuantizationAndDefensiveClimateOwnershipStayUnchanged() throws Exception {
        float[] climate=new float[8];climate[0]=0.5f;
        var result=(LocalTerrainProvider.HeightmapData)assemble(new float[]{-0.25f,32768.5f},new short[]{2,3},climate);
        assertArrayEquals(new short[]{-1,32767},result.heightmap[0]);assertArrayEquals(new short[]{2,3},result.biomeIds[0]);
        climate[0]=1;assertEquals(0.5f,result.climate[0]);
    }
}
