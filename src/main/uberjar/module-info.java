module dev.mccue.jresolve.cli.uber {
    requires java.xml;
    requires java.net.http;

    provides java.util.spi.ToolProvider
            with dev.mccue.resolve.uber.resolve.cli.JResolveToolProvider;
}