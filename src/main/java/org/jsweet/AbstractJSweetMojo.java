package org.jsweet;

import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.jsweet.transpiler.EcmaScriptComplianceLevel;
import org.jsweet.transpiler.JSweetFactory;
import org.jsweet.transpiler.JSweetProblem;
import org.jsweet.transpiler.JSweetTranspiler;
import org.jsweet.transpiler.ModuleKind;
import org.jsweet.transpiler.ModuleResolution;
import org.jsweet.transpiler.SourceFile;
import org.jsweet.transpiler.SourcePosition;
import org.jsweet.transpiler.util.ConsoleTranspilationHandler;
import org.jsweet.transpiler.util.ErrorCountTranspilationHandler;
import org.jsweet.transpiler.util.ProcessUtil;

import com.sun.source.util.JavacTask;

public abstract class AbstractJSweetMojo extends AbstractMojo {

    @Parameter(alias = "target", required = false)
    protected EcmaScriptComplianceLevel targetVersion;

    @Parameter(required = false)
    protected ModuleKind module;

    @Parameter(required = false)
    protected String outDir;

    @Parameter(required = false)
    protected String tsOut;

    @Parameter(required = false)
    protected Boolean tsserver;

    @Parameter(required = false)
    protected Boolean bundle;

    @Parameter(required = false)
    protected Boolean declaration;

    @Parameter(required = false)
    protected Boolean tsOnly;

    @Parameter(required = false)
    protected String dtsOut;

    @Parameter(required = false)
    protected Boolean sourceMap;

    @Parameter(required = false)
    protected String sourceRoot;

    @Parameter(required = false)
    private List<String> compileSourceRootsOverride;

    @Parameter(required = false)
    protected Boolean verbose;

    @Parameter(required = false)
    protected Boolean veryVerbose;

    @Parameter(required = false)
    protected Boolean ignoreDefinitions;

    @Parameter(required = false)
    protected File candiesJsOut;

    @Parameter
    protected String[] includes;

    @Parameter
    protected String[] excludes;

    @Parameter(required = false)
    protected String encoding;

    @Parameter(required = false)
    protected Boolean noRootDirectories;

    @Parameter(required = false)
    protected Boolean enableAssertions;

    @Parameter(required = false)
    protected Boolean disableSinglePrecisionFloats;

    @Parameter(defaultValue = "${java.home}")
    protected File jdkHome;

    @Parameter(required = false)
    protected String extraSystemPath;

    @Parameter(required = false)
    protected ModuleResolution moduleResolution;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor descriptor;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(required = false)
    protected String factoryClassName;

    @Parameter(required = false)
    protected List<JSweetProblem> ignoredProblems;

    @Parameter(required = false)
    protected String javaCompilerExtraOptions;

    @Parameter(required = false)
    protected Boolean ignoreTypeScriptErrors;

    @Parameter(required = false)
    protected File header;

    @Parameter(required = false)
    protected File workingDir;

