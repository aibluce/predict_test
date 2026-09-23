package exam;

import exam.Fill.Outcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** 单个账户的资金与持仓。所有读写必须在持有 lock() 的情况下进行。 */
public final class AccountLedger {

    static final BigDecimal FEE_RATE = new BigDecimal("0.002");
    static final int MONEY_SCALE = 6;

    /** 一张未完结买单的剩余冻结。卖单不冻结，上游撮合保证不超卖。 */
    static final class Reservation {
        BigDecimal remainingQty;
        final BigDecimal limitPrice;

        Reservation(BigDecimal qty, BigDecimal limitPrice) {
            this.remainingQty = qty;
            this.limitPrice = limitPrice;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private BigDecimal available;
    private BigDecimal frozen = BigDecimal.ZERO;
    private final Map<Outcome, BigDecimal> positions = new EnumMap<>(Outcome.class);
    private final Map<String, Reservation> reservations = new HashMap<>();

    public AccountLedger(BigDecimal initialAvailable) {
        this.available = initialAvailable;
        positions.put(Outcome.YES, BigDecimal.ZERO);
        positions.put(Outcome.NO, BigDecimal.ZERO);
    }

    public void lock() { lock.lock(); }

    public void unlock() { lock.unlock(); }

    /** 买单进入盘口前调用：把 qty × 限价 从可用挪到冻结。 */
    public void reserveBuy(String orderId, BigDecimal qty, BigDecimal limitPrice) {
        BigDecimal amount = qty.multiply(limitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (available.compareTo(amount) < 0) {
            throw new IllegalStateException("insufficient available for " + orderId);
        }
        available = available.subtract(amount);
        frozen = frozen.add(amount);
        reservations.put(orderId, new Reservation(qty, limitPrice));
    }

    /** 买方成交：从冻结里实扣成交金额，手续费从可用扣，持仓增加。 */
    public void settleBuy(String orderId, Outcome outcome, BigDecimal qty, BigDecimal execPrice) {
        Reservation r = reservations.get(orderId);
        BigDecimal amount = qty.multiply(execPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal fee = amount.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (available.compareTo(fee) < 0) {
            throw new IllegalStateException("insufficient available for fee " + orderId);
        }
        frozen = frozen.subtract(amount);
        available = available.subtract(fee);
        positions.put(outcome, positions.get(outcome).add(qty));
        r.remainingQty = r.remainingQty.subtract(qty);
        if (r.remainingQty.equals(BigDecimal.ZERO)) {
            reservations.remove(orderId);
        }
    }

    /** 卖方成交：持仓减少，收款减手续费进可用。 */
    public void settleSell(Outcome outcome, BigDecimal qty, BigDecimal execPrice) {
        BigDecimal held = positions.get(outcome);
        if (held.compareTo(qty) < 0) {
            throw new IllegalStateException("insufficient position " + outcome);
        }
        BigDecimal amount = qty.multiply(execPrice).setScale(MONEY_SCALE);
        BigDecimal fee = amount.multiply(FEE_RATE).setScale(MONEY_SCALE);
        positions.put(outcome, held.subtract(qty));
        available = available.add(amount).subtract(fee);
    }

    /** 撤单：剩余冻结全部回到可用。返回释放的金额。 */
    public BigDecimal releaseOnCancel(String orderId) {
        Reservation r = reservations.remove(orderId);
        BigDecimal release = r.remainingQty.multiply(r.limitPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        frozen = frozen.subtract(release);
        available = available.add(release);
        return release;
    }

    /** 提现前检查用：还有未完结订单就不能提取全部可用。 */
    public boolean hasOpenOrders() { return !reservations.isEmpty(); }

    public BigDecimal available() { return available; }

    public BigDecimal frozen() { return frozen; }

    public BigDecimal position(Outcome outcome) { return positions.get(outcome); }
}
