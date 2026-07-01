package io.jenkins.plugins.appcircle.publish.Models;

public class UserResponse {
    private final String accessToken;

    public UserResponse(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getAccessToken() {
        return accessToken;
    }
}
