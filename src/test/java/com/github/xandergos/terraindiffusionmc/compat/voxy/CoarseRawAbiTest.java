package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseRawAbiTest {
    /** Only the environment flag provider is stubbed. WorldSection itself is
     * loaded unmodified from the pinned public Voxy jar, without Minecraft/GL. */
    static final class RawLoader extends ClassLoader {
        RawLoader(){super(CoarseRawAbiTest.class.getClassLoader());}
        @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            if(!name.equals("me.cortex.voxy.common.world.WorldSection")&&!name.equals("me.cortex.voxy.commonImpl.VoxyCommon")
                &&!name.startsWith("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseVoxyRenderer")
                &&!name.startsWith("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseStoredColumns")
                &&!name.startsWith("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseMeshTracker"))return super.loadClass(name,resolve);
            Class<?> loaded=findLoadedClass(name);if(loaded!=null)return loaded;
            byte[] bytes;
            if(name.endsWith("VoxyCommon")) {
                var writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name.replace('.','/'),null,"java/lang/Object",null);
                var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"isVerificationFlagOn","(Ljava/lang/String;)Z",null,null);
                method.visitCode();method.visitInsn(Opcodes.ICONST_0);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(1,1);method.visitEnd();writer.visitEnd();bytes=writer.toByteArray();
            } else try(var input=getResourceAsStream(name.replace('.','/')+".class")){bytes=input.readAllBytes();}
              catch(IOException failure){throw new ClassNotFoundException(name,failure);}
            loaded=defineClass(name,bytes,0,bytes.length);if(resolve)resolveClass(loaded);return loaded;
        }
    }
    @Test void realVoxyUntrackedSectionAcquiresCopiesMetadataAndReleasesWithoutAWorldOrSave() throws Exception {
        Class<?> type=new RawLoader().loadClass("me.cortex.voxy.common.world.WorldSection");
        Method create=type.getMethod("_createRawUntrackedUnsafeSection",int.class,int.class,int.class,int.class);
        Object source=create.invoke(null,4,-2,0,1),preview=create.invoke(null,4,-2,0,1);
        type.getMethod("acquire").invoke(source);type.getMethod("acquire").invoke(preview);
        long[] original=(long[])type.getMethod("_unsafeGetRawDataArray").invoke(source);
        original[0]=99;long[] unchanged=original.clone();
        long[] target=(long[])type.getMethod("_unsafeGetRawDataArray").invoke(preview);
        type.getMethod("copyDataTo",long[].class).invoke(source,(Object)target);
        var field=new CoarseLodPatch.Surface[1024];Arrays.fill(field,new CoarseLodPatch.Surface(64,(short)229,0));
        long[] palette={0,1,2,3,4,5,6,7,8,9,10,11,12};
        var result=CoarseSectionComposer.fill(4,-2,0,1,-64,1871,field,target,target,(x,z)->x< -1008&&z<528,b->palette,v->v==0);
        type.getMethod("_unsafeSetNonEmptyChildren",byte.class).invoke(preview,result.children());
        assertEquals(result.children(),type.getMethod("getNonEmptyChildren").invoke(preview));
        assertArrayEquals(unchanged,original);assertEquals(99,target[0]);assertEquals(0,target[1024]);
        assertEquals(1,type.getMethod("getRefCount").invoke(preview));
        assertEquals(0,type.getMethod("release").invoke(preview));
        assertNull(type.getMethod("_unsafeGetRawDataArray").invoke(preview));
        assertEquals(1,type.getMethod("getRefCount").invoke(source));assertArrayEquals(unchanged,original);
        assertEquals(0,type.getMethod("release").invoke(source));
    }
}
