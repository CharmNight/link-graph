package com.charmnight.linkgraph.fixtures.spring;

public class SpringEventFlow {
}

@org.springframework.stereotype.Service
class OrderPublisher {
    private final org.springframework.context.ApplicationEventPublisher publisher;

    OrderPublisher(org.springframework.context.ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void submit(String orderId) {
        publisher.publishEvent(new OrderCreatedEvent(orderId));
    }
}

class OrderCreatedEvent {
    private final String orderId;

    OrderCreatedEvent(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}

@org.springframework.stereotype.Component
class OrderCreatedListener {
    @org.springframework.context.event.EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        handle(event);
    }

    void handle(OrderCreatedEvent event) {
        event.getOrderId();
    }
}