    @Parameter(required = false)
    protected Boolean usingJavaRuntime;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        logInfo("Maven project: " + project.getName());
        logInfo("maven.version: " + session.getRequest().getSystemProperties().getProperty("maven.version"));
    }

    private void logInfo(String content) {
        if ((verbose != null && verbose) || (veryVerbose != null && veryVerbose)) {
            getLog().info(content);
        }
    }

    protected SourceFile[] collectSourceFiles(MavenProject project) {
        logInfo("source includes: " + ArrayUtils.toString(includes));
        logInfo("source excludes: " + ArrayUtils.toString(excludes));

        List<String> sourcePaths = getCompileSourceRoots(project);
        logInfo("sources paths: " + sourcePaths);

        List<SourceFile> sources = new LinkedList<>();
        for (String sourcePath : sourcePaths) {
            scanForJavaFiles(sources, new File(sourcePath));
        }

        for (Resource resource : project.getResources()) {
            scanForJavaFiles(sources, new File(resource.getDirectory()));
        }

        logInfo("sourceFiles=" + sources);
        return sources.toArray(new SourceFile[0]);
    }

    private void scanForJavaFiles(List<SourceFile> sources, File sourceDirectory) {
        if (!sourceDirectory.exists()) {
            getLog().debug(sourceDirectory.getAbsolutePath() + " does not exist");
            return;
        }

        DirectoryScanner dirScanner = new DirectoryScanner();
        dirScanner.setBasedir(sourceDirectory);
        dirScanner.setIncludes(includes);
        dirScanner.setExcludes(excludes);
        dirScanner.scan();

        for (String includedPath : dirScanner.getIncludedFiles()) {
            if (includedPath.endsWith(".java")) {
                sources.add(new SourceFile(new File(sourceDirectory, includedPath)));
            }
        }
    }

    protected JSweetTranspiler createJSweetTranspiler(MavenProject project) throws MojoExecutionException {
        try {
            List<File> dependenciesFiles = getCandiesJars();

            String classPath = dependenciesFiles.stream()
                    .map(File::getAbsolutePath)
                    .collect(joining(File.pathSeparator));

            logInfo("classpath from Maven: " + classPath);

            File tsOutputDir = getTsOutDir();
            File jsOutDir = getJsOutDir();
            File declarationOutDir = getDeclarationsOutDir();

            boolean isTsserverEnabled = tsserver != null ? tsserver : true;

            logInfo("jsOut: " + jsOutDir);
            logInfo("bundle: " + bundle);
            logInfo("tsOut: " + tsOutputDir);
            logInfo("tsOnly: " + tsOnly);
            logInfo("tsserver: " + isTsserverEnabled);

            LogManager.getLogger("org.jsweet").setLevel(Level.WARN);
            if (verbose != null && verbose) {
                LogManager.getLogger("org.jsweet").setLevel(Level.DEBUG);
            }
            if (veryVerbose != null && veryVerbose) {
                LogManager.getLogger("org.jsweet").setLevel(Level.ALL);
            }

            JSweetFactory factory = createJSweetFactory(project, dependenciesFiles, classPath);

            if (workingDir != null && !workingDir.isAbsolute()) {
                workingDir = new File(getBaseDirectory(), workingDir.getPath());
            }

            JSweetTranspiler transpiler = new JSweetTranspiler(getBaseDirectory(), null, factory, workingDir,
                    tsOutputDir, jsOutDir, candiesJsOut, classPath);

            transpiler.setTscWatchMode(false);
            if (targetVersion != null) transpiler.setEcmaTargetVersion(targetVersion);
            if (module != null) transpiler.setModuleKind(module);
            if (bundle != null) transpiler.setBundle(bundle);
            transpiler.setUseTsserver(isTsserverEnabled);
            if (sourceMap != null) transpiler.setGenerateSourceMaps(sourceMap);
            if (getSourceRoot() != null) transpiler.setSourceRoot(getSourceRoot());
            if (encoding != null) transpiler.setEncoding(encoding);
            if (noRootDirectories != null) transpiler.setNoRootDirectories(noRootDirectories);
            if (enableAssertions != null) transpiler.setIgnoreAssertions(!enableAssertions);
            if (declaration != null) transpiler.setGenerateDeclarations(declaration);
            if (declarationOutDir != null) transpiler.setDeclarationsOutputDir(declarationOutDir);
            if (ignoreDefinitions != null) transpiler.setGenerateDefinitions(!ignoreDefinitions);
            if (tsOnly != null) transpiler.setGenerateJsFiles(!tsOnly);
            if (ignoreTypeScriptErrors != null) transpiler.setIgnoreTypeScriptErrors(ignoreTypeScriptErrors);
            if (header != null) transpiler.setHeaderFile(header);
            if (disableSinglePrecisionFloats != null) transpiler.setDisableSinglePrecisionFloats(disableSinglePrecisionFloats);
            if (moduleResolution != null) transpiler.setModuleResolution(moduleResolution);
            if (tsOutputDir != null) transpiler.setTsOutputDir(tsOutputDir);
            if (jsOutDir != null) transpiler.setJsOutputDir(jsOutDir);
            if (usingJavaRuntime != null) transpiler.setUsingJavaRuntime(usingJavaRuntime);
            if (javaCompilerExtraOptions != null) transpiler.setJavaCompilerExtraOptions(javaCompilerExtraOptions.split(","));

            if (isNotBlank(extraSystemPath)) {
                ProcessUtil.addExtraPath(extraSystemPath);
            }

            return transpiler;

        } catch (Exception e) {
            getLog().error("failed to create transpiler", e);
            throw new MojoExecutionException("failed to create transpiler", e);
        }
    }

    private JSweetFactory createJSweetFactory(MavenProject project, List<File> dependenciesFiles, String classPath)
            throws IOException, MojoExecutionException {

        JSweetFactory factory = null;

        if (factoryClassName != null) {
            try {
                URL[] urls = new ArrayList<File>(dependenciesFiles).stream()
                        .map(f -> {
                            try { return f.toURI().toURL(); } 
                            catch (MalformedURLException e) { return null; }
                        }).toArray(URL[]::new);
                URLClassLoader classLoader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
                factory = (JSweetFactory) classLoader.loadClass(factoryClassName).getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                logInfo("Could not load JSweet factory class via classloader, using default");
            }
        }

        if (factory == null) {
            factory = new JSweetFactory();
        }

        return factory;
    }

    protected File getDeclarationsOutDir() throws IOException {
        if (isNotBlank(this.dtsOut)) {
            File f = new File(this.dtsOut);
            if (!f.isAbsolute()) f = new File(getBaseDirectory(), this.dtsOut);
            return f.getCanonicalFile();
        }
        return null;
    }

    protected File getSourceRoot() throws IOException {
        if (isNotBlank(this.sourceRoot)) {
            File f = new File(this.sourceRoot);
            if (!f.isAbsolute()) f = new File(getBaseDirectory(), this.sourceRoot);
            return f.getCanonicalFile();
        }
        return null;
    }

    protected File getJsOutDir() throws IOException {
        if (isNotBlank(this.outDir)) {
            File f = new File(this.outDir);
            if (!f.isAbsolute()) f = new File(getBaseDirectory(), this.outDir);
            return f.getCanonicalFile();
        }
        return null;
    }

    protected File getBaseDirectory() {
        return project.getBasedir().getAbsoluteFile();
    }

    protected File getTsOutDir() throws IOException {
        if (isNotBlank(this.tsOut)) {
            File f = new File(this.tsOut);
            if (!f.isAbsolute()) f = new File(getBaseDirectory(), this.tsOut);
            return f.getCanonicalFile();
        }
        return null;
    }

    protected List<File> getCandiesJars() {
        // Todos os jars das dependências do projeto (diretas e transitivas)
        Set<org.apache.maven.artifact.Artifact> artifacts = project.getArtifacts();
        List<File> jars = artifacts.stream()
                .map(org.apache.maven.artifact.Artifact::getFile)
                .filter(f -> f != null && f.getName().endsWith(".jar"))
                .collect(Collectors.toList());

        logInfo("Candies jars detected: " + jars);
        return jars;
    }

    protected List<String> getCompileSourceRoots(MavenProject project) {
        if (compileSourceRootsOverride == null || compileSourceRootsOverride.isEmpty()) {
            return project.getCompileSourceRoots();
        }
        compileSourceRootsOverride = compileSourceRootsOverride.stream().filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (compileSourceRootsOverride.isEmpty()) {
            getLog().warn("compileSourceRootsOverride has blank elements, using defaults");
            return project.getCompileSourceRoots();
        }
        logInfo("Overriding compileSourceRoots with: " + compileSourceRootsOverride);
        return compileSourceRootsOverride;
    }

    // ----------------------
    // Transpilation handler
    // ----------------------
    private class JSweetMavenPluginTranspilationHandler extends ErrorCountTranspilationHandler {
        class Error {
            final JSweetProblem problem;
            final SourcePosition sourcePosition;
            final String message;

            public Error(JSweetProblem problem, SourcePosition sourcePosition, String message) {
                this.problem = problem;
                this.sourcePosition = sourcePosition;
                this.message = message;
            }

            @Override
            public String toString() {
                return (sourcePosition != null ? sourcePosition + " " : "") + message;
            }
        }

        private final List<Error> errors = new ArrayList<>();

        public List<Error> getErrors() {
            return List.copyOf(errors);
        }

        public JSweetMavenPluginTranspilationHandler() {
            super(new ConsoleTranspilationHandler());
        }

        @Override
        public void report(JSweetProblem problem, SourcePosition sourcePosition, String message) {
            if (ignoredProblems != null && ignoredProblems.contains(problem)) return;
            super.report(problem, sourcePosition, message);
            errors.add(new Error(problem, sourcePosition, message));
        }
    }

    protected void transpile(MavenProject project, JSweetTranspiler transpiler) throws MojoExecutionException {
        try {
            JSweetMavenPluginTranspilationHandler handler = new JSweetMavenPluginTranspilationHandler();
            SourceFile[] sources = collectSourceFiles(project);
            transpiler.transpile(handler, sources);

            int errorCount = handler.getErrorCount();
            if (errorCount > 0) {
                StringBuilder sb = new StringBuilder("\n\nTRANSPILATION ERRORS:\n");
                for (JSweetMavenPluginTranspilationHandler.Error e : handler.getErrors()) {
                    sb.append("* ").append(e).append("\n");
                }
                throw new MojoFailureException(
                        "Transpilation failed with " + errorCount + " errors and "
                                + handler.getWarningCount() + " warnings\n" + sb
                );
            } else if (handler.getWarningCount() > 0) {
                getLog().info("Transpilation completed with " + handler.getWarningCount() + " warnings");
            } else {
                getLog().info("Transpilation successfully completed with no errors and no warnings");
            }

        } catch (Exception e) {
            getLog().error("transpilation failed", e);
            throw new MojoExecutionException("transpilation failed", e);
        }
    }
}
