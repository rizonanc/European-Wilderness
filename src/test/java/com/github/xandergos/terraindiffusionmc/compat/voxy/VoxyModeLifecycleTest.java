package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxyModeLifecycleTest {
    private static ClassNode type(String name) throws Exception {
        try(var in=VoxyCompat.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")){
            var node=new ClassNode();new ClassReader(in).accept(node,0);return node;
        }
    }
    private static boolean calls(ClassNode type,String method,String call){
        return type.methods.stream().filter(m->m.name.equals(method)).anyMatch(m->{
            for(var i:m.instructions)if(i instanceof MethodInsnNode n&&n.name.equals(call))return true;return false;});
    }
    @Test void modesAreMutuallyExclusiveAndStopImmediatelyInvalidatesWorkerGates(){
        for(boolean enabled:new boolean[]{false,true})for(boolean chunks:new boolean[]{false,true})
            for(boolean experimental:new boolean[]{false,true})
                assertFalse(VoxyCompat.preparedGate(enabled,chunks)&&VoxyCompat.chunksCoarseGate(enabled,chunks,experimental));
        assertFalse(VoxyCompat.preparedGate(false,false));
        assertFalse(VoxyCompat.chunksCoarseGate(false,true,true));
    }
    @Test void liveWorkersRecheckModesAndTickRetiresBothKinds() throws Exception {
        String name="com.github.xandergos.terraindiffusionmc.compat.voxy.VoxyCompat";
        assertTrue(calls(type(name+"$Context"),"current","preparedEnabled"));
        assertTrue(calls(type(name+"$ChunksCoarse"),"current","chunksCoarseGate"));
        assertTrue(calls(type(name),"tick","retire"));assertTrue(calls(type(name),"tick","retireChunksCoarse"));
        assertTrue(calls(type("com.github.xandergos.terraindiffusionmc.compat.voxy.WorldGenCompat"),"pauseNative","preparedEnabled"));
    }
    @Test void retiredProviderCannotDetachReplacementAndLifecycleChangesAreSerialized() throws Exception {
        var old=new CoarseRenderHooks.Provider(){
            public Object render(Object engine,long p,Object original){return "old";}
            public byte children(Object engine,Object original,byte actual){return actual;}
        };
        var next=new CoarseRenderHooks.Provider(){
            public Object render(Object engine,long p,Object original){return "next";}
            public byte children(Object engine,Object original,byte actual){return actual;}
        };
        try{
            CoarseRenderHooks.attach(old);CoarseRenderHooks.attach(next);CoarseRenderHooks.detach(old);
            assertEquals("next",CoarseRenderHooks.render(null,0,"original"));
            for(String method:new String[]{"attach","detach"})
                assertTrue(java.lang.reflect.Modifier.isSynchronized(CoarseRenderHooks.class.getMethod(method,CoarseRenderHooks.Provider.class).getModifiers()));
        }finally{CoarseRenderHooks.detach(next);CoarseRenderHooks.detach(old);}
    }
}
