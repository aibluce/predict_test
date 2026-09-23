# Java 工程师笔试

## 先读这一段

- 可以用任何工具
- 题二做扎实前两层就够进复盘。做完的部分必须能跑、有说明。没做的层在 README 里写明，不要交一个跑不通的半成品。
- 背景是二元预测市场（YES / NO），所有规则都写在题里，不需要行业背景。
- 两个词全文要分清：**qty 是份额数量**（张），**amount 是金额**（U），amount = qty × price。
- 题目里写明的格式、字数、位数都是硬要求。程序输出会被逐字节比对，文字答案超字数的那一题不给分。

交付内容：

1. 代码（zip 或 git 仓库链接），Java 17+，第三方依赖只允许 JUnit。
2. README，固定四节：
   - 我做了哪些假设，为什么。
   - 我放弃了哪个方案，为什么。
   - 我自己的随机测试找出了我几个 bug，分别是什么。写 0 个不扣分，但复盘会问。
   - 哪些代码是 AI 生成的，我改了什么，为什么改。
3. 题三、题四、题五的文字答案。
4. 可以附上一段自己的解释说明。

---

## 题一：审代码

下面四个类是一个市场的成交入账模块。先读规格，再读代码，找出代码违反规格的地方。

### 规格

1. 账户有 available（可用）、frozen（冻结）、YES 和 NO 持仓。qty 保留 2 位小数，price 保留 3 位小数，金额保留 6 位小数，HALF_UP。所有数值都是从消息里解析出来的 BigDecimal，带着这些 scale。
2. 买单下单时冻结 qty × 限价。成交按成交价实扣，成交价不高于限价，差额 (qty × 限价 − qty × 成交价) 从冻结回到可用。撤单时剩余冻结全部回到可用。卖单不冻结份额，上游撮合保证不超卖。
3. 手续费：每一方 fee = 自己的 amount × 0.2%，HALF_UP 保留 6 位，从可用扣。
4. 三种成交类型：
   - NORMAL：同一 outcome 一买一卖，两边成交价相同。
   - MINT：买 YES 和买 NO 撮合，两人各自付钱，系统铸造一对份额，抵押池增加 qty × 1。
   - MERGE：卖 YES 和卖 NO 撮合，两人各自交出份额、各自收钱，抵押池减少 qty × 1。
   - MINT 和 MERGE 时 takerPrice + makerPrice = 1。
5. 手续费账户每笔成交记入 (takerAmount + makerAmount) × 0.2%，HALF_UP 保留 6 位。
6. 守恒：所有账户 (available + frozen) 之和 + 抵押池 + 手续费账户，在没有充值提现时是常数。
7. 每个账户的 frozen 恒等于它所有未完结买单的剩余冻结之和。
8. 一笔成交要么两个账户都入账，要么都不入账。入账失败必须让调用方知道，上游会用**同一 fillId** 重推；已经成功入账的 fillId 再推送，不得重复入账。
9. 账户没有未完结订单时，可以提取全部可用余额。
10. `onFill` 由消费线程池并发调用；`onNewBuyOrder`、`onCancel`、`canWithdrawAll` 由接口线程调用。四者彼此并发。
11. 所有参数由上游保证：不为空，qty > 0，0 < price < 1，账户和订单都存在。不需要在这里校验。

### 代码

代码在 `题一代码/exam/` 下，四个文件，也附在下面。

#### Fill.java

