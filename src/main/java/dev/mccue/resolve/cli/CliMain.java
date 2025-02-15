package dev.mccue.resolve.cli;

import dev.mccue.json.Json;
import dev.mccue.json.JsonDecoder;
import dev.mccue.purl.InvalidException;
import dev.mccue.purl.PackageUrl;
import dev.mccue.resolve.*;
import dev.mccue.resolve.maven.Classifier;
import dev.mccue.resolve.maven.MavenCoordinate;
import dev.mccue.resolve.maven.MavenRepository;
import dev.mccue.resolve.maven.Scope;
import org.tomlj.Toml;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.mccue.json.JsonDecoder.*;
import static dev.mccue.json.JsonDecoder.string;

@CommandLine.Command(
        name = "jresolve",
        mixinStandardHelpOptions = true,
        version = "v2025.02.14",
        description = "Resolves dependencies for the JVM."
)
public final class CliMain implements Callable<Integer> {
    private final PrintWriter out;
    private final PrintWriter err;
    @CommandLine.Option(
            names = {"--output-file"},
            description = "File to output computed path to."
    )
    public File outputFile = null;
    @CommandLine.Option(
            names = {"--output-directory"},
            description = "Directory to copy artifacts to."
    )
    public File outputDirectory = null;
    @CommandLine.Option(
            names = {"--enrich-pom"},
            description = "Enriches the given pom.xml file with the result of dependency resolution."
    )
    public File enrichPom = null;
    @CommandLine.Option(
            names = {"--maven-repositories-file"},
            description = "File containing maven repository definitions"
    )
    public File mavenRepositoriesFile;

    /*
    @CommandLine.Option(
            names = {"--resolution-file"},
            description = "File to save resolutions in to."
    )
    public File resolutionFile = null;

    @CommandLine.Option(
            names = {"--select"},
            description = "Selects dependencies from the given resolution file."
    )
    public boolean select = false; */

    /*
    @CommandLine.Option(
            names = {"--output-format"},
            description = "Format to output"
    )
    public OutputFormat outputFormat = OutputFormat.path;
     */
    @CommandLine.Option(
            names = {"--dependency-file"},
            description = "File containing package urls of dependencies",
            split = ","
    )
    public File[] dependencyFile = new File[]{};
    @CommandLine.Option(
            names = {"--print-tree"},
            description = "Skip fetching artifacts and print the result of dependency resolution"
    )
    public boolean printTree = false;
    @CommandLine.Option(
            names = {"--cache-path"},
            description = "Path to use for caching the files fetched during dependency resolution"
    )
    public File cachePath = null;

    @CommandLine.Option(
            names = {"--use-module-names"},
            description = "Save files in the output directory using the module names the jars represent."
    )
    public boolean useModuleNames = false;

    @CommandLine.Option(
            names = "--purge-output-directory",
            description = "Purges the specified output directory on run"
    )
    public boolean purgeOutputDirectory = false;

    @CommandLine.Parameters(paramLabel = "dependencies", description = "Package urls of dependencies")
    public String[] dependencies = new String[]{};

    public CliMain(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }


