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
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

@CommandLine.Command(
        name = "jresolve",
        mixinStandardHelpOptions = true,
        version = "v2024.05.26",
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
            names = {"--output-directory"},
            description = "Directory to copy artifacts to."
    )
    public File outputDirectory = null;

    @CommandLine.Option(
            names = {"--resolution-file"},
            description = "File to save resolutions in to."
    )
    public File resolutionFile = null;

    @CommandLine.Option(
            names = {"--select"},
            description = "Selects dependencies from the given resolution file."
    )
    public boolean select = false;

    /*
    @CommandLine.Option(
            names = {"--output-format"},
            description = "Format to output"
    )
    public OutputFormat outputFormat = OutputFormat.path;
     */

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

    @CommandLine.Option(
            names = {"--cache-path"},
            description = "Path to use for caching the files fetched during dependency resolution"
    )
    public File cachePath = null;

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

        var dependencies = new ArrayList<Dependency>();

        for (var packageUrl : packageUrls) {
            var group = new Group(String.join(".", Objects.requireNonNull(packageUrl.getNamespace(), "Package url must have a namespace")));
            var artifact = new Artifact(packageUrl.getName());
            var version = new Version(Objects.requireNonNull(packageUrl.getVersion(), "Package url must have a version"));

            var repositoryNames = List.of("central");
            String classifierStr = null;

            var qualifiers = packageUrl.getQualifiers();
            if (qualifiers != null) {
                var repoQualifier = qualifiers.get("repository");
                if (repoQualifier != null) {
                    repositoryNames = Arrays.asList(repoQualifier.split(","));
                }

                var classifierQualifier = qualifiers.get("classifier");
                if (classifierQualifier != null && !classifierQualifier.equals("default")) {
                    classifierStr = classifierQualifier;
                }
            }

            var repositories = new ArrayList<MavenRepository>();
            for (var repositoryName : repositoryNames)  {
                var repo = knownRepositories.get(repositoryName);
                if (repo == null) {
                    err.println("Unknown repository: " + repositoryName);
                    err.flush();
                    return -1;
                }
                repositories.add(repo);
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
                    List.copyOf(repositories),
                    List.of(Scope.COMPILE, Scope.RUNTIME),
                    classifier,
                    Classifier.SOURCES,
                    Classifier.JAVADOC
            );

            var dependency = new Dependency(library, coordinate);
            dependencies.add(dependency);
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

        /*
        if (outputFormat == OutputFormat.manifest) {
            resolution.selectedDependencies()
                    .stream()
                    .sorted(Comparator.comparing((Dependency dep) -> dep.library().group())
                            .thenComparing(dep -> dep.library().artifact()))
                    .forEach(dependency -> {
                        if (dependency.coordinate() instanceof MavenCoordinate mavenCoordinate) {
                            out.println("pkg:maven/"
                                        + mavenCoordinate.group()
                                        + "/"
                                        +  mavenCoordinate.artifact()
                                        + "@"
                                        + mavenCoordinate.version()
                                        + (
                                                mavenCoordinate.classifier() == Classifier.EMPTY
                                                        ? ""
                                                        : "?classifier="
                                                          + URLEncoder.encode(
                                                                mavenCoordinate.classifier().value(),
                                                                StandardCharsets.UTF_8
                                                )
                                        )
                            );
                        }
                    });
            out.flush();
            return 0;
        }
         */

        var deps = resolution.fetch().withCache(cache).run();

        if (outputFile != null) {
            if (outputFile.toPath().getParent() != null) {
                Files.createDirectories(outputFile.toPath().getParent());
            }
        }

        if (!httpsUrls.isEmpty()) {
            var httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
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


        if (outputDirectory == null || outputFile != null) {
            try (var outActual = outputFile == null ? out : new PrintWriter(outputFile)) {
                outActual.println(deps.path(extraPaths));
            }
        }

        if (outputDirectory != null && (!deps.libraries().isEmpty() || !extraPaths.isEmpty())) {
            Files.createDirectories(outputDirectory.toPath());


            var artifacts = new LinkedHashMap<String, Path>();

            Consumer<Path> addPath = (path) -> {
                var fileName = path.getFileName()
                        .toString();
                if (artifacts.containsKey(fileName)) {
                    err.println("Duplicate file: " + fileName + ". Need to rename." );
                    err.flush();
                    fileName = UUID.randomUUID() + "-" + fileName;
                }
                artifacts.put(fileName, path);
            };

            deps.libraries()
                    .forEach((library, path) -> {
                        addPath.accept(path);
                    });

            extraPaths.forEach(addPath);

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

    public CliMain() {
        this(new PrintWriter(System.out), new PrintWriter(System.err));
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new CliMain())
                .execute(args);
        System.exit(exitCode);
    }
}