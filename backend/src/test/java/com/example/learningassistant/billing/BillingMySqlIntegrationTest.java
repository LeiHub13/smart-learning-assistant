package com.example.learningassistant.billing;

import com.example.learningassistant.common.BizException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 显式指定独立的 billing_test 数据库时运行，验证真实 MySQL 事务和并发。 */
@SpringJUnitConfig
@ContextConfiguration(classes = BillingMySqlIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "app.billing.enabled=true", "app.billing.initial-credits=10",
        "app.billing.payment-instructions=测试付款说明", "app.billing.operator-token=01234567890123456789012345678901"
})
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_MYSQL_URL", matches = ".+billing_test.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingMySqlIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            ds.setUrl(System.getenv("BILLING_TEST_MYSQL_URL"));
            ds.setUsername(System.getenv().getOrDefault("BILLING_TEST_MYSQL_USER", "root"));
            ds.setPassword(System.getenv().getOrDefault("BILLING_TEST_MYSQL_PASSWORD", ""));
            return ds;
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean BillingService billing(JdbcTemplate jdbc) { return new BillingService(jdbc); }
        @Bean PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BillingService billing;
    @Autowired private TransactionTemplate transaction;
    private final AtomicLong users = new AtomicLong(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000_000L);

    @BeforeAll
    void initializeIsolatedSchema() {
        assertEquals("billing_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    }

    @Test
    void concurrentReservationsCannotOverspendAndRefundOnlyOnce() throws Exception {
        long user = users.incrementAndGet();
        assertEquals(10, billing.overview(user).balance());
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(pool.submit(() -> {
                start.await();
                try { return billing.reserve(user, "chat"); } catch (BizException e) { return null; }
            }));
            start.countDown();
            List<String> reservations = new ArrayList<>();
            for (Future<String> call : calls) {
                String result = call.get(20, TimeUnit.SECONDS);
                if (result != null) reservations.add(result);
            }
            assertEquals(10, reservations.size());
            assertEquals(0, billing.overview(user).balance());
            for (String reservation : reservations) { billing.release(reservation); billing.release(reservation); }
            assertEquals(10, billing.overview(user).balance());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void orderOwnershipAndDuplicatePaymentAreEnforced() throws Exception {
        long user = users.incrementAndGet();
        long other = users.incrementAndGet();
        BillingService.OrderView order = billing.createOrder(user, "starter");
        assertEquals(990, order.priceFen());
        assertEquals(100, order.credits());
        String reference = UUID.randomUUID().toString();
        assertTrue(billing.orders(other).isEmpty());
        assertThrows(BizException.class, () -> billing.submit(other, order.orderNo(), "AFDIAN", reference));
        assertThrows(BizException.class, () -> billing.cancel(other, order.orderNo()));
        billing.submit(user, order.orderNo(), "AFDIAN", reference);
        assertThrows(BizException.class, () -> billing.confirm(order.orderNo(), "AFDIAN", "wrong"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> billing.confirm(order.orderNo(), "AFDIAN", reference));
            Future<?> second = pool.submit(() -> billing.confirm(order.orderNo(), "AFDIAN", reference));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(110, billing.overview(user).balance());
        BillingService.OrderView second = billing.createOrder(other, "starter");
        billing.submit(other, second.orderNo(), "AFDIAN", reference);
        assertThrows(BizException.class, () -> billing.confirm(second.orderNo(), "AFDIAN", reference));
        assertEquals(10, billing.overview(other).balance());
        assertEquals("REVIEW", billing.orders(other).get(0).status());
        assertThrows(BizException.class, () -> billing.requireOperator("wrong"));
    }

    @Test
    void settlementRollsBackWithResultAndReleaseDoesNotUndoSuccess() {
        long user = users.incrementAndGet();
        String failed = billing.reserve(user, "lecture");
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            billing.settle(failed);
            throw new IllegalStateException("模拟结果保存失败");
        }));
        billing.release(failed);
        assertEquals(10, billing.overview(user).balance());
        String successful = billing.reserve(user, "lecture");
        transaction.executeWithoutResult(status -> billing.settle(successful));
        billing.release(successful);
        assertEquals(9, billing.overview(user).balance());
        assertEquals("CONSUMED", jdbc.queryForObject("SELECT status FROM t_billing_usage WHERE reservation_id=?", String.class, successful));
    }

    @Test
    void chatReservationRetriesAndLateCancellationAreIdempotent() {
        long user = users.incrementAndGet();
        String turn = UUID.randomUUID().toString();
        assertEquals(turn, billing.reserveWithId(user, "chat", turn));
        assertEquals(turn, billing.reserveWithId(user, "chat", turn));
        assertEquals(9, billing.overview(user).balance());
        billing.releaseChat(user, turn);
        billing.releaseChat(user, turn);
        assertEquals(10, billing.overview(user).balance());
        assertThrows(BizException.class, () -> billing.reserveWithId(user, "chat", turn));

        String cancelledBeforeReserve = UUID.randomUUID().toString();
        billing.releaseChat(user, cancelledBeforeReserve);
        assertThrows(BizException.class, () -> billing.reserveWithId(user, "chat", cancelledBeforeReserve));
        assertEquals(10, billing.overview(user).balance());

        String completed = UUID.randomUUID().toString();
        billing.reserveWithId(user, "chat", completed);
        billing.settle(completed);
        billing.settle(completed);
        billing.releaseChat(user, completed);
        assertEquals(9, billing.overview(user).balance());
        assertEquals("CONSUMED", jdbc.queryForObject("SELECT status FROM t_billing_usage WHERE reservation_id=?",
                String.class, completed));
    }

    @Test
    void concurrentChatReserveAndCancellationNeverLeaveACharge() throws Exception {
        long user = users.incrementAndGet();
        billing.overview(user);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                String turn = UUID.randomUUID().toString();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> reserve = pool.submit(() -> {
                    start.await();
                    try { billing.reserveWithId(user, "chat", turn); } catch (BizException ignored) { }
                    return null;
                });
                Future<?> release = pool.submit(() -> {
                    start.await();
                    billing.releaseChat(user, turn);
                    return null;
                });
                start.countDown();
                reserve.get(20, TimeUnit.SECONDS);
                release.get(20, TimeUnit.SECONDS);
                assertEquals("RELEASED", jdbc.queryForObject("SELECT status FROM t_billing_usage WHERE reservation_id=?",
                        String.class, turn));
                assertEquals(10, billing.overview(user).balance());
            }
        } finally { pool.shutdownNow(); }
    }
}
