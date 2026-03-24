package com.charmnight.linkgraph.fixtures.http;

@org.springframework.cloud.openfeign.FeignClient(name = "order-service", path = "/orders")
public interface FeignOrderClient {
    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    String getOrder(String id);
}

class OrderGatewayService {
    private final FeignOrderClient orderClient;

    OrderGatewayService(FeignOrderClient orderClient) {
        this.orderClient = orderClient;
    }

    public String loadOrder(String id) {
        return orderClient.getOrder(id);
    }
}

@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/orders")
class OrderProviderController {
    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public String getOrder(String id) {
        return "provider:" + id;
    }
}
