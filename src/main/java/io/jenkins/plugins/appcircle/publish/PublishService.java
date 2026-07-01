package io.jenkins.plugins.appcircle.publish;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.TaskListener;
import hudson.util.Secret;
import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.http.HttpEntity;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

public class PublishService {
    public static final String DEFAULT_API_ENDPOINT = "https://api.appcircle.io";
    private static final int MAX_RETRIES = 5;

    // Human-readable publish flow step statuses.
    private static final Map<Integer, String> FLOW_STEP_STATUS = new HashMap<>();

    static {
        FLOW_STEP_STATUS.put(0, "Success");
        FLOW_STEP_STATUS.put(1, "Failed");
        FLOW_STEP_STATUS.put(2, "Cancelled");
        FLOW_STEP_STATUS.put(3, "Timeout");
        FLOW_STEP_STATUS.put(90, "Waiting");
        FLOW_STEP_STATUS.put(91, "Running");
        FLOW_STEP_STATUS.put(92, "Completing");
        FLOW_STEP_STATUS.put(99, "Unknown");
        FLOW_STEP_STATUS.put(100, "Skipped");
        FLOW_STEP_STATUS.put(200, "Not Started");
        FLOW_STEP_STATUS.put(201, "Stopped");
        FLOW_STEP_STATUS.put(202, "In Progress");
        FLOW_STEP_STATUS.put(203, "Awaiting Response");
    }

    // Stored as Secret so the bearer token is never held (or serialized) as plaintext.
    private final Secret authToken;
    String baseUrl;

    public PublishService(String authToken, String apiEndpoint) {
        this.authToken = Secret.fromString(authToken);
        this.baseUrl = (apiEndpoint == null || apiEndpoint.trim().isEmpty())
                ? DEFAULT_API_ENDPOINT
                : apiEndpoint.trim().replaceAll("/+$", "");
    }

    private static String stepStatusName(int status) {
        String name = FLOW_STEP_STATUS.get(status);
        return name != null ? name : "Unknown (" + status + ")";
    }

    // Emoji icons carry green/red semantics without ANSI codes, so they render in CI logs.
    private static String stepIcon(int status) {
        switch (status) {
            case 0:
                return "✅";
            case 1:
                return "❌";
            case 2:
                return "🚫";
            case 3:
                return "⌛";
            case 100:
                return "⏭️";
            case 201:
                return "⏹️";
            case 203:
                return "⏸️";
            default:
                return "▶️";
        }
    }

