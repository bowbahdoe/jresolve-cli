package dev.mccue.resolve.cli;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.spi.ToolProvider;

public final class JResolveToolProvider implements ToolProvider {
    @Override
    public String name() {
        return "jresolve";
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        return new CommandLine(new CliMain(out, err)).execute(args);
    }
}
