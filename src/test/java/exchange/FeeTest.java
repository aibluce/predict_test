package exchange;

import static exchange.Outcome.NO;
import static exchange.Outcome.YES;
import static exchange.Side.BUY;
import static exchange.Side.SELL;
import static exchange.TestSupport.assertAmount;
import static exchange.TestSupport.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import exchange.core.EventSourcedExchange;
import exchange.io.ScriptRunner;

/**
 * 第四层：手续费。
 *
 * <p>冻结规则：买入冻结 {@code reserve(q, L) = q×L + ceil4(q×L×rate)}；每笔成交从冻结里扣
 * {@code amount + fee}，再把该单冻结重定为 {@code reserve(剩余 qty, L)}，多出来的回 available。
 * 费率 0 与不设置费率在数值上完全等价。
 */
class FeeTest {

    private static final BigDecimal RATE = new BigDecimal("0.002");

    @Test
    @DisplayName("费率只能设置一次_且必须在第一张订单被接受之前")
    void feeRateCanBeSetOnce_andBeforeMarketOpens() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.setFeeRate(RATE);
        assertThrows(IllegalStateException.class, () -> ex.setFeeRate(new BigDecimal("0.001")),
                "setFeeRate 只能调用一次");
        assertThrows(IllegalArgumentException.class, () -> new EventSourcedExchange().setFeeRate(new BigDecimal("-0.1")));

        // 第一张订单被接受之后不能再设置
        EventSourcedExchange opened = new EventSourcedExchange();
        opened.deposit("A", new BigDecimal("100"));
        opened.submit(order("a1", "A", YES, BUY, "0.10", 1));
        assertThrows(IllegalStateException.class, () -> opened.setFeeRate(RATE));

