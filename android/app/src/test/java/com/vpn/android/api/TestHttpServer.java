package com.vpn.android.api;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A lightweight, zero-dependency HTTP server using standard java.net.ServerSocket
 * that works under Android unit tests without requiring jdk.httpserver or mockwebserver.
 */
public class TestHttpServer implements Closeable {

    public interface Handler {
        Response handle(Request request);
    }

    public static class Request {
        public final String method;
        public final String path;
        public final Map<String, String> headers;
        public final String body;

        public Request(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    public static class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public TestHttpServer() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        executor.execute(this::acceptLoop);
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + getPort();
    }

    public void register(String method, String path, Handler handler) {
        routes.put(method.toUpperCase() + " " + path, handler);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read request line and headers
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon != -1) {
                    String headerName = line.substring(0, colon).trim().toLowerCase();
                    String headerVal = line.substring(colon + 1).trim();
                    headers.put(headerName, headerVal);
                    if ("content-length".equals(headerName)) {
                        try {
                            contentLength = Integer.parseInt(headerVal);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Read request body if present
            String body = "";
            if (contentLength > 0) {
                byte[] bodyBytes = in.readNBytes(contentLength);
                body = new String(bodyBytes, StandardCharsets.UTF_8);
            }

            Request request = new Request(method, path, headers, body);
            Handler handler = routes.get(method.toUpperCase() + " " + path);
            Response response;
            if (handler != null) {
                response = handler.handle(request);
            } else {
                response = new Response(404, "{\"error\":\"Not found\"}");
            }

            byte[] respBytes = response.body.getBytes(StandardCharsets.UTF_8);
            String statusMsg = response.code == 200 ? "OK" : (response.code == 401 ? "Unauthorized" : "Error");
            String header = "HTTP/1.1 " + response.code + " " + statusMsg + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + respBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(respBytes);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buffer.write(b);
        }
        if (b == -1 && buffer.size() == 0) return null;
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {}
        executor.shutdownNow();
    }
}
