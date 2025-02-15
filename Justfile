help:
    just --list

make_reflect_config:
    ./mvnw clean
    ./mvnw compile
    ./mvnw -Ppicocli-codegen dependency:copy-dependencies
    ./mvnw package
    java \
        --class-path target/dependency/picocli-codegen-4.7.5.jar:target/jresolve-cli-2025.02.14.1.jar:target/dependency/json-2024.11.20.jar:target/dependency/picocli-4.7.5.jar:target/dependency/purl-2023.11.07.jar:target/dependency/resolve-2025.02.14.1.jar \
        picocli.codegen.aot.graalvm.ReflectionConfigGenerator \
        dev.mccue.resolve.cli.CliMain > reflect.json

exe static='':
    ./mvnw clean
    ./mvnw compile
    ./mvnw dependency:copy-dependencies
    ./mvnw package
    native-image \
        --class-path $(./join.sh) \
        -H:+UnlockExperimentalVMOptions -H:ReflectionConfigurationFiles=reflect.json -H:+ReportUnsupportedElementsAtRuntime \
        -jar target/jresolve-cli-2025.02.14.1.jar \
        {{static}} jresolve

exe_windows:
    ./mvnw clean
    ./mvnw compile
    ./mvnw dependency:copy-dependencies
    ./mvnw package
    native-image.cmd --module-path "target\dependency\json-2024.11.20.jar;target\dependency\picocli-4.7.5.jar;target\dependency\purl-2023.11.07.jar;target\dependency\resolve-2025.02.14.1.jar" -H:+UnlockExperimentalVMOptions -H:ReflectionConfigurationFiles=reflect.json -H:+ReportUnsupportedElementsAtRuntime -jar "target\jresolve-cli-2024.05.26.jar" jresolve

release:
    ./mvnw clean
    ./mvnw compile
    ./mvnw dependency:copy-dependencies
    ./mvnw package

    rm -rf target/jlinked-jres
    jlink \
        --module-path target/dependency:target/classes \
        --output target/jlinked-jre \
        --add-modules dev.mccue.resolve.cli \
        --launcher jresolve=dev.mccue.resolve.cli/dev.mccue.resolve.cli.CliMain

jreleaser:
    ./mvnw clean
    ./mvnw compile
    ./mvnw dependency:copy-dependencies

    rm -rf sdkman


