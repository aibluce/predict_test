package exchange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import exchange.core.EventSourcedExchange;

/** 单市场、纯内存交易所。实现见 {@code exchange.core.EventSourcedExchange}。 */
public interface Exchange {

    void setFeeRate(BigDecimal rate);

    void deposit(String accountId, BigDecimal amount);

    /** 返回这次提交产生的事件，可能只有一个 OrderRejected */
    List<Event> submit(Order order);

    List<Event> cancel(String orderId);

    Optional<BigDecimal> bestBid(Outcome outcome);

    Optional<BigDecimal> bestAsk(Outcome outcome);

    AccountView account(String accountId);

    BigDecimal collateralPool();

    BigDecimal feeAccount();

    Snapshot snapshot();

    static Exchange restore(Snapshot s) {
        return EventSourcedExchange.restore(s);
    }

    static Exchange replay(List<Event> events) {
        return EventSourcedExchange.replay(events);
    }
}
