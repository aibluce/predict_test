package exchange;

import static exchange.Outcome.NO;
import static exchange.Outcome.YES;
import static exchange.Side.BUY;
import static exchange.Side.SELL;
import static exchange.TestSupport.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import exchange.core.EventSourcedExchange;

/**
 * 第三层：事件日志。同一事件序列重放多少次结果都一致，且和快照位置无关。
 *
 * <p>核心断言方式：{@link Snapshot} 是状态的完整深拷贝（含盘口每张挂单的剩余张数与
 * 接受顺序、抵押池、手续费账户、费率、出现过的 orderId、seq 计数器），所以两份快照
 * {@code equals} 就等于「状态完全一致」。
 */
class EventReplayTest {

    @Test
    @DisplayName("场景本身覆盖四种成交与三种结果")
    void scenarioCoversAllTradeTypesAndOutcomes() {
        EventSourcedExchange ex = new EventSourcedExchange();
        scenario(ex, () -> {
        });

        Set<TradeType> types = EnumSet.noneOf(TradeType.class);
        boolean rejected = false;
        boolean cancelled = false;
        boolean cancelRejected = false;
        for (Event event : ex.eventLog()) {
            if (event instanceof Event.Trade trade) {
                types.add(trade.type());
            } else if (event instanceof Event.OrderRejected) {
                rejected = true;
            } else if (event instanceof Event.OrderCancelled) {
                cancelled = true;
            } else if (event instanceof Event.CancelRejected) {
                cancelRejected = true;
            }
        }
        assertEquals(EnumSet.allOf(TradeType.class), types, "场景要同时出现 NORMAL / MINT / MERGE");
        assertTrue(rejected && cancelled && cancelRejected, "场景要同时出现拒单、撤单、撤单失败");
    }

    @Test
    @DisplayName("在任意第k个事件处快照_与重放到第k个事件一致")
    void snapshotAtAnyEventK_equalsReplayUpToK() {
        EventSourcedExchange live = new EventSourcedExchange();
        List<Event> all = new ArrayList<>();
        List<Integer> marks = new ArrayList<>();
        List<Snapshot> snapshots = new ArrayList<>();

        scenario(live, () -> {
            all.clear();
            all.addAll(live.eventLog());
            marks.add(all.size());
            snapshots.add(live.snapshot());
        });

        assertTrue(marks.size() > 10, "场景步骤太少");
        for (int i = 0; i < marks.size(); i++) {
            int k = marks.get(i);
            Exchange replayed = Exchange.replay(all.subList(0, k));
            assertEquals(snapshots.get(i), replayed.snapshot(),
                    "重放到第 " + k + " 个事件的状态与当时的快照不一致");
        }
    }

    @Test
    @DisplayName("快照恢复后继续应用剩余事件_与实时运行一致")
    void restoreThenApplyRemainingEvents_equalsLiveRun() {
        EventSourcedExchange live = new EventSourcedExchange();
        scenarioPart1(live);
        int k = live.eventLog().size();
        Snapshot snapshot = live.snapshot();
        List<Event> beforeSnapshot = List.copyOf(live.eventLog());
        scenarioPart2(live);
        List<Event> rest = live.eventLog().subList(k, live.eventLog().size());

        // 分支一：快照 restore 之后再应用第 k+1 个起的事件
        Exchange restored = Exchange.restore(snapshot);
        ((EventSourcedExchange) restored).applyEvents(rest);
        assertEquals(live.snapshot(), restored.snapshot(), "restore + 续放 与实时运行不一致");

        // 分支二：从空状态全量重放
        Exchange replayed = Exchange.replay(live.eventLog());
        assertEquals(live.snapshot(), replayed.snapshot(), "全量 replay 与实时运行不一致");

        // 顺带确认快照确实发生在中途
        assertTrue(beforeSnapshot.size() > 0 && rest.size() > 0);
    }

    @Test
    @DisplayName("恢复出来的交易所_后续行为与原来完全一致")
    void restoredExchange_behavesIdenticallyAfterwards() {
        EventSourcedExchange live = new EventSourcedExchange();
        scenario(live, () -> {
        });
        Exchange restored = Exchange.restore(live.snapshot());

        // 同一张新订单分别打到两边：产生的事件要一模一样
        List<Event> liveEvents = live.submit(order("n1", "C", YES, BUY, "0.45", 4));
        List<Event> restoredEvents = restored.submit(order("n1", "C", YES, BUY, "0.45", 4));
        assertEquals(liveEvents, restoredEvents, "恢复后的撮合结果与原来不一致");
        assertEquals(live.snapshot(), restored.snapshot());

        // 盘口查询也要一致
        assertEquals(live.bestBid(YES), restored.bestBid(YES));
        assertEquals(live.bestAsk(NO), restored.bestAsk(NO));
    }

    /** 一段固定的操作：MINT、NORMAL、MERGE、拒单、撤单、撤单失败全都覆盖。 */
    private static void scenario(EventSourcedExchange ex, Runnable afterEachStep) {
        scenarioPart1(ex, afterEachStep);
        scenarioPart2(ex, afterEachStep);
    }

    private static void scenarioPart1(EventSourcedExchange ex) {
        scenarioPart1(ex, () -> {
        });
    }

    private static void scenarioPart2(EventSourcedExchange ex) {
        scenarioPart2(ex, () -> {
        });
    }

    private static void scenarioPart1(EventSourcedExchange ex, Runnable afterEachStep) {
        ex.deposit("A", new BigDecimal("1000"));
        afterEachStep.run();
        ex.deposit("B", new BigDecimal("1000"));
        afterEachStep.run();
        ex.deposit("C", new BigDecimal("1000"));
        afterEachStep.run();
        ex.submit(order("m1", "A", YES, BUY, "0.60", 10));      // 挂 10 张
        afterEachStep.run();
        ex.submit(order("m2", "B", NO, BUY, "0.40", 8));        // MINT 8
        afterEachStep.run();
        ex.submit(order("m3", "B", NO, BUY, "0.45", 5));        // 再 MINT 2，剩 3 张挂着
        afterEachStep.run();
        ex.submit(order("s1", "A", YES, SELL, "0.55", 4));      // 卖盘
        afterEachStep.run();
        ex.submit(order("s2", "C", YES, BUY, "0.70", 4));       // 同价 0.55：先吃更早的 m3（MINT 3），再吃 s1（NORMAL 1）
        afterEachStep.run();
        ex.submit(order("s3", "C", NO, SELL, "0.35", 2));       // C 没有 NO → 拒单
        afterEachStep.run();
        ex.cancel("m3");                                        // 已成交完 → 撤单失败
        afterEachStep.run();
    }

    private static void scenarioPart2(EventSourcedExchange ex, Runnable afterEachStep) {
        ex.submit(order("s4", "A", YES, SELL, "0.40", 3));      // 卖盘
        afterEachStep.run();
        ex.submit(order("s5", "B", NO, SELL, "0.60", 3));       // MERGE 3
        afterEachStep.run();
        ex.cancel("s1");                                        // 撤掉 s1 剩余
        afterEachStep.run();
        ex.submit(order("b1", "C", YES, BUY, "0.20", 5));       // 低价买单
        afterEachStep.run();
        ex.submit(order("b2", "C", YES, BUY, "0.30", 5));       // 同 outcome 更高价
        afterEachStep.run();
        ex.cancel("b2");
        afterEachStep.run();
    }
}
