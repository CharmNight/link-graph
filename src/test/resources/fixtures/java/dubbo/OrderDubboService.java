package com.charmnight.linkgraph.fixtures.dubbo;

public interface OrderDubboService {
    String fetchOrder(String id);
}

class OrderDubboFacade {
    @org.apache.dubbo.config.annotation.DubboReference
    private OrderDubboService orderDubboService;

    public String loadOrder(String id) {
        return orderDubboService.fetchOrder(id);
    }
}

@org.apache.dubbo.config.annotation.DubboService
class OrderDubboServiceImpl implements OrderDubboService {
    @Override
    public String fetchOrder(String id) {
        return "dubbo:" + id;
    }
}
