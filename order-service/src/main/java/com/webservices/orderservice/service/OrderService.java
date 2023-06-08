package com.webservices.orderservice.service;

import com.webservices.orderservice.dto.InventoryResponse;
import com.webservices.orderservice.dto.OrderLineItemsDto;
import com.webservices.orderservice.dto.OrderRequest;
import com.webservices.orderservice.event.OrderPlacedEvent;
import com.webservices.orderservice.model.Order;
import com.webservices.orderservice.model.OrderLineItems;
import com.webservices.orderservice.respository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import zipkin2.internal.Trace;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;

    private final WebClient.Builder webClientBuilder;

    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

    private final Tracer tracer;

    public String placeOrder(OrderRequest orderRequest){
        Order order = new Order();

        order.setOrderNumber(UUID.randomUUID().toString());

        order.setOrderLineItemsList(orderRequest.getOrderLineItemsDtoList()
                .stream().map(this::mapToDto).toList());

        List<String> skuCodes = order.getOrderLineItemsList()
                .stream().map(OrderLineItems::getSkuCode)
                .toList();

        Span inventoryServiceLookup =tracer.nextSpan().name("inventoryServiceLookup");

        try(Tracer.SpanInScope spanInScope =  tracer.withSpan(inventoryServiceLookup.start())){
            InventoryResponse[] inventoryResponseArray =  webClientBuilder.build().get()
                    .uri("http://INVENTORY-SERVICE/api/inventory",
                            uriBuilder -> uriBuilder.queryParam("skuCode",skuCodes)
                                    .build()).retrieve().bodyToMono(InventoryResponse[].class).block();

            Boolean allProductsInStock = Arrays.stream(inventoryResponseArray)
                    .allMatch(InventoryResponse::getIsInStock);

            if (allProductsInStock){
                orderRepository.save(order);
                kafkaTemplate.send("notificationTopic",new OrderPlacedEvent(order.getOrderNumber()));
                return "Order Placed Successfully";
            }else {
                throw new IllegalArgumentException("Product is not in stock, Please try again later");
            }
        }finally {
            inventoryServiceLookup.end();
        }
    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = OrderLineItems.builder()
                .price(orderLineItemsDto.getPrice())
                .quantity(orderLineItemsDto.getQuantity())
                .skuCode(orderLineItemsDto.getSkuCode())
                .build();

        return orderLineItems;
    }
}
