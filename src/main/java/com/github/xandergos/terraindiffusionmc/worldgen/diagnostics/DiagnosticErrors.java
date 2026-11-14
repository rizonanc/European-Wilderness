package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/** Failure side of PerformanceDiagnostics. Its existing worker owns all report IO. */
public final class DiagnosticErrors {
    public enum Code {
        INF_INPUT("TD-INF-001", "ONNX_INFERENCE", "Invalid model input shape"),
        INF_RUN("TD-INF-002", "ONNX_INFERENCE", "Native inference failed"),
        INF_OUTPUT("TD-INF-003", "ONNX_INFERENCE", "Invalid output dimensions/type"),
        INF_NONFINITE("TD-INF-004", "ONNX_INFERENCE", "Non-finite critical output"),
        INF_PROVIDER("TD-INF-005", "ONNX_INFERENCE", "Execution provider unavailable"),
        INF_CPU("TD-INF-006", "ONNX_INFERENCE", "CPU inference/fallback failed"),
        MOD_MISSING("TD-MOD-001", "MODEL_LOADING", "Required model missing"),
        MOD_INVALID("TD-MOD-002", "MODEL_LOADING", "Model integrity/initialization failed"),
        MOD_CACHE("TD-MOD-003", "MODEL_LOADING", "Optimized model cache rejected"),
        CAC_CRC("TD-CAC-001", "CACHE", "CRC mismatch"),
        CAC_VERSION("TD-CAC-002", "CACHE", "Unsupported cache version"),
        CAC_EOF("TD-CAC-003", "CACHE", "Truncated cache entry"),
        CAC_ENTRY("TD-CAC-004", "CACHE", "Cache entry validation failed"),
        CAC_FLIGHT("TD-CAC-005", "CACHE", "Shared tile computation failed"),
        CAC_QUOTA("TD-CAC-006", "CACHE", "Quota/accounting admission failed"),
        IO_READ("TD-IO-001", "DISK_IO", "Cache read failed"),
        IO_WRITE("TD-IO-002", "DISK_IO", "Cache write failed"),
        IO_REPORT("TD-IO-003", "DIAGNOSTICS", "Diagnostic report write failed"),
        HYD_PLAN("TD-HYD-001", "HYDROLOGY", "Water planning failed"),
        HYD_RAW("TD-HYD-002", "HYDROLOGY", "Required raw dependency missing"),
        HYD_DATA("TD-HYD-003", "HYDROLOGY", "Frozen water plan invalid/incompatible"),
        HYD_WRITE("TD-HYD-004", "HYDROLOGY", "Frozen water persistence failed"),
        WGN_REQUEST("TD-WGN-001", "WORLD_GENERATION", "Required terrain request failed"),
        WGN_INVARIANT("TD-WGN-002", "WORLD_GENERATION", "Generation invariant violated"),
        SCH_TASK("TD-SCH-001", "SCHEDULING", "Asynchronous task failed"),
        SCH_WORKER("TD-SCH-002", "SCHEDULING", "Worker terminated unexpectedly"),
        SCH_PRESSURE("TD-SCH-003", "SCHEDULING", "Admission saturation/long wait"),
        SCH_STALL("TD-SCH-004", "SCHEDULING", "Foreground or hydrology wait exceeded threshold"),
        MEM_PRESSURE("TD-MEM-001", "MEMORY", "GPU memory pressure; bounded recovery"),
        LFC_CANCEL("TD-LFC-001", "LIFECYCLE", "Cancelled or retired request"),
        LFC_SESSION("TD-LFC-002", "LIFECYCLE", "Invalid session lifecycle"),
        VOX_ABSENT("TD-VOX-001", "VOXY", "Optional dependency absent"),
        VOX_VERSION("TD-VOX-002", "VOXY", "Unsupported optional integration version"),
        VOX_API("TD-VOX-003", "VOXY", "Bridge API initialization/access failed"),
        VOX_CONVERT("TD-VOX-004", "VOXY", "LOD conversion/publication failed"),
        VOX_ASYNC("TD-VOX-005", "VOXY", "Asynchronous LOD request failed"),
        VOX_COARSE("TD-VOX-006", "VOXY", "Experimental coarse preview disabled by failure"),
        VOX_COARSE_BACKLOG("TD-VOX-007", "VOXY", "Experimental coarse preview backlog or ownership conflict");
        public final String id, subsystem, description;
        Code(String id,String subsystem,String description){this.id=id;this.subsystem=subsystem;this.description=description;}
    }
    public enum Outcome { RECOVERED, FAILED, CANCELLED, DEGRADED }
    public record Context(String requestId,String stage,String dimension,String session,String coordinates) {
        public Context stage(String value){return new Context(requestId,value,dimension,session,coordinates);}
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
    private record Notice(String utc,Code code,Context context,Outcome result,String recovery,String thread,String cause) {}
    private record Critical(Notice notice,Throwable failure,List<Notice> history) {}
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(PerformanceDiagnostics.class);
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private static final AtomicLong IDS=new AtomicLong();
    private static final DiagnosticErrors GLOBAL=new DiagnosticErrors(System::nanoTime,(text,failure)->LOG.error(text,failure));
    private final LongSupplier clock;
    private final BiConsumer<String,Throwable> testSink;
    private final long[] last=new long[Code.values().length*Outcome.values().length],seen=new long[last.length],suppressed=new long[last.length];
    private final ArrayDeque<Notice> events=new ArrayDeque<>();
    private final ArrayBlockingQueue<Critical> critical=new ArrayBlockingQueue<>(4);
    private volatile Map<String,String> environment=Map.of();
    private static volatile String provider="unknown";
    public static void provider(String value){provider=safe(value,80);}
    private volatile Supplier<Map<String,Object>> counters=Map::of;
    private volatile Runnable health=()->{};private long nextHealth;
    public static void health(Runnable check){GLOBAL.health=Objects.requireNonNull(check);}
    private static final Pattern SECRETS=Pattern.compile("(?i)(bearer\\s+|(?:token|password|passwd|secret|authorization|api[_-]?key)\\s*[=:]\\s*)[^\\s,;]+|(?<=://)[^/\\s:@]+:[^/\\s@]+@");
    private static final Pattern PATHS=Pattern.compile("(?i)[a-z]:[\\\\/][^\\r\\n\"<>]*|/(?:home|Users|tmp|var|mnt)/[^\\r\\n\"<>]*");
    public static boolean debug(){return Boolean.getBoolean("terrain.diffusion.diagnostics.debug")||LOG.isDebugEnabled();}
    DiagnosticErrors(LongSupplier clock,BiConsumer<String,Throwable> sink){this.clock=clock;this.testSink=sink;}
        public static Context context(String stage,String coordinates){
        var existing=CURRENT.get();if(existing!=null)return new Context(existing.requestId,stage,existing.dimension,existing.session,coordinates==null?existing.coordinates:coordinates);
        var world=WorldSession.current();
        return new Context("td-"+IDS.incrementAndGet(),stage,world==null?"unknown":world.diagnosticDimension(),world==null?"none":world.id().toString(),coordinates==null?"unknown":coordinates);
    }
    public static Context current(String stage){return context(stage,null);}
    public static Scope scope(Context context){var old=CURRENT.get();CURRENT.set(context);return ()->{if(old==null)CURRENT.remove();else CURRENT.set(old);};}
    public static void environment(Map<String,String> values,Supplier<Map<String,Object>> snapshot){GLOBAL.environment=Map.copyOf(values);GLOBAL.counters=Objects.requireNonNull(snapshot);}
    public static void emit(Code code,Context context,Throwable failure,Outcome result,String recovery,boolean report){
        try{GLOBAL.record(code,context,failure,result,recovery,report);}catch(Throwable diagnosticFailure){
            // Reporting must never replace the original exception or turn a recovered path into a crash.
            try{LOG.error("[TerrainDiffusion][diagnostic-failure] originalCode={}",code,failure);}catch(Throwable unavailable){}
        }
    }
    public static void failed(Code code,String stage,Throwable failure){
        if(cancelled(failure))emit(Code.LFC_CANCEL,current(stage),failure,Outcome.CANCELLED,"No result published",false);
        else emit(code,current(stage),failure,Outcome.FAILED,"Propagated; no substitute terrain published",true);
    }
    public static boolean cancelled(Throwable failure){
        for(int i=0;failure!=null&&i<16;i++,failure=failure.getCause())
            if(failure instanceof CancellationException||failure instanceof InterruptedException||failure instanceof WorldSession.RetiredSessionException)return true;
        return false;
    }
    public static Throwable root(Throwable failure){
        Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        while(failure!=null&&visited.add(failure)&&failure.getCause()!=null&&!visited.contains(failure.getCause()))failure=failure.getCause();
        return failure;
    }
    static String safe(String value,int limit){
        if(value==null)return "unknown";
        String bounded=value.substring(0,Math.min(value.length(),limit));
        return PATHS.matcher(SECRETS.matcher(bounded).replaceAll("<redacted>")).replaceAll("<path>").replace('\r',' ').replace('\n',' ');
    }
    static String line(Notice n){return "[TerrainDiffusion]["+n.code.id+"] Subsystem="+n.code.subsystem+" Stage="+n.context.stage
        +" Dimension="+n.context.dimension+" Session="+n.context.session+" Coordinates="+n.context.coordinates+" RequestId="+n.context.requestId
        +" Thread="+n.thread+" Provider="+provider+" Failure="+n.code.description+" Cause="+n.cause+" Recovery="+n.recovery+" Result="+n.result;}
    void record(Code code,Context context,Throwable failure,Outcome outcome,String recovery,boolean report){
        Objects.requireNonNull(code);Objects.requireNonNull(context);Objects.requireNonNull(outcome);
        int at=code.ordinal()*Outcome.values().length+outcome.ordinal();long now=clock.getAsLong(),skipped;
        var clean=new Context(safe(context.requestId,80),safe(context.stage,120),safe(context.dimension,120),safe(context.session,80),safe(context.coordinates,120));
        var cause=root(failure);
        var notice=new Notice(Instant.now().toString(),code,clean,outcome,safe(recovery,320),safe(Thread.currentThread().getName(),120),cause==null?"none":safe(cause.getClass().getSimpleName()+": "+cause.getMessage(),512));
        synchronized(this){
            seen[at]++;if(events.size()==64)events.removeFirst();events.addLast(notice);
            if(seen[at]>1&&now-last[at]<TimeUnit.SECONDS.toNanos(15)){suppressed[at]++;return;}
            last[at]=now;skipped=suppressed[at];suppressed[at]=0;
        }
        String message=line(notice)+(skipped==0?"":" SuppressedSincePrevious="+skipped);
        if(this!=GLOBAL)testSink.accept(message,failure);
        else if(outcome==Outcome.CANCELLED){if(debug())LOG.debug(message,failure);}
        else if(outcome==Outcome.FAILED)LOG.error(message,failure);
        else if(code==Code.VOX_ABSENT)LOG.info(message);
        else LOG.warn(message,failure);
        if(report&&outcome==Outcome.FAILED){List<Notice> history;synchronized(this){history=List.copyOf(events);}critical.offer(new Critical(notice,failure,history));}
    }
    public static Map<String,Object> statistics(){return GLOBAL.snapshot();}
    synchronized Map<String,Object> snapshot(){
        Map<String,Object> result=new LinkedHashMap<>();
        for(var code:Code.values()){
            long count=0,skipped=0;
            for(var outcome:Outcome.values()){int at=code.ordinal()*Outcome.values().length+outcome.ordinal();count+=seen[at];skipped+=suppressed[at];}
            if(count!=0)result.put(code.id,Map.of("seen",count,"suppressed_pending",skipped));
        }
        result.put("pending_reports",critical.size());result.put("recent_events",events.size());return result;
    }
    /** Called only by the existing performance-diagnostics worker, including on dedicated servers. */
    static void drain(Path root){try{long now=GLOBAL.clock.getAsLong();if(now>=GLOBAL.nextHealth){GLOBAL.nextHealth=now+TimeUnit.SECONDS.toNanos(15);GLOBAL.health.run();}GLOBAL.writeNext(root);}catch(Throwable unavailable){emit(Code.IO_REPORT,current("CRITICAL_REPORT"),unavailable,Outcome.DEGRADED,"Original exception retained in standard log",false);}}
    void writeNext(Path directory){
        Critical item=critical.poll();if(item==null)return;
        try{
            var data=new LinkedHashMap<String,Object>();data.put("schema",1);data.put("error_code",item.notice.code.id);data.put("subsystem",item.notice.code.subsystem);data.put("provider",provider);data.put("event",item.notice);data.put("environment",environment);
            data.put("java",safe(System.getProperty("java.version"),80));data.put("platform",safe(System.getProperty("os.name")+"/"+System.getProperty("os.arch"),100));
            data.put("history",item.history);data.put("diagnostics",snapshot());
            try{data.put("pipeline_counters",counters.get());}catch(RuntimeException unavailable){data.put("counters_unavailable",safe(unavailable.getClass().getSimpleName(),80));}
            data.put("stack_trace",stack(item.failure));data.put("conclusion","Recorded failure and recovery status; no unproven root-cause attribution.");
            var gson=new Gson();byte[] bytes=gson.toJson(data).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>128*1024){data.put("history",List.of(item.notice));data.put("report_truncated",true);bytes=gson.toJson(data).getBytes(StandardCharsets.UTF_8);}
            if(bytes.length>128*1024){data.put("pipeline_counters","omitted: size limit");data.put("stack_trace",safe(stack(item.failure),6000));bytes=gson.toJson(data).getBytes(StandardCharsets.UTF_8);}
            if(bytes.length>128*1024)throw new IOException("Diagnostic report exceeds 128 KiB limit");
            Files.createDirectories(directory);
            try(var files=Files.list(directory)){
                var owned=files.filter(p->p.getFileName().toString().matches("td-critical-[0-9]+-[0-9]+\\.json")).sorted(Comparator.comparing(p->p.getFileName().toString())).toList();
                for(int i=0;i<=owned.size()-10;i++)Files.deleteIfExists(owned.get(i));
            }
            Files.write(directory.resolve("td-critical-"+System.currentTimeMillis()+"-"+IDS.incrementAndGet()+".json"),bytes,StandardOpenOption.CREATE_NEW);
        }catch(Exception failure){record(Code.IO_REPORT,item.notice.context,failure,Outcome.DEGRADED,"Original failure remains in latest.log; report unavailable",false);}
    }
    private static String stack(Throwable failure){
        if(failure==null)return "none";
        // Bound construction too: Throwable.printStackTrace can otherwise allocate an unlimited StringWriter.
        var out=new StringBuilder();var visited=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        appendStack(failure,out,visited,"");return out.toString();
    }
    private static void appendStack(Throwable failure,StringBuilder out,Set<Throwable> seen,String prefix){
        if(failure==null||!seen.add(failure)||out.length()>24000)return;
        if(seen.size()>32){out.append("[exception chain truncated]\n");return;}
        out.append(prefix).append(safe(failure.toString(),1024)).append('\n');
        for(var frame:failure.getStackTrace()){if(out.length()>24000){out.append("[stack truncated]\n");return;}out.append("  at ").append(safe(frame.toString(),512)).append('\n');}
        for(var suppressed:failure.getSuppressed())appendStack(suppressed,out,seen,"Suppressed: ");
        appendStack(failure.getCause(),out,seen,"Caused by: ");
    }
}