```java
package exam;

import java.math.BigDecimal;

/** 上游撮合推送过来的一笔成交。所有字段由上游保证非空且合法。 */
public final class Fill {

    public enum Outcome { YES, NO }
    public enum Side { BUY, SELL }
    /** NORMAL：同一 outcome 一买一卖；MINT：买 YES + 买 NO 铸造一对；MERGE：卖 YES + 卖 NO 销毁一对 */
    public enum Type { NORMAL, MINT, MERGE }

    public final String fillId;
    public final Type type;
    public final String takerAccount;
    public final String makerAccount;
    public final String takerOrderId;
    public final String makerOrderId;
    /** taker 的 outcome。NORMAL 时 maker 同 outcome；MINT / MERGE 时 maker 是另一个 outcome */
    public final Outcome takerOutcome;
    /** taker 的方向。NORMAL 时 maker 反向；MINT 双方都是 BUY；MERGE 双方都是 SELL */
    public final Side takerSide;
    /** 份额数量，2 位小数 */
    public final BigDecimal qty;
    /** 各自的成交价，3 位小数。MINT / MERGE 时 takerPrice + makerPrice = 1 */
    public final BigDecimal takerPrice;
    public final BigDecimal makerPrice;
    /** 各自下单时的限价。买方成交时用来算该解冻多少 */
    public final BigDecimal takerLimit;
    public final BigDecimal makerLimit;

    public Fill(String fillId, Type type,
                String takerAccount, String makerAccount,
                String takerOrderId, String makerOrderId,
                Outcome takerOutcome, Side takerSide,
                BigDecimal qty, BigDecimal takerPrice, BigDecimal makerPrice,
                BigDecimal takerLimit, BigDecimal makerLimit) {
        this.fillId = fillId;
        this.type = type;
        this.takerAccount = takerAccount;
        this.makerAccount = makerAccount;
        this.takerOrderId = takerOrderId;
        this.makerOrderId = makerOrderId;
        this.takerOutcome = takerOutcome;
        this.takerSide = takerSide;
        this.qty = qty;
        this.takerPrice = takerPrice;
        this.makerPrice = makerPrice;
        this.takerLimit = takerLimit;
        this.makerLimit = makerLimit;
    }
}
```

#### AccountLedger.java

```java
package exam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import exam.Fill.Outcome;

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
```

#### MarketSettler.java

```java
package exam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import exam.Fill.Outcome;
import exam.Fill.Side;
import exam.Fill.Type;

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
```

#### FillApplier.java

```java
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
```

### 要交什么

每个问题一条，格式固定：

- 在哪个类哪一行。如果问题是几个类合起来才出现的，把涉及的位置都写上。
- 违反规格第几条。
- 一个带数字的触发例子：初始状态、来了什么、错误结果是什么。并发问题给出两个线程各自在做什么。
- 怎么改，一两句。

代码之外你还发现了别的问题，单独写一节，同样要用数字证明。

---

## 题二：四层小交易所

实现一个单市场、纯内存的交易系统，分四层。每一层都有必须守住的不变量，下一层依赖上一层。

### 第一层：撮合

**份额与价格**

- 两种份额 YES、NO。价格最小变动 0.01，范围 [0.01, 0.99]。qty 为正整数。
- 一张 YES 加一张 NO 合起来等于 1 U。

**订单**

- 字段：orderId、accountId、outcome、side（BUY / SELL）、price、qty。只有限价单。
- 不能立即成交的部分留在盘口，价格优先、同价时间优先。
- 按 orderId 撤单，撤掉未成交的剩余部分。

**四种成交**（必须全部实现）

| 一方 | 另一方 | 成交条件 | 成交后 |
|---|---|---|---|
| BUY YES @p | SELL YES @q | q ≤ p | YES 从卖方转到买方，买方付钱卖方收钱 |
| BUY NO @p | SELL NO @q | q ≤ p | NO 同上 |
| BUY YES @p | BUY NO @r | p + r ≥ 1 | 系统铸造一对：YES 给 YES 买方，NO 给 NO 买方，两人各自付钱 |
| SELL YES @p | SELL NO @r | p + r ≤ 1 | 系统销毁一对：两人各自交出份额，各自收钱 |

哪一方先在盘口、哪一方后来，都可以匹配。

**盘口查询**

- `bestBid(outcome)`：一张该 outcome 的份额现在能立即卖出的最高价。
- `bestAsk(outcome)`：一张该 outcome 的份额现在能立即买入的最低价。
- 这两个值要把铸造和销毁算进去。

**第一层不变量**

- 铸造成交两边付款之和恰好等于 qty × 1；销毁成交两边收款之和恰好等于 qty × 1。
- 任何一方的成交价不比自己的限价差。

### 第二层：账本

每个账户有 available、frozen、frozenShares[YES/NO]、positions[YES/NO]。资金只能通过 `deposit(accountId, amount)` 进入系统。账户从第一次 deposit 起才存在；没有 deposit 过的账户提交订单，按资金或份额不足拒绝，并且不因此变成一个存在的账户。

