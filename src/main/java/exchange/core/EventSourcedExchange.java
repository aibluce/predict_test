package exchange.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import exchange.AccountView;
import exchange.Event;
import exchange.Exchange;
import exchange.Money;
import exchange.Order;
import exchange.Outcome;
import exchange.RejectReason;
import exchange.Side;
import exchange.Snapshot;
import exchange.TradeType;
import exchange.book.MatchEngine;
import exchange.book.RestingOrder;
import exchange.book.TradeIntent;

/**
 * 单市场、纯内存交易所。
 *
 * <p>submit / cancel 的流程固定为两步：<b>先校验并生成事件列表，再把事件应用到状态</b>。
 * 校验只有两条（题目要求）：orderId 是否出现过、资金或份额是否够，且前者先于后者。
 */
public final class EventSourcedExchange implements Exchange {

    private final StateApplier state;
    private final MatchEngine matcher;
    /** 事件流，供第三层的 replay 与测试比对用；不是状态的一部分。 */
    private final List<Event> eventLog = new ArrayList<>();

    public EventSourcedExchange() {
        this(new StateApplier());
    }

    private EventSourcedExchange(StateApplier state) {
        this.state = state;
        this.matcher = new MatchEngine(state.book());
    }

    /** 第三层：从快照恢复出一个交易所。 */
    public static EventSourcedExchange restore(Snapshot snapshot) {
        return new EventSourcedExchange(new StateApplier(snapshot));
    }

    /** 第三层：从空状态把事件全部应用一遍。 */
    public static EventSourcedExchange replay(List<Event> events) {
        EventSourcedExchange exchange = new EventSourcedExchange();
        exchange.applyEvents(events);
        return exchange;
    }

    /** 第三层：把事件继续应用到当前状态（快照 restore 之后接着消费 k+1 起的事件）。 */
    public void applyEvents(List<Event> events) {
        state.applyAll(events);
        eventLog.addAll(events);
    }

    @Override
    public void setFeeRate(BigDecimal rate) {
        if (state.feeRateSet()) {
            throw new IllegalStateException("setFeeRate 只能调用一次");
        }
        if (state.marketOpened()) {
            throw new IllegalStateException("第一张订单被接受之后不能再设置费率");
        }
        if (rate == null || rate.signum() < 0) {
            throw new IllegalArgumentException("费率不能为负：" + rate);
        }
        applyAndReturn(List.of(new Event.FeeRateSet(state.nextSeq(), Money.scale4(rate))));
    }

    @Override
    public void deposit(String accountId, BigDecimal amount) {
        applyAndReturn(List.of(new Event.Deposit(state.nextSeq(), accountId, Money.scale4(amount))));
    }

    @Override
    public List<Event> submit(Order order) {
        // 1. orderId 只要在之前任何一次 submit 里出现过（接受或拒绝都算），先按重复拒掉
        if (state.seen(order.orderId())) {
            return applyAndReturn(List.of(new Event.OrderRejected(state.nextSeq(), order.orderId(),
                    RejectReason.DUPLICATE_ORDER_ID)));
        }
        // 2. 资金或份额不够，整单拒绝，不允许部分接受
        if (order.side() == Side.BUY) {
            BigDecimal needed = Money.reserve(order.qty(), order.price(), state.feeRate());
            if (state.ledger().available(order.accountId()).compareTo(needed) < 0) {
                return applyAndReturn(List.of(new Event.OrderRejected(state.nextSeq(), order.orderId(),
                        RejectReason.INSUFFICIENT_FUNDS)));
            }
        } else if (state.ledger().position(order.accountId(), order.outcome()) < order.qty()) {
            return applyAndReturn(List.of(new Event.OrderRejected(state.nextSeq(), order.orderId(),
                    RejectReason.INSUFFICIENT_SHARES)));
        }

        // 3. 先生成事件列表：先接受（冻结 + 挂盘口），再按撮合顺序逐笔成交
        long seq = state.nextSeq();
        List<Event> events = new ArrayList<>();
        events.add(new Event.OrderAccepted(seq++, order.orderId(), order.accountId(),
                order.outcome(), order.side(), order.price(), order.qty()));
        for (TradeIntent intent : matcher.match(order)) {
            events.add(tradeEvent(seq++, intent));
        }
        return applyAndReturn(events);
    }

