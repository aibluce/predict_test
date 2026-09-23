package exchange;

import static exchange.Outcome.NO;
import static exchange.Outcome.YES;
import static exchange.Side.BUY;
import static exchange.Side.SELL;
import static exchange.TestSupport.assertAmount;
import static exchange.TestSupport.mintShares;
import static exchange.TestSupport.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import exchange.core.EventSourcedExchange;

/** 第二层：拒单、冻结、解冻、抵押池与守恒。 */
class LedgerTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Test
    @DisplayName("三种拒单")
    void threeRejectReasons() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);

        // 资金不足：200 张 × 0.99 = 198.00 > 100.00
        List<Event> e1 = ex.submit(order("x1", "A", YES, BUY, "0.99", 200));
        assertEquals(RejectReason.INSUFFICIENT_FUNDS, reason(e1));
        assertAmount("100.00", ex.account("A").available());
        assertAmount("0.00", ex.account("A").frozen());

        // 份额不足
        List<Event> e2 = ex.submit(order("x2", "A", YES, SELL, "0.60", 1));
        assertEquals(RejectReason.INSUFFICIENT_SHARES, reason(e2));

        // 重复：出现在之前任何一次 submit 里的 orderId（哪怕那次被拒）
        List<Event> e3 = ex.submit(order("x1", "A", YES, BUY, "0.01", 1));
        assertEquals(RejectReason.DUPLICATE_ORDER_ID, reason(e3));
        List<Event> e4 = ex.submit(order("x2", "A", YES, BUY, "0.99", 200));
        assertEquals(RejectReason.DUPLICATE_ORDER_ID, reason(e4), "重复检查先于资金检查");
        assertTrue(ex.openOrders().isEmpty());
    }

    @Test
    @DisplayName("没有deposit过的账户_按资金或份额不足拒_且不因此存在")
    void accountWithoutDeposit_isRejected_andNotCreated() {
        EventSourcedExchange ex = new EventSourcedExchange();
        assertEquals(RejectReason.INSUFFICIENT_FUNDS,
                reason(ex.submit(order("z1", "Z", YES, BUY, "0.01", 1))));
        assertEquals(RejectReason.INSUFFICIENT_SHARES,
                reason(ex.submit(order("z2", "Z", YES, SELL, "0.60", 1))));
        assertTrue(ex.accountIds().isEmpty(), "被拒的订单不应该让账户存在");
        assertAmount("0.00", ex.account("Z").available());
        assertEquals(0, ex.account("Z").posYes());
    }

    @Test
    @DisplayName("部分成交后撤单_释放剩余冻结")
    void cancelAfterPartialFill_releasesRemainingReserve() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        ex.deposit("C", HUNDRED);

        ex.submit(order("oA", "A", YES, BUY, "0.60", 10));
        assertAmount("94.00", ex.account("A").available());
        assertAmount("6.00", ex.account("A").frozen());

        // C 买 NO 8 张中的 4 张与 A 铸造：A 实扣 4 × 0.60，剩 6 张的冻结
        ex.submit(order("oC", "C", NO, BUY, "0.40", 4));
        assertAmount("94.00", ex.account("A").available());
        assertAmount("3.60", ex.account("A").frozen());
        assertEquals(4, ex.account("A").posYes());
        assertAmount("4.00", ex.collateralPool());

        Event.OrderCancelled cancelled = assertInstanceOf(Event.OrderCancelled.class, ex.cancel("oA").get(0));
        assertEquals(6, cancelled.remainingQty());
        assertAmount("97.60", ex.account("A").available());
        assertAmount("0.00", ex.account("A").frozen());
        assertEquals(4, ex.account("A").posYes());
        assertAmount("98.40", ex.account("C").available());
        // 守恒：A、C 的 (available + frozen) 之和 + 抵押池 = 200
        assertAmount("200.00", ex.account("A").available().add(ex.account("A").frozen())
                .add(ex.account("C").available()).add(ex.account("C").frozen())
                .add(ex.collateralPool()).add(ex.feeAccount()));
    }

    @Test
    @DisplayName("撤卖单_冻结份额回到持仓")
    void cancelSellOrder_returnsFrozenShares() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        mintShares(ex, "A", YES, 10);

        ex.submit(order("s1", "A", YES, SELL, "0.60", 4));
        assertEquals(6, ex.account("A").posYes());
        assertEquals(4, ex.account("A").frozenYes());

        Event.OrderCancelled cancelled = assertInstanceOf(Event.OrderCancelled.class, ex.cancel("s1").get(0));
        assertEquals(4, cancelled.remainingQty());
        assertEquals(10, ex.account("A").posYes());
        assertEquals(0, ex.account("A").frozenYes());
    }

    @Test
    @DisplayName("成交价优于限价_差额回可用_撤单释放剩余")
    void priceImprovementReturnsToAvailable_thenCancelReleasesRest() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("S", HUNDRED);
        ex.deposit("A", HUNDRED);
        mintShares(ex, "S", YES, 6);
        ex.submit(order("s1", "S", YES, SELL, "0.50", 6));

        // A 限价 0.60 买 10 张，实际按 0.50 成交 6 张
        ex.submit(order("a1", "A", YES, BUY, "0.60", 10));
        assertAmount("94.60", ex.account("A").available(), "冻结 6.00，实扣 3.00，价差 0.60 要回可用");
        assertAmount("2.40", ex.account("A").frozen());
        assertEquals(6, ex.account("A").posYes());

        assertEquals(4, assertInstanceOf(Event.OrderCancelled.class, ex.cancel("a1").get(0)).remainingQty());
        assertAmount("97.00", ex.account("A").available());
        assertAmount("0.00", ex.account("A").frozen());
    }

    @Test
    @DisplayName("撤不存在的_已撤的_已全部成交的订单都是CANCEL_REJECTED")
    void cancelUnknownCancelledOrFilledOrder_isCancelRejected() {
        EventSourcedExchange ex = new EventSourcedExchange();
        ex.deposit("A", HUNDRED);
        ex.deposit("B", HUNDRED);

        ex.submit(order("a1", "A", YES, BUY, "0.50", 5));
        assertInstanceOf(Event.OrderCancelled.class, ex.cancel("a1").get(0));
        assertInstanceOf(Event.CancelRejected.class, ex.cancel("a1").get(0));
        assertInstanceOf(Event.CancelRejected.class, ex.cancel("nope").get(0));

        // 全部成交的订单也不在盘口上
        ex.submit(order("a2", "A", YES, BUY, "0.50", 5));
        ex.submit(order("b1", "B", NO, BUY, "0.50", 5));
        assertAmount("0.00", ex.account("A").frozen());
        assertInstanceOf(Event.CancelRejected.class, ex.cancel("a2").get(0));
    }

    @Test
    @DisplayName("走一遍例子之后资金守恒")
    void moneyIsConservedAfterTheExample() {
        EventSourcedExchange ex = WalkthroughTest.exampleState();
        BigDecimal sum = BigDecimal.ZERO;
        for (String accountId : ex.accountIds()) {
            AccountView view = ex.account(accountId);
            sum = sum.add(view.available()).add(view.frozen());
        }
        assertAmount("300.00", sum.add(ex.collateralPool()).add(ex.feeAccount()));
        // YES 份额总数 = NO 份额总数 = 抵押池
        long yes = 0;
        long no = 0;
        for (String accountId : ex.accountIds()) {
            AccountView view = ex.account(accountId);
            yes += view.posYes() + view.frozenYes();
            no += view.posNo() + view.frozenNo();
        }
        assertEquals(8, yes);
        assertEquals(yes, no);
        assertAmount("8.00", ex.collateralPool());
    }

    private static RejectReason reason(List<Event> events) {
        return assertInstanceOf(Event.OrderRejected.class, events.get(0)).reason();
    }
}
