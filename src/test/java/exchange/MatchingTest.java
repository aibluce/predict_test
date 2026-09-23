package exchange;

import static exchange.Side.BUY;
import static exchange.Side.SELL;
import static exchange.TestSupport.assertAmount;
import static exchange.TestSupport.mintShares;
import static exchange.TestSupport.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import exchange.core.EventSourcedExchange;

/** 四种成交 + 价格优先 + 时间优先 + 两种来源同价时的取舍。 */
class MatchingTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Test
    @DisplayName("同outcome一买一卖_按挂单价成交_taker拿价差")
    void normalTrade_usesMakerPrice_takerKeepsImprovement() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("S", HUNDRED);
        ex.deposit("C", HUNDRED);
        mintShares(ex, "S", Outcome.YES, 10);

        // S 挂 SELL YES 4 @0.55
        ex.submit(order("s1", "S", Outcome.YES, SELL, "0.55", 4));
        assertEquals(4, ex.account("S").frozenYes());

        // C 以 0.70 进来买，实际按挂单方的 0.55 成交
        List<Event> events = ex.submit(order("c1", "C", Outcome.YES, BUY, "0.70", 4));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.NORMAL, trade.type());
        assertEquals(4, trade.qty());
        assertEquals("c1", trade.takerOrderId());
        assertEquals(Outcome.YES, trade.takerOutcome());
        assertEquals(BUY, trade.takerSide());
        assertAmount("0.55", trade.takerPrice());
        assertAmount("2.20", trade.takerAmount());
        assertEquals("s1", trade.makerOrderId());
        assertEquals(SELL, trade.makerSide());
        assertAmount("0.55", trade.makerPrice());
        assertAmount("2.20", trade.makerAmount());
        // 任何一方都不比自己的限价差
        assertTrue(trade.takerPrice().compareTo(new BigDecimal("0.70")) <= 0);

        // C 冻结 2.80 只花了 2.20，价差 0.60 回到 available
        AccountView c = ex.account("C");
        assertAmount("97.80", c.available());
        assertAmount("0.00", c.frozen());
        assertEquals(4, c.posYes());
        // S 交出 4 张，收到 2.20
        AccountView s = ex.account("S");
        assertAmount("97.20", s.available());
        assertEquals(0, s.frozenYes());
        assertEquals(6, s.posYes());
    }

    @Test
    @DisplayName("买YES和买NO撮合_铸造一对")
    void buyYesMatchesBuyNo_mintsAPair() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        ex.deposit("B", HUNDRED);
        ex.submit(order("a1", "A", Outcome.YES, BUY, "0.60", 10));

        List<Event> events = ex.submit(order("b1", "B", Outcome.NO, BUY, "0.40", 8));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.MINT, trade.type());
        // taker 是后进来的 B，挂单方 A 按自己的限价 0.60 成交，B 付补价 0.40
        assertEquals("b1", trade.takerOrderId());
        assertAmount("0.40", trade.takerPrice());
        assertEquals("a1", trade.makerOrderId());
        assertAmount("0.60", trade.makerPrice());
        assertAmount("1.00", trade.takerPrice().add(trade.makerPrice()));
        // 两边付款之和恰好等于 8 × 1，全部进抵押池
        assertAmount("8.00", trade.takerAmount().add(trade.makerAmount()));
        assertAmount("8.00", ex.collateralPool());
        assertEquals(8, ex.account("A").posYes());
        assertEquals(8, ex.account("B").posNo());
        // A 还剩 2 张的冻结
        assertAmount("1.20", ex.account("A").frozen());
        assertAmount("0.00", ex.account("B").frozen());
    }

    @Test
    @DisplayName("卖YES和卖NO撮合_销毁一对")
    void sellYesMatchesSellNo_mergesAPair() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        ex.deposit("B", HUNDRED);
        // A 和 B 各拿 5 张（A 拿 YES、B 拿 NO）：A 挂买、B 吃出铸造
        ex.submit(order("a1", "A", Outcome.YES, BUY, "0.50", 5));
        ex.submit(order("b1", "B", Outcome.NO, BUY, "0.50", 5));
        assertAmount("5.00", ex.collateralPool());

        // A 挂 SELL YES 5 @0.40
        ex.submit(order("s1", "A", Outcome.YES, SELL, "0.40", 5));
        // B 挂 SELL NO 5 @0.60 进来：0.40 + 0.60 <= 1，销毁一对
        List<Event> events = ex.submit(order("s2", "B", Outcome.NO, SELL, "0.60", 5));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.MERGE, trade.type());
        assertEquals(5, trade.qty());
        assertEquals("s2", trade.takerOrderId());
        assertEquals(Outcome.NO, trade.takerOutcome());
        assertEquals(SELL, trade.takerSide());
        assertAmount("0.60", trade.takerPrice());
        assertAmount("3.00", trade.takerAmount());
        assertEquals("s1", trade.makerOrderId());
        assertEquals(Outcome.YES, trade.makerOutcome());
        assertAmount("0.40", trade.makerPrice());
        assertAmount("2.00", trade.makerAmount());
        // 两边收款之和恰好等于 5 × 1，抵押池被拿空
        assertAmount("5.00", trade.takerAmount().add(trade.makerAmount()));
        assertAmount("0.00", ex.collateralPool());

        AccountView a = ex.account("A");
        assertEquals(0, a.posYes());
        assertEquals(0, a.frozenYes());
        assertAmount("99.50", a.available());
        AccountView b = ex.account("B");
        assertEquals(0, b.posNo());
        assertAmount("100.50", b.available());
    }

    @Test
    @DisplayName("价格优先_先吃更优价的挂单")
    void pricePriority_takesTheBetterPriceFirst() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        ex.deposit("B", HUNDRED);
        ex.deposit("S", HUNDRED);
        mintShares(ex, "S", Outcome.YES, 10);

        ex.submit(order("a1", "A", Outcome.YES, BUY, "0.60", 5));   // 早但价低
        ex.submit(order("b1", "B", Outcome.YES, BUY, "0.62", 5));   // 晚但价高

        List<Event> events = ex.submit(order("s1", "S", Outcome.YES, SELL, "0.50", 5));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals("b1", trade.makerOrderId(), "应该先吃 0.62 的买单");
        assertAmount("0.62", trade.takerPrice());
        assertNotNull(ex.openOrders().stream().filter(o -> o.orderId().equals("a1")).findFirst().orElse(null),
                "0.60 的买单应该还在盘口");
    }

    @Test
    @DisplayName("时间优先_同价先到先成交")
    void timePriority_samePriceIsFifo() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        ex.deposit("B", HUNDRED);
        ex.deposit("S", HUNDRED);
        mintShares(ex, "S", Outcome.YES, 10);

        ex.submit(order("a1", "A", Outcome.YES, BUY, "0.60", 5));   // 先到
        ex.submit(order("b1", "B", Outcome.YES, BUY, "0.60", 5));   // 后到

        List<Event> events = ex.submit(order("s1", "S", Outcome.YES, SELL, "0.60", 5));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals("a1", trade.makerOrderId(), "同价应该先成交先挂的那张");
    }

    @Test
    @DisplayName("同价时_真实价位先于合成价位")
    void tieOnPrice_realLevelWinsWhenItIsEarlier() {
        // 卖一张 YES：既可以用 0.60 的 BUY YES 直接成交，也可以和 0.40 的 SELL NO 销毁，
        // 两者对 taker 都是 0.60。规则：同价按挂单先后。
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("M", HUNDRED);
        ex.deposit("Q", HUNDRED);
        ex.deposit("S", HUNDRED);
        mintShares(ex, "Q", Outcome.NO, 1);
        mintShares(ex, "S", Outcome.YES, 1);

        ex.submit(order("m1", "M", Outcome.YES, BUY, "0.60", 1));   // 先挂：真实买价
        ex.submit(order("q1", "Q", Outcome.NO, SELL, "0.40", 1));   // 后挂：合成买价 1 − 0.40 = 0.60

        List<Event> events = ex.submit(order("s1", "S", Outcome.YES, SELL, "0.60", 1));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.NORMAL, trade.type());
        assertEquals("m1", trade.makerOrderId());
    }

    @Test
    @DisplayName("同价时_挂得更早的合成价位更优先")
    void tieOnPrice_syntheticLevelWinsWhenItIsEarlier() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("M", HUNDRED);
        ex.deposit("Q", HUNDRED);
        ex.deposit("S", HUNDRED);
        mintShares(ex, "Q", Outcome.NO, 1);
        mintShares(ex, "S", Outcome.YES, 1);

        ex.submit(order("q1", "Q", Outcome.NO, SELL, "0.40", 1));   // 先挂：合成买价 1 − 0.40 = 0.60
        ex.submit(order("m1", "M", Outcome.YES, BUY, "0.60", 1));   // 后挂：真实买价

        List<Event> events = ex.submit(order("s1", "S", Outcome.YES, SELL, "0.60", 1));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.MERGE, trade.type());
        assertEquals("q1", trade.makerOrderId());
    }

    @Test
    @DisplayName("合成价更优时_优先吃合成价")
    void betterSyntheticPriceIsMatchedFirst() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("S", HUNDRED);
        ex.deposit("V", HUNDRED);
        ex.deposit("T", HUNDRED);
        mintShares(ex, "S", Outcome.YES, 1);

        ex.submit(order("s1", "S", Outcome.YES, SELL, "0.69", 1));  // 直接买要 0.69
        ex.submit(order("v1", "V", Outcome.NO, BUY, "0.99", 1));    // 和它铸造只要 0.01

        List<Event> events = ex.submit(order("t1", "T", Outcome.YES, BUY, "0.70", 1));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.MINT, trade.type(), "0.01 比 0.69 对买方更优，应该走铸造");
        assertEquals("v1", trade.makerOrderId());
        assertAmount("0.01", trade.takerPrice());
        assertAmount("0.99", trade.makerPrice());
        assertAmount("1.00", trade.takerPrice().add(trade.makerPrice()));
    }

    @Test
    @DisplayName("成交后残单留在盘口_并保持时间优先")
    void residualRestsOnBook_andKeepsTimePriority() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        ex.deposit("S", HUNDRED);
        mintShares(ex, "S", Outcome.YES, 10);
        ex.submit(order("s1", "S", Outcome.YES, SELL, "0.60", 4));

        // A 想买 10 张，只能吃到 4 张，剩下 6 张挂盘口
        List<Event> events = ex.submit(order("a1", "A", Outcome.YES, BUY, "0.60", 10));
        assertEquals(2, events.size());
        var resting = ex.openOrders().stream().filter(o -> o.orderId().equals("a1")).findFirst().orElseThrow();
        assertEquals(6, resting.remaining());
        // 残单的冻结是 6 × 0.60
        assertAmount("3.60", ex.account("A").frozen());
        assertEquals(4, ex.account("A").posYes());
    }
}
