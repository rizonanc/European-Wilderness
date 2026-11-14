package com.github.xandergos.terraindiffusionmc.compat.voxy;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import static org.junit.jupiter.api.Assertions.*;
class VoxyFramePointTest {
 @Test void depthPixelUsesOpenGlBottomOriginAndCameraRelativeProjection(){
  assertEquals("10.00,20.00,30.00",VoxyWaterFrameDiagnostics.worldPoint(new Matrix4f(),0,0,1,1,.5f,10,20,30));
  assertEquals("9.50,19.50,29.50",VoxyWaterFrameDiagnostics.worldPoint(new Matrix4f(),0,0,2,2,.25f,10,20,30));
  assertEquals("SKY/UNKNOWN",VoxyWaterFrameDiagnostics.worldPoint(new Matrix4f(),0,0,1,1,1,0,0,0));
 }
}
