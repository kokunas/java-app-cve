package com.kokunas.bankdemo.service;

import com.kokunas.bankdemo.config.AuditLogger;
import com.kokunas.bankdemo.model.Customer;
import com.kokunas.bankdemo.model.MortgageLoan;
import com.kokunas.bankdemo.repository.MortgageLoanRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

@Service
public class LoanService {

    private static final BigDecimal MAX_LOAN_TO_VALUE = new BigDecimal("0.80");

    private final MortgageLoanRepository loanRepository;
    private final AuditLogger auditLogger;

    public LoanService(MortgageLoanRepository loanRepository, AuditLogger auditLogger) {
        this.loanRepository = loanRepository;
        this.auditLogger = auditLogger;
    }

    /** French amortization system: fixed monthly payment. */
    public BigDecimal calculateMonthlyPayment(BigDecimal amount, BigDecimal annualRatePercent, int termYears) {
        BigDecimal monthlyRate = annualRatePercent.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .divide(BigDecimal.valueOf(12), MathContext.DECIMAL64);
        int totalMonths = termYears * 12;

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return amount.divide(BigDecimal.valueOf(totalMonths), 2, RoundingMode.HALF_UP);
        }

        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal factor = onePlusR.pow(totalMonths, MathContext.DECIMAL64);

        BigDecimal numerator = amount.multiply(monthlyRate).multiply(factor);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE);

        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    public MortgageLoan.Status evaluateEligibility(BigDecimal propertyValue, BigDecimal requestedAmount) {
        BigDecimal maxLoan = propertyValue.multiply(MAX_LOAN_TO_VALUE);
        return requestedAmount.compareTo(maxLoan) <= 0
                ? MortgageLoan.Status.APPROVED
                : MortgageLoan.Status.REJECTED;
    }

    public MortgageLoan submitApplication(Customer customer, BigDecimal propertyValue,
                                           BigDecimal requestedAmount, BigDecimal interestRate, int termYears) {
        MortgageLoan loan = new MortgageLoan();
        loan.setCustomer(customer);
        loan.setPropertyValue(propertyValue);
        loan.setRequestedAmount(requestedAmount);
        loan.setInterestRate(interestRate);
        loan.setTermYears(termYears);
        loan.setMonthlyPayment(calculateMonthlyPayment(requestedAmount, interestRate, termYears));
        loan.setStatus(evaluateEligibility(propertyValue, requestedAmount));

        MortgageLoan saved = loanRepository.save(loan);
        auditLogger.logMortgageApplication(customer.getNif(), saved.getStatus().name());
        return saved;
    }

    public List<MortgageLoan> findAll() {
        return loanRepository.findAll();
    }

    public MortgageLoan findById(Long id) {
        return loanRepository.findById(id).orElseThrow();
    }
}
