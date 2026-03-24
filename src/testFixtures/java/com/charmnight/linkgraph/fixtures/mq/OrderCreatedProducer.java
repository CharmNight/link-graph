package com.charmnight.linkgraph.fixtures.mq;

public class OrderCreatedProducer {
    private final FakeMqTemplate mqTemplate = new FakeMqTemplate();

    public void publish(String orderId) {
        mqTemplate.send("order.created", orderId);
    }
}

class FakeMqTemplate {
    void send(String topic, String payload) {
    }
}

class OrderCreatedConsumer {
    @org.springframework.kafka.annotation.KafkaListener(topics = "order.created")
    public void onOrderCreated(String orderId) {
        handle(orderId);
    }

    void handle(String orderId) {
    }
}
