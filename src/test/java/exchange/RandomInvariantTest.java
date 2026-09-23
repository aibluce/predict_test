package exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import exchange.book.RestingOrder;
import exchange.core.EventSourcedExchange;

/**
 * 随机测试。
 *
 * <ul>
 *   <li>10 万次随机 deposit / submit / cancel，每 1000 次检查第二层与第四层的不变量；</li>
 *   <li>无费率、费率 0.002 各跑一遍（后者会同时压到手续费准备金与手续费账户）；</li>
 *   <li>另跑一遍在随机位置快照，比对「restore + 续放」「全量 replay」「实时运行」三者，
 *       并在 25% / 50% / 75% 处比对「重放到第 k 个事件」与当时的快照。</li>
 * </ul>
 */
class RandomInvariantTest {

    private static final int OPS = 100_000;
    private static final int CHECK_EVERY = 1_000;

    @Test
    @DisplayName("随机十万次操作_不变量成立_并在五秒内")
    void random100kOps_invariantsHoldWithinFiveSeconds() {
        runRandom(BigDecimal.ZERO, 20240923L, false);
    }

    @Test
    @DisplayName("随机十万次操作_带费率_不变量成立_并在五秒内")
    void random100kOps_withFeeRate_invariantsHold() {
        runRandom(new BigDecimal("0.002"), 7L, false);
    }

    @Test
    @DisplayName("随机十万次操作_刁钻费率_不变量成立_并在五秒内")
    void random100kOps_withAwkwardFeeRate_invariantsHold() {
        // 0.00123 有 5 位小数，qty × price × rate 会有 7 位，向上取整与向下截断都会被压到
        runRandom(new BigDecimal("0.00123"), 20250101L, false);
    }

    @Test
    @DisplayName("随机位置快照_恢复续放_全量重放_实时运行三者一致")
    void randomSnapshot_restoreReplayAndLiveRunAgree() {
        runRandom(new BigDecimal("0.002"), 99L, true);
    }

