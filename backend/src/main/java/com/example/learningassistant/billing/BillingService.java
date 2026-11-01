package com.example.learningassistant.billing;

import com.example.learningassistant.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 次数余额、人工核对订单和生成任务的预留流水。 */
@Service
public class BillingService {
    private final JdbcTemplate jdbc;
    @Value("${app.billing.enabled:false}") private boolean enabled;
    @Value("${app.billing.initial-credits:10}") private int initialCredits;
    @Value("${app.billing.payment-instructions:}") private String paymentInstructions;
    @Value("${app.billing.payment-url:}") private String paymentUrl;
    @Value("${app.billing.operator-token:}") private String operatorToken;
    @Value("${app.billing.starter-credits:100}") private int starterCredits;
    @Value("${app.billing.starter-price-fen:990}") private int starterPriceFen;
    @Value("${app.billing.standard-credits:250}") private int standardCredits;
    @Value("${app.billing.standard-price-fen:1990}") private int standardPriceFen;

    public BillingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PackageView(String code, String name, int credits, int priceFen) { }
    public record Overview(boolean enabled, int balance, int initialCredits, boolean orderingAvailable,
                           String paymentInstructions, String paymentUrl, List<PackageView> packages) { }
    public record OrderView(String orderNo, Long userId, String packageName, int credits, int priceFen,
                            String status, String submittedChannel, String submittedReference,
                            LocalDateTime createdAt, LocalDateTime paidAt) { }
    public record UsageView(String reservationId, Long userId, String scene, String status, LocalDateTime createdAt) { }

    private List<PackageView> packages() {
        return List.of(new PackageView("starter", "轻量次数包", starterCredits, starterPriceFen),
                       new PackageView("standard", "常用次数包", standardCredits, standardPriceFen));
    }

    private boolean canOrder() {
        return enabled && !paymentInstructions.isBlank() && operatorToken.length() >= 32;
    }

