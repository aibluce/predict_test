package exchange;

import static exchange.Side.BUY;
import static exchange.Side.SELL;
import static exchange.TestSupport.assertAmount;
import static exchange.TestSupport.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import exchange.core.EventSourcedExchange;

/** 题目「走一遍例子」的六步，逐个数对齐。 */
class WalkthroughTest {

    @Test
    @DisplayName("走一遍题目的例子")
    void walksThroughTheExampleFromTheSpec() {
        EventSourcedExchange ex = new EventSourcedExchange();

        // 1. A、B、C 各 deposit 100
        ex.deposit("A", new BigDecimal("100"));
        ex.deposit("B", new BigDecimal("100"));
        ex.deposit("C", new BigDecimal("100"));

        // 2. A 提交 BUY YES 10 @0.60：冻结 6.00，available 94.00，挂在盘口
        List<Event> aEvents = ex.submit(order("oA", "A", Outcome.YES, BUY, "0.60", 10));
        assertEquals(1, aEvents.size());
        assertInstanceOf(Event.OrderAccepted.class, aEvents.get(0));
        assertAmount("94.00", ex.account("A").available());
        assertAmount("6.00", ex.account("A").frozen());
        assertEquals(0, ex.collateralPool().signum());

        // 3. B 提交 SELL YES 4 @0.60：B 没有 YES，整单拒绝
        List<Event> bEvents = ex.submit(order("oB", "B", Outcome.YES, SELL, "0.60", 4));
        assertEquals(1, bEvents.size());
        assertEquals(RejectReason.INSUFFICIENT_SHARES,
                assertInstanceOf(Event.OrderRejected.class, bEvents.get(0)).reason());
        assertAmount("100.00", ex.account("B").available());
        assertAmount("0.00", ex.account("B").frozen());

        // 4. C 提交 BUY NO 8 @0.40：0.60 + 0.40 >= 1，与 A 铸造 8 张
        List<Event> cEvents = ex.submit(order("oC", "C", Outcome.NO, BUY, "0.40", 8));
        assertEquals(2, cEvents.size());
        assertInstanceOf(Event.OrderAccepted.class, cEvents.get(0));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, cEvents.get(1));
        assertEquals(TradeType.MINT, trade.type());
        assertEquals(8, trade.qty());
        assertEquals("oC", trade.takerOrderId());
        assertEquals(Outcome.NO, trade.takerOutcome());
        assertEquals(BUY, trade.takerSide());
        assertAmount("0.40", trade.takerPrice());
        assertAmount("3.20", trade.takerAmount());
        assertAmount("0.00", trade.takerFee());
        assertEquals("oA", trade.makerOrderId());
        assertEquals(Outcome.YES, trade.makerOutcome());
        assertEquals(BUY, trade.makerSide());
        assertAmount("0.60", trade.makerPrice());
        assertAmount("4.80", trade.makerAmount());
        assertAmount("0.00", trade.makerFee());
        // 铸造的两边付款之和恰好等于 8 × 1
        assertAmount("8.00", trade.takerAmount().add(trade.makerAmount()));
        assertAmount("8.00", ex.collateralPool());

        // A 付 4.80（从冻结扣），frozen 剩 1.20 = 2 张 × 0.60
        AccountView a = ex.account("A");
        assertAmount("94.00", a.available());
        assertAmount("1.20", a.frozen());
        assertEquals(8, a.posYes());
        // C 付 3.20，frozen 归零，拿到 8 张 NO
        AccountView c = ex.account("C");
        assertAmount("96.80", c.available());
        assertAmount("0.00", c.frozen());
        assertEquals(8, c.posNo());
    }

    @Test
    @DisplayName("走一遍例子的第6步_这一步的盘口")
    void exampleStep6_bookQuotesAtThatPoint() {
        EventSourcedExchange ex = exampleState();

        // 盘口只剩 A 的 BUY YES 2 @0.60
        assertEquals(1, ex.openOrders().size());
        assertAmount("0.60", ex.openOrders().iterator().next().price());
        assertEquals(2, ex.openOrders().iterator().next().remaining());

        // 卖一张 YES：只能配 A 的买单，价 0.60
        assertAmount("0.60", ex.bestBid(Outcome.YES).orElseThrow());
        // 买一张 YES：没有卖盘，也没有 BUY NO 可以铸造
        assertTrue(ex.bestAsk(Outcome.YES).isEmpty());
        // 买一张 NO：A 的 BUY YES 0.60 可以铸造，故 1 − 0.60 = 0.40
        assertAmount("0.40", ex.bestAsk(Outcome.NO).orElseThrow());
        // 卖一张 NO：既无 BUY NO，也无 SELL YES 可以销毁
        assertTrue(ex.bestBid(Outcome.NO).isEmpty());
    }

    /** 题目例子的第 5 步状态。 */
    static EventSourcedExchange exampleState() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", new BigDecimal("100"));
        ex.deposit("B", new BigDecimal("100"));
        ex.deposit("C", new BigDecimal("100"));
        ex.submit(order("oA", "A", Outcome.YES, BUY, "0.60", 10));
        ex.submit(order("oB", "B", Outcome.YES, SELL, "0.60", 4));
        ex.submit(order("oC", "C", Outcome.NO, BUY, "0.40", 8));
        return ex;
    }
}
