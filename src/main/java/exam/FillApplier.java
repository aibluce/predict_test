package exam;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 对外入口。onFill 由 Kafka 消费线程池并发调用，其余方法由接口线程调用，彼此并发。 */
public final class FillApplier {

    private final Set<String> appliedFillIds = new HashSet<>();
    private final MarketSettler settler;
    private final Map<String, AccountLedger> ledgers;

    public FillApplier(MarketSettler settler, Map<String, AccountLedger> ledgers) {
        this.settler = settler;
        this.ledgers = ledgers;
    }

    /** 入账一笔成交。同一 fillId 成功入账后再推送，不得重复入账。 */
    public void onFill(Fill f) {
        if (!appliedFillIds.add(f.fillId)) {
            return;
        }
        settler.apply(f);
    }

    public void onNewBuyOrder(String accountId, String orderId, BigDecimal qty, BigDecimal limitPrice) {
        settler.onNewBuyOrder(accountId, orderId, qty, limitPrice);
    }

    public void onCancel(String accountId, String orderId) {
        settler.onCancel(accountId, orderId);
    }

    /** 提现前检查：没有未完结订单才允许提取全部可用。 */
    public boolean canWithdrawAll(String accountId) {
        AccountLedger ledger = ledgers.get(accountId);
        ledger.lock();
        try {
            return !ledger.hasOpenOrders();
        } finally {
            ledger.unlock();
        }
    }
}
