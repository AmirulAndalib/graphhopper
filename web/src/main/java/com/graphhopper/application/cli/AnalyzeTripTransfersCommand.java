package com.graphhopper.application.cli;

import com.graphhopper.application.GraphHopperServerConfiguration;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.http.GraphHopperManaged;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Namespace;

public class AnalyzeTripTransfersCommand extends ConfiguredCommand<GraphHopperServerConfiguration> {
    public AnalyzeTripTransfersCommand() {
        super("triptransfers", "Create trip transfers");
    }

    @Override
    protected void run(Bootstrap<GraphHopperServerConfiguration> bootstrap, Namespace namespace, GraphHopperServerConfiguration configuration) throws Exception {
        GraphHopperGtfs graphHopper = (GraphHopperGtfs) new GraphHopperManaged(configuration.getGraphHopperConfiguration()).getGraphHopper();
        graphHopper.importOrLoad();
        graphHopper.close();
    }
}
