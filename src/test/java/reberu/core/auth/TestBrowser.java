package reberu.core.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

final class TestBrowser {

    static final String SESSION_COOKIE = "__Host-session";

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> cookies = new LinkedHashMap<>();

    TestBrowser(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    HttpResponse<String> get(String path) {
        return send("GET", path, null);
    }

    HttpResponse<String> send(String method, String path, String jsonBody) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        if (!method.equals("GET") && cookies.containsKey("XSRF-TOKEN")) {
            request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.entrySet().stream()
                    .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; ")));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().allValues("Set-Cookie").forEach(this::store);
            return response;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    String cookie(String name) {
        return cookies.get(name);
    }

    void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    void removeCookie(String name) {
        cookies.remove(name);
    }

    private void store(String header) {
        String pair = header.split(";", 2)[0];
        int separator = pair.indexOf('=');
        String name = pair.substring(0, separator);
        String value = pair.substring(separator + 1);
        boolean expired = header.contains("Max-Age=0") || value.isEmpty();
        if (expired) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }

}