    public CliMain() {
        this(new PrintWriter(System.out), new PrintWriter(System.err));
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new CliMain())
                .execute(args);
        System.exit(exitCode);
    }

    private CacheKey uriToCacheKey(URI uri) {
        var url = uri.toString();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        return new CacheKey(Arrays.asList(url.split("((:)*/)+")));
    }

    @CommandLine.Command(name = "install")
    public int install() throws Exception {
        var toml = Toml.parse(Path.of("jproject.toml"));
        if (toml.hasErrors()) {
            System.err.println("Encountered errors when parsing jproject.toml");
            toml.errors().forEach(error -> System.err.println(error.toString()));
            return 1;
        }

        var projectToml = toml.getTable("project");

        if (projectToml != null) {
            var project = Json.read(projectToml.toJson());
            var defaultUsage = optionalField(
                    project,
                    "defaultUsage",
                    string().map(Usage::new)
            ).orElse(null);

            record UsagesAndDep(List<Usage> usages, Dependency dependency) {}

            var dependencySetToDeps = new HashMap<String, List<UsagesAndDep>>();
            var dependencies = optionalField(project, "dependencies", array())
                    .orElse(null);
            if (dependencies != null && !dependencies.isEmpty()) {

                for (var dependencyObject : dependencies) {
                    var coordinate = field(dependencyObject, "coordinate", string());
                    Dependency dependency = Dependency.fromCoordinate(coordinate);

                    var usages = optionalField(dependencyObject, "usage", JsonDecoder.oneOf(
                            string().map(Usage::new).map(List::of),
                            array(string().map(Usage::new))
                    )).orElse(List.of());
                    if (usages.isEmpty()) {
                        if (defaultUsage == null) {
                            err.println("Dependency missing \"usage\" and no \"defaultUsage\" specified: " + coordinate);
                            err.flush();
                            return 1;
                        }
                    }

                    var dependencySets = optionalField(dependencyObject, "dependencySets", JsonDecoder.oneOf(
                            string().map(List::of),
                            array(string())
                    )).orElse(null);
                    if (dependencySets == null) {
                        dependencySets = List.of("default");
                    }

                    for (String dependencySet : dependencySets) {
                        dependencySetToDeps.putIfAbsent(dependencySet, new ArrayList<>());
                        dependencySetToDeps.get(dependencySet).add(new UsagesAndDep(usages, dependency));
                    }
                }

                for (var entry : dependencySetToDeps.entrySet()) {
                    var dependencySet = entry.getKey();

                    var libraryToUsages = new LinkedHashMap<Library, Set<Usage>>();

                    var usagesAndDeps = entry.getValue();

                    var resolve = new Resolve();
                    for (var usagesAndDep : usagesAndDeps) {
                        libraryToUsages.put(usagesAndDep.dependency.library(), new LinkedHashSet<>(usagesAndDep.usages));
                        resolve.addDependency(usagesAndDep.dependency);
                    }

                    var resolution = resolve.run();

                    var librariesForUsage = resolution.librariesForUsage(
                            libraryToUsages,
                            defaultUsage
                    );

                    var fetch = resolution.fetch().run();

                    var args = new ArrayList<String>();
                    librariesForUsage.forEach(((usage, libraries) -> {
                        if (!libraries.isEmpty()) {
                            var libToPath = new HashMap<>(fetch.libraries());
                            libToPath.keySet().retainAll(libraries);
                            args.add(usage.value());
                            args.add(libToPath.values()
                                    .stream()
                                    .map(Path::toString)
                                    .collect(Collectors.joining(File.pathSeparator)));
                        }

                    }));

                    var dependencySetsPath = Path.of("dependencySets");
                    try {
                        Files.walkFileTree(dependencySetsPath, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file,
                                                             BasicFileAttributes attrs) throws IOException {
                                Files.delete(file);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult postVisitDirectory(Path dir,
                                                                      IOException e) throws IOException {
                                if (e == null) {
                                    Files.delete(dir);
                                    return FileVisitResult.CONTINUE;
                                } else {
                                    throw e;
                                }
                            }

                        });
                    } catch (NoSuchFileException e) {
                        // NoOp
                    }

                    Files.createDirectories(dependencySetsPath);
                    Files.writeString(dependencySetsPath.resolve(dependencySet), String.join("\n", args));
                }
            }
        }
        return 0;
    }

    @Override
    public Integer call() throws Exception {
        var osName = System.getProperty("os.name")
                .toLowerCase(Locale.US);
        var osArch = System.getProperty("os.arch")
                .toLowerCase(Locale.US);
        if (osArch.equals("x86-64")) {
            osArch = "x86_64";
        }


        var dependencies = new ArrayList<Dependency>();


        var knownRepositories = new HashMap<String, MavenRepository>();
        knownRepositories.put("central", MavenRepository.central());
        knownRepositories.put("local", MavenRepository.local());

        if (mavenRepositoriesFile != null) {
            var repoDeclarations = JsonDecoder.object(
                    Json.readString(Files.readString(mavenRepositoriesFile.toPath())),
                    Repository::fromJson
            );
            repoDeclarations.forEach((name, value) ->
                    knownRepositories.put(name, MavenRepository.remote(value.url(), () -> {
                        var builder = HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NORMAL);
                        value.authentication().ifPresent(authentication -> {
                            builder.authenticator(new Authenticator() {
                                @Override
                                protected PasswordAuthentication getPasswordAuthentication() {
                                    return new PasswordAuthentication(
                                            authentication.username(),
                                            authentication.password().toCharArray()
                                    );
                                }
                            });
                        });
                        return builder.build();
                    }))
            );
        }

        String finalOsArch = osArch;
        Function<String, String> processLine = line -> {
            var subbedLine = line
                    .replace("{{os.name}}", osName)
                    .replace("{{os.arch}}", finalOsArch)
                    .replace("{{user.dir}}", System.getProperty("user.dir"))
                    .trim();
            if (!line.isBlank()) {
                try {
                    var dependency = Dependency.fromCoordinate(subbedLine, knownRepositories);
                    dependencies.add(dependency);
                } catch (Exception e) {
                    return e.getMessage();
                }
            }

            return null;
        };

        for (var dependencyFile : this.dependencyFile) {
            for (var line : Files.readAllLines(dependencyFile.toPath())) {
                var msg = processLine.apply(line);
                if (msg != null) {
                    err.println("Invalid dependency declaration: " + line);
                    err.println(msg);
                    err.flush();
                    return -1;
                }
            }
        }

        for (var line : this.dependencies) {
            var msg = processLine.apply(line);
            if (msg != null) {
                err.println("Invalid dependency declaration: " + line);
                err.println(msg);
                err.flush();
                return -1;
            }
        }


        var cache = cachePath == null ? Cache.standard() : Cache.standard(cachePath.toPath());

        var resolve = new Resolve().withCache(cache);
        resolve.addDependencies(dependencies);

        var resolution = resolve.run();

        if (printTree) {
            resolution.printTree(out);
            out.flush();
            return 0;
        }

        if (enrichPom != null) {
            var pomContents = Files.readString(enrichPom.toPath());
            var factory = DocumentBuilderFactory.newDefaultInstance();
            Document document;
            try {
                document = factory
                        .newDocumentBuilder()
                        .parse(new InputSource(new StringReader(pomContents)));
            } catch (SAXException | IOException e) {
                err.println("Error parsing POM file: " + e.getMessage());
                err.flush();
                return 1;
            }
            var rootElement = document.getDocumentElement();

            Node dependenciesElement = null;
            var rootElementChildren = rootElement.getChildNodes();
            for (int i = 0; i < rootElementChildren.getLength(); i++) {
                var projectElementChild = rootElementChildren.item(i);
                if (projectElementChild.getNodeName().equals("dependencies")) {
                    dependenciesElement = projectElementChild;
                }

            }

            if (dependenciesElement == null) {
                dependenciesElement = document.createElement("dependencies");
                rootElement.appendChild(dependenciesElement);
            }

            var firstChild = dependenciesElement.getFirstChild();
            while (firstChild != null) {
                dependenciesElement.removeChild(firstChild);
                firstChild = dependenciesElement.getFirstChild();
            }

            for (var dependency : resolution.selectedDependencies()) {
                if (dependency.coordinate() instanceof MavenCoordinate mavenCoordinate) {
                    var dependencyElement = document.createElement("dependency");

                    var groupIdElement = document.createElement("groupId");
                    groupIdElement.setTextContent(mavenCoordinate.group().value());
                    dependencyElement.appendChild(groupIdElement);

                    var artifactIdElement = document.createElement("artifactId");
                    artifactIdElement.setTextContent(mavenCoordinate.artifact().value());
                    dependencyElement.appendChild(artifactIdElement);

                    var versionElement = document.createElement("version");
                    versionElement.setTextContent(mavenCoordinate.version().toString());
                    dependencyElement.appendChild(versionElement);

                    dependenciesElement.appendChild(dependencyElement);
                }
            }

            var transformerFactory = TransformerFactory.newDefaultInstance();

            Transformer transformer = transformerFactory.newTransformer(new StreamSource(new ByteArrayInputStream("""
                    <xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:output indent="yes"/>
                      <xsl:strip-space elements="*"/>
                    
                      <xsl:template match="@*|node()">
                        <xsl:copy>
                          <xsl:apply-templates select="@*|node()"/>
                        </xsl:copy>
                      </xsl:template>
                    
                    </xsl:stylesheet>
                    """.getBytes()
            )));
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            // pretty print

            DOMSource source = new DOMSource(document);
            try (var os = Files.newOutputStream(enrichPom.toPath())) {
                transformer.transform(source, new StreamResult(os));
            }
        }

        var deps = resolution.fetch().withCache(cache).run();

        if (outputFile != null) {
            if (outputFile.toPath().getParent() != null) {
                Files.createDirectories(outputFile.toPath().getParent());
            }
        }

        if ((outputDirectory == null && enrichPom == null) || outputFile != null) {
            try (var outActual = outputFile == null ? out : new PrintWriter(outputFile)) {
                outActual.println(deps.path());
            }
        }

        if (outputDirectory != null && purgeOutputDirectory) {
            try {
                Files.walkFileTree(outputDirectory.toPath(), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file,
                                                     BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir,
                                                              IOException e) throws IOException {
                        if (e == null) {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        } else {
                            throw e;
                        }
                    }

                });
            } catch (NoSuchFileException e) {
                // NoOp
            }

        }

        if (outputDirectory != null && (!deps.libraries().isEmpty())) {
            Files.createDirectories(outputDirectory.toPath());


            var artifacts = new LinkedHashMap<String, Path>();

            Function<Path, Integer> addPath = (path) -> {
                if (useModuleNames) {
                    var modules = ModuleFinder.of(path).findAll().toArray(ModuleReference[]::new);
                    if (modules.length == 0) {
                        err.println("No module found: " + path);
                        err.flush();
                        return 1;
                    }
                    if (modules.length > 1) {
                        err.println("More than one module found: " + path);
                        err.println(
                                Arrays.stream(modules)
                                        .map(ModuleReference::descriptor)
                                        .map(ModuleDescriptor::name)
                                        .collect(Collectors.joining(", "))
                        );
                        err.flush();
                        return 1;
                    }

                    var module = modules[0];

                    var fileName = module.descriptor().name() + ".jar";
                    if (artifacts.containsKey(fileName)) {
                        err.println("Duplicate module: " + module.descriptor().name());
                        err.flush();
                        return 1;
                    }
                    artifacts.put(fileName, path);
                } else {
                    var fileName = path.getFileName()
                            .toString();
                    if (artifacts.containsKey(fileName)) {
                        err.println("Duplicate file: " + fileName + ". Need to rename.");
                        err.flush();
                        fileName = UUID.randomUUID() + "-" + fileName;
                    }
                    artifacts.put(fileName, path);
                }

                return 0;
            };

            for (var path : deps.libraries().values()) {
                int status = addPath.apply(path);
                if (status != 0) {
                    return status;
                }
            }

            artifacts.forEach((fileName, path) -> {
                try {
                    Files.copy(
                            path,
                            Path.of(outputDirectory.toString(), fileName),
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (IOException e) {
                    err.println("Could not copy file: " + path);
                    err.flush();
                    throw new UncheckedIOException(e);
                }

            });
        }

        return 0;
    }
}