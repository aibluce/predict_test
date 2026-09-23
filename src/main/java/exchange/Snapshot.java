package exchange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 第三层：状态快照。这里是**状态的完整深拷贝**，不是事件列表：
 * 账户（含「已存在但余额为 0」的账户）、盘口上每张挂单（含未成交张数与接受顺序 seq，
 * 所以同价 FIFO 也被保存）、抵押池、手续费账户、费率、开市标记、出现过的 orderId、seq 计数器。
 *
 * <p>所有金额在这里统一成 4 位小数（{@link Money#SCALE}），所以两份快照的 {@link #equals(Object)}
 * 可以直接当「状态完全一致」的判据用。列表一律排序存放：账户按 accountId 字典序、
 * 挂单按 seq 升序、orderId 按字典序。
 */
public final class Snapshot {

    /** 盘口上一张挂单的完整状态。 */
    public record OrderState(String orderId, String accountId, Outcome outcome, Side side,
                             BigDecimal price, long remaining, long seq) {
    }

    private final List<AccountView> accounts;
    private final List<OrderState> orders;
    private final BigDecimal collateralPool;
    private final BigDecimal feeAccount;
    private final BigDecimal feeRate;
    private final boolean feeRateSet;
    private final boolean marketOpened;
    private final List<String> seenOrderIds;
    private final long nextSeq;

    public Snapshot(List<AccountView> accounts,
                    List<OrderState> orders,
                    BigDecimal collateralPool,
                    BigDecimal feeAccount,
                    BigDecimal feeRate,
                    boolean feeRateSet,
                    boolean marketOpened,
                    List<String> seenOrderIds,
                    long nextSeq) {
        List<AccountView> accountsCopy = new ArrayList<>(accounts.size());
        for (AccountView view : accounts) {
            accountsCopy.add(new AccountView(view.accountId(), Money.scale4(view.available()),
                    Money.scale4(view.frozen()), view.frozenYes(), view.frozenNo(),
                    view.posYes(), view.posNo()));
        }
        accountsCopy.sort((a, b) -> a.accountId().compareTo(b.accountId()));

        List<OrderState> ordersCopy = new ArrayList<>(orders.size());
        for (OrderState order : orders) {
            ordersCopy.add(new OrderState(order.orderId(), order.accountId(), order.outcome(), order.side(),
                    Money.scale4(order.price()), order.remaining(), order.seq()));
        }
        ordersCopy.sort((a, b) -> Long.compare(a.seq(), b.seq()));

        List<String> seenCopy = new ArrayList<>(seenOrderIds);
        seenCopy.sort(String::compareTo);

        this.accounts = List.copyOf(accountsCopy);
        this.orders = List.copyOf(ordersCopy);
        this.collateralPool = Money.scale4(collateralPool);
        this.feeAccount = Money.scale4(feeAccount);
        this.feeRate = Money.scale4(feeRate);
        this.feeRateSet = feeRateSet;
        this.marketOpened = marketOpened;
        this.seenOrderIds = List.copyOf(seenCopy);
        this.nextSeq = nextSeq;
    }

    /** 已 deposit 过的账户，按 accountId 字典序。 */
    public List<AccountView> accounts() {
        return accounts;
    }

    /** 盘口上的挂单，按接受顺序（seq）升序。 */
    public List<OrderState> orders() {
        return orders;
    }

    public BigDecimal collateralPool() {
        return collateralPool;
    }

    public BigDecimal feeAccount() {
        return feeAccount;
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

    public List<String> seenOrderIds() {
        return seenOrderIds;
    }

    public long nextSeq() {
        return nextSeq;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Snapshot snapshot)) {
            return false;
        }
        return feeRateSet == snapshot.feeRateSet
                && marketOpened == snapshot.marketOpened
                && nextSeq == snapshot.nextSeq
                && accounts.equals(snapshot.accounts)
                && orders.equals(snapshot.orders)
                && collateralPool.equals(snapshot.collateralPool)
                && feeAccount.equals(snapshot.feeAccount)
                && feeRate.equals(snapshot.feeRate)
                && seenOrderIds.equals(snapshot.seenOrderIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accounts, orders, collateralPool, feeAccount, feeRate,
                feeRateSet, marketOpened, seenOrderIds, nextSeq);
    }

    /** 规范化文本，用于断言失败时看清差异。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("feeRate=").append(feeRate.toPlainString())
                .append(" feeRateSet=").append(feeRateSet)
                .append(" marketOpened=").append(marketOpened)
                .append(" nextSeq=").append(nextSeq)
                .append(" pool=").append(Money.formatAmount(collateralPool))
                .append(" fees=").append(Money.formatAmount(feeAccount))
                .append('\n');
        for (AccountView view : accounts) {
            sb.append("account ").append(view.accountId())
                    .append(' ').append(Money.formatAmount(view.available()))
                    .append(' ').append(Money.formatAmount(view.frozen()))
                    .append(' ').append(view.frozenYes()).append(' ').append(view.frozenNo())
                    .append(' ').append(view.posYes()).append(' ').append(view.posNo()).append('\n');
        }
        for (OrderState order : orders) {
            sb.append("order ").append(order.orderId())
                    .append(' ').append(order.accountId())
                    .append(' ').append(order.outcome()).append(' ').append(order.side())
                    .append(' ').append(Money.formatPrice(order.price()))
                    .append(" x").append(order.remaining()).append(" seq=").append(order.seq()).append('\n');
        }
        sb.append("seen=").append(seenOrderIds);
        return sb.toString();
    }
}
