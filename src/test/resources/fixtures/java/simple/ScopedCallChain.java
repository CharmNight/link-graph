package com.charmnight.linkgraph.fixtures.simple;

import java.util.List;

public class ScopedCallChain {
    public void render(Order order) {
        List<Line> lines = order.getLines();
        before();
        lines.forEach((Line line) -> {
            if (line.isActive()) {
                line.getSku();
            }
        });
        for (Line line : lines) {
            line.getSku();
        }
        after();
    }

    SysUser buildUser(Object source) {
        SysUser user = null;
        if (source != null) {
            user = new SysUser();
            BeanUtils.copyBeanProp(user, source);
        }
        return user;
    }

    void before() {
    }

    void after() {
    }
}

class SysUser {
}

class BeanUtils {
    static void copyBeanProp(SysUser user, Object source) {
    }
}

class Order {
    List<Line> getLines() {
        return List.of();
    }
}

class Line {
    boolean isActive() {
        return true;
    }

    String getSku() {
        return "sku";
    }
}
