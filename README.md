# 注 只做了前两题，
题一由ai阅读代码，找出相关问题，我阅读代码逻辑并一一确认

题二代码和测试均由ai生成，先让ai阅读题二需求生成前两层，
我大概看了主要部分代码没发现问题就接着生成后两层代码逻辑
# 题一: 见[answer.md](note/answer.md)

# 题二：四层小交易所（单市场、纯内存）

四层都已实现并有测试：第一层撮合、第二层账本、第三层事件日志（`replay` / `snapshot` / `restore`）、
第四层手续费，外加评分适配器（读操作脚本，逐字节输出）。测试 39 个全绿，随机测试 10 万次含不变量检查约 0.1 秒。

## 一、怎么跑

```bash
# 全部测试
./gradlew test

# 评分脚本（读文件路径，结果打到标准输出）
./gradlew runScript -Pscript=scripts/sample_nofee.txt      # 无费率：前三层
./gradlew runScript -Pscript=scripts/sample_fee.txt        # FEE_RATE 0.002：第四层
java -cp build/classes/java/main exchange.io.Main scripts/sample_nofee.txt

# 不给参数则从标准输入读脚本
echo "DEPOSIT A 100" | java -cp build/classes/java/main exchange.io.Main
```

要求 Java 17+，依赖只有 JUnit（测试用）。

`scripts/sample_nofee.txt` 是题目的「走一遍例子」：

```
DEPOSITED A 100.0000
DEPOSITED B 100.0000
DEPOSITED C 100.0000
ACCEPTED oA
REJECTED oB INSUFFICIENT_SHARES
ACCEPTED oC
TRADE MINT 8 oC NO BUY 0.40 3.2000 0.0000 oA YES BUY 0.60 4.8000 0.0000
ACCOUNT A 94.0000 1.2000 0 0 8 0
ACCOUNT B 100.0000 0.0000 0 0 0 0
ACCOUNT C 96.8000 0.0000 0 0 0 8
POOL 8.0000
FEES 0.0000
```

`scripts/sample_fee.txt`（`FEE_RATE 0.002`）：

```
FEE_RATE_SET 0.002
DEPOSITED A 100.0000
DEPOSITED B 100.0000
ACCEPTED a1
ACCEPTED b1
TRADE MINT 8 b1 NO BUY 0.40 3.2000 0.0064 a1 YES BUY 0.60 4.8000 0.0096
CANCELLED a1 2
ACCOUNT A 95.1904 0.0000 0 0 8 0
ACCOUNT B 96.7936 0.0000 0 0 0 8
POOL 8.0000
FEES 0.0160
```

输出格式：价格 2 位小数、金额一律 4 位小数、qty 与份额是不带小数点的整数、rate 原样输出，
账户按 accountId 字典序，文件以一个换行结尾（`ScriptAdapterTest` 逐字节比对，连行尾空格都管）。

## 二、代码结构

```
src/main/java/exchange/
  Exchange.java Order.java Event.java AccountView.java Snapshot.java
  Outcome.java Side.java TradeType.java RejectReason.java Money.java
  book/    OrderBook.java RestingOrder.java MatchEngine.java TradeIntent.java
  ledger/  Ledger.java AccountState.java
  core/    StateApplier.java EventSourcedExchange.java
  io/      ScriptRunner.java Main.java
src/test/java/exchange/
  WalkthroughTest MatchingTest LedgerTest EventReplayTest FeeTest ScriptAdapterTest RandomInvariantTest
```

题一的代码在 `src/main/java/exam/`，没有被改动。

## 三、设计要点

### 3.1 第一层：一张「有效盘口」，四种成交是同一件事

对每个 outcome 都把另一侧凑出有效价：

| 进来的单（taker） | 真实价位（NORMAL） | 合成价位（MINT / MERGE） |
|---|---|---|
| BUY YES @p | 挂着的 SELL YES @q，q ≤ p，成交价 q | 挂着的 BUY NO @r，p + r ≥ 1，成交价 1 − r |
| BUY NO @p | 挂着的 SELL NO @q，q ≤ p，成交价 q | 挂着的 BUY YES @r，p + r ≥ 1，成交价 1 − r |
| SELL YES @p | 挂着的 BUY YES @q，q ≥ p，成交价 q | 挂着的 SELL NO @r，p + r ≤ 1，成交价 1 − r |
| SELL NO @p | 挂着的 BUY NO @q，q ≥ p，成交价 q | 挂着的 SELL YES @r，p + r ≤ 1，成交价 1 − r |

统一规则：**挂单方永远按自己的限价成交，taker 拿价差**。于是

- NORMAL 时两边价相同（挂单价更优）；
- MINT / MERGE 时 `takerPrice + makerPrice = 1` 精确成立 → 「铸造成交两边付款之和恰好等于 qty × 1」
  是恒等式，不是靠事后凑（实现里还有一条断言守着）；价格 2 位小数在 [0.01, 0.99] 上对补价封闭；
