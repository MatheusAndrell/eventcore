package com.eventcore.platform.messaging;

import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnRole({Role.RELAY, Role.WORKER})
class MessagingTopologyConfiguration {

    @Bean
    Declarables eventcoreTopology() {
        DirectExchange events = ExchangeBuilder.directExchange(MessagingTopology.EVENTS_EXCHANGE).durable(true).build();
        FanoutExchange deadLetters = ExchangeBuilder.fanoutExchange(MessagingTopology.DEAD_LETTER_EXCHANGE)
                .durable(true).build();
        Queue eventAccepted = workQueue(MessagingTopology.EVENT_ACCEPTED_QUEUE);
        Queue deliveryRequested = workQueue(MessagingTopology.DELIVERY_REQUESTED_QUEUE);
        Queue parking = QueueBuilder.durable(MessagingTopology.PARKING_QUEUE).quorum().build();
        Binding eventAcceptedBinding = BindingBuilder.bind(eventAccepted).to(events)
                .with(MessagingTopology.EVENT_ACCEPTED_ROUTING_KEY);
        Binding deliveryRequestedBinding = BindingBuilder.bind(deliveryRequested).to(events)
                .with(MessagingTopology.DELIVERY_REQUESTED_ROUTING_KEY);
        Binding parkingBinding = BindingBuilder.bind(parking).to(deadLetters);
        return new Declarables(events, deadLetters, eventAccepted, deliveryRequested, parking, eventAcceptedBinding,
                deliveryRequestedBinding, parkingBinding);
    }

    private static Queue workQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deliveryLimit(MessagingTopology.DELIVERY_LIMIT)
                .deadLetterExchange(MessagingTopology.DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }
}
