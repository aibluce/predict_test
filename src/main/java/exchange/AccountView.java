package exchange;

import java.math.BigDecimal;

/** 账户只读视图：available、frozen、frozenShares、positions。份额都是整数张。 */
public record AccountView(String accountId,
                          BigDecimal available,
                          BigDecimal frozen,
                          long frozenYes,
                          long frozenNo,
                          long posYes,
                          long posNo) {
}
