package cn.chatplanner.control;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class ApiClient {
    static final class ApiException extends Exception {
        final int status;
        ApiException(int status) { super("HTTP " + status); this.status = status; }
    }
    static String validateBase(String input) {
        URI uri = URI.create(input.trim());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean emulator = BuildConfig.DEBUG && "http".equalsIgnoreCase(uri.getScheme()) && "10.0.2.2".equals(uri.getHost());
        if ((!https && !emulator) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid service URL");
        return input.trim().replaceAll("/+$", "");
    }
    static JSONObject request(String base, String token, String method, String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(validateBase(base) + path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setRequestMethod(method);
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new ApiException(code);
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (out.size() + count > 2097152) throw new IllegalStateException("Response too large");
                    out.write(buffer, 0, count);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}
