package io.jenkins.plugins.appcircle.publish;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.appcircle.publish.Models.UserResponse;
import java.io.IOException;
import java.net.URISyntaxException;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.json.JSONException;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class PublishBuilder extends Builder implements SimpleBuildStep {

    private final Secret personalAPIToken;
    private final String platform;
    private final String publishProfile;
    private boolean upload;
    private boolean publish;
    private String appPath;
    private String authEndpoint;
    private String apiEndpoint;

    @DataBoundConstructor
    public PublishBuilder(String personalAPIToken, String platform, String publishProfile) {
        this.personalAPIToken = Secret.fromString(personalAPIToken);
        this.platform = platform;
        this.publishProfile = publishProfile;
    }

    public String getPersonalAPIToken() {
        return personalAPIToken.getPlainText();
    }

    public String getPlatform() {
        return platform;
    }

    public String getPublishProfile() {
        return publishProfile;
    }

    public boolean getUpload() {
        return upload;
    }

    @DataBoundSetter
    public void setUpload(boolean upload) {
        this.upload = upload;
    }

    public boolean getPublish() {
        return publish;
    }

    @DataBoundSetter
    public void setPublish(boolean publish) {
        this.publish = publish;
    }

    public String getAppPath() {
        return appPath;
    }

    @DataBoundSetter
    public void setAppPath(String appPath) {
        this.appPath = appPath;
    }

    public String getAuthEndpoint() {
        return authEndpoint;
    }

    @DataBoundSetter
    public void setAuthEndpoint(String authEndpoint) {
        this.authEndpoint = authEndpoint;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    @DataBoundSetter
    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        try {
            String plat = this.platform == null ? "" : this.platform.toLowerCase();

            if (!this.upload && !this.publish) {
                throw new IOException("Nothing to do: enable 'upload' and/or 'publish'.");
            }
            if (!plat.equals("ios") && !plat.equals("android")) {
                throw new IOException("Invalid platform: " + this.platform + ". Use 'ios' or 'android'.");
            }
            if (this.upload) {
                if (this.appPath == null || this.appPath.isEmpty()) {
                    throw new IOException("'appPath' is required when 'upload' is enabled.");
                }
                if (!validateFileExtension(this.appPath)) {
                    throw new IOException("Invalid file extension: " + this.appPath
                            + ". For Android, use .apk or .aab. For iOS, use .ipa.");
                }
            }

            UserResponse response = AuthService.getAcToken(this.personalAPIToken.getPlainText(), this.authEndpoint);
            listener.getLogger().println("Login is successful.");
            PublishService publishService = new PublishService(response.getAccessToken(), this.apiEndpoint);

            String publishProfileId = publishService.getPublishProfileId(plat, this.publishProfile);

            if (this.publish && publishService.getActivePublishCountForProfile(publishProfileId) > 0) {
                throw new IOException("A publish is already in progress for profile '" + this.publishProfile
                        + "'. Not starting a new one.");
            }

            String appVersionId = null;

            if (this.upload) {
                JSONObject uploadResponse = publishService.uploadArtifact(plat, publishProfileId, this.appPath);
                Boolean uploaded = publishService.checkUploadStatus(uploadResponse.optString("taskId"));
                if (uploaded) {
                    appVersionId = publishService.getLatestAppVersionId(plat, publishProfileId);
                    listener.getLogger()
                            .println(this.appPath + " uploaded to the Appcircle Publish profile '" + this.publishProfile
                                    + "' successfully.");
                }
            }

            if (this.publish) {
                if (this.upload && appVersionId != null) {
                    publishService.markReleaseCandidate(plat, publishProfileId, appVersionId);
                    listener.getLogger().println("Marked the uploaded version as release candidate.");
                } else {
                    appVersionId = publishService.getReleaseCandidateVersionId(plat, publishProfileId);
                }
                String publishId = publishService.getPublishId(plat, publishProfileId, appVersionId);
                publishService.startPublish(plat, publishProfileId, publishId);
                listener.getLogger().println("Publish flow started for profile '" + this.publishProfile + "'.");
                boolean success = publishService.pollPublishStatus(plat, publishProfileId, appVersionId, listener);
                if (!success) {
                    run.setResult(Result.FAILURE);
                }
            }
        } catch (JSONException e) {
            listener.getLogger().println(e.getMessage());
            run.setResult(Result.FAILURE);
        } catch (URISyntaxException e) {
            listener.error("Invalid URI: " + e.getMessage());
            run.setResult(Result.FAILURE);
        } catch (Exception e) {
            listener.getLogger().println(e.getMessage());
            run.setResult(Result.FAILURE);
        }
    }

    Boolean validateFileExtension(String filePath) {
        return filePath.matches(".*\\.(apk|aab|ipa)$");
    }

    @Symbol("appcirclePublish")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @POST
        public FormValidation doCheckPersonalAPIToken(@QueryParameter String value) {
            if (value.isEmpty()) return FormValidation.error("Personal API Token cannot be empty");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckPlatform(@QueryParameter String value) {
            if (value.isEmpty()) return FormValidation.error("Platform cannot be empty");
            if (!value.equalsIgnoreCase("ios") && !value.equalsIgnoreCase("android")) {
                return FormValidation.error("Platform must be 'ios' or 'android'.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckPublishProfile(@QueryParameter String value) {
            if (value.isEmpty()) return FormValidation.error("Publish Profile cannot be empty");
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckAppPath(@QueryParameter String value) {
            if (!value.isEmpty() && !value.matches(".*\\.(apk|aab|ipa)$")) {
                return FormValidation.error(
                        "Invalid file extension: For Android, use .apk or .aab. For iOS, use .ipa.");
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.AppcirclePublish_DescriptorImpl_DisplayName();
        }
    }
}
