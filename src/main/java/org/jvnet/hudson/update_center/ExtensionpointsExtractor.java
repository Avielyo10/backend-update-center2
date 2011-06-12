package org.jvnet.hudson.update_center;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import org.apache.commons.io.IOUtils;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Finds the defined extension points in a HPI.
 */
@SuppressWarnings({"Since15"})
public class ExtensionpointsExtractor {

    private HPI hpi;

    public ExtensionpointsExtractor(HPI hpi) {
        this.hpi = hpi;
    }

    public List<ExtensionImpl> extract() throws IOException, InterruptedException {
        File sourcesJar = hpi.resolveSources();
        File dir = unzip(sourcesJar);
        File pom = hpi.resolvePOM();
        FileUtils.copyInto(pom, dir, "pom.xml");
        File dependencies = downloadDependencies(dir);

        final JavacTask javac = prepareJavac(dir, dependencies);
        final Trees trees = Trees.instance(javac);
        final Elements elements = javac.getElements();
        final Types types = javac.getTypes();

        final TypeElement extensionPoint = elements.getTypeElement("hudson.ExtensionPoint");

        Iterable<? extends CompilationUnitTree> parsed = javac.parse();
        javac.analyze();

        final List<ExtensionImpl> r = new ArrayList<ExtensionImpl>();

        // discover all compiled types
        TreePathScanner<?,?> classScanner = new TreePathScanner<Void,Void>() {
            public Void visitClass(ClassTree ct, Void _) {
                TreePath path = getCurrentPath();
                TypeElement e = (TypeElement) trees.getElement(path);
                if(e!=null) {
//                    if (!e.getModifiers().contains(Modifier.ABSTRACT))
                        checkIfExtension(e,e);
                }

                return super.visitClass(ct, _);
            }

            private void checkIfExtension(TypeElement root, TypeElement e) {
                for (TypeMirror i : e.getInterfaces()) {
                    if (types.asElement(i).equals(extensionPoint))
                        r.add(new ExtensionImpl(hpi, javac, root, e));
                    checkIfExtension(root,(TypeElement)types.asElement(i));
                }
                TypeMirror s = e.getSuperclass();
                if (!(s instanceof NoType))
                    checkIfExtension(root,(TypeElement)types.asElement(s));
            }
        };

        for( CompilationUnitTree u : parsed )
            classScanner.scan(u,null);

        return r;
    }

    private JavacTask prepareJavac(File sourceFiles, File dependencies) throws IOException {
        JavaCompiler javac = JavacTool.create();
        DiagnosticListener<? super JavaFileObject> errorListener = new DiagnosticListener<JavaFileObject>() {
            public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                //TODO report
                System.out.println(diagnostic);
            }
        };
        StandardJavaFileManager fileManager = javac.getStandardFileManager(errorListener, Locale.getDefault(), Charset.defaultCharset());


        fileManager.setLocation(StandardLocation.CLASS_PATH, generateClassPath(dependencies));

        // annotation processing appears to cause the source files to be reparsed
        // (even though I couldn't find exactly where it's done), which causes
        // Tree symbols created by the original JavacTask.parse() call to be thrown away,
        // which breaks later processing.
        // So for now, don't perform annotation processing
        List<String> options = Arrays.asList("-proc:none");

        Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromFiles(generateSources(sourceFiles));
        JavaCompiler.CompilationTask task = javac.getTask(null, fileManager, errorListener, options, null, files);
        return (JavacTask) task;
    }

    private Iterable<? extends File> generateClassPath(File dependencies) {
        return FileUtils.getFileIterator(dependencies, "jar");
    }

    private Iterable<? extends File> generateSources(File sourceDir) {
        return FileUtils.getFileIterator(sourceDir, "java");

    }

    private File downloadDependencies(File pomDir) throws IOException, InterruptedException {
        File tempDir = new File(System.getProperty("java.io.tmp"), hpi.artifact.artifactId + "-dependencies-" + System.currentTimeMillis());
        tempDir.mkdirs();
        tempDir.deleteOnExit();
        ProcessBuilder builder = new ProcessBuilder("mvn",
                "dependency:copy-dependencies",
                "-DincludeScope=compile",
                "-DoutputDirectory=" + tempDir.getAbsolutePath());
        builder.directory(pomDir);
        builder.redirectErrorStream(true);
        Process proc = builder.start();

        // capture the output, but only report it in case of an error
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        proc.getOutputStream().close();
        IOUtils.copy(proc.getInputStream(),output);
        proc.getErrorStream().close();
        proc.getInputStream().close();

        int result = proc.waitFor();
        if (result != 0) {
            System.out.write(output.toByteArray());
            throw new IOException("Maven didn't like this! " + pomDir.getAbsolutePath());
        }
        return tempDir;
    }

    private File unzip(File sourcesJar) throws IOException {
        File tempDir = new File(System.getProperty("java.io.tmp"), hpi.artifact.artifactId + "-source-" + System.currentTimeMillis());
        tempDir.mkdirs();
        tempDir.deleteOnExit();
        FileUtils.unzip(sourcesJar, tempDir);
        return tempDir;
    }

    public static void main(String[] args) throws Exception {
        MavenRepositoryImpl r = new MavenRepositoryImpl();
        r.addRemoteRepository("java.net2",
                new File("updates.jenkins-ci.org"),
                new URL("http://maven.glassfish.org/content/groups/public/"));
        PluginHistory p = r.listHudsonPlugins().iterator().next();
        System.out.println(p.artifactId);

        List<ExtensionImpl> impls = new ExtensionpointsExtractor(p.latest()).extract();
        for (ExtensionImpl impl : impls) {
            System.out.printf("Found %s as %s\n",
                    impl.implementation.getQualifiedName(),
                    impl.extensionPoint.getQualifiedName());
        }
    }
}
