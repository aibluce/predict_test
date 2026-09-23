package exchange.book;

import java.math.BigDecimal;

import exchange.Outcome;
import exchange.Side;

/** 挂在盘口上的一张限价单。remaining 是未成交张数，全部成交后由盘口移除。 */
public final class RestingOrder {

    private final String orderId;
    private final String accountId;
    private final Outcome outcome;
    private final Side side;
    /** 下单限价，成交后重定冻结、算价差都用它。 */
    private final BigDecimal price;
    /** 接受顺序，同价时间优先按它比较。 */
    private final long seq;
    private long remaining;

    public RestingOrder(String orderId, String accountId, Outcome outcome, Side side,
                        BigDecimal price, long qty, long seq) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.outcome = outcome;
        this.side = side;
        this.price = price;
        this.seq = seq;
        this.remaining = qty;
    }

    public String orderId() {
        return orderId;
    }

    public String accountId() {
        return accountId;
    }

    public Outcome outcome() {
        return outcome;
    }

    public Side side() {
        return side;
    }

    public BigDecimal price() {
        return price;
    }

    public long seq() {
        return seq;
    }

    public long remaining() {
        return remaining;
    }

    /** 成交后扣减未成交张数。 */
    public void reduce(long qty) {
        if (qty <= 0 || qty > remaining) {
            throw new IllegalArgumentException("bad reduce " + qty + " on " + orderId + " remaining=" + remaining);
        }
        remaining -= qty;
    }

    @Override
    public String toString() {
        return orderId + "(" + side + " " + outcome + " @" + price + " x" + remaining + ")";
    }
}
