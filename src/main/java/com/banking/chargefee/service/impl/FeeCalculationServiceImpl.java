package com.banking.chargefee.service.impl;

import com.banking.chargefee.dto.FeeRequest;
import com.banking.chargefee.dto.FeeResponse;
import com.banking.chargefee.policy.FeePolicy;
import com.banking.chargefee.policy.FeeResult;
import com.banking.chargefee.service.FeeCalculationService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FeeCalculationServiceImpl implements FeeCalculationService {

    private final List<FeePolicy> policies;

    public FeeCalculationServiceImpl(List<FeePolicy> policies) {
        this.policies = policies;
    }

    @Override
    public FeeResponse calculate(FeeRequest request) {
        FeeResult result = policies.stream()
                .filter(policy -> policy.supports(request))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No fee policy matches this request"))
                .calculate(request);

        return new FeeResponse(result.fee(), result.feeType(), result.description());
    }
}