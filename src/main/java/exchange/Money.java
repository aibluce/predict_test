package exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额与价格的精度规则集中在这一个类，避免各处各写一份 setScale。
 *
 * <ul>
 *   <li>金额一律 scale = 4（题目第四层要求；费率 0 时与 2 位小数完全等价，所以前三层、
 *       第四层共用同一套实现）；</li>
 *   <li>价格 scale = 2，份额是整数张；</li>
 *   <li>手续费向下截断（{@link RoundingMode#DOWN}，金额非负时即 FLOOR）；</li>
 *   <li>买单手续费准备金向上取整（{@link RoundingMode#CEILING}）。</li>
 * </ul>
 */
public final class Money {

    public static final int SCALE = 4;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    public static final BigDecimal ONE = BigDecimal.ONE;

    private Money() {
    }

    public static BigDecimal scale4(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** amount = qty × price（qty 是整数张数，price 2 位小数，结果恰好 2 位）。 */
    public static BigDecimal amount(long qty, BigDecimal price) {
        return scale4(price.multiply(BigDecimal.valueOf(qty)));
    }

    /** 一方的成交手续费：自己的 amount × rate，向下截断到 4 位。 */
    public static BigDecimal fee(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(SCALE, RoundingMode.DOWN);
    }

    /**
     * 买单准备金：reserve(q, L) = q × L + ceil4(q × L × rate)，reserve(0, L) = 0。
     * 费率 0 时就是 q × L，因此第二层与第四层共用这一个公式。
     */
    public static BigDecimal reserve(long qty, BigDecimal limit, BigDecimal rate) {
        if (qty <= 0) {
            return ZERO;
        }
        BigDecimal base = limit.multiply(BigDecimal.valueOf(qty));
        BigDecimal feeReserve = base.multiply(rate).setScale(SCALE, RoundingMode.CEILING);
        return scale4(base).add(feeReserve);
    }

    /** 金额一律 4 位小数输出。 */
    public static String formatAmount(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 价格 2 位小数输出。 */
    public static String formatPrice(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