    private void runRandom(BigDecimal rate, long seed, boolean checkSnapshots) {
        Random rnd = new Random(seed);
        EventSourcedExchange live = new EventSourcedExchange();
        if (rate.signum() > 0) {
            live.setFeeRate(rate);
        }
        List<String> accounts = new ArrayList<>();
        BigDecimal totalDeposits = BigDecimal.ZERO;
        for (int i = 0; i < 10; i++) {
            String accountId = "A" + i;
            accounts.add(accountId);
            BigDecimal amount = new BigDecimal("10000");
            live.deposit(accountId, amount);
            totalDeposits = totalDeposits.add(amount);
        }

        Set<String> maybeOpen = new LinkedHashSet<>();
        List<Integer> marks = new ArrayList<>();
        List<Snapshot> snapshots = new ArrayList<>();
        int randomSnapshotAt = checkSnapshots ? OPS / 2 + rnd.nextInt(OPS / 4) : -1;
        int randomMark = -1;
        Snapshot randomSnapshot = null;

        long normal = 0;
        long mint = 0;
        long merge = 0;
        long accepted = 0;
        long rejected = 0;
        long cancelled = 0;

        long started = System.nanoTime();
        for (int i = 1; i <= OPS; i++) {
            double roll = rnd.nextDouble();
            if (roll < 0.55 || (maybeOpen.isEmpty() && roll < 0.90)) {
                String accountId = accounts.get(rnd.nextInt(accounts.size()));
                Outcome outcome = rnd.nextBoolean() ? Outcome.YES : Outcome.NO;
                Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                long qty = 1 + rnd.nextInt(50);
                BigDecimal price = BigDecimal.valueOf(1 + rnd.nextInt(99), 2);
                List<Event> events = live.submit(new Order("o" + i, accountId, outcome, side, price, qty));
                for (Event event : events) {
                    if (event instanceof Event.OrderAccepted a) {
                        accepted++;
                        maybeOpen.add(a.orderId());
                    } else if (event instanceof Event.OrderRejected) {
                        rejected++;
                    } else if (event instanceof Event.Trade t) {
                        if (t.type() == TradeType.NORMAL) {
                            normal++;
                        } else if (t.type() == TradeType.MINT) {
                            mint++;
                        } else {
                            merge++;
                        }
                    }
                }
            } else if (roll < 0.90) {
                List<String> open = new ArrayList<>(maybeOpen);
                String orderId = open.get(rnd.nextInt(open.size()));
                maybeOpen.remove(orderId);
                for (Event event : live.cancel(orderId)) {
                    if (event instanceof Event.OrderCancelled) {
                        cancelled++;
                    }
                }
            } else {
                String accountId = accounts.get(rnd.nextInt(accounts.size()));
                BigDecimal amount = new BigDecimal(100 + rnd.nextInt(4901));
                live.deposit(accountId, amount);
                totalDeposits = totalDeposits.add(amount);
            }

            if (i % CHECK_EVERY == 0) {
                checkInvariants(live, totalDeposits, i);
                maybeOpen.retainAll(openOrderIds(live));   // 剔掉已被成交吃掉或已撤掉的单
                if (checkSnapshots && (i == OPS / 4 || i == OPS / 2 || i == OPS * 3 / 4)) {
                    marks.add(live.eventLog().size());
                    snapshots.add(live.snapshot());
                }
            }
            if (i == randomSnapshotAt) {
                randomMark = live.eventLog().size();
                randomSnapshot = live.snapshot();
            }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        System.out.printf("随机测试(费率 %s, %d 次操作)：accepted=%d rejected=%d cancelled=%d "
                        + "NORMAL=%d MINT=%d MERGE=%d，含不变量检查耗时 %d ms，未完结订单 %d 张%n",
                rate.toPlainString(), OPS, accepted, rejected, cancelled, normal, mint, merge,
                elapsedMillis, live.openOrders().size());
        assertTrue(normal > 0, "随机测试里应该出现过 NORMAL 成交");
        assertTrue(mint > 0, "随机测试里应该出现过 MINT 成交");
        assertTrue(merge > 0, "随机测试里应该出现过 MERGE 成交");
        assertTrue(rejected > 0, "随机测试里应该出现过拒单");
        assertTrue(elapsedMillis < 5000, "10 万次操作含不变量检查应在 5 秒内，实际 " + elapsedMillis + " ms");

        if (!checkSnapshots) {
            return;
        }
        List<Event> all = live.eventLog();
        Snapshot liveFinal = live.snapshot();

        // 「在任意第 k 个事件处快照」= 重放到第 k 个事件
        for (int i = 0; i < marks.size(); i++) {
            Exchange replayed = Exchange.replay(all.subList(0, marks.get(i)));
            assertEquals(snapshots.get(i), replayed.snapshot(),
                    "重放到第 " + marks.get(i) + " 个事件的状态与当时的快照不一致");
        }

        // restore 后再应用第 k+1 个起的事件
        Exchange restored = Exchange.restore(randomSnapshot);
        ((EventSourcedExchange) restored).applyEvents(all.subList(randomMark, all.size()));
        assertEquals(liveFinal, restored.snapshot(), "restore + 续放 与实时运行不一致");

        // 从空状态全量重放
        Exchange replayed = Exchange.replay(all);
        assertEquals(liveFinal, replayed.snapshot(), "全量 replay 与实时运行不一致");

        System.out.printf("快照比对通过：事件总数 %d，随机快照在第 %d 个事件处%n", all.size(), randomMark);
    }

    /** 第二层与第四层的不变量 + 盘口与账本的一致性。 */
    private static void checkInvariants(EventSourcedExchange ex, BigDecimal totalDeposits, int opIndex) {
        String where = "（第 " + opIndex + " 次操作后，费率 " + ex.feeRate().toPlainString() + "）";
        Map<String, BigDecimal> buyReserve = new HashMap<>();
        Map<String, Long> frozenYes = new HashMap<>();
        Map<String, Long> frozenNo = new HashMap<>();
        for (RestingOrder order : ex.openOrders()) {
            assertTrue(order.remaining() > 0, "盘口上不该有 0 张的订单" + where);
            if (order.side() == Side.BUY) {
                buyReserve.merge(order.accountId(),
                        Money.reserve(order.remaining(), order.price(), ex.feeRate()), BigDecimal::add);
            } else if (order.outcome() == Outcome.YES) {
                frozenYes.merge(order.accountId(), order.remaining(), Long::sum);
            } else {
                frozenNo.merge(order.accountId(), order.remaining(), Long::sum);
            }
        }

        BigDecimal sumAvailable = Money.ZERO;
        BigDecimal sumFrozen = Money.ZERO;
        long totalYes = 0;
        long totalNo = 0;
        for (String accountId : ex.accountIds()) {
            AccountView view = ex.account(accountId);
            assertTrue(view.available().signum() >= 0, "available 为负：" + accountId + where);
            assertTrue(view.frozen().signum() >= 0, "frozen 为负：" + accountId + where);
            assertTrue(view.posYes() >= 0 && view.posNo() >= 0 && view.frozenYes() >= 0 && view.frozenNo() >= 0,
                    "份额为负：" + accountId + where);
            assertEquals(0, buyReserve.getOrDefault(accountId, Money.ZERO).compareTo(view.frozen()),
                    "frozen 不等于未完结买单的 reserve(剩余 qty, 限价) 之和：" + accountId + where);
            assertEquals(frozenYes.getOrDefault(accountId, 0L), view.frozenYes(),
                    "frozenShares[YES] 不等于未完结卖单剩余之和：" + accountId + where);
            assertEquals(frozenNo.getOrDefault(accountId, 0L), view.frozenNo(),
                    "frozenShares[NO] 不等于未完结卖单剩余之和：" + accountId + where);
            sumAvailable = sumAvailable.add(view.available());
            sumFrozen = sumFrozen.add(view.frozen());
            totalYes += view.posYes() + view.frozenYes();
            totalNo += view.posNo() + view.frozenNo();
        }

        assertNotNull(ex.collateralPool(), where);
        // 所有账户 (available + frozen) 之和 + 抵押池 + 手续费账户 = 所有 deposit 之和
        assertEquals(0, totalDeposits.compareTo(sumAvailable.add(sumFrozen)
                        .add(ex.collateralPool()).add(ex.feeAccount())),
                "资金不守恒" + where + " deposits=" + totalDeposits + " available=" + sumAvailable
                        + " frozen=" + sumFrozen + " pool=" + ex.collateralPool() + " fees=" + ex.feeAccount());
        // 两侧份额总数相等，且等于抵押池
        assertEquals(totalYes, totalNo, "YES / NO 份额总数不相等" + where);
        assertEquals(0, Money.amount(totalYes, Money.ONE).compareTo(ex.collateralPool()),
                "份额总数不等于抵押池" + where);
    }

    private static Set<String> openOrderIds(EventSourcedExchange ex) {
        Set<String> ids = new LinkedHashSet<>();
        for (RestingOrder order : ex.openOrders()) {
            ids.add(order.orderId());
        }
        return ids;
    }
}
