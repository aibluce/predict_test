package exchange;

import java.math.BigDecimal;

/**
 * 限价单。price 2 位小数、范围 [0.01, 0.99]，qty 为正整数（份额张数）。
 * 合法性由上游保证，核心不做边界校验。
 */
public record Order(String orderId, String accountId, Outcome outcome, Side side,
                    BigDecimal price, long qty) {
}
