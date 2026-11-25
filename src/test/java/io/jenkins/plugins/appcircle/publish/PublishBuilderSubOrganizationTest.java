package io.jenkins.plugins.appcircle.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PublishBuilderSubOrganizationTest {

    private static PublishBuilder builder() {
        return new PublishBuilder("my-pat", "ios", "Example Publish Profile");
    }

    @Test
    void subOrganizationNameSurvivesConfigRoundtrip(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        PublishBuilder builder = builder();
        builder.setSubOrganizationName("PlugInTest");
        project.getBuildersList().add(builder);

        project = j.configRoundtrip(project);

        PublishBuilder after = project.getBuildersList().get(PublishBuilder.class);
        assertEquals("PlugInTest", after.getSubOrganizationName());
    }

    @Test
    void subOrganizationNameIsOptional(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(builder());

        project = j.configRoundtrip(project);

        PublishBuilder after = project.getBuildersList().get(PublishBuilder.class);
        String name = after.getSubOrganizationName();
        assertTrue(name == null || name.isEmpty());
    }
}
