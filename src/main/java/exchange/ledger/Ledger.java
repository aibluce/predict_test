package exchange.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.TreeMap;

import exchange.AccountView;
import exchange.Money;
import exchange.Outcome;

/**
 * 第二层账本：每个账户的 available / frozen / frozenShares / positions，加市场的抵押池。
 *
 * <p>所有对外方法的金额参数都必须是已经算好的精确值（scale 4），这个类不自己决定舍入。
 * 买单的冻结额一律等于 {@link Money#reserve}，所以「frozen = 所有未完结买单的 reserve 之和」
 * 是构造性成立的。
 */
public final class Ledger {

    private final TreeMap<String, AccountState> accounts = new TreeMap<>();
    /** 铸造出来的每一对份额对应池里 1 U，销毁时拿回来。 */
    private BigDecimal collateralPool = Money.ZERO;
    private BigDecimal feeAccount = Money.ZERO;

    public boolean exists(String accountId) {
        return accounts.containsKey(accountId);
    }

    public void deposit(String accountId, BigDecimal amount) {
        if (accountId == null || accountId.isEmpty()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("deposit 金额必须为正：" + amount);
        }
        AccountState account = accounts.computeIfAbsent(accountId, AccountState::new);
        account.available = account.available.add(Money.scale4(amount));
    }

    public BigDecimal available(String accountId) {
        AccountState account = accounts.get(accountId);
        return account == null ? Money.ZERO : account.available;
    }

    public BigDecimal frozen(String accountId) {
        AccountState account = accounts.get(accountId);
        return account == null ? Money.ZERO : account.frozen;
    }

    public long frozenShares(String accountId, Outcome outcome) {
        AccountState account = accounts.get(accountId);
        return account == null ? 0L : account.frozenShares(outcome);
    }

    public long position(String accountId, Outcome outcome) {
        AccountState account = accounts.get(accountId);
        return account == null ? 0L : account.position(outcome);
    }

    /** 没有 deposit 过的账户返回全 0 视图，不会因此创建账户。 */
    public AccountView view(String accountId) {
        AccountState account = accounts.get(accountId);
        if (account == null) {
            return new AccountView(accountId, Money.ZERO, Money.ZERO, 0L, 0L, 0L, 0L);
        }
        return new AccountView(accountId, account.available, account.frozen,
                account.frozenYes, account.frozenNo, account.posYes, account.posNo);
    }

    /** 已 deposit 过的账户，按 accountId 字典序。 */
    public List<String> accountIds() {
        return List.copyOf(accounts.keySet());
    }

    // ---- 买单：available -> frozen ----

    public void freezeBuy(String accountId, BigDecimal amount) {
        AccountState account = require(accountId);
        if (account.available.compareTo(amount) < 0) {
            throw new IllegalStateException("冻结超过可用余额：" + accountId);
        }
        account.available = account.available.subtract(amount);
        account.frozen = account.frozen.add(amount);
    }

    /**
     * 买方成交：本单原先冻结 frozenBefore，实扣 amount + fee，成交后该单冻结重定为 frozenAfter，
     * 差额回到 available。
     */
    public void settleBuy(String accountId, Outcome outcome, long qty, BigDecimal amount, BigDecimal fee,
                          BigDecimal frozenBefore, BigDecimal frozenAfter) {
        AccountState account = require(accountId);
        BigDecimal release = frozenBefore.subtract(amount).subtract(fee).subtract(frozenAfter);
        if (release.signum() < 0) {
            throw new IllegalStateException("买单成交释放额为负（准备金不足）：" + accountId
                    + " before=" + frozenBefore + " amount=" + amount + " fee=" + fee + " after=" + frozenAfter);
        }
        if (account.frozen.compareTo(frozenBefore) < 0) {
            throw new IllegalStateException("账户冻结额小于该单冻结额：" + accountId);
        }
        account.frozen = account.frozen.subtract(frozenBefore).add(frozenAfter);
        account.available = account.available.add(release);
        account.addPosition(outcome, qty);
    }

    /** 撤买单：释放该单剩余冻结。 */
    public void releaseBuy(String accountId, BigDecimal amount) {
        AccountState account = require(accountId);
        if (account.frozen.compareTo(amount) < 0) {
            throw new IllegalStateException("账户冻结额小于待释放额：" + accountId);
        }
        account.frozen = account.frozen.subtract(amount);
        account.available = account.available.add(amount);
    }

    // ---- 卖单：positions -> frozenShares ----

    public void freezeShares(String accountId, Outcome outcome, long qty) {
        AccountState account = require(accountId);
        if (account.position(outcome) < qty) {
            throw new IllegalStateException("冻结份额超过持仓：" + accountId + " " + outcome);
        }
        account.addPosition(outcome, -qty);
        account.addFrozenShares(outcome, qty);
    }

    /** 卖方成交：冻结份额扣掉 qty，收款减手续费进 available。 */
    public void settleSell(String accountId, Outcome outcome, long qty, BigDecimal amount, BigDecimal fee) {
        AccountState account = require(accountId);
        if (account.frozenShares(outcome) < qty) {
            throw new IllegalStateException("冻结份额不足：" + accountId + " " + outcome);
        }
        account.addFrozenShares(outcome, -qty);
        account.available = account.available.add(amount).subtract(fee);
    }

    /** 撤卖单：冻结份额回到 positions。 */
    public void releaseShares(String accountId, Outcome outcome, long qty) {
        AccountState account = require(accountId);
        if (account.frozenShares(outcome) < qty) {
            throw new IllegalStateException("冻结份额不足：" + accountId + " " + outcome);
        }
        account.addFrozenShares(outcome, -qty);
        account.addPosition(outcome, qty);
    }

    // ---- 抵押池与手续费账户 ----

    /** 铸造：抵押池增加 qty × 1 U。 */
    public void poolMint(long qty) {
        collateralPool = collateralPool.add(Money.amount(qty, Money.ONE));
    }

    /** 销毁：抵押池减少 qty × 1 U。 */
    public void poolMerge(long qty) {
        collateralPool = collateralPool.subtract(Money.amount(qty, Money.ONE));
        if (collateralPool.signum() < 0) {
            throw new IllegalStateException("抵押池为负");
        }
    }

    public void addFee(BigDecimal fee) {
        feeAccount = feeAccount.add(fee);
    }

    public BigDecimal collateralPool() {
        return collateralPool;
    }

    public BigDecimal feeAccount() {
        return feeAccount;
    }

    // ---- 第三层：从快照恢复 ----

    /** 恢复一个账户。余额全为 0 也照样创建，保持「这个账户存在」这件事。 */
    public void restoreAccount(AccountView view) {
        AccountState account = accounts.computeIfAbsent(view.accountId(), AccountState::new);
        account.available = Money.scale4(view.available());
        account.frozen = Money.scale4(view.frozen());
        account.frozenYes = view.frozenYes();
        account.frozenNo = view.frozenNo();
        account.posYes = view.posYes();
        account.posNo = view.posNo();
    }

    public void restorePoolAndFees(BigDecimal pool, BigDecimal fees) {
        this.collateralPool = Money.scale4(pool);
        this.feeAccount = Money.scale4(fees);
    }

    private AccountState require(String accountId) {
        AccountState account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("账户不存在：" + accountId);
        }
        return account;
    }
}