- 买单提交时冻结 qty × 限价，从 available 挪到 frozen。卖单提交时冻结 qty 张对应 outcome 的份额，从 positions 挪到 frozenShares。
- 资金或份额不够，订单整体拒绝，不允许部分接受。
- orderId 只要在之前任何一次 submit 里出现过（不管那次是被接受还是被拒绝），再次出现就以 DUPLICATE_ORDER_ID 拒绝。这条检查先于资金和份额检查。
- 以上两条是本题仅有的边界校验。
- 买方成交：从 frozen 实扣 qty × 成交价，成交价比限价好的差额回到 available。卖方成交：从 frozenShares 扣 qty，收款进 available。
- 铸造：两人付款进抵押池。销毁：抵押池付给两人。
- 撤单：剩余冻结（钱或份额）全部回到 available 或 positions。
- 第二层本身不收手续费，手续费在第四层加。

**第二层不变量**

- 所有账户 (available + frozen) 之和 + 抵押池 = 所有 deposit 之和。
- 所有账户 (positions[YES] + frozenShares[YES]) 之和 = 所有账户 (positions[NO] + frozenShares[NO]) 之和 = 抵押池金额。份额只能来自铸造。
- 每个账户 frozen = 它所有未完结买单的剩余冻结之和；frozenShares 同理对未完结卖单。

### 第三层：事件日志

- 系统所有状态变化只能由事件驱动：`submit` 和 `cancel` 先生成事件列表（比如 OrderAccepted、OrderRejected、Trade、OrderCancelled、Deposit），再把事件应用到状态。事件类型和字段你定。
- 提供 `replay(events)`：从空状态把事件全部应用一遍，得到的状态和原来完全一致。
- 提供 `snapshot()` 和 `restore(snapshot)`：在任意第 k 个事件处快照，restore 后再应用第 k+1 个起的事件，得到的状态和一次性 replay 全部事件完全一致。

**第三层不变量**

- 同一事件序列，重放多少次结果都一致，和快照位置无关。

### 第四层：手续费

开市前（第一张订单被接受之前）可以调用一次 `setFeeRate(rate)`，不调用则费率为 0。费率生效后：

- 所有金额精度从 2 位变成 4 位小数。价格仍是 2 位，qty 仍是整数。
- 每笔成交每一方：fee = 自己的 amount × rate，**向下截断**到 4 位。买方的 fee 从冻结里扣，卖方的 fee 从收款里扣，两边的 fee 都进手续费账户。
- 买单要多冻一笔手续费准备金。定义 reserve(q, L) = q × L + (q × L × rate **向上取整**到 4 位)，reserve(0, L) = 0。提交时 available 不足 reserve(qty, 限价) 就整单拒绝；接受时冻结 reserve(qty, 限价)。
- 买方每笔成交：从 frozen 扣掉 amount + fee，然后把这张单的冻结重定为 reserve(剩余 qty, 限价)，多出来的部分回到 available。
- 撤买单：释放 reserve(剩余 qty, 限价)。
- 铸造和销毁进出抵押池的仍然恰好是 qty × 1，手续费不进池。

**第四层不变量**

- 所有账户 (available + frozen) 之和 + 抵押池 + 手续费账户 = 所有 deposit 之和，精确相等。
- 每个账户 frozen = 它所有未完结买单的 reserve(剩余 qty, 限价) 之和。
- 任何一笔成交入账都不会因为买方钱不够而失败，回到 available 的那部分永远不为负。在 README 里证明这一点，并说明如果 fee 改成四舍五入会在什么数字上出事。
- 费率、手续费账户同样只能由事件改变，replay 和 snapshot / restore 的要求对它们同样成立。

### 接口

```java
public interface Exchange {
    void setFeeRate(BigDecimal rate);
    void deposit(String accountId, BigDecimal amount);
    /** 返回这次提交产生的事件，可能只有一个 OrderRejected */
    List<Event> submit(Order order);
    List<Event> cancel(String orderId);
    Optional<BigDecimal> bestBid(Outcome outcome);
    Optional<BigDecimal> bestAsk(Outcome outcome);
    AccountView account(String accountId);   // available, frozen, frozenShares, positions
    BigDecimal collateralPool();
    BigDecimal feeAccount();
    Snapshot snapshot();
    static Exchange restore(Snapshot s) { ... }
    static Exchange replay(List<Event> events) { ... }
}
```

`Order`、`Event`、`Snapshot`、`AccountView` 由你定义。`Trade` 事件至少要能看出：哪两个订单、成交类型、qty、**每一方**的成交价、amount 和 fee。

