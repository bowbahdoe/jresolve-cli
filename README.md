# jresolve-cli

Command line tool for resolving dependencies on the JVM.

**NOTE**: This is a very early draft of this tool. The social contract is that
if you use it and find it useful, I will be available to help you adapt to any changes. But I will
still make breaking changes if I have to. Expect rough edges.

## Installation

There are [packages](https://github.com/bowbahdoe/jresolve-cli/actions/runs/6788974508)
generated in CI which can be added to your path. 

For windows, I'm still debugging the GitHub actions setup.

For M1 Macs, GitHub does not provide an executor with a compatible CPU, so I'm building
those locally for now. Shoot me a message and I can send you an exe or you can build locally if
you have maven, [just](https://github.com/casey/just), and [native-image](https://www.graalvm.org/22.0/reference-manual/native-image/) on your machine.

Very much a work in progress.

## Usage

The concept is that you provide a "[package url](https://github.com/package-url/purl-spec)"
for every dependency you want to have at runtime. The tool will then output
a path containing those dependencies as well as any transitive dependencies.

### Simple Dependencies

For a simple dependency like `commons-collections4`, you will get a path
to its jar.


```
jresolve pkg:maven/org.apache.commons/commons-collections4@4.4
```

```
/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar
```

### Transitive Dependencies

For dependencies like `jdbi3-postgres`, which have dependencies of their own,
you will get a path containing the jars of the entire dependency tree.

```
jresolve pkg:maven/org.jdbi/jdbi3-postgres@3.41.3
```

```
/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/jakarta/annotation/jakarta.annotation-api/2.1.1/jakarta.annotation-api-2.1.1.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.22.0/error_prone_annotations-2.22.0.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/checkerframework/checker-qual/3.38.0/checker-qual-3.38.0.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/io/leangen/geantyref/geantyref/1.3.14/geantyref-1.3.14.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/jdbi/jdbi3-core/3.41.3/jdbi3-core-3.41.3.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/jdbi/jdbi3-postgres/3.41.3/jdbi3-postgres-3.41.3.jar
```

### Multiple dependencies

If you have more than one dependency, you can provide them one after another on the command line.

```
jresolve pkg:maven/org.apache.commons/commons-collections4@4.4 \
         pkg:maven/org.junit.jupiter/junit-jupiter-api@5.10.1
```

```
/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/junit/platform/junit-platform-commons/1.10.1/junit-platform-commons-1.10.1.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar:/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/junit/jupiter/junit-jupiter-api/5.10.1/junit-jupiter-api-5.10.1.jar
```

Or you can put them all into a file separated by newlines.

`deps`:

```
pkg:maven/org.apache.commons/commons-collections4@4.4
pkg:maven/org.junit.jupiter/junit-jupiter-api@5.10.1
```

Then you can use that as an argfile. Put `@` before the filename and the args will
expand out.

```
jresolve @deps
```

### Multiple sets of dependencies

If you have sets of dependencies which are available at different points in
your project's lifecycle, you can use multiple argfiles for them.

#### Compile Time

```
jresolve @deps/compile
```

#### Run Time

(includes both compile and runtime dependencies)
```
jresolve @deps/compile @deps/runtime
```

### Usage from JVM Tools

If you want to pass the result of a dependency resolution to tools like `javac` you have a few options

The first is that, if your shell supports it, you can use the output of the tool inline
with an invocation.

```
java --class-path $(jresolve @deps) src/Main.java
```

The more portable option is to run the tool and put the results into a file.

```
jresolve --output-file build/argfiles/runtime @deps/compile @deps/runtime
```

And then use the argfile when invoking the tool.

```
java --class-path @build/argfiles/runtime src/Main.java
```

Most tools in the JVM support expanding arguments from argfiles with `@`, but there
are some exceptions. A notable one is `jshell`, for some reason.

### Including file paths

If you need to include a path to a specific file or folder in your final `--class-path` or `--module-path`
you can do that in one of two ways.

The first is to include it as-is. Anything that cannot be parsed as a URL will be forwarded directly
to the final path.

```
jresolve pkg:maven/org.apache.commons/commons-collections4@4.4 some/other/path
```

```
/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar:some/other/path
```


The second is to supply it as an argument with a `file:///` url. Keep in mind that such urls
are always absolute paths and as such likely won't be portable to other machines.

```
jresolve pkg:maven/org.apache.commons/commons-collections4@4.4 file:///an/absolute/path
```

```
/Users/emccue/.jresolve/cache/https/repo1.maven.org/maven2/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar:/an/absolute/path
```


### Including remote files

If you need to include files hosted on some remote source, there is limited support for that.
If you provide an `https://` url, that file will be downloaded and put onto the final path.

There is no support for, and no plans to support, files hosted via other protocols like `tcp` or `http`
or to support getting files behind urls which require authentication.

```
jresolve https://piston-data.mojang.com/v1/objects/5b868151bd02b41319f54c8d4061b8cae84e665c/server.jar
```

### Usage to make a project

If you are curious what it would look like to make an actual project using this
and other JVM tools instead of a full build system, there is a demo of that [here](https://github.com/bowbahdoe/jresolve-example-simple/tree/main).

Getting IDEs to recognize what dependencies are made available is unsolved, but definitely solvable.

### Custom maven repositories

If you make a json file like this

```json 
{
  "jitpack": {
    "url": "https://jitpack.io"
  }
}
```

You can supply it to the tool

```
jresolve --maven-repositories-file repositories.json @deps
```

And include the repository to use on a specific coordinate.

```
pkg:maven/com.github.thegatesdev/maple@4.0.0?repository=jitpack
```

## Anticipated Questions


### Why package urls?

It is annoying to write `pkg:maven` in front of everything. It also makes the syntax for overriding
properties of dependencies a bit wonky - `?repository=jitpack` and such.

Those are the downsides. The upside is that it is possible to support other kinds of dependency declarations.
The underlying resolver is based on [tools.deps](https://github.com/clojure/tools.deps) and is agnostic to maven.
It should be relatively straightforward to add support for git dependencies (without going through jitpack), local 
dependencies (not just files, but locally developed projects with their own dependency manifests), and whatever
else is relevant.

### Why not _just_ use maven/gradle/ivy

Short answer

* Maven lags behind in its support for JVM tools, this approach is more generically composable.
Tons of pros and cons to that.
* Gradle is similar, but also ties itself to Groovy and Kotlin. I'm pretty sick of having to tell people to
learn two languages in order to learn Java.
* Ivy is technically very generic, but it suffers greatly from being made in the days of XML and Ant.
* I wanted to make something which is directly usable with the upcoming [Multi-File Source-Code Programs](https://bugs.openjdk.org/browse/JDK-8304400)
* I want to make the "command line flow" actually practical instead of a strange joke we play on early students before saying "sike!"
and giving them maven or gradle.

Elaboration available upon request.

