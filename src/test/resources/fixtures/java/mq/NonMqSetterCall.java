package com.charmnight.linkgraph.fixtures.mq;

import java.util.Map;

public class NonMqSetterCall {
    public void configure(String defaultTargetDataSource, Map<String, String> targetDataSources) {
        setDefaultTargetDataSource(defaultTargetDataSource);
        setTargetDataSources(targetDataSources);
    }

    private void setDefaultTargetDataSource(String value) {
    }

    private void setTargetDataSources(Map<String, String> values) {
    }
}