### 走一遍例子

1. A、B、C 各 deposit 100。
2. A 提交 BUY YES 10 @0.60。冻结 6.00，A available 94.00，frozen 6.00。挂在盘口。
3. B 提交 SELL YES 4 @0.60。B 没有 YES，整单拒绝。
4. C 提交 BUY NO 8 @0.40。冻结 3.20。0.60 + 0.40 ≥ 1，与 A 铸造 8 张：A 付 4.80，C 付 3.20，抵押池 8.00。A 得 8 YES，C 得 8 NO。A frozen 剩 1.20（2 张 × 0.60），C frozen 0。
5. 此时盘口只剩 A 的 BUY YES 2 @0.60。`bestAsk(NO)` = 0.40：一张 NO 想立即买到，出 0.40 就能和它铸造。`bestBid(NO)` 为空：卖 NO 只能配 BUY NO 或者和 SELL YES 销毁，两者都没有。
6. 请你在 README 里用同样的方式走一遍这时的 `bestBid(YES)` 和 `bestAsk(YES)`。

### 测试

必须有，分两类：

- 定向用例：四种成交各一个、三种拒单、部分成交后撤单解冻、价格优先、时间优先、快照恢复、带手续费的部分成交。
- 随机测试：随机生成至少 10 万次 deposit / submit / cancel，每 1000 次检查一次第二层和第四层的不变量；在随机位置快照一次，比对 restore + 后续重放、全量重放、实时运行 三者的最终状态。这个测试找出了你几个 bug，写进 README。

### 性能

10 万次随机操作含不变量检查，笔记本上 5 秒以内。每笔订单不能对整个盘口做线性扫描。

### 评分适配器

评分时会用同一份操作脚本跑你的实现和参考实现，逐行比对输出。请提供一个 `main`，读脚本文件路径，把结果打到标准输出。

脚本每行一条。`FEE_RATE` 最多一行，只会出现在第一张 SUBMIT 之前：

```
FEE_RATE <rate>
DEPOSIT <accountId> <amount>
SUBMIT <orderId> <accountId> <YES|NO> <BUY|SELL> <price> <qty>
CANCEL <orderId>
```

输出先按事件发生顺序逐行，再输出全部账户（deposit 过的账户，按 accountId 字典序），然后一行抵押池，最后一行手续费账户。价格 2 位小数，金额一律 4 位小数，qty 和份额是不带小数点的整数，rate 原样输出。文件以一个换行结尾。

```
FEE_RATE_SET <rate>
DEPOSITED <accountId> <amount>
ACCEPTED <orderId>
REJECTED <orderId> <INSUFFICIENT_FUNDS|INSUFFICIENT_SHARES|DUPLICATE_ORDER_ID>
TRADE <NORMAL|MINT|MERGE> <qty> <takerOrderId> <takerOutcome> <takerSide> <takerPrice> <takerAmount> <takerFee> <makerOrderId> <makerOutcome> <makerSide> <makerPrice> <makerAmount> <makerFee>
CANCELLED <orderId> <remainingQty>
CANCEL_REJECTED <orderId>
ACCOUNT <accountId> <available> <frozen> <frozenYes> <frozenNo> <posYes> <posNo>
POOL <amount>
FEES <amount>
```

taker 是本次进来的订单。

评分用两组脚本：一组不带 `FEE_RATE`（考前三层），一组带 `FEE_RATE 0.002`（考第四层）。每组的输出与参考输出**逐字节相同**才算通过，不做任何格式归一；没做第四层的只影响第二组。你在「没说清的规则」上做了和参考不同的选择没关系，写进 README，评分会按你的选择重新生成参考输出；除此之外的任何差异都算不通过。

---

## 题三：对账

下单流程是：资金服务先冻结，再进撮合；成交后资金服务消费成交事件实扣；撤单和拒单后解冻。资金服务消费成交事件的延迟通常在 2 秒以内。

下面是两个服务在 **15:00:01.200** 同一时刻的快照。价格改善的差额应在实扣时一并解冻。

**撮合侧订单终态**

