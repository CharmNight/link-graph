package com.charmnight.linkgraph.fixtures.spring;

import org.springframework.stereotype.Component;

@Component
public class SpringBeanWithoutDependencies {
    public String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
