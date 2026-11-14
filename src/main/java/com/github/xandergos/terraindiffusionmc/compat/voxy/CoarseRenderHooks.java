package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Common entry points keep Voxy types out of the ordinary mod initializer. */
public final class CoarseRenderHooks {
    public interface Provider {
        Object render(Object engine,long position,Object original);
        default Object neighbor(Object engine,long position,Object original){return render(engine,position,original);}
        byte children(Object engine,Object section,byte actual);
        default void observeService(Object engine,Object service){}
        default String describe(Object engine,long position){return "coarse provider: no diagnostic";}
        default String describePoint(Object engine,long position,int x,int y,int z){return describe(engine,position);}
        default boolean meshCurrent(long position,long revision){return true;}
        default void staleMesh(){}
    }
    private static volatile Provider active;
    private record MeshStamp(Provider owner,long position,long revision){}
    // Model-bake retries retain a virtual WorldSection; its original revision must survive the retry.
    private static final java.util.Map<Object,MeshStamp> stamps=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private CoarseRenderHooks(){}
    public static synchronized void attach(Provider provider){active=provider;}
    public static synchronized void detach(Provider provider){if(active==provider)active=null;}
    public static Object render(Object engine,long position,Object original){var p=active;return p==null?original:p.render(engine,position,original);}
    public static Object neighbor(Object engine,long position,Object original){var p=active;return p==null?original:p.neighbor(engine,position,original);}
    public static byte children(Object engine,Object section,byte actual){var p=active;return p==null?actual:p.children(engine,section,actual);}
    public static void observeService(Object engine,Object service){var p=active;if(p!=null)p.observeService(engine,service);}
    public static String describe(Object engine,long position){var p=active;return p==null?"coarse provider: detached":p.describe(engine,position);}
    public static String describePoint(Object engine,long position,int x,int y,int z){var p=active;return p==null?"coarse provider: detached":p.describePoint(engine,position,x,y,z);}
    public static void tag(Object section,Provider owner,long position,long revision){if(section!=null)stamps.put(section,new MeshStamp(owner,position,revision));}
    public static boolean meshCurrent(Object section){
        var stamp=stamps.get(section);if(stamp==null)return true;
        if(stamp.owner==active&&stamp.owner.meshCurrent(stamp.position,stamp.revision))return true;
        stamp.owner.staleMesh();return false;
    }
    public static void meshFinished(Object section){stamps.remove(section);}
    public static void copyMeshStamp(Object section,Object mesh){var stamp=stamps.get(section);if(stamp!=null&&mesh!=null)stamps.put(mesh,stamp);}
}
