package com.charmnight.linkgraph.fixtures.kotlin

class KotlinOrderFlow(
    private val orderService: OrderService = OrderService(),
) {
    fun submit(orderId: String): String {
        val placed = orderService.place(orderId)
        return format(placed)
    }

    private fun format(value: String): String {
        return value.trim()
    }
}

class OrderService {
    fun place(orderId: String): String {
        return "placed-$orderId"
    }
}

class KotlinOrderController(
    private val flow: KotlinOrderFlow = KotlinOrderFlow(),
) {
    fun trigger(orderId: String): String {
        return flow.submit(orderId)
    }
}
