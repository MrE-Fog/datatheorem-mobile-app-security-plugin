package com.datatheorem.mobileappsecurity.jenkins.plugin;

import groovy.lang.Tuple2;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;

/**
 * This class aims to provide a simple plugin to automatically upload builds to Data Theorem Upload API.
 * <p>
 * The plugin is a publish action that gets a buildName as a parameter and automatically sends the corresponding build
 * to Data Theorem.
 * It needs a secret DATA_THEOREM_UPLOAD_API_KEY variable generated using:
 * https://wiki.jenkins.io/display/JENKINS/Credentials+Plugin
 * </p>
 */

public class SendBuildToDataTheoremPublisher extends Publisher implements SimpleBuildStep, Serializable {
    private  String buildToUpload;
    private final boolean dontUpload;
    private final String proxyHostname;
    private final int proxyPort;
    private final String proxyUsername;
    private Secret proxyPassword = null;
    private final boolean proxyUnsecuredConnection;
    private String dataTheoremUploadApiKey = null;

    @DataBoundConstructor
    public SendBuildToDataTheoremPublisher(
            String buildToUpload,
            boolean dontUpload,
            String proxyHostname,
            int proxyPort,
            String proxyUsername,
            String proxyPassword,
            boolean proxyUnsecuredConnection
        ) {
        /*
        * Bind the parameter value of the job configuration page
        */
        this.buildToUpload = buildToUpload;
        this.dontUpload = dontUpload;
        this.proxyHostname = proxyHostname;
        this.proxyPort = proxyPort;
        this.proxyUsername = proxyUsername;
        this.proxyPassword = Secret.fromString(proxyPassword);
        this.proxyUnsecuredConnection = proxyUnsecuredConnection;
    }

    public String getDataTheoremUploadApiKey() {
        return dataTheoremUploadApiKey;
    }

    @DataBoundSetter
    public void setDataTheoremUploadApiKey(String dataTheoremUploadApiKey) {
        /*
        * dataTheoremUploadApiKey can be set in a jenkins pipeline by adding dataTheoremUploadApiKey parameter at the call of the plugin
        */
        this.dataTheoremUploadApiKey = dataTheoremUploadApiKey;
    }

    private String getSecretKey(Run<?,?> run, TaskListener listener) throws IOException, InterruptedException {
    /*
    * Environment variable is handled differently as a pipeline step or as a post-build action
    * Documentation: https://jenkins.io/doc/developer/plugin-development/pipeline-integration/ section "Variable substitutions"
    */
        if (run instanceof AbstractBuild) {
            //As a post-build action we have access to any defined environment variable value
            listener.getLogger().println("Data Theorem Upload Build plugin is running as a post-build action");
            return run.getEnvironment(listener).get("DATA_THEOREM_UPLOAD_API_KEY");
        } else {
            //As a pipeline step, the plugin should take any configuration values as literal strings
            listener.getLogger().println(
                    "Data Theorem Upload Build plugin is called from a jenkins pipeline script"
            );
            if (this.dataTheoremUploadApiKey == null)
                listener.getLogger().println(
                        "You should set dataTheoremUploadApiKey " +
                        "with DATA_THEOREM_UPLOAD_API_KEY environment variable value "
                );
            return this.dataTheoremUploadApiKey;
        }
    }

