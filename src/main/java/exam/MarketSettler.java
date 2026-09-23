package exam;

import exam.Fill.Outcome;
import exam.Fill.Side;
import exam.Fill.Type;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** 一个市场的成交入账、抵押池、手续费账户和市场级冻结总额。 */
public final class MarketSettler {

    private static final BigDecimal FEE_RATE = new BigDecimal(0.002);

    private final ReentrantLock marketLock = new ReentrantLock();
    private final Map<String, AccountLedger> ledgers;
    /** 铸造出来的每一对份额对应池里 1 U，销毁时拿回来 */
    private BigDecimal collateralPool = BigDecimal.ZERO;
    private BigDecimal feeAccount = BigDecimal.ZERO;
    /** 本市场所有账户当前买单冻结总额，风控用来看敞口 */
    private BigDecimal reservedTotal = BigDecimal.ZERO;

    public MarketSettler(Map<String, AccountLedger> ledgers) {
        this.ledgers = ledgers;
    }

    /** 买单下单：先在账户上冻结，再登记到市场冻结总额。 */
    public void onNewBuyOrder(String accountId, String orderId, BigDecimal qty, BigDecimal limitPrice) {
        AccountLedger ledger = ledgers.get(accountId);
        ledger.lock();
        try {
            ledger.reserveBuy(orderId, qty, limitPrice);
            marketLock.lock();
            try {
                reservedTotal = reservedTotal.add(money(qty, limitPrice));
            } finally {
                marketLock.unlock();
            }
        } finally {
            ledger.unlock();
        }
    }

    /** 撤单：账户解冻，市场冻结总额同步减少。 */
    public void onCancel(String accountId, String orderId) {
        AccountLedger ledger = ledgers.get(accountId);
        ledger.lock();
        try {
            BigDecimal released = ledger.releaseOnCancel(orderId);
            marketLock.lock();
            try {
                reservedTotal = reservedTotal.subtract(released);
            } finally {
                marketLock.unlock();
            }
        } finally {
            ledger.unlock();
        }
    }

    /** 成交入账。一笔成交要么两个账户都入账，要么都不入账。 */
    public void apply(Fill f) {
        marketLock.lock();
        try {
            Outcome makerOutcome = f.type == Type.NORMAL ? f.takerOutcome : other(f.takerOutcome);
            Side makerSide = f.type == Type.NORMAL ? other(f.takerSide) : Side.BUY;

            settleSide(f.takerAccount, f.takerOrderId, f.takerOutcome, f.takerSide, f.qty, f.takerPrice);
            settleSide(f.makerAccount, f.makerOrderId, makerOutcome, makerSide, f.qty, f.makerPrice);

            BigDecimal takerAmount = money(f.qty, f.takerPrice);
            BigDecimal makerAmount = money(f.qty, f.makerPrice);
            if (f.takerSide == Side.BUY) {
                reservedTotal = reservedTotal.subtract(money(f.qty, f.takerLimit));
            }
            if (makerSide == Side.BUY) {
                reservedTotal = reservedTotal.subtract(money(f.qty, f.makerLimit));
            }
            if (f.type == Type.MINT) {
                collateralPool = collateralPool.add(f.qty);
            } else if (f.type == Type.MERGE) {
                collateralPool = collateralPool.subtract(f.qty);
            }
            BigDecimal totalFee = takerAmount.add(makerAmount)
                    .multiply(FEE_RATE)
                    .setScale(AccountLedger.MONEY_SCALE, RoundingMode.HALF_UP);
            feeAccount = feeAccount.add(totalFee);
        } finally {
            marketLock.unlock();
        }
    }

    private void settleSide(String accountId, String orderId, Outcome outcome, Side side,
                            BigDecimal qty, BigDecimal price) {
        AccountLedger ledger = ledgers.get(accountId);
        ledger.lock();
        try {
            if (side == Side.BUY) {
                ledger.settleBuy(orderId, outcome, qty, price);
            } else {
                ledger.settleSell(outcome, qty, price);
            }
        } finally {
            ledger.unlock();
        }
    }

    private static BigDecimal money(BigDecimal qty, BigDecimal price) {
        return qty.multiply(price).setScale(AccountLedger.MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    private static Outcome other(Outcome o) { return o == Outcome.YES ? Outcome.NO : Outcome.YES; }

    private static Side other(Side s) { return s == Side.BUY ? Side.SELL : Side.BUY; }

    public BigDecimal collateralPool() { return collateralPool; }

    public BigDecimal feeAccount() { return feeAccount; }

    public BigDecimal reservedTotal() { return reservedTotal; }
}
