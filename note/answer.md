# 题一：审代码
1. 死锁问题
- 位置：MarketSettler.apply 第 65 行（先 marketLock）→ settleSide 第 98 行（再账户锁）、AccountLedger.lock() 第 41 行；对照 MarketSettler.onNewBuyOrder 第 32 行（先账户锁）→ 第 35
  行（再 marketLock），onCancel 第 49/52 行同理。
- 规格：第 10 条（四者彼此并发）。
- 例子：消费线程 T1 走 onFill→apply，第 65 行拿到 marketLock，在 settleSide 等 A 的账户锁；接口线程 T2 对同一账户做 onNewBuyOrder，第 32 行拿到 A 的账户锁，第 35 行等 marketLock。
  互等。

- 改法：统一加锁顺序，任何路径都先 marketLock 再账户锁（把 onNewBuyOrder/onCancel 改成先锁市场再锁账户）；单市场本来就被 apply 串行化了，也可以直接去掉账户级锁、全部在 marketLock
  下改。

2. 成交价优于限价时，差额没回到可用，钱卡死在 frozen

- 位置：AccountLedger.settleBuy 第 64-67 行（第 64 行只按成交金额解冻）；MarketSettler.apply 第 76 行却按整笔限价额减 reservedTotal，两边对不上。
- 规格：第 2 条（差额从冻结回到可用），连带破第 7 条和第 9 条。
- 例子：A 限价下单买入100 x 0.6  冻结60，实际成交 100 x 0.5，代码计算时是按照0.5价格来解冻和增加可用的，未计算差额

- 改法：settleBuy 按这部分限价额解冻、把差额计入可用：frozen -= qty*limitPrice; available += qty*limitPrice - amount - fee;（Reservation 里已经存了 limitPrice）。

3. 卖单手续费丢了舍入模式，直接抛异常

- 位置：AccountLedger.settleSell 第 80 行（第 79 行同样缺参数，但 qty 2 位×price 3 位最多 5 位小数，setScale(6) 是精确扩展，恰好不触发）。
- 规格：第 1 条、第 3 条（金额 6 位、HALF_UP）。
- 例子：A 持 10.00 YES，来 NORMAL qty=1.23 @0.456：amount=0.560880，fee=0.560880×0.002=0.001121760 需要舍入到 6 位 → 抛 java.lang.ArithmeticException: Rounding necessary。凡是
  amount×0.002 满 6 位还多出非零位的卖单全都入不了账（amount=5.800000 这类恰好 0.011600 的能过）。

- 改法：第 80 行补成 .setScale(MONEY_SCALE, RoundingMode.HALF_UP)，和第 60 行一致。



4. MERGE 的 maker 被当成买方

- 位置：MarketSettler.apply 第 68 行（第 78-80 行的 frozen/reservedTotal 分支跟着一起错）。
- 规格：第 4 条（MERGE 双方都是 SELL），连带破第 7 条。
- 例子：A 持 10 YES、C 持 10 NO、抵押池 10.00。来 MERGE：taker=A SELL YES 10.00 @0.600，maker=C SELL NO 10.00 @0.400。期望 A +5.988000、C +3.992000、池 0。实际 makerSide 被算成
  BUY，第 71 行走 settleBuy(C,"sC",…)，而 C 的卖单从没冻结过 → reservations.get("sC")=null → 第 67 行 NPE。实测异常抛出时 A 已经成交完（YES 10→0、可用 +5.988000），C 的 NO 反而
  10→20、frozen 变成 −4.000000，池仍是 10.00。

- 改法：第 68 行按类型给方向：MINT → BUY，MERGE → SELL，NORMAL → other(takerSide)；这样第 78 行的冻结分支也自然对了。

5. 一笔成交不是原子的，会只入账一半

- 位置：MarketSettler.apply 第 70-71 行（两行之间没有回滚）；触发点在 AccountLedger.settleSell 第 80 行、settleBuy 第 61 行。
- 规格：第 8 条。
- 例子：A 持 10.00 NO、D 可用 100.00。D 下 BUY NO 1.23 @0.456（冻结 0.560880）。来 NORMAL：taker=D BUY NO @0.456、maker=A SELL NO @0.456。第 70 行先算 taker 成功：D 冻结
  0.560880→0、可用 99.439120→99.437998、NO +1.23；第 71 行算 maker 时因问题 3 抛异常。结果 D 已付钱拿到份额，A 一分钱没收到、份额也没少。实测 totalMoney 300.000000→299.437998，
  ΣNO=11.23 而抵押池还是 10.00。

- 改法：把两边先"检查+计算"再一起提交，或任一步失败就回滚已做的改动；settleBuy/settleSell 拆成 check 与 commit 两段。

6. 入账失败被记成成功，同一 fillId 重推被静默丢弃

- 位置：FillApplier.onFill 第 22 行（先 add 再 apply）。
- 规格：第 8 条（失败要让调用方知道、同一 fillId 重推）。
- 例子：问题 5 那笔成交抛异常，但 fillId 已在第 22 行进集合。上游按约定用同一 fillId 重推 → add 返回 false → 第 23 行直接 return，不抛异常也不入账。调用方以为成功，这笔成交两边永
  远不入账。

