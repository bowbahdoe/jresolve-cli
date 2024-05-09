import dev.mccue.resolve.cli.JResolveToolProvider;

import java.util.spi.ToolProvider;

module dev.mccue.resolve.cli {
    requires dev.mccue.resolve;
    requires dev.mccue.json;
    requires dev.mccue.purl;
    requires info.picocli;

    opens dev.mccue.resolve.cli
            to info.picocli;

    exports dev.mccue.resolve.cli;

    provides ToolProvider
            with JResolveToolProvider;
}