- 「任何一方的成交价不比自己的限价差」由候选筛选条件（taker 价 ≤/≥ 自己的限价）加「挂单价 = 限价」保证。

这条规则不是我拍脑袋定的：题目走一遍例子第 5 步给出 `bestAsk(NO) = 0.40 = 1 − 0.60`，
正是「挂单方 A 的 BUY YES @0.60 被尊重、买 NO 的一方拿补价」。

匹配实现是两指针归并：真实价位与合成价位各按对 taker 有利的方向单调推进（买 NORMAL 从低价往上、
卖 NORMAL 从高价往下、买 MINT 从高价买盘往下、卖 MERGE 从低价卖盘往上），遇到第一个不划算的价位就结束。
**每笔订单只走它真正吃到的价位，不做整个盘口的线性扫描**；价位用 `TreeMap`（价格优先），
价位内用 `LinkedHashMap`（同价时间优先 + 按 orderId O(1) 删除）。

### 3.2 盘口查询把铸造和销毁算进去

```
bestBid(X) = max( 真实最高买价(X) , 1 − 真实最低卖价(other(X)) )
bestAsk(X) = min( 真实最低卖价(X) , 1 − 真实最高买价(other(X)) )
```

题目第 5 步之后（盘口只剩 A 的 BUY YES 2 @0.60）走一遍：

- `bestBid(YES) = 0.60` —— 真实买盘 0.60；YES 上没有卖盘，所以「1 − bestAsk(NO)」这一项不存在。
- `bestAsk(YES) = 空` —— YES 没有卖盘；NO 也没有买盘，所以既不能直接买，也没有对手可以铸造。
  （同一时刻 `bestAsk(NO) = 1 − 0.60 = 0.40`、`bestBid(NO) = 空`，与题目第 5 步一致。）

### 3.3 第二层：冻结/解冻与两条校验

- 买单冻结 `reserve(qty, 限价)`（第四层含手续费准备金，见 3.5）；卖单冻结 qty 张份额（positions → frozenShares）。
- 校验只有两条，且顺序固定：① orderId 是否在之前任何一次 submit 里出现过（被拒的也算）→
  `DUPLICATE_ORDER_ID`；② 资金或份额不够 → `INSUFFICIENT_FUNDS` / `INSUFFICIENT_SHARES`。整单拒绝，不允许部分接受。
- 买方成交：从冻结实扣 `qty × 成交价`，然后把这单冻结**重定**为「剩余 qty 的 reserve」，多出来的回到 available
  （所以「frozen = 所有未完结买单 reserve 之和」是构造性成立的）。
- 卖方成交：frozenShares 扣 qty，收款进 available。撤单：剩余冻结（钱或份额）全部回到 available / positions。
- 账户从第一次 deposit 起才存在；没 deposit 过的账户提交订单按资金/份额不足拒，且不会因此变成存在的账户。
- 抵押池：铸造 +qty × 1，销毁 −qty × 1；手续费不进池。

### 3.4 第三层：事件日志、replay、快照

- **所有状态变化都由事件驱动**：`submit / cancel / deposit / setFeeRate` 都是「先生成事件列表，再 apply」，
  `StateApplier.apply(Event)` 是**唯一**改状态的地方。这也是 `replay` 能与实时运行逐字段一致的原因——
  它们跑的是同一段代码。
- 事件类型：`FeeRateSet / Deposit / OrderAccepted / OrderRejected / Trade / OrderCancelled / CancelRejected`；
  每次 submit 的顺序固定为 `OrderAccepted` → 各笔 `Trade`。`Trade` 自带每一方的 price / amount / fee，
  所以打印、replay、对账用同一份数字，不会两处各算一遍。
- `replay(events)`：空状态依次 apply；`restore(snapshot)`：直接从快照重建状态，之后用
  `applyEvents(...)` 接续消费第 k+1 个起的事件。
- `Snapshot` 是**状态的完整深拷贝**：账户（含「存在但余额为 0」的）、盘口每张挂单（剩余张数 + 接受顺序 seq，
  所以同价 FIFO 不会丢）、抵押池、手续费账户、费率、开市标记、出现过的 orderId、seq 计数器。
  构造时把所有金额统一成 4 位小数、账户按字典序、挂单按 seq 升序，因此两份快照 `equals`
  可以直接当「状态完全一致」的判据（测试就是这么断言的）。

### 3.5 第四层：手续费

- `fee = floor4(自己的 amount × rate)`：买方从冻结里扣，卖方从收款里扣，两边都进手续费账户；
- 买单准备金 `reserve(q, L) = q × L + ceil4(q × L × rate)`，`reserve(0, L) = 0`；
  提交时 available 不足 `reserve(qty, 限价)` 就整单拒绝，接受时按它冻结；
  每笔成交后该单冻结重定为 `reserve(剩余 qty, 限价)`，多出来的回 available；撤单释放 `reserve(剩余 qty, 限价)`；