    private String safePaymentUrl() {
        if (paymentUrl.isBlank()) return "";
        try {
            URI uri = URI.create(paymentUrl.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri.toString() : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private void ensureAccount(Long userId) {
        jdbc.update("INSERT INTO t_billing_account (user_id, balance) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE user_id = user_id", userId, Math.max(initialCredits, 0));
    }

    public Overview overview(Long userId) {
        ensureAccount(userId);
        int balance = jdbc.queryForObject("SELECT balance FROM t_billing_account WHERE user_id = ?", Integer.class, userId);
        return new Overview(enabled, balance, Math.max(initialCredits, 0), canOrder(),
                canOrder() ? paymentInstructions : "", canOrder() ? safePaymentUrl() : "", packages());
    }

    @Transactional
    public OrderView createOrder(Long userId, String code) {
        if (!canOrder()) throw new BizException("购买暂未开放");
        PackageView selected = packages().stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> new BizException("套餐不存在"));
        if (selected.credits() <= 0 || selected.priceFen() <= 0) throw new BizException("套餐暂不可购买");
        ensureAccount(userId);
        jdbc.queryForObject("SELECT user_id FROM t_billing_account WHERE user_id = ? FOR UPDATE", Long.class, userId);
        int open = jdbc.queryForObject("SELECT COUNT(*) FROM t_billing_order WHERE user_id = ? AND status IN ('WAITING','REVIEW')",
                Integer.class, userId);
        if (open >= 10) throw new BizException("待处理订单过多，请先处理已有订单");
        String orderNo = "LA" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO t_billing_order (order_no,user_id,package_code,package_name,credits,price_fen,status) VALUES (?,?,?,?,?,?,'WAITING')",
                orderNo, userId, selected.code(), selected.name(), selected.credits(), selected.priceFen());
        return findUserOrder(userId, orderNo);
    }

    public List<OrderView> orders(Long userId) {
        return jdbc.query("SELECT * FROM t_billing_order WHERE user_id = ? ORDER BY id DESC LIMIT 100", this::orderRow, userId);
    }

    private OrderView findUserOrder(Long userId, String orderNo) {
        return jdbc.query("SELECT * FROM t_billing_order WHERE user_id = ? AND order_no = ?", this::orderRow, userId, orderNo)
                .stream().findFirst().orElseThrow(() -> new BizException("订单不存在"));
    }

    private OrderView orderRow(ResultSet rs, int row) throws SQLException {
        return new OrderView(rs.getString("order_no"), rs.getLong("user_id"), rs.getString("package_name"),
                rs.getInt("credits"), rs.getInt("price_fen"), rs.getString("status"),
                rs.getString("submitted_channel"), rs.getString("submitted_reference"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toLocalDateTime());
    }

    private static String channel(String raw) {
        String c = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("WECHAT", "ALIPAY", "AFDIAN", "OTHER").contains(c)) throw new BizException("请选择付款平台");
        return c;
    }

    private static String reference(String raw) {
        String r = raw == null ? "" : raw.trim();
        if (r.isEmpty() || r.length() > 120) throw new BizException("请填写不超过 120 字的交易单号");
        return r;
    }

    @Transactional
    public OrderView submit(Long userId, String orderNo, String rawChannel, String rawReference) {
        String c = channel(rawChannel);
        String r = reference(rawReference);
        int changed = jdbc.update("UPDATE t_billing_order SET status='REVIEW', submitted_channel=?, submitted_reference=?, submitted_at=CURRENT_TIMESTAMP " +
                "WHERE user_id=? AND order_no=? AND status IN ('WAITING','REVIEW')", c, r, userId, orderNo);
        if (changed != 1) throw new BizException("订单不存在或当前状态不可提交");
        return findUserOrder(userId, orderNo);
    }

    @Transactional
    public void cancel(Long userId, String orderNo) {
        int changed = jdbc.update("UPDATE t_billing_order SET status='CANCELLED' WHERE user_id=? AND order_no=? AND status='WAITING'",
                userId, orderNo);
        if (changed != 1) throw new BizException("仅待付款订单可以取消");
    }

    public void requireOperator(String token) {
        if (operatorToken.length() < 32 || token == null || !MessageDigest.isEqual(
                operatorToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(403, "操作鉴权失败");
        }
    }

    public List<OrderView> reviewOrders() {
        return jdbc.query("SELECT * FROM t_billing_order WHERE status='REVIEW' ORDER BY id ASC LIMIT 100", this::orderRow);
    }

    /** 调用前运营者必须在收款平台核实到账、金额和交易单号；重复确认不会重复加额。 */
    @Transactional
    public OrderView confirm(String orderNo, String rawChannel, String rawReference) {
        String c = channel(rawChannel);
        String r = reference(rawReference);
        OrderView order = jdbc.query("SELECT * FROM t_billing_order WHERE order_no = ? FOR UPDATE", this::orderRow, orderNo)
                .stream().findFirst().orElseThrow(() -> new BizException("订单不存在"));
        if (!c.equals(order.submittedChannel()) || !r.equals(order.submittedReference())) {
            throw new BizException("交易信息与用户提交的不一致");
        }
        if ("PAID".equals(order.status())) return order;
        if (!"REVIEW".equals(order.status())) throw new BizException("订单尚未提交付款信息");
        try {
            jdbc.update("UPDATE t_billing_order SET status='PAID', verified_channel=?, verified_reference=?, paid_at=CURRENT_TIMESTAMP WHERE order_no=?",
                    c, r, orderNo);
        } catch (DuplicateKeyException e) {
            throw new BizException("该平台交易单号已用于其他订单");
        }
        ensureAccount(order.userId());
        jdbc.update("UPDATE t_billing_account SET balance=balance+? WHERE user_id=?", order.credits(), order.userId());
        return findUserOrder(order.userId(), orderNo);
    }

    /** 生成前原子预留一次；即使前端断线也由后台生成结果决定结算或退回。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String reserve(Long userId, String scene) {
        if (!enabled) return null;
        ensureAccount(userId);
        if (jdbc.update("UPDATE t_billing_account SET balance=balance-1 WHERE user_id=? AND balance>0", userId) != 1) {
            throw new BizException("AI 次数不足，请前往个人中心购买次数包");
        }
        String reservation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO t_billing_usage (reservation_id,user_id,scene,status) VALUES (?,?,?,'RESERVED')",
                reservation, userId, scene);
        return reservation;
    }

    /** 与结果保存处于同一个事务，确保结果和扣费同时成功。 */
    @Transactional
    public void settle(String reservation) {
        if (reservation == null) return;
        int changed = jdbc.update("UPDATE t_billing_usage SET status='CONSUMED' WHERE reservation_id=? AND status='RESERVED'", reservation);
        if (changed != 1) {
            String status = jdbc.query("SELECT status FROM t_billing_usage WHERE reservation_id=?", (rs, n) -> rs.getString(1), reservation)
                    .stream().findFirst().orElse("");
            if (!"CONSUMED".equals(status)) throw new IllegalStateException("次数预留状态异常");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String reservation) {
        if (reservation == null) return;
        int changed = jdbc.update("UPDATE t_billing_usage SET status='RELEASED' WHERE reservation_id=? AND status='RESERVED'", reservation);
        if (changed == 1) {
            jdbc.update("UPDATE t_billing_account SET balance=balance+1 WHERE user_id=(SELECT user_id FROM t_billing_usage WHERE reservation_id=?)",
                    reservation);
        }
    }

    public List<UsageView> reservedUsages() {
        return jdbc.query("SELECT reservation_id,user_id,scene,status,created_at FROM t_billing_usage WHERE status='RESERVED' ORDER BY created_at LIMIT 100",
                (rs, n) -> new UsageView(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toLocalDateTime()));
    }
}
