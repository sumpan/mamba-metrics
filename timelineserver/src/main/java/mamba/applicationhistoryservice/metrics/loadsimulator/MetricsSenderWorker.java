package mamba.applicationhistoryservice.metrics.loadsimulator;

import mamba.applicationhistoryservice.metrics.loadsimulator.data.AppMetrics;
import mamba.applicationhistoryservice.metrics.loadsimulator.data.HostMetricsGenerator;
import mamba.applicationhistoryservice.metrics.loadsimulator.net.MetricsSender;
import mamba.applicationhistoryservice.metrics.loadsimulator.net.RestMetricsSender;
import mamba.applicationhistoryservice.metrics.loadsimulator.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Created by dongbin on 2016/10/10.
 */
public class MetricsSenderWorker implements Callable<String> {


    private final static Logger LOG = LoggerFactory.getLogger(RestMetricsSender.class);

    MetricsSender sender;
    HostMetricsGenerator hmg;

    public MetricsSenderWorker(MetricsSender sender, HostMetricsGenerator metricsGenerator) {
        this.sender = sender;
        hmg = metricsGenerator;
    }

    @Override
    public String call() throws Exception {
        AppMetrics hostMetrics = hmg.createMetrics();

        try {
            String request = new Json().serialize(hostMetrics); //inject?
            String response = sender.pushMetrics(request);

            return response;
        } catch (IOException e) {
            LOG.error("Error while pushing metrics: ", e);
            throw e;
        }

    }
}
