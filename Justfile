help:
    just --list

make_reflect_config:
    ./mvnw clean
    ./mvnw compile
    ./mvnw -Ppicocli-codegen dependency:copy-dependencies
    ./mvnw package
    java \
        --class-path target/jresolve-cli-0.0.2.jar:target/dependency/json-0.2.4.jar:target/dependency/picocli-4.7.5.jar:target/dependency/picocli-codegen-4.7.5.jar:target/dependency/purl-0.0.1.jar:target/dependency/resolve-0.0.3.jar \
        picocli.codegen.aot.graalvm.ReflectionConfigGenerator \
        dev.mccue.resolve.cli.CliMain > reflect.json

exe native='native-image':
    ./mvnw clean
    ./mvnw compile
    ./mvnw dependency:copy-dependencies
    ./mvnw package
    {{native}} \
        --module-path target/dependency/json-0.2.4.jar:target/dependency/picocli-4.7.5.jar:target/dependency/purl-0.0.1.jar:target/dependency/resolve-0.0.3.jar \
        -H:+UnlockExperimentalVMOptions -H:ReflectionConfigurationFiles=reflect.json -H:+ReportUnsupportedElementsAtRuntime \
        -jar target/jresolve-cli-0.0.2.jar \
        jresolve
