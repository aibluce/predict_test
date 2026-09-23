package exchange.ledger;

import java.math.BigDecimal;

import exchange.Money;
import exchange.Outcome;

/** 单个账户的资金与持仓。账户从第一次 deposit 起才存在。 */
final class AccountState {

    private final String accountId;
    BigDecimal available = Money.ZERO;
    BigDecimal frozen = Money.ZERO;
    long frozenYes;
    long frozenNo;
    long posYes;
    long posNo;

    AccountState(String accountId) {
        this.accountId = accountId;
    }

    String accountId() {
        return accountId;
    }

    long frozenShares(Outcome outcome) {
        return outcome == Outcome.YES ? frozenYes : frozenNo;
    }

    long position(Outcome outcome) {
        return outcome == Outcome.YES ? posYes : posNo;
    }

    void addFrozenShares(Outcome outcome, long delta) {
        if (outcome == Outcome.YES) {
            frozenYes += delta;
        } else {
            frozenNo += delta;
        }
    }

    void addPosition(Outcome outcome, long delta) {
        if (outcome == Outcome.YES) {
            posYes += delta;
        } else {
            posNo += delta;
        }
    }
}
