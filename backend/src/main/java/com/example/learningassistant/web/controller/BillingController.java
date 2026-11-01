package com.example.learningassistant.web.controller;

import com.example.learningassistant.billing.BillingService;
import com.example.learningassistant.common.ApiResponse;
import com.example.learningassistant.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 用户只能查看和提交自己的订单，提交交易单号不会自动开通额度。 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {
    private final BillingService billing;

    @GetMapping("/overview")
    public ApiResponse<BillingService.Overview> overview(HttpServletRequest request) {
        return ApiResponse.ok(billing.overview(CurrentUser.get(request).id()));
    }

    @GetMapping("/orders")
    public ApiResponse<List<BillingService.OrderView>> orders(HttpServletRequest request) {
        return ApiResponse.ok(billing.orders(CurrentUser.get(request).id()));
    }

    @PostMapping("/orders")
    public ApiResponse<BillingService.OrderView> create(HttpServletRequest request, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(billing.createOrder(CurrentUser.get(request).id(), body.get("packageCode")));
    }

    @PostMapping("/orders/{orderNo}/submit")
    public ApiResponse<BillingService.OrderView> submit(HttpServletRequest request, @PathVariable String orderNo,
                                                         @RequestBody Map<String, String> body) {
        return ApiResponse.ok(billing.submit(CurrentUser.get(request).id(), orderNo, body.get("channel"), body.get("reference")));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<Void> cancel(HttpServletRequest request, @PathVariable String orderNo) {
        billing.cancel(CurrentUser.get(request).id(), orderNo);
        return ApiResponse.ok(null);
    }
}
