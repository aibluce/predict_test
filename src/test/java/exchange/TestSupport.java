package exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import exchange.core.EventSourcedExchange;

/** 测试用的小工具：金额比较不看 scale，只看值。 */
final class TestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private TestSupport() {
    }

    static void assertAmount(String expected, BigDecimal actual) {
        assertAmount(expected, actual, "");
    }

    static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + " 期望 " + expected + " 实际 " + actual);
    }

    static Order order(String orderId, String accountId, Outcome outcome, Side side, String price, long qty) {
        return new Order(orderId, accountId, outcome, side, new BigDecimal(price), qty);
    }

    /**
     * 让 accountId 拿到 qty 张 outcome 的份额：和一个临时对手机账户在 0.50 / 0.50 铸造一对。
     * 双方订单都会全部成交，不留残单。
     */
    static void mintShares(EventSourcedExchange ex, String accountId, Outcome outcome, long qty) {
        int tag = SEQ.incrementAndGet();
        String counterparty = "cp" + tag;
        ex.deposit(counterparty, new BigDecimal("1000000"));
        ex.submit(order("give" + tag, accountId, outcome, Side.BUY, "0.50", qty));
        ex.submit(order("givecp" + tag, counterparty, outcome.other(), Side.BUY, "0.50", qty));
    }
}
