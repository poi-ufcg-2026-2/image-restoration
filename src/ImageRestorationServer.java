import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
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
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Identificador sequencial para correlacionar as linhas de log de uma mesma requisição
    private static final AtomicLong requestCounter = new AtomicLong();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        int threads = Integer.parseInt(env("THREADS", String.valueOf(Runtime.getRuntime().availableProcessors())));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/restore", ImageRestorationServer::handleRestore);
        server.setExecutor(Executors.newFixedThreadPool(threads));
        server.start();

        log("INFO", "servidor iniciado porta=%d threads=%d cpus=%d max_heap_mb=%d",
                port, threads, Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    private static void handleRestore(HttpExchange exchange) throws IOException {
        long id = requestCounter.incrementAndGet();
        long start = System.nanoTime();
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
            BufferedImage output = Deconvolution.restore(input);
            long restoreMs = elapsedMs(t0);

            t0 = System.nanoTime();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(output, "png", png);
            long encodeMs = elapsedMs(t0);

            long totalMs = elapsedMs(start);
            exchange.getResponseHeaders().set("X-Processing-Time-Ms", String.valueOf(totalMs));
            send(exchange, 200, "image/png", png.toByteArray());

            log("INFO", "req=%d %s /restore client=%s status=200 img=%dx%d in_bytes=%d out_bytes=%d "
                            + "decode_ms=%d restore_ms=%d encode_ms=%d total_ms=%d",
                    id, method, client, input.getWidth(), input.getHeight(), body.length, png.size(),
                    decodeMs, restoreMs, encodeMs, totalMs);
        } catch (Exception e) {
            log("ERROR", "req=%d %s /restore client=%s status=500 total_ms=%d erro=%s",
                    id, method, client, elapsedMs(start), e);
            e.printStackTrace();
            sendError(exchange, 500, "erro interno");
        }
    }

    private static void logRejected(long id, String method, String client, int status, String reason, long start) {
        log("WARN", "req=%d %s /restore client=%s status=%d motivo=\"%s\" total_ms=%d",
                id, method, client, status, reason, elapsedMs(start));
    }

    private static void log(String level, String format, Object... args) {
        System.out.printf("%s %-5s [%s] %s%n", LocalDateTime.now().format(TIMESTAMP), level,
                Thread.currentThread().getName(), String.format(format, args));
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
