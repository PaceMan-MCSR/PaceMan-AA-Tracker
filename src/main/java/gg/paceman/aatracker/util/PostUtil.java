package gg.paceman.aatracker.util;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public final class PostUtil {
    private static final int MIN_DENY_CODE = 400;

    private PostUtil() {
    }

    public static PostResponse sendData(String endpointUrl, String jsonData) throws IOException {
        // Create URL object
        URL url = new URL(endpointUrl);
        HttpURLConnection connection = null;
        try {
            // Open connection
            connection = (HttpURLConnection) url.openConnection();

            // Set the necessary properties
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Write JSON data to the connection output stream
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            int responseCode = connection.getResponseCode();
            String message = responseCode >= MIN_DENY_CODE ? readStream(connection.getErrorStream()) : connection.getResponseMessage();


            // Return the response code
            return new PostResponse(responseCode, message);
        } finally {
            // Close the connection
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readStream(InputStream inputStream) {
        return new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
    }

    public static class PostResponse {
        public final int code;
        public final String message;

        private PostResponse(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
