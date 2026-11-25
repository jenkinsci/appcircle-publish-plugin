package io.jenkins.plugins.appcircle.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class AuthServiceEndpointTest {
    private static final String MESSAGE =
            "Set both authEndpoint and apiEndpoint for a self-hosted Appcircle server, or leave both empty for the Appcircle cloud.";

    @Test
    void cloudEndpointsPass() throws Exception {
        AuthService.validateEndpoints(null, null);
        AuthService.validateEndpoints("", "  ");
        AuthService.validateEndpoints("https://auth.appcircle.io/", "https://API.appcircle.io");
    }

    @Test
    void selfHostedEndpointsPass() throws Exception {
        AuthService.validateEndpoints("https://auth.example.test", "https://api.example.test/");
    }

    @Test
    void onlyAuthEndpointFails() {
        assertEquals(
                MESSAGE,
                assertThrows(IOException.class, () -> AuthService.validateEndpoints("https://auth.example.test", null))
                        .getMessage());
        assertEquals(
                MESSAGE,
                assertThrows(
                                IOException.class,
                                () -> AuthService.validateEndpoints(
                                        "https://auth.example.test", "https://api.appcircle.io"))
                        .getMessage());
    }

    @Test
    void onlyApiEndpointFails() {
        assertEquals(
                MESSAGE,
                assertThrows(IOException.class, () -> AuthService.validateEndpoints("", "https://api.example.test"))
                        .getMessage());
    }
}