- 改法：只有 apply 成功返回后才记为已入账；稳妥做法是在apply内部先查重，finally在add标记

7. 去重集合不是线程安全的，同一 fillId 会入账两次

- 位置：FillApplier 第 11 行 new HashSet<>()，第 22 行在消费线程池里调用。
- 规格：第 8 条（不得重复入账）、第 10 条。
- 例子：T1、T2 两个消费线程同时推同一 fillId，HashSet.add 不是原子的，两边可能都读到桶为空再各自插入、都返回 true；并发扩容还可能把内部链表弄成环让读线程死循环。

- 改法：换 ConcurrentHashMap.newKeySet()，并把查重与入账收进同一把锁。


8. 手续费账户的取整口径和各方实扣不一致，守恒被打破

- 位置：MarketSettler.apply 第 86-89 行（先相加再 HALF_UP）对照 AccountLedger 第 60 行、第 80 行（各方自己 HALF_UP）。
- 规格：第 6 条。
- 例子：MINT，qty=0.01，taker 买 YES @0.025（amount 0.000250）、maker 买 NO @0.975（amount 0.009750），两边各充 1.000000。taker 实扣 HALF_UP(0.000250×0.002)=0.000001，maker 实扣
  HALF_UP(0.009750×0.002)=0.000020，共 0.000021；手续费账户按第 86-89 行只记 HALF_UP(0.010000×0.002)=0.000020。实测 Σ(available+frozen)+池+手续费 从 2.000000 掉到 1.999999。

- 改法：手续费账户改成加两边实际扣走的手续费之和（feeAccount += takerFee + makerFee）,或者在撮合侧生成双方手续费，下游直接使用

9. "未完结买单"永远删不掉（BigDecimal.equals 连 scale 一起比）
- 位置：AccountLedger.settleBuy 第 68 行。
- 规格：第 9 条（没有未完结订单就能提走全部可用）。
- 例子：qty 是 2 位小数，10.00 - 10.00 = 0.00（scale 2），而 BigDecimal.ZERO 的 scale 是 0，0.00.equals(ZERO) 为 false（compareTo 才是 0）。实测 A 下 10.00 @0.600 并被全额成交后，
  canWithdrawAll(A) 返回 false，订单其实一张都不剩。

- 改法：改成 r.remainingQty.signum() == 0 或 compareTo(BigDecimal.ZERO) == 0。

10. 撤单碰到没有冻结记录的订单直接 NPE

- 位置：AccountLedger.releaseOnCancel 第 87-88 行。
- 规格：第 2 条（撤单把剩余冻结还给可用，剩余为 0 就该还 0）。
- 例子：卖单按规格第 2 条不冻结，来 onCancel(B,"sB") 时 reservations.remove("sB") 返回 null → 第 88 行 NPE，撤单失败。实测抛 java.lang.NullPointerException: Cannot read field
  "remainingQty" because "<local2>" is null；已全部成交的买单走同一路径。

- 改法：取不到就还 0（Reservation r = reservations.remove(orderId); BigDecimal release = r == null ? BigDecimal.ZERO : r.remainingQty.multiply(r.limitPrice)…）。

11. 余额和持仓的无锁读

- 位置：AccountLedger 第 97-101 行、MarketSettler 第 118-122 行；类注释却要求"所有读写必须在持有 lock() 的情况下进行"。
- 规格：第 10 条。
- 例子：T1 消费线程刚把 A 的 available 从 94.000000 改成 93.988400，T2 接口线程立刻调 available()，没有 happens-before，仍可能读到 94.000000。
- 改法：读方法内部加锁，或字段改 volatile/AtomicReference。

规格本身的问题，以及看得到但当前触发不了的

- 你的规格第 3 条（每方自己 amount×0.2% 后 HALF_UP）和第 5 条（两边 amount 相加后 HALF_UP）在数学上不等价，上面的 0.000250/0.009750 就差 0.000001；两条都满足就不可能满足第 6 条守
  恒。建议把第 5 条改成"手续费账户记入两边实扣手续费之和"，让第 3 条做准。

- MarketSettler 第 111 行 money() 用的是 HALF_EVEN（规格第 1 条要求 HALF_UP）。因为 qty 2 位×price 3 位最多 5 位小数，setScale(6) 是精确扩展、永不触发舍入，所以目前是死代码、改不
  改输出一样；价格位数一变就会算错，建议顺手改成 HALF_UP。

- MarketSettler 第 15 行 new BigDecimal(0.002) 是 double 构造，实际值 0.002000000000000000041633363423443370265886187553405761718750，不是 0.2%：new
  BigDecimal(0.002).compareTo(new BigDecimal("0.002")) != 0。按 6 位 HALF_UP 目前算不出不同输出（误差 1e-22 量级），仍建议改成 new BigDecimal("0.002")。

- FillApplier.canWithdrawAll 是 check-then-act：检查通过后、真正提现前，另一线程的 onNewBuyOrder 可以再冻结资金。规格第 9 条没要求这两步原子，但接口若承诺"能提就能提走"，得把检查
  +扣款放进同一把账户锁里一次做完。

# 题二
  代码全部由ai生成,README.md为ai生成过程中的逻辑