- 费率只能开市前（第一张订单被**接受**之前，被拒的单不算开市）调用一次；不调用或设为 0 时，
  `ceil4/floor4` 都退化为 0，与前三层数值完全等价（有测试逐字节比对这两种情况的输出）。

**证明（写进 README 的那一段）**：设本笔成交 f 张、成交价 P ≤ 限价 L，成交前该单冻结 `qL + ceil4(qL·r)`，
且 f ≤ q。

1. *扣得动*：`fP + floor4(fP·r) ≤ qL + ceil4(qL·r)`——因为 f ≤ q、P ≤ L，且 `floor4(x) ≤ ceil4(x)`、
   `ceil4` 单调不减。
2. *回到 available 的部分不为负*：释放额
   `released = frozenBefore − amount − fee − frozenAfter`
   `= f(L−P) + [ceil4(qL·r) − floor4(fP·r) − ceil4((q−f)L·r)]`。
   把三个 ceil / floor 拆成「整数部分 + 小数部分」，换成 1e-4 单位（记 `V = fP·r/U`、`W = (q−f)L·r/U`）后
   `released/U = f(L−P)/U + ⌈V+W⌉ − ⌊V⌋ − ⌈W⌉`，而 `⌊V⌋ + ⌈W⌉ ≤ ⌈V+W⌉` 恒成立
   （因为 `⌊V⌋+⌈W⌉ = ⌊V+⌈W⌉⌋ ≤ ⌈V+W⌉`），所以 `released ≥ 0`。
   直觉是：**手续费向下截断、剩余 qty 的冻结向上取整，两个方向的误差正好互相抵消**。
   实现里 `Ledger.settleBuy` 对 `release < 0` 直接抛异常，两个费率的随机测试各 10 万次都没触发过。

**如果 fee 改成四舍五入，在这些数字上出事**（`FeeTest` 里有这个用例）：

`rate = 0.002`，`BUY @0.13 × 5`（冻结 `0.65 + ceil4(0.0013) = 0.6513`），部分成交 1 张 @0.13：

| | fee | 剩余 4 张的冻结 | released |
|---|---|---|---|
| 向下截断（题目要求） | `floor4(0.00026) = 0.0002` | `0.52 + ceil4(0.00104) = 0.5211` | `0.6513 − 0.13 − 0.0002 − 0.5211 = 0.0000` |
| 四舍五入 | `round4(0.00026) = 0.0003` | 同上 | `0.6513 − 0.13 − 0.0003 − 0.5211 = −0.0001` |

截断时刚好为 0（设计是紧的），四舍五入就会多扣 0.0001，也就是「回到 available 的那部分为负」，
账户 available 会被扣成负数。

## 四、题目没写清的规则，我这样定的

| 没说清的点 | 我的选择 | 理由 |
|---|---|---|
| MINT / MERGE 谁拿价差 | 挂单方按自己限价，taker 拿补价 | 题目第 5 步的 `bestAsk(NO) = 0.40` 就是这个意思；也让「两边之和 = qty × 1」成为恒等式 |
| 真实价位与合成价位同价时谁优先 | 按挂单先后（`seq`） | 「同价时间优先」按有效价成立；两笔对 taker 完全等价 |
| 合成价更优时是否优先吃合成价 | 是（按 taker 实际成交价择优） | 否则会出现「能以 0.01 买到却去成交 0.69」，与价格优先矛盾 |
| 同一账户自成交 | 允许 | 输出格式里只有三种拒单码，没有自成交的位置；加一条拒单就违反了「仅有的边界校验」 |
| 撤不存在的 / 已撤的 / 已全部成交的订单 | `CANCEL_REJECTED` | 输出格式里唯一的撤单失败行 |
| 一次 submit 的事件顺序 | `OrderAccepted` 在 `Trade` 之前 | 没接受就没法引用 taker 订单；事件驱动本来也是这个顺序 |
| `setFeeRate` 的时机 | 只允许一次，且在第一张订单被**接受**前；被拒的单不算开市 | 题目原文「第一张订单被接受之前」 |
| `FEE_RATE 0` | 允许，照样输出 `FEE_RATE_SET 0` | 数值上与不设置完全等价（有测试逐字节比对） |
| rate 的输出写法 | 按解析出的 BigDecimal 的 `toPlainString()`（`0.002` → `0.002`） | 题目说「rate 原样输出」；脚本里不会写科学计数法 |
| 无费率那一组金额的位数 | 也按 4 位小数输出 | 题目「金额一律 4 位小数」；费率 0 时与 2 位小数结果等价 |
| 未 deposit 过的账户查询 | 返回全 0 视图，不创建账户 | 便于查询，又不违反「从第一次 deposit 起才存在」 |
| 快照的判等 | 金额统一 scale 4，账户按字典序、挂单按 seq 升序 | 这样 `Snapshot.equals` 就是「状态完全一致」，测试可以直接断言 |
| `restore` 出来的交易所的事件流 | `restore` 之后 eventLog 为空（快照不含事件流），需要时用 `applyEvents` 接着喂 | 快照是状态，不是日志；`replay` 出来的实例则带着那批事件 |
| `applyEvents(List<Event>)` | 接口之外新增的公开方法 | 题目要求的「restore 后再应用第 k+1 起的事件」需要一个入口 |
| deposit 金额 | 必须为正，否则抛 `IllegalArgumentException` | 脚本里不会出现，宁可显式失败 |
| 脚本格式 | 空行与 `#` 注释行忽略；字段数不足、费率时机不对直接报错 | 与参考输出无关的宽容项，出错时要有清晰信息 |

