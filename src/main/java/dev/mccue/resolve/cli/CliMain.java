package dev.mccue.resolve.cli;

import dev.mccue.json.Json;
import dev.mccue.json.JsonDecoder;
import dev.mccue.purl.InvalidException;
import dev.mccue.purl.PackageUrl;
import dev.mccue.resolve.*;
import dev.mccue.resolve.maven.*;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

@CommandLine.Command(
        name = "jresolve",
        mixinStandardHelpOptions = true,
        version = "ALPHA",
        description = "Resolves dependencies for the JVM."
)
public final class CliMain implements Callable<Integer> {
    private final PrintWriter out;
    private final PrintWriter err;

    public CliMain(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    @CommandLine.Option(
            names = {"--output-file"},
            description = "File to output computed path to."
    )
    public File outputFile = null;

    @CommandLine.Option(
            names = {"--maven-repositories-file"},
            description = "File containing maven repository definitions"
    )
    public File mavenRepositoriesFile;

    @CommandLine.Option(
            names = {"--dependency-file"},
            description = "File containing package urls of dependencies",
            split = ","
    )
    public File[] dependencyFile = new File[] {};

    @CommandLine.Option(
            names = {"--print-tree"},
            description = "Skip fetching artifacts and print the result of dependency resolution"
    )
    public boolean printTree = false;

    @CommandLine.Parameters(paramLabel = "dependencies", description = "Package urls of dependencies")
    public String[] dependencies = new String[] {};

    private CacheKey uriToCacheKey(URI uri) {
        var url = uri.toString();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        return new CacheKey(Arrays.asList(url.split("((:)*/)+")));
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

        var extraPaths = new ArrayList<Path>();
        var httpsUrls = new ArrayList<URI>();
        var packageUrls = new ArrayList<PackageUrl>();

        String finalOsArch = osArch;
        Function<String, String> processLine = line -> {
            var subbedLine = line
                    .replace("{{os.name}}", osName)
                    .replace("{{os.arch}}", finalOsArch)
                    .replace("{{user.dir}}", System.getProperty("user.dir"))
                    .trim();
            if (!line.isBlank()) {
                if (subbedLine.startsWith("pkg:")) {
                    try {
                        var packageUrl = PackageUrl.parse(subbedLine);
                        if (packageUrl.getType().equals("maven")) {
                            packageUrls.add(PackageUrl.parse(subbedLine));
                        }
                        else {
                            return packageUrl.getType() + " is not a supported package url type.";
                        }
                    } catch (InvalidException e) {
                        return e.getMessage();
                    }
                }
                else if (subbedLine.startsWith("file:///")) {
                    try {
                        var uri = new URI(subbedLine);
                        extraPaths.add(Paths.get(uri));
                    } catch (URISyntaxException e) {
                        return e.getMessage();
                    }
                }
                else if (subbedLine.startsWith("file:")) {
                    return "File URLs must start with file:///";
                }
                else if (subbedLine.startsWith("https://")) {
                    try {
                        var uri = new URI(subbedLine);
                        httpsUrls.add(uri);
                    } catch (URISyntaxException e) {
                        return e.getMessage();
                    }
                }
                else if (subbedLine.contains(":")) {
                    return subbedLine.split(":")[0] + " is not a supported protocol.";
                }
                else {
                    extraPaths.add(Path.of(subbedLine));
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

        for (var line : dependencies) {
            var msg = processLine.apply(line);
            if (msg != null) {
                err.println("Invalid dependency declaration: " + line);
                err.println(msg);
                err.flush();
                return -1;
            }
        }


        var repositories = new HashMap<String, MavenRepository>();
        repositories.put("central", MavenRepository.central());
        repositories.put("local", MavenRepository.local());

        if (mavenRepositoriesFile != null) {
            var repoDeclarations = JsonDecoder.object(
                    Json.readString(Files.readString(mavenRepositoriesFile.toPath())),
                    Repository::fromJson
            );
            repoDeclarations.forEach((name, value) ->
                    repositories.put(name, MavenRepository.remote(value.url()))
            );
        }

        var dependencies = new ArrayList<Dependency>();
        for (var packageUrl : packageUrls) {
            var group = new Group(String.join(".", Objects.requireNonNull(packageUrl.getNamespace(), "Package url must have a namespace")));
            var artifact = new Artifact(packageUrl.getName());
            var version = new Version(Objects.requireNonNull(packageUrl.getVersion(), "Package url must have a version"));

            var repository = "central";
            String classifierStr = null;

            var qualifiers = packageUrl.getQualifiers();
            if (qualifiers != null) {
                var repoQualifier = qualifiers.get("repository");
                if (repoQualifier != null) {
                    repository = repoQualifier;
                }

                var classifierQualifier = qualifiers.get("classifier");
                if (classifierQualifier != null && !classifierQualifier.equals("default")) {
                    classifierStr = classifierQualifier;
                }
            }

            var repo = repositories.get(repository);
            if (repo == null) {
                err.println("Unknown repository: " + repository);
                err.flush();
                return -1;
            }

            var library = new Library(
                    group,
                    artifact,
                    classifierStr == null ? Variant.DEFAULT : new Variant(classifierStr)
            );

            var classifier = classifierStr == null ? Classifier.EMPTY : new Classifier(classifierStr);

            var coordinate = new MavenCoordinate(
                    group,
                    artifact,
                    version,
                    List.of(repo),
                    List.of(Scope.COMPILE),
                    classifier,
                    Classifier.SOURCES,
                    Classifier.JAVADOC
            );

            var dependency = new Dependency(library, coordinate);
            dependencies.add(dependency);
        }

        var resolve = new Resolve();
        resolve.addDependencies(dependencies);

        var resolution = resolve.run();

        if (printTree) {
            resolution.printTree(out);
            out.flush();
            return 0;
        }

        var deps = resolution.fetch().run();

        if (outputFile != null) {
            if (outputFile.toPath().getParent() != null) {
                Files.createDirectories(outputFile.toPath().getParent());
            }
        }


        var cache = Cache.standard();
        if (!httpsUrls.isEmpty()) {
            var httpClient = HttpClient.newHttpClient();
            for (var httpsUrl : httpsUrls) {
                var cacheKey = uriToCacheKey(httpsUrl);
                try {
                    extraPaths.add(cache.fetch(cacheKey, () -> {
                                try {
                                    var response = httpClient.send(
                                            HttpRequest.newBuilder(httpsUrl)
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofInputStream());
                                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                        throw new IOException("Bad status code: " + response.statusCode());
                                    }
                                    return response.body();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                    );
                } catch (UncheckedIOException e) {
                    err.println(e.getMessage());
                    err.flush();
                    return -1;
                }
            }
        }


        try (var outActual = outputFile == null ? out : new PrintWriter(outputFile)) {
            outActual.println(deps.path(extraPaths));
        }

        return 0;
    }

    public CliMain() {
        this(new PrintWriter(System.out), new PrintWriter(System.err));
    }


    // this example implements Callable, so parsing, error handling and handling user
    // requests for usage help or version help can be done with one line of code.
    public static void main(String... args) {
        int exitCode = new CommandLine(new CliMain())
                .execute(args);
        System.exit(exitCode);
    }
}