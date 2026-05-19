package com.charmnight.linkgraph.fixtures.aop;

class OrderService {
    @TracedBusinessEvent
    public String place(String orderId) {
        return orderId.trim();
    }
}

@Aspect
class TracingAspect {
    @Pointcut("execution(* com.charmnight.linkgraph.fixtures.aop.OrderService.place(..))")
    void placeOrder() {
    }

    @Around("placeOrder()")
    Object wrapPlace() {
        return null;
    }

    @Before("@annotation(com.charmnight.linkgraph.fixtures.aop.TracedBusinessEvent)")
    void beforeBusinessEvent() {
    }
}

@interface Aspect {
}

@interface Pointcut {
    String value();
}

@interface Around {
    String value();
}

@interface Before {
    String value();
}

@interface TracedBusinessEvent {
}