## 五、测试

测试方法名一律用英文（例如 `normalTrade_usesMakerPrice_takerKeepsImprovement`），
每条的中文说明放在 `@DisplayName` 里，所以测试报告里照样看得懂每条在考什么。

定向用例（`WalkthroughTest` / `MatchingTest` / `LedgerTest` / `EventReplayTest` / `FeeTest` / `ScriptAdapterTest`）：

- 题目六步例子（逐个数断言，含第 6 步两个盘口值）；
- 四种成交各一个；价格优先；时间优先；同价时真实/合成价位的取舍（两个方向）；合成价更优；成交后残单挂盘口；
- 三种拒单；重复检查先于资金检查；没 deposit 过的账户；部分成交后撤单释放；撤卖单份额回持仓；
  成交价优于限价的差额回可用；撤不存在/已撤/已成交完的订单；
- 第三层：场景在**每个事件处**都取一次快照，与「重放到第 k 个事件」逐个比对；
  中途快照 → `restore` + 续放剩余事件、全量 `replay`、实时运行三者状态一致；
  恢复出来的交易所再提交同一张订单，产生的事件与原来的逐条相等；
- 第四层：费率时机（只能一次、开市后不行、被拒单不算开市）；`reserve` 含向上取整的准备金；
  够 `qty × 限价` 但不够 `reserve` 时整单拒绝；带手续费的部分成交（每方 amount / fee / 冻结重定 / 撤单释放逐个断言）；
  卖方费从收款扣、买方费从冻结扣；销毁进出池仍是 qty × 1；`restore` 后费率与手续费账户仍然生效；
  截断 vs 四舍五入的 0.0001 差异；费率 0 与不设置的输出等价；
- 两个评分脚本逐字节比对（无费率脚本、`FEE_RATE 0.002` 脚本，期望值都是手工算的）。

随机测试（`RandomInvariantTest`，10 万次操作，每 1000 次检查一次不变量——清单见下）：

```
随机测试(费率 0,       100000 次操作)：accepted=54357 rejected=814  cancelled=13434 NORMAL=20118 MINT=10272 MERGE=9614，含不变量检查耗时  92 ms
随机测试(费率 0.002,   100000 次操作)：accepted=54011 rejected=916  cancelled=13016 NORMAL=19978 MINT=10398 MERGE=9681，含不变量检查耗时  95 ms
随机测试(费率 0.00123, 100000 次操作)：accepted=53671 rejected=1110 cancelled=12892 NORMAL=19854 MINT=10341 MERGE=9675，含不变量检查耗时 106 ms
快照比对通过：事件总数 140068，随机快照在第 92110 个事件处（该轮含不变量检查 154 ms）
```

检查的不变量：`Σ(available + frozen) + 抵押池 + 手续费账户 = Σ deposit` 精确相等；
两侧份额总数相等且等于抵押池；每个账户 `frozen = Σ reserve(剩余 qty, 限价)`、
`frozenShares[outcome] = Σ 未完结卖单剩余`；所有余额/份额非负；盘口上没有 0 张的订单。
快照那一轮还会比对「25% / 50% / 75% 处快照 vs 重放到第 k 个事件」以及随机位置快照的三方一致性。

性能：10 万次随机操作含不变量检查约 0.1 秒（要求 5 秒内）；每笔订单只访问它真正吃到的价位。


## 六、还能再做的（不在本次范围）

- 并发：题目对题二没有并发要求（题一才考并发），所以这边是单线程同步实现、没有加锁；
  要支持并发消费，最小改动是把 `EventSourcedExchange` 的入口方法串行化（单市场本来就被撮合串行化）。
- 快照复用/增量快照：现在每次都是全量深拷贝，10 万挂单量级下内存与延迟都还够用，量级再大就该做增量。
- 题三、题四、题五属于试卷的其他题目，不在题二范围内。
