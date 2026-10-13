import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * Backend HTTP para restauração de imagens (perfil CPU-bound).
 *
 * Endpoints:
 *   GET  /health   -> status da aplicação
 *   GET  /restore  -> restaura a imagem de exemplo (images/lena-borrada.jpg) e devolve o PNG
 *   POST /restore  -> restaura a imagem enviada no corpo e devolve o PNG
 */
public class ImageRestorationServer {

    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    // Carregada na inicialização para não medir I/O de disco nas requisições
    private static BufferedImage sampleImage;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        int threads = Integer.parseInt(env("THREADS", String.valueOf(Runtime.getRuntime().availableProcessors())));

        sampleImage = ImageIO.read(new File("images/lena-borrada.jpg"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ImageRestorationServer::handleHealth);
        server.createContext("/restore", ImageRestorationServer::handleRestore);
        server.setExecutor(Executors.newFixedThreadPool(threads));
        server.start();

        System.out.printf("Servidor ouvindo na porta %d (threads=%d)%n", port, threads);
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        send(exchange, 200, "application/json", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static void handleRestore(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String method = exchange.getRequestMethod();

        try {
            BufferedImage input;
            if (method.equals("GET")) {
                input = sampleImage;
            } else if (method.equals("POST")) {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_UPLOAD_BYTES + 1);
                if (body.length > MAX_UPLOAD_BYTES) {
                    sendError(exchange, 413, "imagem maior que 10 MB");
                    return;
                }
                input = ImageIO.read(new ByteArrayInputStream(body));
                if (input == null) {
                    sendError(exchange, 400, "corpo da requisicao nao e uma imagem valida");
                    return;
                }
            } else {
                sendError(exchange, 405, "metodo nao permitido");
                return;
            }

            BufferedImage output = Deconvolution.restore(input);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(output, "png", png);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            exchange.getResponseHeaders().set("X-Processing-Time-Ms", String.valueOf(elapsedMs));
            send(exchange, 200, "image/png", png.toByteArray());
            System.out.printf("%s /restore %dx%d -> 200 em %d ms%n", method, input.getWidth(), input.getHeight(), elapsedMs);
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "erro interno");
        }
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
