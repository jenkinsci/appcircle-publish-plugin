package io.jenkins.plugins.appcircle.publish.Models;

import hudson.util.Secret;

public class UserResponse {
    // Stored as Secret so the access token is never held (or serialized) as plaintext.
    private final Secret accessToken;

    public UserResponse(String accessToken) {
        this.accessToken = Secret.fromString(accessToken);
    }

    public String getAccessToken() {
        return Secret.toString(accessToken);
    }
}
