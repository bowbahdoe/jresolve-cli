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
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "jresolve",
        mixinStandardHelpOptions = true,
        version = "ALPHA",
        description = "Resolves dependencies for the JVM."
)
public class CliMain implements Callable<Integer> {
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

        var packageUrls = new ArrayList<PackageUrl>();
        for (var dependencyFile : this.dependencyFile) {
            for (var line : Files.readAllLines(dependencyFile.toPath())) {
                var subbedLine = line
                        .replace("{os.name}", osName)
                        .replace("{os.arch}", osArch);
                if (!line.isBlank()) {
                    try {
                        packageUrls.add(PackageUrl.parse(subbedLine));
                    } catch (InvalidException e) {
                        extraPaths.add(Path.of(subbedLine));
                    }
                }
            }
        }

        for (var line : dependencies) {
            var subbedLine = line
                    .replace("{os.name}", osName)
                    .replace("{os.arch}", osArch);
            if (!line.isBlank()) {
                try {
                    packageUrls.add(PackageUrl.parse(subbedLine));
                } catch (InvalidException e) {
                    extraPaths.add(Path.of(subbedLine));
                }
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
            if (packageUrl.getType().equals("maven")) {
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
            else {
                err.println("Invalid package url type: " + packageUrl.getType());
                return -1;
            }
        }

        var resolve = new Resolve();
        resolve.addDependencies(dependencies);

        var resolution = resolve.run();

        if (printTree) {
            resolution.printTree(out, List.of());
            return 0;
        }

        var deps = resolution.fetch().run();

        if (outputFile != null) {
            Files.createDirectories(outputFile.toPath().getParent());
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