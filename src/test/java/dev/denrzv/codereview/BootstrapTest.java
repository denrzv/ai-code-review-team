package dev.denrzv.codereview;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import dev.denrzv.codereview.adapter.api.ReviewController;
import dev.denrzv.codereview.config.ReviewProperties;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "review.output-dir=custom/reviews")
class BootstrapTest {

    private static final String LOOPBACK = "127.0.0.1";

    @Autowired
    private ServletWebServerApplicationContext serverContext;

    @Autowired
    private ReviewProperties properties;

    @Autowired
    private ReviewController controller;

    @LocalServerPort
    private int port;

    @Test
    void servesHealthOnLoopback() throws Exception {
        var server = (TomcatWebServer) serverContext.getWebServer();
        var connector = (AbstractProtocol<?>) server.getTomcat().getConnector().getProtocolHandler();
        assertThat(connector.getAddress()).isEqualTo(InetAddress.ofLiteral(LOOPBACK));

        HttpRequest request = HttpRequest.newBuilder(localUri("/actuator/health")).GET().build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new JsonMapper().readTree(response.body()).path("status").asText()).isEqualTo("UP");
        }
    }

    @Test
    void reservesControllerWithoutMappingSubmission() throws Exception {
        assertThat(controller).isNotNull();
        HttpRequest request = HttpRequest.newBuilder(localUri("/api/reviews"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void bindsOperatorOverridesAndDefaultSettings() {
        assertThat(properties.outputDir()).isEqualTo(Path.of("custom/reviews"));
        assertThat(properties.model().name()).isEqualTo("deepseek-flash");
        assertThat(properties.model().thinkingEnabled()).isFalse();
        assertThat(properties.budget().maxElapsed()).hasMinutes(15);
        assertThat(properties.limits().maxChangedFiles()).isEqualTo(20);
        assertThat(properties.allowlist()).isEmpty();
        assertThat(properties.prices()).containsKey(properties.model().name());
    }

    private URI localUri(String path) {
        return URI.create("http://" + LOOPBACK + ":" + port + path);
    }
}