    @Override
    public void perform(
            Run<?, ?> run,
            @Nonnull FilePath workspace,
            @Nonnull Launcher launcher,
            TaskListener listener
    ) throws InterruptedException, IOException {

        SendBuildAction sendBuild;
        listener.getLogger().println("Data Theorem upload build plugin starting...");

        Result result = run.getResult();
        if (result != null && result.isWorseOrEqualTo(Result.UNSTABLE)) {
            listener.getLogger().println(
                    "Skipping Data Theorem CI/CD because the previous step result is: " + Result.UNSTABLE.toString()
            );
            run.setResult(result);
            return;
        }

        listener.getLogger().println("Uploading the build to Data Theorem : " + this.buildToUpload);

        // First find the path to the build to upload
        FindBuildPathAction buildToSend = new FindBuildPathAction(this.buildToUpload, workspace, run, listener.getLogger());
        Tuple2<String, Boolean> findPathResult = buildToSend.perform();
        if (findPathResult == null) {
            listener.getLogger().println("Unable to find any build with name : " + this.buildToUpload);
            run.setResult(Result.UNSTABLE);
            return;
        }

        // Check if the build is in artifact folder or the workspace
        String buildPath = findPathResult.getFirst();
        Boolean isBuildStoredInArtifactFolder = findPathResult.getSecond();

        listener.getLogger().println("Found the build at path: " + buildPath);

        // If the user only wants to check if the path was correct we don't call the Upload API
        if (dontUpload) {
            listener.getLogger().println("Skipping upload... \"Don't Upload\" option enabled");
            run.setResult(Result.SUCCESS);
            return;
        }

        // Then upload the build to DT
        if (proxyHostname == null || proxyHostname.isEmpty()) {
            listener.getLogger().println("No proxy configuration");

            sendBuild = new SendBuildAction(
                    getSecretKey(run, listener),
                    listener.getLogger(),
                    workspace
            );
        }
        else {
            listener.getLogger().println("Proxy Configuration is : " + proxyHostname + ":" + proxyPort);

            sendBuild = new SendBuildAction(
                    getSecretKey(run, listener),
                    listener.getLogger(),
                    workspace,
                    proxyHostname,
                    proxyPort,
                    proxyUsername,
                    proxyPassword.getPlainText(),
                    proxyUnsecuredConnection
            );
        }

        SendBuildMessage sendBuildResult = sendBuild.perform(
                buildPath,
                isBuildStoredInArtifactFolder
        );

        if (!sendBuildResult.message.isEmpty()) {
            listener.getLogger().println(sendBuildResult.message);
        }
        if (!sendBuildResult.success) {
            run.setResult(Result.UNSTABLE);
            return;
        }

       run.setResult(Result.SUCCESS);
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public String getBuildToUpload() {

        // Required to get the last value when we update a job config
        return buildToUpload;
    }

    public boolean isDontUpload() {

        // Required to get the last value when we update a job config
        return dontUpload;
    }

    public String getProxyHostname() {
        // Required to get the last value when we update a job config

        return proxyHostname;
    }

    public int getProxyPort() {
        // Required to get the last value when we update a job config

        return proxyPort;
    }

    public String getProxyUsername() {
        // Required to get the last value when we update a job config

        return proxyUsername;
    }

    public Secret getProxyPassword() {
        // Returns the encrypted value of the field

        return this.proxyPassword;
    }

    public boolean getProxyUnsecuredConnection() {
        // Required to get the last value when we update a job config

        return proxyUnsecuredConnection;
    }


    @Extension
    // Define the symbols needed to call the jenkins plugin in a DSL pipeline
    @Symbol({
            "sendBuildToDataTheorem",
            "buildToUpload",
            "dontUpload",
            "proxyHostname",
            "proxyPort",
            "proxyUsername",
            "proxyPassword",
            "proxyUnsecuredConnection",
            "dataTheoremUploadApiKey"
    })
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        /*
         * Validate that the build name parameter in job configuration page is not left empty
         */

        public FormValidation doCheckBuildToUpload(@QueryParameter(value = "buildToUpload") String value) {
            if (value.length() == 0)
                return FormValidation.error("The build name is empty");
            if (!value.toLowerCase().endsWith(".apk") && !value.toLowerCase().endsWith(".ipa"))
                return FormValidation.error("the build name should ends with .apk or .ipa");
            if (value.length() < 5)
                return FormValidation.error("The build name is too short");
            return FormValidation.ok();

        }

        @Override
        public String getDisplayName() {
            return "Upload build to Data Theorem";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }
    }


}
