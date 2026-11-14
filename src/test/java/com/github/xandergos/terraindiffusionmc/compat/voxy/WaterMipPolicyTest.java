package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

public class WaterMipPolicyTest {
    private static final int WATER=7,SAND=171,STONE=2;
    private static long voxel(int id){return ((long)id<<27)|(15L<<56);}
    public static boolean air(long value){return WaterMipPolicy.blockId(value)==0;}
    public static int opacity(long value){int id=WaterMipPolicy.blockId(value);return id==WATER?1:id==31||id==134||id==135?0:15;}
    public static int grassKind(int id){return id==0?GrassMipMapping.AIR:id==171?GrassMipMapping.GROUND:id==31?GrassMipMapping.SHORT:id==134||id==135?GrassMipMapping.TALL:GrassMipMapping.OTHER;}
    public static int light(long value){return (int)(value>>>56);}
    public static long withLight(long value,int light){return (value&0x00FFFFFFFFFFFFFFL)|((long)(light&255)<<56);}
    public static boolean water(int id){return id==WATER;}
    public static long surfaceWater(long value){int id=WaterMipPolicy.blockId(value);return id==WATER?value:id==181?(value&~(0xFFFFFL<<27))|((long)WATER<<27):0;}
    private static final WaterMipMapping MARINE=new WaterMipMapping(){
        public boolean ewh$isWater(int id){return water(id);}
    };

