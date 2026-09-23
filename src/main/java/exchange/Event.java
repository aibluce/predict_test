package exchange;

import java.math.BigDecimal;

/**
 * 系统所有状态变化只能由事件驱动：submit / cancel / deposit / setFeeRate 先生成事件，
 * 再把事件应用到状态。事件按 seq 单调递增生成，同一事件序列重放结果一致。
 */
public sealed interface Event {

    long seq();

    /** 开市前设置费率，只能一次。 */
    record FeeRateSet(long seq, BigDecimal rate) implements Event {
    }

    record Deposit(long seq, String accountId, BigDecimal amount) implements Event {
    }

    /** 订单被接受：冻结资金或份额，并把订单挂到盘口队尾（时间优先由事件顺序决定）。 */
    record OrderAccepted(long seq, String orderId, String accountId, Outcome outcome, Side side,
                         BigDecimal price, long qty) implements Event {
    }

    /** 订单被拒：唯一的状态效果是把这个 orderId 记入“见过”，再提交就是 DUPLICATE_ORDER_ID。 */
    record OrderRejected(long seq, String orderId, RejectReason reason) implements Event {
    }

    /**
     * 一笔成交。taker 是本次进来的订单，maker 是原先挂在盘口上的订单。
     * 每一方的 price / amount / fee 都写在事件里，所以打印、replay、对账用的是同一份数字。
     */
    record Trade(long seq, TradeType type, long qty,
                 String takerOrderId, Outcome takerOutcome, Side takerSide,
                 BigDecimal takerPrice, BigDecimal takerAmount, BigDecimal takerFee,
                 String makerOrderId, Outcome makerOutcome, Side makerSide,
                 BigDecimal makerPrice, BigDecimal makerAmount, BigDecimal makerFee) implements Event {
    }

    /** 撤单成功：释放剩余冻结，remainingQty 是撤掉时未成交的张数。 */
    record OrderCancelled(long seq, String orderId, long remainingQty) implements Event {
    }

    /** 撤单失败：订单不存在、已撤销或已全部成交。无状态效果。 */
    record CancelRejected(long seq, String orderId) implements Event {
    }
}
