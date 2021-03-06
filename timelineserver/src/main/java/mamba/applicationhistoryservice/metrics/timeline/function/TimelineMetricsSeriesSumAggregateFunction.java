package mamba.applicationhistoryservice.metrics.timeline.function;

import java.util.List;

/**
 * Created by dongbin on 2016/10/10.
 */
public class TimelineMetricsSeriesSumAggregateFunction extends AbstractTimelineMetricsSeriesAggregateFunction {
    private static final String FUNCTION_NAME = "SUM";

    @Override
    protected Double applyFunction(List<Double> values) {
        double sum = 0.0d;
        for (Double value : values) {
            sum += value;
        }

        return sum;
    }

    @Override
    protected String getFunctionName() {
        return FUNCTION_NAME;
    }
}