| orderId | 账户 | 方向 | qty | 限价 | 状态 | 已成交 qty | 成交均价 | 最后成交时间 |
|---|---|---|---|---|---|---|---|---|
| o1 | A | BUY YES | 10 | 0.60 | FILLED | 10 | 0.58 | 15:00:00.900 |
| o2 | A | BUY YES | 5 | 0.50 | CANCELLED | 0 | - | - |
| o3 | B | BUY NO | 20 | 0.45 | PARTIAL | 8 | 0.45 | 14:59:30.000 |
| o4 | C | BUY YES | 3 | 0.70 | REJECTED | 0 | - | - |
| o5 | B | BUY NO | 4 | 0.40 | FILLED | 4 | 0.40 | 14:58:10.000 |
| o6 | D | BUY YES | 10 | 0.55 | FILLED | 10 | 0.55 | 14:57:00.000 |
| o7 | A | BUY YES | 2 | 0.65 | FILLED | 2 | 0.60 | 14:58:00.000 |
| o8 | C | BUY YES | 6 | 0.30 | OPEN | 0 | - | - |

**资金侧冻结记录**

| orderId | 账户 | 冻结 | 已实扣 | 已解冻 | 状态 | 最后更新 |
|---|---|---|---|---|---|---|
| o1 | A | 6.00 | 5.22 | 0.18 | OPEN | 15:00:00.850 |
| o2 | A | 2.50 | 0 | 2.50 | CLOSED | 14:59:00.000 |
| o3 | B | 9.00 | 3.60 | 0 | OPEN | 14:59:30.100 |
| o4 | C | 2.10 | 0 | 0 | OPEN | 14:56:00.000 |
| o5 | B | 1.60 | 1.60 | 0 | CLOSED | 14:58:10.100 |
| o6 | D | 5.50 | 11.00 | 0 | CLOSED | 14:57:00.300 |
| o7 | A | 1.30 | 1.20 | 0 | CLOSED | 14:58:00.100 |
| o8 | C | 1.80 | 0 | 0 | OPEN | 14:59:50.000 |

回答：

1. 逐条判断每个 orderId 两边是否一致。不一致的，说明是真错误还是还在途中，判断依据是什么。
2. 对每个真错误写出纠正动作：对哪个账户、动哪个字段、动多少。
3. 对账程序以哪一边为事实？为什么不能两边互相校验？
4. 如果 o1 到了 15:00:10 还是这个状态，你的程序会怎么处理，阈值怎么定。

---

## 题四：排障

### 背景

每个市场一个撮合线程，下单和撤单请求进同一个队列，撮合线程按顺序处理。每笔成交产生后，撮合线程把成交事件发到 Kafka，资金服务消费。

Kafka 生产端 `acks=all`，broker 在另一个可用区，网络往返约 20ms。JVM 堆 2G，G1。

### 现象

某市场活跃时（每秒约 100 笔成交），撤单接口 p99 从 30ms 涨到 4s。日志里撤单从「入队」到「处理完成」间隔 3.9s，撮合逻辑本身耗时 1 到 3ms。CPU 不到 20%。

撮合线程的线程 dump：

```
"match-BTC-15m" #47 prio=5 os_prio=31 cpu=812.11ms elapsed=3612.44s tid=0x00007f8b1c0 nid=0x5e03 waiting on condition
   java.lang.Thread.State: TIMED_WAITING (parking)
        at jdk.internal.misc.Unsafe.park(Native Method)
        at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:252)
        at java.util.concurrent.CountDownLatch.await(CountDownLatch.java:230)
        at org.apache.kafka.clients.producer.internals.ProduceRequestResult.await(ProduceRequestResult.java:76)
        at org.apache.kafka.clients.producer.internals.FutureRecordMetadata.get(FutureRecordMetadata.java:67)
        at com.exam.match.TradeEventPublisher.publish(TradeEventPublisher.java:41)
        at com.exam.match.MatchingEngine.onTrade(MatchingEngine.java:118)
        at com.exam.match.MatchingEngine.process(MatchingEngine.java:96)
        at com.exam.match.MatchLoop.run(MatchLoop.java:33)
```

同一时段的 GC 日志片段：

```
[15:02:07.905s][info][gc] GC(1840) Pause Young (Normal) (G1 Evacuation Pause) 609M->97M(2048M) 195.4ms
[15:02:11.412s][info][gc] GC(1841) Pause Young (Normal) (G1 Evacuation Pause) 612M->98M(2048M) 187.3ms
[15:02:14.907s][info][gc] GC(1842) Pause Young (Normal) (G1 Evacuation Pause) 615M->99M(2048M) 203.1ms
```

