package com.github.xandergos.terraindiffusionmc.compat.voxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
class VoxyWaterFrameAbiTest {
 @Test void pinnedRendererHasExactlyTheExpectedConcreteTranslucentDrawMethodAndFields() throws Exception {
  var loader=getClass().getClassLoader();var node=new ClassNode();
  try(var stream=loader.getResourceAsStream("me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.class")){
   assertNotNull(stream);new ClassReader(stream).accept(node,0);
  }
  var matches=node.methods.stream().filter(m->m.name.equals("renderTranslucent")&&m.desc.equals("(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V")).toList();
  assertEquals(1,matches.size());var method=matches.getFirst();assertEquals(0,method.access&Opcodes.ACC_STATIC);
  assertEquals(Opcodes.RETURN,Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList().getLast().getOpcode());
  for(String name:new String[]{"me/cortex/voxy/client/core/rendering/Viewport.class","me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport.class"}){
   var type=new ClassNode();try(var stream=loader.getResourceAsStream(name)){assertNotNull(stream);new ClassReader(stream).accept(type,ClassReader.SKIP_CODE);}
   if(name.endsWith("MDICViewport.class"))assertTrue(type.fields.stream().anyMatch(f->f.name.equals("drawCountCallBuffer")));
   else for(String field:List.of("width","height","depthBoundingBuffer"))assertTrue(type.fields.stream().anyMatch(f->f.name.equals(field)));
  }
 }
 @Test void unrequestedCaptureDoesNotNeedMinecraftBootstrapOrAnOpenGLContext(){
  assertEquals("water-frame: no capture requested",VoxyWaterFrameDiagnostics.status());
  VoxyWaterFrameDiagnostics.capture(new Object(),new Object());
  assertEquals("water-frame: no capture requested",VoxyWaterFrameDiagnostics.status());
 }
}
