import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Metrics {

    public record Sample(long latencyMs, long queueMs, long cpuMs) {}

    private final ThreadPoolExecutor pool;
    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final long startNanos = System.nanoTime();

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    private final List<Sample> allSamples = new ArrayList<>();
    private List<Sample> intervalSamples = new ArrayList<>();

    private long lastTickNanos = startNanos;
    private long lastGcCount;
    private long lastGcTimeMs;

    public Metrics(ThreadPoolExecutor pool) {
        this.pool = pool;
        long[] gc = gcTotals();
        lastGcCount = gc[0];
        lastGcTimeMs = gc[1];
    }

    public int requestStarted() {
        return inFlight.incrementAndGet();
    }

    public void requestSucceeded(Sample sample) {
        inFlight.decrementAndGet();
        synchronized (this) {
            allSamples.add(sample);
            intervalSamples.add(sample);
        }
    }

    public void requestRejected() {
        inFlight.decrementAndGet();
        rejected.incrementAndGet();
    }

    public void requestFailed() {
        inFlight.decrementAndGet();
        errors.incrementAndGet();
    }

    public int queueSize() {
        return pool.getQueue().size();
    }

    public void logInterval() {
        List<Sample> samples;
        synchronized (this) {
            samples = intervalSamples;
            intervalSamples = new ArrayList<>();
        }

        long now = System.nanoTime();
        double seconds = (now - lastTickNanos) / 1e9;
        lastTickNanos = now;

        long[] gc = gcTotals();
        long gcCount = gc[0] - lastGcCount;
        long gcTimeMs = gc[1] - lastGcTimeMs;
        lastGcCount = gc[0];
        lastGcTimeMs = gc[1];

        if (samples.isEmpty() && inFlight.get() == 0) {
            return;
        }

        long[] latencies = samples.stream().mapToLong(Sample::latencyMs).sorted().toArray();
        Runtime rt = Runtime.getRuntime();
        Log.info("metricas intervalo_s=%.1f req_ok=%d req_por_s=%.2f lat_media_ms=%.0f lat_p95_ms=%d lat_max_ms=%d "
                        + "fila_media_ms=%.0f cpu_media_ms=%.0f em_andamento=%d threads_ativas=%d/%d fila=%d "
                        + "cpu_processo_pct=%.1f cpu_sistema_pct=%.1f load_avg=%.2f heap_usado_mb=%d heap_total_mb=%d "
                        + "gc_coletas=%d gc_ms=%d",
                seconds, samples.size(), samples.size() / seconds, mean(latencies), percentile(latencies, 0.95),
                percentile(latencies, 1.0),
                samples.stream().mapToLong(Sample::queueMs).average().orElse(0),
                samples.stream().mapToLong(Sample::cpuMs).average().orElse(0),
                inFlight.get(), pool.getActiveCount(), pool.getMaximumPoolSize(), pool.getQueue().size(),
                os.getProcessCpuLoad() * 100, os.getCpuLoad() * 100, os.getSystemLoadAverage(),
                (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024), rt.totalMemory() / (1024 * 1024),
                gcCount, gcTimeMs);
    }

    public void logSummary() {
        List<Sample> samples;
        synchronized (this) {
            samples = new ArrayList<>(allSamples);
        }

        double uptime = (System.nanoTime() - startNanos) / 1e9;
        long[] latencies = samples.stream().mapToLong(Sample::latencyMs).sorted().toArray();
        long[] gc = gcTotals();
        Log.info("resumo uptime_s=%.1f req_ok=%d rejeitadas=%d erros=%d req_por_s=%.2f lat_media_ms=%.0f "
                        + "lat_p50_ms=%d lat_p95_ms=%d lat_p99_ms=%d lat_max_ms=%d fila_media_ms=%.0f cpu_media_ms=%.0f "
                        + "gc_coletas=%d gc_ms=%d",
                uptime, samples.size(), rejected.get(), errors.get(), samples.size() / uptime, mean(latencies),
                percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99),
                percentile(latencies, 1.0),
                samples.stream().mapToLong(Sample::queueMs).average().orElse(0),
                samples.stream().mapToLong(Sample::cpuMs).average().orElse(0),
                gc[0], gc[1]);
    }

    private static double mean(long[] values) {
        return Arrays.stream(values).average().orElse(0);
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }

    private static long[] gcTotals() {
        long count = 0, timeMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, gc.getCollectionCount());
            timeMs += Math.max(0, gc.getCollectionTime());
        }
        return new long[] {count, timeMs};
    }
}
