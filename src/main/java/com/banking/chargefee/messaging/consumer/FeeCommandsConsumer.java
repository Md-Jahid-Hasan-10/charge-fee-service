package com.banking.chargefee.messaging.consumer;

import com.banking.chargefee.messaging.CalculateFeeHandler;
import com.banking.chargefee.messaging.EventEnvelope;
import com.banking.chargefee.messaging.EventParser;
import com.banking.chargefee.messaging.TopicNames;
import com.banking.chargefee.messaging.payload.CalculateFeeCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FeeCommandsConsumer {

    private static final Logger log = LoggerFactory.getLogger(FeeCommandsConsumer.class);

    private final EventParser parser;
    private final CalculateFeeHandler handler;

    public FeeCommandsConsumer(EventParser parser, CalculateFeeHandler handler) {
        this.parser = parser;
        this.handler = handler;
    }

    @KafkaListener(topics = TopicNames.FEE_COMMANDS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        if ("CalculateFee".equals(envelope.eventType())) {
            handler.handle(envelope, parser.payload(envelope, CalculateFeeCommand.class));
        } else {
            log.debug("Ignoring unknown command type {}", envelope.eventType());
        }
    }
}