    /** Only Mapper's environment-bound palette methods are stubbed. The mipper
     * bytecode is loaded unmodified from the exact supported Voxy JAR. */
    static final class Fixture extends ClassLoader {
        final Method mip;final Object mapper;
        private final Map<String,byte[]> definitions;
        Fixture() throws Exception {this(Map.of());}
        Fixture(Map<String,byte[]> definitions) throws Exception {
            super(WaterMipPolicyTest.class.getClassLoader());
            this.definitions=definitions;
            Class<?> map=loadClass("me.cortex.voxy.common.world.other.Mapper");mapper=map.getConstructor().newInstance();
            mip=loadClass("me.cortex.voxy.common.world.other.Mipper").getMethod("mip",
                long.class,long.class,long.class,long.class,long.class,long.class,long.class,long.class,map);
        }
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            if(!Set.of("me.cortex.voxy.common.world.other.Mapper","me.cortex.voxy.common.world.other.Mipper",
                "me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper","me.cortex.voxy.common.voxelization.VoxelizedSection",
                "me.cortex.voxy.common.world.WorldSection","me.cortex.voxy.common.world.WorldUpdater","me.cortex.voxy.commonImpl.VoxyCommon").contains(name))return super.loadClass(name,resolve);
            Class<?> found=findLoadedClass(name);if(found!=null)return found;
            byte[] bytes;
            if(name.endsWith("Mapper")){
                var w=new ClassWriter(0);w.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name.replace('.','/'),null,"java/lang/Object",new String[]{WaterMipMapping.class.getName().replace('.','/'),GrassMipMapping.class.getName().replace('.','/')});
                var ctor=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(1,1);ctor.visitEnd();
                bridge(w,"isAir","(J)Z","air",true,Opcodes.IRETURN);
                bridge(w,"getBlockStateOpacity","(J)I","opacity",false,Opcodes.IRETURN);
                bridge(w,"getLightId","(J)I","light",true,Opcodes.IRETURN);
                bridge(w,"withLight","(JI)J","withLight",true,Opcodes.LRETURN);
                var water=w.visitMethod(Opcodes.ACC_PUBLIC,"ewh$isWater","(I)Z",null,null);water.visitCode();water.visitVarInsn(Opcodes.ILOAD,1);water.visitMethodInsn(Opcodes.INVOKESTATIC,WaterMipPolicyTest.class.getName().replace('.','/'),"water","(I)Z",false);water.visitInsn(Opcodes.IRETURN);water.visitMaxs(1,2);water.visitEnd();
                bridge(w,"ewh$surfaceWaterValue","(J)J","surfaceWater",false,Opcodes.LRETURN);
                var grass=w.visitMethod(Opcodes.ACC_PUBLIC,"ewh$grassKind","(I)I",null,null);grass.visitCode();grass.visitVarInsn(Opcodes.ILOAD,1);grass.visitMethodInsn(Opcodes.INVOKESTATIC,WaterMipPolicyTest.class.getName().replace('.','/'),"grassKind","(I)I",false);grass.visitInsn(Opcodes.IRETURN);grass.visitMaxs(1,2);grass.visitEnd();
                w.visitEnd();bytes=w.toByteArray();
            }else if(name.endsWith("VoxyCommon")){
                var w=new ClassWriter(0);w.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name.replace('.','/'),null,"java/lang/Object",null);
                w.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL,"IS_MINE_IN_ABYSS","Z",null,0).visitEnd();
                var m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"isVerificationFlagOn","(Ljava/lang/String;)Z",null,null);m.visitCode();m.visitInsn(Opcodes.ICONST_0);m.visitInsn(Opcodes.IRETURN);m.visitMaxs(1,1);m.visitEnd();w.visitEnd();bytes=w.toByteArray();
            }else if(definitions.containsKey(name)){bytes=definitions.get(name);
            }else try(var in=getResourceAsStream(name.replace('.','/')+".class")){bytes=in.readAllBytes();}
             catch(Exception failure){throw new ClassNotFoundException(name,failure);}
            found=defineClass(name,bytes,0,bytes.length);if(resolve)resolveClass(found);return found;
        }
        private void bridge(ClassWriter w,String name,String descriptor,String target,boolean stat,int ret){
            var m=w.visitMethod(Opcodes.ACC_PUBLIC|(stat?Opcodes.ACC_STATIC:0),name,descriptor,null,null);m.visitCode();
            m.visitVarInsn(Opcodes.LLOAD,stat?0:1);
            if(descriptor.equals("(JI)J"))m.visitVarInsn(Opcodes.ILOAD,2);
            m.visitMethodInsn(Opcodes.INVOKESTATIC,WaterMipPolicyTest.class.getName().replace('.','/'),target,descriptor,false);
            m.visitInsn(ret);m.visitMaxs(3,4);m.visitEnd();
        }
        long reduce(long[] a,boolean fix)throws Exception {
            Object[] arguments={a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],mapper};
            long original=(long)mip.invoke(null,arguments);
            return fix?WaterMipPolicy.preserve(original,a[4],a[5],a[6],a[7],MARINE):original;
        }
    }

    @Test void realVoxyDropsCompleteWaterSurfaceAboveSandAndPolicyPreservesExistingWaterMapping()throws Exception {
        var f=new Fixture();long sand=voxel(SAND),water=voxel(WATER);
        long[] cell={sand,sand,sand,sand,water,water,water,water};
        assertEquals(sand,f.reduce(cell,false));assertEquals(water,f.reduce(cell,true));
    }
    @Test void thinSheetsSurviveAllFourMipLevelsAtEveryVerticalAlignment()throws Exception {
        var fixture=new Fixture();int regressions=0;
        for(int surface=0;surface<16;surface++)for(boolean fixed:new boolean[]{false,true}){
            int n=16;long[] data=new long[n*n*n];
            for(int y=0;y<n;y++)for(int z=0;z<n;z++)for(int x=0;x<n;x++)data[y*n*n+z*n+x]=y<surface?voxel(SAND):y==surface?voxel(WATER):0;
            while(n>1){int next=n/2;long[] reduced=new long[next*next*next];
                for(int y=0;y<next;y++)for(int z=0;z<next;z++)for(int x=0;x<next;x++){
                    long[] cell=new long[8];int p=0;
                    for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)cell[p++]=data[(y*2+dy)*n*n+(z*2+dz)*n+x*2+dx];
                    reduced[y*next*next+z*next+x]=fixture.reduce(cell,fixed);
                }data=reduced;n=next;
            }
            if(fixed)assertEquals(WATER,WaterMipPolicy.blockId(data[0]),"surface="+surface);
            else if(WaterMipPolicy.blockId(data[0])!=WATER)regressions++;
        }
        assertEquals(15,regressions);
    }
    @Test void mixedShoreRoofAndNonWaterCasesRetainPinnedDecision()throws Exception {
        var f=new Fixture();long s=voxel(SAND),w=voxel(WATER),roof=voxel(STONE);
        for(long[] cell:new long[][]{
            {s,s,s,s,w,w,w,0}, {s,s,s,s,w,w,roof,w}, {w,w,w,w,roof,roof,roof,roof},
            {s,s,s,s,roof,roof,roof,roof}, {0,0,0,0,w,w,w,w}, {s,s,s,s,0,0,0,0}})
            assertEquals(f.reduce(cell,false),f.reduce(cell,true));
        long top=voxel(WATER)|(23L<<47);
        assertEquals(top,WaterMipPolicy.preserve(s,w,w,w,top,id->id==WATER));
    }
    @Test void uniformTerrainAndExistingWaterUseNoPaletteClassification(){
        WaterMipMapping forbidden=id->{fail("Uniform cell must use quick path");return false;};
        for(int id=0;id<1024;id++)assertEquals(voxel(id),WaterMipPolicy.preserve(voxel(id),voxel(id),voxel(id),voxel(id),voxel(id),forbidden));
    }
    @Test void pinnedAbiSupportsClassificationAndDerivedOnlyRebuild()throws Exception {
        var mapper=node("me/cortex/voxy/common/world/other/Mapper");
        assertTrue(mapper.fields.stream().anyMatch(f->f.name.equals("blockId2stateEntry")&&f.desc.equals("Lit/unimi/dsi/fastutil/objects/ObjectArrayList;")));
        assertTrue(node("me/cortex/voxy/common/world/other/Mapper$StateEntry").fields.stream().anyMatch(f->f.name.equals("state")&&(f.access&Opcodes.ACC_PUBLIC)!=0));
        assertTrue(node("me/cortex/voxy/common/world/WorldSection").methods.stream().anyMatch(m->m.name.equals("copyData")&&m.desc.equals("()[J")));
        assertTrue(node("me/cortex/voxy/common/world/WorldUpdater").methods.stream().anyMatch(m->m.name.equals("insertSectionLvlIntoWorld")&&m.desc.equals("(Lme/cortex/voxy/common/voxelization/VoxelizedSection;Lme/cortex/voxy/common/world/WorldSection;)J")&&(m.access&Opcodes.ACC_STATIC)!=0));
        var engine=node("me/cortex/voxy/common/world/WorldEngine");
        assertTrue(engine.methods.stream().anyMatch(m->m.name.equals("markDirty")&&m.desc.equals("(Lme/cortex/voxy/common/world/WorldSection;II)V")));
        assertTrue(engine.fields.stream().anyMatch(f->f.name.equals("UPDATE_TYPE_BLOCK_BIT")&&(f.access&Opcodes.ACC_PUBLIC)!=0));
        var sectionMipper=node("me/cortex/voxy/common/voxelization/WorldVoxilizedSectionMipper");
        long sites=sectionMipper.methods.stream().filter(m->m.name.equals("mipSection"))
            .flatMap(m->Arrays.stream(m.instructions.toArray()))
            .filter(i->i instanceof org.objectweb.asm.tree.MethodInsnNode call&&call.owner.equals("me/cortex/voxy/common/world/other/Mipper")&&call.name.equals("mip")&&call.desc.equals("(JJJJJJJJLme/cortex/voxy/common/world/other/Mapper;)J")).count();
        assertEquals(4,sites);
    }
    @Test void selectiveRepairMatchesPinnedParentLayoutAndPreservesConcurrentAndBaseData()throws Exception {
        var f=new Fixture();Class<?> section=f.loadClass("me.cortex.voxy.common.world.WorldSection");
        Class<?> voxel=f.loadClass("me.cortex.voxy.common.voxelization.VoxelizedSection");
        Object input=voxel.getMethod("createEmpty").invoke(null);long[] data=(long[])voxel.getField("section").get(input);
        Arrays.fill(data,4096,data.length,voxel(SAND));long[] base=Arrays.copyOf(data,4096),fixed=data.clone();
        Arrays.fill(fixed,4096,fixed.length,voxel(WATER));
        voxel.getMethod("setPosition",int.class,int.class,int.class).invoke(input,-3,3,-5);
        Method write=f.loadClass("me.cortex.voxy.common.world.WorldUpdater").getDeclaredMethod("insertSectionLvlIntoWorld",voxel,section);write.setAccessible(true);
        Method index=section.getMethod("getIndex",int.class,int.class,int.class);
        for(int level=1;level<=4;level++){
            Object parent=section.getMethod("_createRawUntrackedUnsafeSection",int.class,int.class,int.class,int.class)
                .invoke(null,level,-3>>(level+1),3>>(level+1),-5>>(level+1));
            section.getMethod("acquire").invoke(parent);
            try{
                long[] values=(long[])section.getMethod("_unsafeGetRawDataArray").invoke(parent);Arrays.fill(values,voxel(STONE));
                assertEquals(1,(long)write.invoke(null,input,parent)&1);
                int expected=1<<(3*(4-level));
                assertEquals(0,(long)write.invoke(null,input,parent)&1);
                // Simulate another writer changing one expected old value before migration.
                int size=16>>level,mask=(1<<(level+1))-1;
                int protectedIndex=(int)index.invoke(null,(-3&mask)*size,(3&mask)*size,(-5&mask)*size);
                values[protectedIndex]=voxel(STONE);
                assertEquals(expected-1,WaterMipRepairData.patch(level,-3,3,-5,values,data,fixed,id->id==WATER));
                assertEquals(voxel(STONE),values[protectedIndex]);
                assertEquals(expected-1,Arrays.stream(values).filter(v->WaterMipPolicy.blockId(v)==WATER).count());
                assertEquals(32768-expected+1,Arrays.stream(values).filter(v->WaterMipPolicy.blockId(v)==STONE).count());
                assertEquals(0,WaterMipRepairData.patch(level,-3,3,-5,values,data,fixed,id->id==WATER));
                assertArrayEquals(base,Arrays.copyOf(data,4096));
            }finally{section.getMethod("release").invoke(parent);}
        }
    }
    @Test void stockHierarchyBuilderMatchesEveryVoxelOfRealPinnedSectionMipper()throws Exception {
        var f=new Fixture();Class<?> voxel=f.loadClass("me.cortex.voxy.common.voxelization.VoxelizedSection");
        Object input=voxel.getMethod("createEmpty").invoke(null);long[] nativeData=(long[])voxel.getField("section").get(input);
        var random=new Random(912);
        for(int i=0;i<4096;i++)nativeData[i]=voxel(new int[]{0,WATER,SAND,STONE}[random.nextInt(4)]);
        long[] ours=nativeData.clone();
        WaterMipRepairData.stock(ours,(a,b,c,d,e,g,h,i)->f.reduce(new long[]{a,b,c,d,e,g,h,i},false));
        f.loadClass("me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper")
            .getMethod("mipSection",voxel,f.mapper.getClass()).invoke(null,input,f.mapper);
        assertArrayEquals(nativeData,ours);
        for(int level=1;level<=4;level++)assertEquals(voxel.getMethod("getBaseIndexForLevel",int.class).invoke(null,level),WaterMipRepairData.offset(level));
        assertThrows(IllegalArgumentException.class,()->WaterMipRepairData.offset(0));
    }
    @Test void repairNeverConvertsAirOrOtherWaterStates(){
        long[] parent=new long[32768],stock=new long[4681],fixed=new long[4681];Arrays.fill(fixed,voxel(WATER));
        assertEquals(0,WaterMipRepairData.patch(4,0,0,0,parent,stock,fixed,id->id==WATER));
        Arrays.fill(parent,voxel(WATER));Arrays.fill(stock,voxel(WATER));Arrays.fill(fixed,voxel(8));
        assertEquals(0,WaterMipRepairData.patch(4,0,0,0,parent,stock,fixed,id->id==WATER||id==8));
    }
    private ClassNode node(String name)throws Exception {
        var node=new ClassNode();try(var in=getClass().getClassLoader().getResourceAsStream(name+".class")){assertNotNull(in);new ClassReader(in).accept(node,0);}return node;
    }
}
