package exchange.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import exchange.AccountView;
import exchange.Event;
import exchange.Money;
import exchange.Outcome;
import exchange.Side;
import exchange.Snapshot;
import exchange.TradeType;
import exchange.book.OrderBook;
import exchange.book.RestingOrder;
import exchange.ledger.Ledger;

/**
 * 第三层：把事件应用到状态。这里是<b>唯一</b>会改状态的地方，
 * 所以 replay 就是「空状态 + 依次 apply」，不需要另写一套逻辑。
 */
public final class StateApplier {

    private final Ledger ledger = new Ledger();
    private final OrderBook book = new OrderBook();
    /** 所有出现过的 orderId（含被拒的），重复提交要按 DUPLICATE_ORDER_ID 拒。 */
    private final Set<String> seenOrderIds = new HashSet<>();
    private BigDecimal feeRate = BigDecimal.ZERO.setScale(Money.SCALE);
    private boolean feeRateSet;
    private boolean marketOpened;
    private long nextSeq = 1;

    public StateApplier() {
    }

    /** 从快照恢复：不经过事件，直接把状态拷回来。 */
    public StateApplier(Snapshot snapshot) {
        ledger.restorePoolAndFees(snapshot.collateralPool(), snapshot.feeAccount());
        for (AccountView account : snapshot.accounts()) {
            ledger.restoreAccount(account);
        }
        // 按 seq 升序加回盘口，同价 FIFO 的顺序就与原来一致
        for (Snapshot.OrderState order : snapshot.orders()) {
            book.add(new RestingOrder(order.orderId(), order.accountId(), order.outcome(),
                    order.side(), order.price(), order.remaining(), order.seq()));
        }
        seenOrderIds.addAll(snapshot.seenOrderIds());
        feeRate = snapshot.feeRate();
        feeRateSet = snapshot.feeRateSet();
        marketOpened = snapshot.marketOpened();
        nextSeq = snapshot.nextSeq();
    }

    /** 当前状态的完整深拷贝。 */
    public Snapshot snapshot() {
        List<AccountView> accounts = new ArrayList<>();
        for (String accountId : ledger.accountIds()) {
            accounts.add(ledger.view(accountId));
        }
        List<Snapshot.OrderState> orders = new ArrayList<>();
        for (RestingOrder order : book.openOrders()) {
            orders.add(new Snapshot.OrderState(order.orderId(), order.accountId(), order.outcome(),
                    order.side(), order.price(), order.remaining(), order.seq()));
        }
        return new Snapshot(accounts, orders, ledger.collateralPool(), ledger.feeAccount(),
                feeRate, feeRateSet, marketOpened, List.copyOf(seenOrderIds), nextSeq);
    }

    public void applyAll(List<Event> events) {
        for (Event event : events) {
            apply(event);
        }
    }

    public void apply(Event event) {
        nextSeq = Math.max(nextSeq, event.seq() + 1);
        if (event instanceof Event.FeeRateSet feeRateSetEvent) {
            feeRate = feeRateSetEvent.rate();
            feeRateSet = true;
        } else if (event instanceof Event.Deposit deposit) {
            ledger.deposit(deposit.accountId(), deposit.amount());
        } else if (event instanceof Event.OrderAccepted accepted) {
            applyAccepted(accepted);
        } else if (event instanceof Event.OrderRejected rejected) {
            seenOrderIds.add(rejected.orderId());
        } else if (event instanceof Event.Trade trade) {
            applyTrade(trade);
        } else if (event instanceof Event.OrderCancelled cancelled) {
            applyCancelled(cancelled);
        } else if (event instanceof Event.CancelRejected) {
            // 无状态变化：订单本来就不在盘口上
        } else {
            throw new IllegalStateException("未知事件：" + event);
        }
    }

    /** 接受订单：按 reserve(qty, 限价) 冻结，并挂到盘口队尾。 */
    private void applyAccepted(Event.OrderAccepted accepted) {
        marketOpened = true;
        seenOrderIds.add(accepted.orderId());
        if (accepted.side() == Side.BUY) {
            ledger.freezeBuy(accepted.accountId(), Money.reserve(accepted.qty(), accepted.price(), feeRate));
        } else {
            ledger.freezeShares(accepted.accountId(), accepted.outcome(), accepted.qty());
        }
        book.add(new RestingOrder(accepted.orderId(), accepted.accountId(), accepted.outcome(),
                accepted.side(), accepted.price(), accepted.qty(), accepted.seq()));
    }

    /**
     * 一笔成交：两边入账，然后处理抵押池和手续费账户。
     * 金额和手续费都取自事件本身，不在入账时重算，保证 replay 与实时完全一致。
     */
    private void applyTrade(Event.Trade trade) {
        settleSide(trade.takerOrderId(), trade.takerSide(), trade.takerOutcome(),
                trade.qty(), trade.takerAmount(), trade.takerFee());
        settleSide(trade.makerOrderId(), trade.makerSide(), trade.makerOutcome(),
                trade.qty(), trade.makerAmount(), trade.makerFee());
        if (trade.type() == TradeType.MINT) {
            ledger.poolMint(trade.qty());
        } else if (trade.type() == TradeType.MERGE) {
            ledger.poolMerge(trade.qty());
        }
        BigDecimal totalFee = trade.takerFee().add(trade.makerFee());
        if (totalFee.signum() != 0) {
            ledger.addFee(totalFee);
        }
    }

    private void settleSide(String orderId, Side side, Outcome outcome, long qty, BigDecimal amount, BigDecimal fee) {
        RestingOrder order = book.get(orderId);
        if (order == null) {
            throw new IllegalStateException("成交引用了不存在的订单：" + orderId);
        }
        if (side == Side.BUY) {
            BigDecimal frozenBefore = Money.reserve(order.remaining(), order.price(), feeRate);
            BigDecimal frozenAfter = Money.reserve(order.remaining() - qty, order.price(), feeRate);
            ledger.settleBuy(order.accountId(), outcome, qty, amount, fee, frozenBefore, frozenAfter);
        } else {
            ledger.settleSell(order.accountId(), outcome, qty, amount, fee);
        }
        book.reduce(order, qty);
    }

    /** 撤单：剩余冻结（钱或份额）全部回到 available 或 positions。 */
    private void applyCancelled(Event.OrderCancelled cancelled) {
        RestingOrder order = book.get(cancelled.orderId());
        if (order == null) {
            throw new IllegalStateException("撤单引用了不存在的订单：" + cancelled.orderId());
        }
        if (order.side() == Side.BUY) {
            ledger.releaseBuy(order.accountId(), Money.reserve(order.remaining(), order.price(), feeRate));
        } else {
            ledger.releaseShares(order.accountId(), order.outcome(), order.remaining());
        }
        book.remove(order.orderId());
    }

    public Ledger ledger() {
        return ledger;
    }

    public OrderBook book() {
        return book;
    }

    public BigDecimal feeRate() {
        return feeRate;
    }

    public boolean feeRateSet() {
        return feeRateSet;
    }

    public boolean marketOpened() {
        return marketOpened;
    }

    public boolean seen(String orderId) {
        return seenOrderIds.contains(orderId);
    }

    public long nextSeq() {
        return nextSeq;
    }
}
