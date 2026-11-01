package com.example.learningassistant.web.controller;

import com.example.learningassistant.billing.BillingService;
import com.example.learningassistant.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 仅供服务器运营者使用；独立密钥，不能通过公开前端代理访问。 */
@RestController
@RequestMapping("/internal/billing")
@RequiredArgsConstructor
public class InternalBillingController {
    private final BillingService billing;

    @GetMapping("/orders")
    public ApiResponse<List<BillingService.OrderView>> orders(@RequestHeader(value = "X-Billing-Token", required = false) String token) {
        billing.requireOperator(token);
        return ApiResponse.ok(billing.reviewOrders());
    }

    @PostMapping("/orders/{orderNo}/confirm")
    public ApiResponse<BillingService.OrderView> confirm(@RequestHeader(value = "X-Billing-Token", required = false) String token,
                                                          @PathVariable String orderNo, @RequestBody Map<String, String> body) {
        billing.requireOperator(token);
        return ApiResponse.ok(billing.confirm(orderNo, body.get("channel"), body.get("reference")));
    }

    @GetMapping("/usages/reserved")
    public ApiResponse<List<BillingService.UsageView>> reservations(@RequestHeader(value = "X-Billing-Token", required = false) String token) {
        billing.requireOperator(token);
        return ApiResponse.ok(billing.reservedUsages());
    }

    @PostMapping("/usages/{reservationId}/release")
    public ApiResponse<Void> release(@RequestHeader(value = "X-Billing-Token", required = false) String token,
                                     @PathVariable String reservationId) {
        billing.requireOperator(token);
        billing.release(reservationId);
        return ApiResponse.ok(null);
    }
}