    private JSONObject getJson(String url) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
            request.setHeader("Accept", "application/json");
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity());
                if (status < 200 || status >= 300) {
                    throw new IOException("Request failed (" + status + "): " + body);
                }
                return new JSONObject(body);
            }
        }
    }

    private JSONArray getJsonArray(String url) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
            request.setHeader("Accept", "application/json");
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity());
                if (status < 200 || status >= 300) {
                    throw new IOException("Request failed (" + status + "): " + body);
                }
                return new JSONArray(body);
            }
        }
    }

    // Resolve a publish profile id from its name for the given platform (names unique per platform).
    public String getPublishProfileId(String platform, String profileName) throws IOException {
        String url = String.format("%s/publish/v2/profiles/%s", this.baseUrl, platform);
        JSONArray profiles = getJsonArray(url);
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject p = profiles.getJSONObject(i);
            if (profileName.equals(p.optString("name"))) {
                return p.optString("id");
            }
        }
        throw new IOException("Publish profile '" + profileName + "' not found for platform '" + platform + "'.");
    }

    private JSONArray getAppVersions(String platform, String publishProfileId) throws IOException {
        String url =
                String.format("%s/publish/v2/profiles/%s/%s/app-versions", this.baseUrl, platform, publishProfileId);
        return getJsonArray(url);
    }

    public String getLatestAppVersionId(String platform, String publishProfileId) throws IOException {
        JSONArray versions = getAppVersions(platform, publishProfileId);
        if (versions.length() == 0) {
            throw new IOException("No app versions found on the publish profile after upload.");
        }
        return versions.getJSONObject(0).optString("id");
    }

    public String getReleaseCandidateVersionId(String platform, String publishProfileId) throws IOException {
        JSONArray versions = getAppVersions(platform, publishProfileId);
        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            if (v.optBoolean("releaseCandidate", false)) {
                return v.optString("id");
            }
        }
        throw new IOException(
                "No release candidate app version found on the publish profile. Mark a version as release candidate (or enable upload) before publishing.");
    }

    public void markReleaseCandidate(String platform, String publishProfileId, String appVersionId) throws IOException {
        String url = String.format(
                "%s/publish/v2/profiles/%s/%s/app-versions/%s?action=releaseCandidate",
                this.baseUrl, platform, publishProfileId, appVersionId);
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPatch request = new HttpPatch(url);
            request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
            request.setHeader("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            body.put("ReleaseCandidate", true);
            request.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());
                if (status < 200 || status >= 300) {
                    throw new IOException("Failed to mark release candidate (" + status + ").");
                }
            }
        }
    }

    // In-progress publishes for the given profile (scope: target profile).
    public int getActivePublishCountForProfile(String publishProfileId) throws IOException {
        String url = String.format("%s/build/v1/queue/my-dashboard?page=1&size=1000", this.baseUrl);
        JSONObject response = getJson(url);
        JSONArray items = response.optJSONArray("data");
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (!item.isNull("publishId") && publishProfileId.equals(item.optString("profileId"))) {
                count++;
            }
        }
        return count;
    }

    public String getPublishId(String platform, String publishProfileId, String appVersionId) throws IOException {
        String url = String.format(
                "%s/publish/v2/profiles/%s/%s/app-versions/%s/publish",
                this.baseUrl, platform, publishProfileId, appVersionId);
        JSONObject response = getJson(url);
        JSONArray steps = response.optJSONArray("steps");
        if (steps != null && steps.length() > 0) {
            String publishId = steps.getJSONObject(0).optString("publishId");
            if (publishId != null && !publishId.isEmpty()) {
                return publishId;
            }
        }
        throw new IOException(
                "No publish flow steps found for the app version. Configure a publish flow on the profile first.");
    }

    public void startPublish(String platform, String publishProfileId, String publishId) throws IOException {
        String url = String.format(
                "%s/publish/v2/profiles/%s/%s/publish/%s?action=restart",
                this.baseUrl, platform, publishProfileId, publishId);
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(url);
            request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity("{}", ContentType.APPLICATION_JSON));
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());
                if (status < 200 || status >= 300) {
                    throw new IOException("Failed to start publish (" + status + ").");
                }
            }
        }
    }

    // Poll the publish status until terminal (0=success, 1=failed, else running),
    // logging each step's start / await / terminal once with status icons.
    public boolean pollPublishStatus(
            String platform, String publishProfileId, String appVersionId, TaskListener listener) throws Exception {
        String url = String.format(
                "%s/publish/v1/profiles/%s/%s/app-versions/%s/publish",
                this.baseUrl, platform, publishProfileId, appVersionId);
        int maxAttempts = 240;
        Map<String, int[]> stepState = new HashMap<>(); // id -> {started, awaiting, done} flags

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            JSONObject data = getJson(url);
            JSONArray steps = data.optJSONArray("steps");
            if (steps != null) {
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.getJSONObject(i);
                    String id = step.optString("id", step.optString("name"));
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
                    int status = step.optInt("status", 99);
                    String name = step.optString("name");
                    int[] state = stepState.computeIfAbsent(id, k -> new int[3]);
                    boolean terminal =
                            status == 0 || status == 1 || status == 2 || status == 3 || status == 100 || status == 201;
                    boolean active = status == 91 || status == 92 || status == 202;
                    if (terminal && state[2] == 0) {
                        state[2] = 1;
                        listener.getLogger().println(stepIcon(status) + " " + name + " — " + stepStatusName(status));
                    } else if (status == 203 && state[1] == 0 && state[2] == 0) {
                        state[1] = 1;
                        listener.getLogger().println(stepIcon(status) + " " + name + " — " + stepStatusName(status));
                    } else if (active && state[0] == 0 && state[2] == 0) {
                        state[0] = 1;
                        listener.getLogger().println(stepIcon(status) + " " + name + " — " + stepStatusName(status));
                    }
                }
            }
            int status = data.has("status") ? data.optInt("status", 99) : 99;
            if (status == 0) {
                listener.getLogger().println("Publish completed successfully.");
                return true;
            }
            if (status == 1) {
                listener.getLogger().println("Publish failed.");
                return false;
            }
            Thread.sleep(5000);
        }
        throw new IOException("Publish status polling timed out.");
    }

    /* ----- Upload (v2 signed-URL flow, on the publish/v1 path) ----- */

    public JSONObject uploadArtifact(String platform, String publishProfileId, String appPath) throws IOException {
        File file = new File(appPath);
        String fileName = file.getName();
        long fileSize = file.length();

        JSONObject uploadInfo = getUploadInformation(platform, publishProfileId, fileName, fileSize);
        String fileId = uploadInfo.optString("fileId");
        String uploadUrl = uploadInfo.optString("uploadUrl");
        JSONObject configuration = uploadInfo.optJSONObject("configuration");
        String httpMethod =
                (configuration != null && !configuration.optString("httpMethod").isEmpty())
                        ? configuration.optString("httpMethod").toUpperCase()
                        : "PUT";

        if ("POST".equals(httpMethod)) {
            uploadViaPost(uploadUrl, file, configuration);
        } else {
            uploadViaPut(uploadUrl, file);
        }

        return commitFileUpload(platform, publishProfileId, fileId, fileName);
    }

    private JSONObject getUploadInformation(String platform, String publishProfileId, String fileName, long fileSize)
            throws IOException {
        try {
            URI uri = new URIBuilder(String.format(
                            "%s/publish/v1/profiles/%s/%s/app-versions", this.baseUrl, platform, publishProfileId))
                    .addParameter("action", "uploadInformation")
                    .addParameter("fileName", fileName)
                    .addParameter("fileSize", String.valueOf(fileSize))
                    .build();
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpGet request = new HttpGet(uri);
                request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
                request.setHeader("Accept", "application/json");
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(response.getEntity());
                    if (status < 200 || status >= 300) {
                        throw new IOException("Failed to retrieve file upload information (" + status + "): " + body);
                    }
                    return new JSONObject(body);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid upload information URI: " + e.getMessage(), e);
        }
    }

    private void uploadViaPut(String uploadUrl, File file) throws IOException {
        IOException lastError = null;
        long delayMillis = 1000;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPut request = new HttpPut(uploadUrl);
                request.setEntity(new FileEntity(file, ContentType.APPLICATION_OCTET_STREAM));
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    EntityUtils.consumeQuietly(response.getEntity());
                    if (status >= 200 && status < 300) {
                        return;
                    }
                    if (status != 503 || attempt >= MAX_RETRIES) {
                        throw new IOException("File upload failed with status code: " + status);
                    }
                    lastError = new IOException("File upload failed with status code: " + status);
                }
            } catch (NoHttpResponseException | SocketException e) {
                if (attempt >= MAX_RETRIES) {
                    throw e;
                }
                lastError = e;
            }
            sleepWithJitter(delayMillis);
            delayMillis *= 2;
        }
        throw lastError != null ? lastError : new IOException("File upload failed.");
    }

    private void uploadViaPost(String uploadUrl, File file, @Nullable JSONObject configuration) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(uploadUrl);
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            JSONObject signParameters = configuration != null ? configuration.optJSONObject("signParameters") : null;
            if (signParameters != null) {
                for (String key : signParameters.keySet()) {
                    builder.addTextBody(key, signParameters.optString(key));
                }
            }
            builder.addPart("file", new FileBody(file)); // file field MUST be last
            request.setEntity(builder.build());
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());
                if (status < 200 || status >= 300) {
                    throw new IOException("File upload failed with status code: " + status);
                }
            }
        }
    }

    private JSONObject commitFileUpload(String platform, String publishProfileId, String fileId, String fileName)
            throws IOException {
        try {
            URI uri = new URIBuilder(String.format(
                            "%s/publish/v1/profiles/%s/%s/app-versions", this.baseUrl, platform, publishProfileId))
                    .addParameter("action", "commitFileUpload")
                    .build();
            JSONObject payload = new JSONObject();
            payload.put("fileId", fileId);
            payload.put("fileName", fileName);
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost request = new HttpPost(uri);
                request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
                request.setHeader("Accept", "application/json");
                request.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    String body = EntityUtils.toString(response.getEntity());
                    if (status < 200 || status >= 300) {
                        throw new IOException("Commit failed with status code: " + status + ": " + body);
                    }
                    return new JSONObject(body);
                }
            }
        } catch (URISyntaxException e) {
            throw new IOException("Invalid commit URI: " + e.getMessage(), e);
        }
    }

    private void sleepWithJitter(long delayMillis) throws IOException {
        try {
            long jitter = ThreadLocalRandom.current().nextInt(300);
            Thread.sleep(delayMillis + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload retry interrupted", ie);
        }
    }

    public Boolean checkUploadStatus(String taskId) throws Exception {
        String url = String.format("%s/task/v1/tasks/%s", this.baseUrl, taskId);
        String result = "";
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Authorization", "Bearer " + Secret.toString(this.authToken));
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    result = EntityUtils.toString(entity);
                }
                JSONObject jsonResponse = new JSONObject(result);
                Integer stateValue = jsonResponse.optInt("stateValue", -1);
                String stateName = jsonResponse.optString("stateName");
                if (stateName == null) {
                    throw new Exception("Upload Status Could Not Received");
                } else if (stateValue == 2) {
                    throw new Exception(taskId + " id upload request failed with status " + stateName);
                } else if (stateValue == 1) {
                    Thread.sleep(2000);
                    return checkUploadStatus(taskId);
                } else if (stateValue == 3) {
                    return true;
                }
            }
        } catch (Exception e) {
            throw e;
        }
        return true;
    }
}