    @Override
    public List<Event> cancel(String orderId) {
        RestingOrder resting = state.book().get(orderId);
        Event event = resting == null
                ? new Event.CancelRejected(state.nextSeq(), orderId)
                : new Event.OrderCancelled(state.nextSeq(), orderId, resting.remaining());
        return applyAndReturn(List.of(event));
    }

    /** 一张该 outcome 的份额现在能立即卖出的最高价：真实买价与「1 − 另一 outcome 最低卖价」取高。 */
    @Override
    public Optional<BigDecimal> bestBid(Outcome outcome) {
        BigDecimal real = state.book().rawBestBid(outcome);
        BigDecimal synthetic = complement(state.book().rawBestAsk(outcome.other()));
        return pick(real, synthetic, true);
    }

    /** 一张该 outcome 的份额现在能立即买入的最低价：真实卖价与「1 − 另一 outcome 最高买价」取低。 */
    @Override
    public Optional<BigDecimal> bestAsk(Outcome outcome) {
        BigDecimal real = state.book().rawBestAsk(outcome);
        BigDecimal synthetic = complement(state.book().rawBestBid(outcome.other()));
        return pick(real, synthetic, false);
    }

    @Override
    public AccountView account(String accountId) {
        return state.ledger().view(accountId);
    }

    @Override
    public BigDecimal collateralPool() {
        return state.ledger().collateralPool();
    }

    @Override
    public BigDecimal feeAccount() {
        return state.ledger().feeAccount();
    }

    @Override
    public Snapshot snapshot() {
        return state.snapshot();
    }

    // ---- 扩展：测试与适配器用 ----

    /** 已 deposit 过的账户，字典序。 */
    public List<String> accountIds() {
        return state.ledger().accountIds();
    }

    /** 当前未完结订单。 */
    public Collection<RestingOrder> openOrders() {
        return state.book().openOrders();
    }

    public BigDecimal feeRate() {
        return state.feeRate();
    }

    public long nextSeq() {
        return state.nextSeq();
    }

    /** 事件流（含 deposit 产生的事件），按发生顺序。 */
    public List<Event> eventLog() {
        return Collections.unmodifiableList(eventLog);
    }

    private List<Event> applyAndReturn(List<Event> events) {
        state.applyAll(events);
        eventLog.addAll(events);
        return List.copyOf(events);
    }

    private Event.Trade tradeEvent(long seq, TradeIntent intent) {
        BigDecimal takerAmount = Money.amount(intent.qty(), intent.takerPrice());
        BigDecimal makerAmount = Money.amount(intent.qty(), intent.makerPrice());
        BigDecimal takerFee = Money.fee(takerAmount, state.feeRate());
        BigDecimal makerFee = Money.fee(makerAmount, state.feeRate());
        if (intent.type() != TradeType.NORMAL) {
            // 第一层不变量：铸造 / 销毁两边金额之和恰好等于 qty × 1
            BigDecimal sum = takerAmount.add(makerAmount);
            BigDecimal expected = Money.amount(intent.qty(), Money.ONE);
            if (sum.compareTo(expected) != 0) {
                throw new IllegalStateException("铸造/销毁两边金额之和不等于 qty × 1：" + sum + " vs " + expected);
            }
        }
        return new Event.Trade(seq, intent.type(), intent.qty(),
                intent.takerOrderId(), intent.takerOutcome(), intent.takerSide(),
                intent.takerPrice(), takerAmount, takerFee,
                intent.makerOrderId(), intent.makerOutcome(), intent.makerSide(),
                intent.makerPrice(), makerAmount, makerFee);
    }

    private static BigDecimal complement(BigDecimal price) {
        return price == null ? null : Money.ONE.subtract(price);
    }

    private static Optional<BigDecimal> pick(BigDecimal a, BigDecimal b, boolean max) {
        if (a == null) {
            return Optional.ofNullable(b);
        }
        if (b == null) {
            return Optional.of(a);
        }
        int cmp = a.compareTo(b);
        return Optional.of(max ? (cmp >= 0 ? a : b) : (cmp <= 0 ? a : b));
    }
}
