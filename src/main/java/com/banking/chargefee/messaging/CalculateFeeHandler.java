package com.banking.chargefee.messaging;

import com.banking.chargefee.dto.FeeRequest;
import com.banking.chargefee.dto.FeeResponse;
import com.banking.chargefee.messaging.payload.CalculateFeeCommand;
import com.banking.chargefee.messaging.payload.FeeCalculatedPayload;
import com.banking.chargefee.service.FeeCalculationService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CalculateFeeHandler {

    private final FeeCalculationService feeService;
    private final DirectEventPublisher publisher;
    private final Validator validator;
    private final MeterRegistry meters;

    public CalculateFeeHandler(FeeCalculationService feeService,
                               DirectEventPublisher publisher,
                               Validator validator,
                               MeterRegistry meters) {
        this.feeService = feeService;
        this.publisher = publisher;
        this.validator = validator;
        this.meters = meters;
    }

    public void handle(EventEnvelope command, CalculateFeeCommand cmd) {
        if (cmd.transferId() == null || cmd.transferId().isBlank() || cmd.transferId().trim().length() > 80) {
            throw new InvalidEventException("CalculateFee: transferId is missing or too long");
        }
        String transferId = cmd.transferId().trim();

        FeeRequest request = new FeeRequest(
                cmd.fromAccountNumber(), cmd.toAccountNumber(), cmd.amount(), cmd.sameOwner());

        Set<ConstraintViolation<FeeRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String problems = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new InvalidEventException("CalculateFee: " + problems);
        }

        // A pure calculation, so a repeated command gives an identical answer. Nothing to remember.
        FeeResponse response = feeService.calculate(request);

        publisher.publish(TopicNames.FEE_EVENTS, transferId, "FeeCalculated", transferId, command.eventId(),
                new FeeCalculatedPayload(transferId, response.fee(), response.feeType()));

        meters.counter("fee.calculated", "type", response.feeType()).increment();
    }
}