        // 被拒的订单不算开市
        EventSourcedExchange rejected = new EventSourcedExchange();
        rejected.submit(order("z1", "Z", YES, BUY, "0.10", 1));
        rejected.setFeeRate(RATE);
        assertAmount("0.002", rejected.feeRate());
    }

    @Test
    @DisplayName("买单冻结的是reserve_含向上取整的手续费准备金")
    void buyOrderFreezesReserve_includingCeiledFeeBuffer() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.setFeeRate(RATE);
        ex.deposit("A", new BigDecimal("100"));

        // 10 × 0.60 = 6.00，准备金 ceil4(6.00 × 0.002) = 0.0120
        ex.submit(order("a1", "A", YES, BUY, "0.60", 10));
        assertAmount("6.0120", ex.account("A").frozen());
        assertAmount("93.9880", ex.account("A").available());
    }

    @Test
    @DisplayName("资金够qty乘限价但不够reserve_整单拒绝")
    void enoughForQtyTimesLimitButNotForReserve_rejectsWholeOrder() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.setFeeRate(RATE);
        ex.deposit("A", new BigDecimal("6.0050"));   // 够 6.00，不够 6.0120
        List<Event> events = ex.submit(order("a1", "A", YES, BUY, "0.60", 10));
        assertEquals(RejectReason.INSUFFICIENT_FUNDS,
                assertInstanceOf(Event.OrderRejected.class, events.get(0)).reason());
        assertAmount("6.0050", ex.account("A").available());
        assertAmount("0.0000", ex.account("A").frozen());
    }

    @Test
    @DisplayName("带手续费的部分成交_冻结重定_手续费账户_都可以逐个数对上")
    void partialFillWithFee_refreezesAndBooksFee() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.setFeeRate(RATE);
        ex.deposit("A", new BigDecimal("100"));
        ex.deposit("B", new BigDecimal("100"));
        ex.submit(order("a1", "A", YES, BUY, "0.60", 10));

        // B 以 0.40 买 NO 8 张：铸造 8 张，A 是挂单方
        List<Event> events = ex.submit(order("b1", "B", NO, BUY, "0.40", 8));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.MINT, trade.type());
        assertAmount("3.2000", trade.takerAmount());
        assertAmount("0.0064", trade.takerFee());          // floor4(3.20 × 0.002) = 0.0064
        assertAmount("4.8000", trade.makerAmount());
        assertAmount("0.0096", trade.makerFee());          // floor4(4.80 × 0.002) = 0.0096
        // 铸造两边付款之和仍然是 qty × 1，手续费不进池
        assertAmount("8.0000", trade.takerAmount().add(trade.makerAmount()));
        assertAmount("8.0000", ex.collateralPool());

        // A：冻结从 6.0120（10 张）重定为 1.2024（2 张 = 1.20 + ceil4(0.0024)），实扣 4.8000 + 0.0096
        assertAmount("1.2024", ex.account("A").frozen());
        assertAmount("93.9880", ex.account("A").available());
        assertEquals(8, ex.account("A").posYes());
        // B：冻结 3.2064 正好够 3.2000 + 0.0064，交割后为 0
        assertAmount("96.7936", ex.account("B").available());
        assertAmount("0.0000", ex.account("B").frozen());
        assertEquals(8, ex.account("B").posNo());
        assertAmount("0.0160", ex.feeAccount());

        // 撤掉 A 的残单：释放的正是 reserve(2, 0.60) = 1.2024
        assertEquals(2, assertInstanceOf(Event.OrderCancelled.class, ex.cancel("a1").get(0)).remainingQty());
        assertAmount("95.1904", ex.account("A").available());
        assertAmount("0.0000", ex.account("A").frozen());
        // 守恒：A + B 的资金 + 抵押池 + 手续费账户 = 200
        assertAmount("200.0000", ex.account("A").available().add(ex.account("A").frozen())
                .add(ex.account("B").available()).add(ex.account("B").frozen())
                .add(ex.collateralPool()).add(ex.feeAccount()));
    }

    @Test
    @DisplayName("卖方的手续费从收款里扣_买方从冻结里扣")
    void sellerFeeComesFromProceeds_buyerFeeFromFrozen() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.setFeeRate(RATE);
        ex.deposit("A", new BigDecimal("1000"));
        ex.deposit("B", new BigDecimal("1000"));

        // A 买 YES、B 买 NO，各 10 张，0.50 / 0.50 铸造
        ex.submit(order("m1", "A", YES, BUY, "0.50", 10));
        ex.submit(order("m2", "B", NO, BUY, "0.50", 10));
        assertAmount("994.9900", ex.account("A").available());     // 1000 − 5.0100
        assertAmount("994.9900", ex.account("B").available());
        assertAmount("10.0000", ex.collateralPool());
        assertAmount("0.0200", ex.feeAccount());                   // 两边各 0.0100

        // A 挂卖 4 张 YES @0.60，B 买 4 张 → NORMAL，成交价 0.60
        ex.submit(order("s1", "A", YES, SELL, "0.60", 4));
        assertEquals(4, ex.account("A").frozenYes());
        List<Event> events = ex.submit(order("t1", "B", YES, BUY, "0.60", 4));
        Event.Trade trade = assertInstanceOf(Event.Trade.class, events.get(1));
        assertEquals(TradeType.NORMAL, trade.type());
        // 买方：冻结 2.4048，实扣 2.4000 + 0.0048
        assertAmount("2.4000", trade.takerAmount());
        assertAmount("0.0048", trade.takerFee());
        assertAmount("992.5852", ex.account("B").available());
        assertEquals(4, ex.account("B").posYes());
        // 卖方：收款 2.4000 扣掉自己的 0.0048
        assertAmount("2.4000", trade.makerAmount());
        assertAmount("0.0048", trade.makerFee());
        assertAmount("997.3852", ex.account("A").available());
        assertEquals(0, ex.account("A").frozenYes());
        assertEquals(6, ex.account("A").posYes());
        // 手续费账户累计 = 0.0200 + 0.0048 + 0.0048
        assertAmount("0.0296", ex.feeAccount());
    }

    @Test
    @DisplayName("销毁进出抵押池的也恰好是qty乘1_手续费不进池")
    void mergeMovesExactlyQtyIntoPool_feeDoesNotEnterPool() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.setFeeRate(RATE);
        ex.deposit("A", new BigDecimal("1000"));
        ex.deposit("B", new BigDecimal("1000"));
        ex.submit(order("m1", "A", YES, BUY, "0.50", 5));
        ex.submit(order("m2", "B", NO, BUY, "0.50", 5));
        assertAmount("5.0000", ex.collateralPool());

        ex.submit(order("s1", "A", YES, SELL, "0.40", 5));
        ex.submit(order("s2", "B", NO, SELL, "0.60", 5));
        assertAmount("0.0000", ex.collateralPool(), "销毁把 5 × 1 从池里拿走");
        assertTrue(ex.feeAccount().signum() > 0, "手续费进手续费账户，不进池");
    }

    @Test
    @DisplayName("手续费向下截断_四舍五入会多扣0_0001并让available为负")
    void feeTruncationVsRounding_wouldMakeReleaseNegative() {
        BigDecimal rate = new BigDecimal("0.002");
        BigDecimal limit = new BigDecimal("0.13");
        long qty = 5;
        long fill = 1;

        BigDecimal frozenBefore = Money.reserve(qty, limit, rate);
        BigDecimal amount = Money.amount(fill, limit);
        BigDecimal frozenAfter = Money.reserve(qty - fill, limit, rate);
        assertAmount("0.6513", frozenBefore);   // 0.65 + ceil4(0.0013)
        assertAmount("0.5211", frozenAfter);    // 0.52 + ceil4(0.00104)

        BigDecimal feeFloor = Money.fee(amount, rate);
        assertAmount("0.0002", feeFloor);       // floor4(0.00026)
        BigDecimal released = frozenBefore.subtract(amount).subtract(feeFloor).subtract(frozenAfter);
        assertAmount("0.0000", released, "向下截断时回到 available 的部分刚好是 0，不会为负");

        BigDecimal feeRounded = amount.multiply(rate).setScale(Money.SCALE, RoundingMode.HALF_UP);
        assertAmount("0.0003", feeRounded);     // round4(0.00026)
        BigDecimal releasedWithRounding =
                frozenBefore.subtract(amount).subtract(feeRounded).subtract(frozenAfter);
        assertAmount("-0.0001", releasedWithRounding, "改成四舍五入就会多扣 0.0001，available 会被扣成负数");
    }

    @Test
    @DisplayName("恢复出来的交易所_费率与手续费账户仍然生效")
    void restoredExchange_keepsFeeRateAndFeeAccount() {
        EventSourcedExchange live = new EventSourcedExchange();
        live.setFeeRate(RATE);
        live.deposit("A", new BigDecimal("1000"));
        live.deposit("B", new BigDecimal("1000"));
        live.submit(order("a1", "A", YES, BUY, "0.50", 4));
        live.submit(order("b1", "B", NO, BUY, "0.50", 4));    // MINT 4，两边各 floor4(2.00 × 0.002) = 0.0040
        assertAmount("0.0080", live.feeAccount());
        assertAmount("0.0080", Exchange.restore(live.snapshot()).feeAccount());
        assertAmount("0.0080", Exchange.replay(live.eventLog()).feeAccount());

        // 恢复之后新来的订单，冻结同样要含手续费准备金：2 × 0.50 + ceil4(0.002) = 1.0020
        Exchange restored = Exchange.restore(live.snapshot());
        List<Event> liveEvents = live.submit(order("n1", "A", YES, BUY, "0.50", 2));
        List<Event> restoredEvents = ((EventSourcedExchange) restored).submit(order("n1", "A", YES, BUY, "0.50", 2));
        assertEquals(liveEvents, restoredEvents);
        assertEquals(live.snapshot(), restored.snapshot());
        assertAmount("1.0020", live.account("A").frozen());
    }

    @Test
    @DisplayName("费率0与不设置费率数值上完全等价")
    void zeroFeeRate_isNumericallyEqualToNoFeeRate() {
        String body = """
                DEPOSIT A 100
                DEPOSIT B 100
                SUBMIT a1 A YES BUY 0.60 10
                SUBMIT b1 B NO BUY 0.40 8
                SUBMIT b2 B NO BUY 0.45 4
                CANCEL b2
                """;
        String withZero = ScriptRunner.run("FEE_RATE 0\n" + body);
        String without = ScriptRunner.run(body);
        assertEquals(without, withZero.substring(withZero.indexOf('\n') + 1),
                "显式设置 0 费率除了多一行 FEE_RATE_SET，输出应该与不设置完全一样");
    }
}
