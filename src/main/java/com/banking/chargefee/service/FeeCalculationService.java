package com.banking.chargefee.service;

import com.banking.chargefee.dto.FeeRequest;
import com.banking.chargefee.dto.FeeResponse;

public interface FeeCalculationService {

    FeeResponse calculate(FeeRequest request);
}