package com.charmnight.linkgraph.fixtures.simple;

public class CallOrderChain {
    public void execute() {
        gamma();
        alpha();
        beta();
    }

    public void alpha() {}

    public void beta() {}

    public void gamma() {}
}
