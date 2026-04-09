package com.charmnight.linkgraph.fixtures.simple;

/**
 * Coordinates simple Java call extraction.
 */
public class SimpleCallChain {
    private final OrderGateway gateway = new OrderGatewayImpl();

    /**
     * Loads an order summary.
     *
     * @param orderId business order id
     * @return resolved order summary
     */
    public String load(String orderId) {
        String sanitized = sanitize(orderId);
        return gateway.fetch(sanitized);
    }

    public String branchy(String state) {
        try {
            if (state == null) {
                return "missing";
            }

            switch (state) {
                case "NEW":
                    return sanitize(state);
                default:
                    return gateway.fetch(state);
            }
        } catch (IllegalArgumentException ex) {
            return "invalid";
        }
    }

    private String sanitize(String orderId) {
        return orderId.trim();
    }
}

interface OrderGateway {
    String fetch(String orderId);
}

class OrderGatewayImpl implements OrderGateway {
    @Override
    public String fetch(String orderId) {
        return repositoryFetch(orderId);
    }

    private String repositoryFetch(String orderId) {
        return "repo:" + orderId;
    }
}
