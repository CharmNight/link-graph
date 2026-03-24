package com.charmnight.linkgraph.fixtures.spring;

public class SpringControllerServiceRepo {
}

@org.springframework.web.bind.annotation.RestController
@org.springframework.web.bind.annotation.RequestMapping("/orders")
class OrderController {
    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public String getOrder(String id) {
        return service.findOrder(id);
    }
}

interface OrderService {
    String findOrder(String id);
}

@org.springframework.stereotype.Service
class DefaultOrderService implements OrderService {
    private final OrderRepository repository;

    DefaultOrderService(OrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public String findOrder(String id) {
        return repository.loadOrder(id);
    }
}

interface OrderRepository {
    String loadOrder(String id);
}

@org.springframework.stereotype.Repository
class JdbcOrderRepository implements OrderRepository {
    @Override
    public String loadOrder(String id) {
        return "db:" + id;
    }
}
