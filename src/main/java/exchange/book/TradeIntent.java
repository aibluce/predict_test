package exchange.book;

import java.math.BigDecimal;

import exchange.Outcome;
import exchange.Side;
import exchange.TradeType;

/**
 * 撮合的中间结果：撮合层只产出它，入账由事件层做。
 * taker 是本次进来的订单，maker 是原先挂在盘口上的订单。
 */
public record TradeIntent(TradeType type,
                          long qty,
                          String takerOrderId,
                          Outcome takerOutcome,
                          Side takerSide,
                          BigDecimal takerPrice,
                          String makerOrderId,
                          String makerAccountId,
                          Outcome makerOutcome,
                          Side makerSide,
                          BigDecimal makerPrice) {
}
