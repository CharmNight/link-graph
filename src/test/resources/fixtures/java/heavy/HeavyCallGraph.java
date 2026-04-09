package com.charmnight.linkgraph.fixtures.heavy;

public class HeavyCallGraph {
    /**
     * 一个扇出很多的方法，用来验证链路提取不会把所有下游方法一次性塞满画布。
     */
    public void fanOut() {
        helper01();
        helper02();
        helper03();
        helper04();
        helper05();
        helper06();
        helper07();
        helper08();
        helper09();
        helper10();
    }

    public void helper01() {}

    public void helper02() {}

    public void helper03() {}

    public void helper04() {}

    public void helper05() {}

    public void helper06() {}

    public void helper07() {}

    public void helper08() {}

    public void helper09() {}

    public void helper10() {}

    /**
     * 一个被很多上游调用的方法，用来模拟 AOP / 公共切面入口的高扇入场景。
     */
    public void joinPoint() {}

    public void caller01() { joinPoint(); }

    public void caller02() { joinPoint(); }

    public void caller03() { joinPoint(); }

    public void caller04() { joinPoint(); }

    public void caller05() { joinPoint(); }

    public void caller06() { joinPoint(); }

    public void caller07() { joinPoint(); }

    public void caller08() { joinPoint(); }

    public void caller09() { joinPoint(); }

    public void caller10() { joinPoint(); }
}
