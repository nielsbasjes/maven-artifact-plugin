/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.artifact.buildinfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;

/**
 * Base buildinfo-generating class, for goals related to Reproducible Builds {@code .buildinfo} files.
 *
 * @since 3.2.0
 */
public abstract class AbstractBuildinfoMojo extends AbstractMojo {
    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    /**
     * The reactor projects.
     */
    @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;

    /**
     * Location of the generated buildinfo file.
     */
    @Parameter(
            defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}.buildinfo",
            required = true,
            readonly = true)
    protected File buildinfoFile;

    /**
     * Ignore javadoc attached artifacts from buildinfo generation.
     */
    @Parameter(property = "buildinfo.ignoreJavadoc", defaultValue = "true")
    private boolean ignoreJavadoc;

    /**
     * Artifacts to ignore, specified as <code>extension</code> or <code>classifier.extension</code>.
     */
    @Parameter(property = "buildinfo.ignore", defaultValue = "")
    private Set<String> ignore;

    /**
     * Detect projects/modules with install or deploy skipped: avoid taking fingerprints.
     */
    @Parameter(property = "buildinfo.detect.skip", defaultValue = "true")
    private boolean detectSkip;

    /**
     * Makes the generated {@code .buildinfo} file reproducible, by dropping detailed environment recording: OS will be
     * recorded as "Windows" or "Unix", JVM version as major version only.
     *
     * @since 3.1.0
     */
    @Parameter(property = "buildinfo.reproducible", defaultValue = "false")
    private boolean reproducible;

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 3.2.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * To obtain a toolchain if possible.
     */
    @Component
    private ToolchainManager toolchainManager;

    @Component
    protected ArtifactHandlerManager artifactHandlerManager;

    @Component
    protected RuntimeInformation rtInformation;

    @Override
    public void execute() throws MojoExecutionException {
        boolean mono = reactorProjects.size() == 1;

        MavenArchiver archiver = new MavenArchiver();
        Date timestamp = archiver.parseOutputTimestamp(outputTimestamp);
        if (timestamp == null) {
            getLog().warn("Reproducible Build not activated by project.build.outputTimestamp property: "
                    + "see https://maven.apache.org/guides/mini/guide-reproducible-builds.html");
        } else {
            if (getLog().isDebugEnabled()) {
                getLog().debug("project.build.outputTimestamp = \"" + outputTimestamp + "\" => "
                        + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(timestamp));
            }

            // check if timestamp well defined in a project from reactor
            boolean parentInReactor = false;
            MavenProject reactorParent = project;
            while (reactorProjects.contains(reactorParent.getParent())) {
                parentInReactor = true;
                reactorParent = reactorParent.getParent();
            }
            String prop = reactorParent.getOriginalModel().getProperties().getProperty("project.build.outputTimestamp");
            if (prop == null) {
                getLog().error("project.build.outputTimestamp property should not be inherited but defined in "
                        + (parentInReactor ? "parent POM from reactor " : "POM ") + reactorParent.getFile());
            }
        }

        if (!mono) {
            // if module skips install and/or deploy
            if (isSkip(project)) {
                getLog().info("Skipping goal because module skips install and/or deploy");
                return;
            }
            // if multi-module build, generate (aggregate) buildinfo only in last module
            MavenProject last = getLastProject();
            if (project != last) {
                skip(last);
                return;
            }
        }

        // generate buildinfo
        Map<Artifact, String> artifacts = generateBuildinfo(mono);
        getLog().info("Saved " + (mono ? "" : "aggregate ") + "info on build to " + buildinfoFile);

        copyAggregateToRoot(buildinfoFile);

        execute(artifacts);
    }

    /**
     * Execute after buildinfo has been generated for current build (eventually aggregated).
     *
     * @param artifacts a Map of artifacts added to the build info with their associated property key prefix
     *         (<code>outputs.[#module.].#artifact</code>)
     */
    abstract void execute(Map<Artifact, String> artifacts) throws MojoExecutionException;

    protected void skip(MavenProject last) throws MojoExecutionException {
        getLog().info("Skipping intermediate goal run, aggregate will be " + last.getArtifactId());
    }

    protected void copyAggregateToRoot(File aggregate) throws MojoExecutionException {
        if (reactorProjects.size() == 1) {
            // mono-module, no aggregate file to deal with
            return;
        }

        // copy aggregate file to root target directory
        MavenProject root = getExecutionRoot();
        String extension = aggregate.getName().substring(aggregate.getName().lastIndexOf('.'));
        File rootCopy =
                new File(root.getBuild().getDirectory(), root.getArtifactId() + '-' + root.getVersion() + extension);
        try {
            FileUtils.copyFile(aggregate, rootCopy);
            getLog().info("Aggregate " + extension.substring(1) + " copied to " + rootCopy);
        } catch (IOException ioe) {
            throw new MojoExecutionException("Could not copy " + aggregate + "to " + rootCopy);
        }
    }

    /**
     * Generate buildinfo file.
     *
     * @param mono is it a mono-module build?
     * @return a Map of artifacts added to the build info with their associated property key prefix
     *         (<code>outputs.[#module.].#artifact</code>)
     * @throws MojoExecutionException
     */
    protected Map<Artifact, String> generateBuildinfo(boolean mono) throws MojoExecutionException {
        MavenProject root = mono ? project : getExecutionRoot();

        buildinfoFile.getParentFile().mkdirs();

        try (PrintWriter p = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(buildinfoFile), StandardCharsets.UTF_8)))) {
            BuildInfoWriter bi = new BuildInfoWriter(getLog(), p, mono, artifactHandlerManager, rtInformation);
            bi.setIgnoreJavadoc(ignoreJavadoc);
            bi.setIgnore(ignore);
            bi.setToolchain(getToolchain());

            bi.printHeader(root, mono ? null : project, reproducible);

            // artifact(s) fingerprints
            if (mono) {
                bi.printArtifacts(project);
            } else {
                for (MavenProject project : reactorProjects) {
                    if (!isSkip(project)) {
                        bi.printArtifacts(project);
                    }
                }
            }

            if (p.checkError()) {
                throw new MojoExecutionException("Write error to " + buildinfoFile);
            }

            return bi.getArtifacts();
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating file " + buildinfoFile, e);
        }
    }

    protected MavenProject getExecutionRoot() {
        for (MavenProject p : reactorProjects) {
            if (p.isExecutionRoot()) {
                return p;
            }
        }
        return null;
    }

    private MavenProject getLastProject() {
        int i = reactorProjects.size();
        while (i > 0) {
            MavenProject project = reactorProjects.get(--i);
            if (!isSkip(project)) {
                return project;
            }
        }
        return null;
    }

    private boolean isSkip(MavenProject project) {
        return detectSkip && PluginUtil.isSkip(project);
    }

    private Toolchain getToolchain() {
        Toolchain tc = null;
        if (toolchainManager != null) {
            tc = toolchainManager.getToolchainFromBuildContext("jdk", session);
        }

        return tc;
    }
}