运维同事的判断是「GC 停顿太长，先调堆」。

### 回答

1. 根因是什么。运维的判断你怎么看，用数字说话。
2. 撮合只要 2ms，为什么撤单等了 4s。把账算出来。
3. 怎么改。你的改法自己会带来什么新问题，怎么兜住。
4. 有人提议 (a) 把 acks 改成 1，(b) 撮合线程改成线程池。逐条给出你的意见，用数字。
5. 上线前怎么证明修好了，并且没有修出新问题。

---

## 题五

材料：`题五材料/FD-CQ-S-算力池偏差结算中心_v3.1.md`，一份算力池偏差结算模块的设计文档。

### 第一部分：手算（8 分）

结算下面三个窗口，参数取文档默认值。每个租户给出：A_h、D、tol、band、E、N、rem、H、band_amount、netted_amount、excess_amount、base_amount、premium、carry、total。每个窗口另给出 S_pool、pool_tol、pool_state、premium_pool。全部 4 位小数。

三个窗口的 POOL_NET_STATE 都已到达：pool_net_deviation_h 与按文档规则算出的 Σ D_i 一致，total_reserved_h 等于表中预约量之和。表里 group 一列是 G 给出的有效绑定，「-」表示没有绑定。

**窗口 W1**，60 分钟，P_base = 4.0000。

| 租户 | reserved_gpu_h | group_mode | group | USAGE_FACT（metering_version: usage_value，单位 GPU_MIN） |
|---|---|---|---|---|
| T1 | 100.00 | NETTED | G1 | v1: 6180 |
| T2 | 50.00 | NETTED | G1 | v1: 3120 |
| T3 | 200.00 | NETTED | G1 | v1: 12480 |
| T4 | 75.00 | NETTED | G1 | v1: 4350 |
| T5 | 120.00 | STANDALONE | G1 | v1: 6900 |
| T6 | 60.00 | STANDALONE | - | v1: 3900；v2: 4001 |
| T7 | 25.00 | STANDALONE | - | v1: 1530 |

**窗口 W2**，15 分钟，P_base = 4.0000。

| 租户 | reserved_gpu_h | group_mode | group | USAGE_FACT |
|---|---|---|---|---|
| T8 | 10.07 | STANDALONE | - | v1: 640 |
| T9 | 189.93 | STANDALONE | - | v1: 11560 |
| T10 | 200.00 | STANDALONE | - | v1: 12040 |

**窗口 W3**，60 分钟，P_base = 5.0000。

| 租户 | reserved_gpu_h | group_mode | group | USAGE_FACT |
|---|---|---|---|---|
| T11 | 6000.00 | STANDALONE | - | v1: 339000 |
| T12 | 6000.00 | STANDALONE | - | v1: 339000 |
| T13 | 6000.00 | STANDALONE | - | v1: 339000 |
| T14 | 2000.00 | STANDALONE | - | v1: 123000 |

评分只看数字对不对。某个数字取决于你对文档的某种读法时，把读法写在表格下面。

### 第二部分：文档审读（5 分）

写出你的审查的报告

### 第三部分：实现（4 分）

用 Java 实现这个计算器：输入 S2 的四个契约对象和 S3 的 policy，输出 S7.2 定义的窗口返回值。要求：

- 用第一部分的三个窗口做测试。代码算出来的和你手算的对不上，说清哪个错了。
- S7.2 的四个 contract_error 各写一个测试，范围必须和 S7.2 一致：租户级错误之后，同窗口其他租户的输出要有断言。
- S8 的 Q5、Q8、Q10、Q11 各写一个测试。

### 第四部分：为什么（3 分）

用自己的话回答。每题**不超过 150 个汉字**，超了这一题不给分。

1. 把 recovery_ratio 从 0.20 调到 1.00，哪一类租户的行为会变、往哪个方向变？BALANCED 窗口的占比会怎么走？
2. 容忍带为什么不许参与组内轧差？假设允许，一家客户可以怎么拆租户来钻空子，给一个带数字的例子。
3. 缺组绑定的 NETTED 租户不产出结算结果，它的 D_i 却仍然计入 Σ D_i 校验，为什么？如果不计入会发生什么？
4. 为什么溢价必须单独一行，不能和退款相抵后记一个净额？
