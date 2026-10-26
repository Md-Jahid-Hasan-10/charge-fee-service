package com.banking.chargefee.controller;

import com.banking.chargefee.dto.FeeRequest;
import com.banking.chargefee.dto.FeeResponse;
import com.banking.chargefee.service.FeeCalculationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/fees")
public class InternalFeeController {

    private final FeeCalculationService feeCalculationService;

    public InternalFeeController(FeeCalculationService feeCalculationService) {
        this.feeCalculationService = feeCalculationService;
    }

    @PostMapping("/calculate")
    public ResponseEntity<FeeResponse> calculate(@Valid @RequestBody FeeRequest request) {
        return ResponseEntity.ok(feeCalculationService.calculate(request));
    }
}