import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/**
 * Backend HTTP para restauração de imagens (perfil CPU-bound).
 *
 * Endpoint:
 *   POST /restore  -> restaura a imagem enviada no corpo e devolve o PNG
 */
public class ImageRestorationServer {

    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    // Identificador sequencial para correlacionar as linhas de log de uma mesma requisição
    private static final AtomicLong requestCounter = new AtomicLong();

    // Momento em que a requisição entrou na fila do pool, para medir o tempo de espera
    private static final ThreadLocal<Long> enqueuedAt = new ThreadLocal<>();

    private static final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    private static Metrics metrics;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        int threads = Integer.parseInt(env("THREADS", String.valueOf(Runtime.getRuntime().availableProcessors())));
        int metricsIntervalS = Integer.parseInt(env("METRICS_INTERVAL_S", "5"));

        AtomicInteger workerId = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> new Thread(r, "worker-" + workerId.incrementAndGet()));
        metrics = new Metrics(pool);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/restore", ImageRestorationServer::handleRestore);
        server.setExecutor(task -> {
            long queuedAt = System.nanoTime();
            pool.execute(() -> {
                enqueuedAt.set(queuedAt);
                task.run();
            });
        });
        server.start();

        if (metricsIntervalS > 0) {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "metrics");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(metrics::logInterval, metricsIntervalS, metricsIntervalS, TimeUnit.SECONDS);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(metrics::logSummary, "shutdown"));

        Log.info("servidor iniciado porta=%d threads=%d cpus=%d max_heap_mb=%d metricas_intervalo_s=%d "
                        + "cpu_time_suportado=%b java=%s",
                port, threads, Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024), metricsIntervalS,
                threadBean.isCurrentThreadCpuTimeSupported(), System.getProperty("java.version"));
    }

    private static void handleRestore(HttpExchange exchange) throws IOException {
        long id = requestCounter.incrementAndGet();
        long start = System.nanoTime();
        long cpuStart = threadBean.getCurrentThreadCpuTime();
        Long queuedAt = enqueuedAt.get();
        long queueMs = queuedAt == null ? 0 : (start - queuedAt) / 1_000_000;
        int inFlight = metrics.requestStarted();
        int queueSize = metrics.queueSize();
        String method = exchange.getRequestMethod();
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();

        try {
            if (!method.equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendError(exchange, 405, "metodo nao permitido");
                logRejected(id, method, client, 405, "metodo nao permitido", start);
                return;
            }

            byte[] body = exchange.getRequestBody().readNBytes(MAX_UPLOAD_BYTES + 1);
            if (body.length > MAX_UPLOAD_BYTES) {
                sendError(exchange, 413, "imagem maior que 10 MB");
                logRejected(id, method, client, 413, "corpo maior que " + MAX_UPLOAD_BYTES + " bytes", start);
                return;
            }

            long t0 = System.nanoTime();
            BufferedImage input = ImageIO.read(new ByteArrayInputStream(body));
            long decodeMs = elapsedMs(t0);
            if (input == null) {
                sendError(exchange, 400, "corpo da requisicao nao e uma imagem valida");
                logRejected(id, method, client, 400, "imagem invalida (" + body.length + " bytes)", start);
                return;
            }

            t0 = System.nanoTime();
            long restoreCpuStart = threadBean.getCurrentThreadCpuTime();
            BufferedImage output = Deconvolution.restore(input);
            long restoreCpuMs = (threadBean.getCurrentThreadCpuTime() - restoreCpuStart) / 1_000_000;
            long restoreMs = elapsedMs(t0);

            t0 = System.nanoTime();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(output, "png", png);
            long encodeMs = elapsedMs(t0);

            long totalMs = elapsedMs(start);
            exchange.getResponseHeaders().set("X-Processing-Time-Ms", String.valueOf(totalMs));
            send(exchange, 200, "image/png", png.toByteArray());

            long cpuMs = (threadBean.getCurrentThreadCpuTime() - cpuStart) / 1_000_000;
            long latencyMs = queueMs + elapsedMs(start);
            long pixels = (long) input.getWidth() * input.getHeight();
            metrics.requestSucceeded(new Metrics.Sample(latencyMs, queueMs, cpuMs));

            // cpu_ratio = CPU da thread / tempo de relógio no algoritmo; abaixo de 1 indica disputa por núcleos
            Log.info("req=%d %s /restore client=%s status=200 img=%dx%d pixels=%d in_bytes=%d out_bytes=%d "
                            + "em_andamento=%d fila=%d queue_ms=%d decode_ms=%d restore_ms=%d restore_cpu_ms=%d "
                            + "cpu_ratio=%.2f encode_ms=%d total_ms=%d cpu_ms=%d latency_ms=%d mpixels_por_s=%.3f",
                    id, method, client, input.getWidth(), input.getHeight(), pixels, body.length, png.size(),
                    inFlight, queueSize, queueMs, decodeMs, restoreMs, restoreCpuMs,
                    restoreMs == 0 ? 0.0 : (double) restoreCpuMs / restoreMs, encodeMs, totalMs, cpuMs, latencyMs,
                    restoreMs == 0 ? 0.0 : pixels / 1e6 / (restoreMs / 1000.0));
        } catch (Exception e) {
            metrics.requestFailed();
            Log.error("req=%d %s /restore client=%s status=500 total_ms=%d erro=%s",
                    id, method, client, elapsedMs(start), e);
            e.printStackTrace();
            sendError(exchange, 500, "erro interno");
        }
    }

    private static void logRejected(long id, String method, String client, int status, String reason, long start) {
        metrics.requestRejected();
        Log.warn("req=%d %s /restore client=%s status=%d motivo=\"%s\" total_ms=%d",
                id, method, client, status, reason, elapsedMs(start));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "application/json", ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }
}
