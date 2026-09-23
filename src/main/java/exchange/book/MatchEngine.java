package exchange.book;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import exchange.Money;
import exchange.Order;
import exchange.Outcome;
import exchange.Side;
import exchange.TradeType;

/**
 * 撮合层：纯函数，只读盘口算出“这笔新单应该成交什么”，不改任何状态。
 *
 * <p>核心是把四种成交看成同一件事：对每个 outcome 都把另一侧凑出“有效价”。
 * 进来的新单是 taker，<b>挂单方永远按自己的限价成交，taker 拿价差</b>：
 *
 * <ul>
 *   <li>NORMAL：占盘的是反向同 outcome 的单（BUY YES 吃 SELL YES），taker 成交价 = 挂单价；</li>
 *   <li>MINT：占盘的是同向另一 outcome 的买单（BUY YES 吃 BUY NO），成交价 = 1 − 挂单价，
 *       于是 takerPrice + makerPrice = 1 精确成立；</li>
 *   <li>MERGE：占盘的是同向另一 outcome 的卖单（SELL YES 吃 SELL NO），同样成交价 = 1 − 挂单价。</li>
 * </ul>
 *
 * <p>两个来源（真实价位、合成价位）按 taker 的成交价归并：谁的价对 taker 更优谁先成交，
 * 同价按挂单先后（seq）。因为每个来源内部都按对自己有利的方向单调推进，所以每笔订单
 * 只走它真正吃到的那些价位，不做整个盘口的线性扫描。
 */
public final class MatchEngine {

    private final OrderBook book;

    public MatchEngine(OrderBook book) {
        this.book = book;
    }

    /** 算出 taker 这笔单能成交的清单（按成交先后）。 */
    public List<TradeIntent> match(Order taker) {
        List<TradeIntent> intents = new ArrayList<>();
        Map<String, Long> consumed = new HashMap<>();
        Stream normal = new Stream(directionLevels(taker, TradeType.NORMAL), taker, consumed, TradeType.NORMAL);
        Stream synthetic = new Stream(directionLevels(taker, taker.side() == Side.BUY ? TradeType.MINT : TradeType.MERGE),
                taker, consumed, taker.side() == Side.BUY ? TradeType.MINT : TradeType.MERGE);

        long remaining = taker.qty();
        while (remaining > 0) {
            Candidate best = better(normal.peek(), synthetic.peek(), taker.side());
            if (best == null) {
                break;
            }
            long qty = Math.min(remaining, best.remaining());
            intents.add(new TradeIntent(best.type(), qty,
                    taker.orderId(), taker.outcome(), taker.side(), best.takerPrice(),
                    best.order().orderId(), best.order().accountId(),
                    best.makerOutcome(), best.makerSide(), best.order().price()));
            remaining -= qty;
            consumed.merge(best.order().orderId(), qty, Long::sum);
        }
        return intents;
    }

    /** 选出对 taker 成交价更优的一方，同价按挂单先后。 */
    private static Candidate better(Candidate a, Candidate b, Side takerSide) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        int cmp = a.takerPrice().compareTo(b.takerPrice());
        if (cmp != 0) {
            boolean aWins = takerSide == Side.BUY ? cmp < 0 : cmp > 0;
            return aWins ? a : b;
        }
        return a.order().seq() <= b.order().seq() ? a : b;
    }

    /**
     * 这个 taker 该按哪个方向遍历哪一侧盘口。
     * NORMAL 吃反向同 outcome；MINT / MERGE 吃同向的另一 outcome。
     */
    private TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>> directionLevels(Order taker, TradeType type) {
        if (type == TradeType.NORMAL) {
            return taker.side() == Side.BUY
                    ? book.levels(taker.outcome(), Side.SELL)          // 低卖价优先
                    : book.levels(taker.outcome(), Side.BUY);           // 高买价优先
        }
        return taker.side() == Side.BUY
                ? book.levels(taker.outcome().other(), Side.BUY)        // 高买价优先：1 − 价 更低
                : book.levels(taker.outcome().other(), Side.SELL);      // 低卖价优先：1 − 价 更高
    }

    private static Iterator<Map.Entry<BigDecimal, LinkedHashMap<String, RestingOrder>>> iterator(
            TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>> levels, boolean ascending) {
        return (ascending ? levels : levels.descendingMap()).entrySet().iterator();
    }

    /**
     * 遍历方向：让 taker 的成交价沿遍历方向单调变差，这样遇到第一个不划算的价位就能停下。
     * 买 NORMAL（吃卖盘）与卖 MERGE（吃另一 outcome 的卖盘）从低价往上走；
     * 卖 NORMAL（吃买盘）与买 MINT（吃另一 outcome 的买盘）从高价往下走。
     */
    private static boolean ascending(Side takerSide, TradeType type) {
        return (takerSide == Side.BUY) == (type == TradeType.NORMAL);
    }

    /** 一个候选：盘口上某张挂单 + 它对 taker 的成交价。 */
    private record Candidate(RestingOrder order, TradeType type, Outcome makerOutcome, Side makerSide,
                             BigDecimal takerPrice, long remaining) {
    }

    /** 一个来源（真实价位或合成价位）上的候选流，按对自己有利的方向推进。 */
    private static final class Stream {

        private final Iterator<Map.Entry<BigDecimal, LinkedHashMap<String, RestingOrder>>> levelIt;
        private final Map<String, Long> consumed;
        private final String takerOrderId;
        private final Side takerSide;
        private final TradeType type;
        private final Outcome makerOutcome;
        private final Side makerSide;
        private final BigDecimal takerLimit;
        private Iterator<RestingOrder> orderIt;
        private RestingOrder head;

        Stream(TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>> levels, Order taker,
               Map<String, Long> consumed, TradeType type) {
            this.levelIt = iterator(levels, ascending(taker.side(), type));
            this.consumed = consumed;
            this.takerOrderId = taker.orderId();
            this.takerSide = taker.side();
            this.type = type;
            this.takerLimit = taker.price();
            if (type == TradeType.NORMAL) {
                this.makerOutcome = taker.outcome();
                this.makerSide = taker.side().other();
            } else {
                this.makerOutcome = taker.outcome().other();
                this.makerSide = taker.side();
            }
        }

        /** 下一个还有剩余量的候选，没有就返回 null（本轮撮合结束）。 */
        Candidate peek() {
            while (head == null || remainingOf(head) == 0) {
                if (orderIt != null && orderIt.hasNext()) {
                    RestingOrder next = orderIt.next();
                    if (next.orderId().equals(takerOrderId)) {
                        continue;                       // 自己刚挂上去的残单不和自己成交
                    }
                    if (remainingOf(next) == 0) {
                        continue;                       // 本轮已被前面的成交吃掉了
                    }
                    head = next;
                } else if (levelIt.hasNext()) {
                    Map.Entry<BigDecimal, LinkedHashMap<String, RestingOrder>> level = levelIt.next();
                    if (!eligible(takerPrice(level.getKey()))) {
                        return null;                    // 遍历方向单调，后面的价位只会更不划算
                    }
                    orderIt = level.getValue().values().iterator();
                    continue;
                } else {
                    return null;
                }
                if (!eligible(takerPrice(head.price()))) {
                    return null;
                }
            }
            return new Candidate(head, type, makerOutcome, makerSide, takerPrice(head.price()), remainingOf(head));
        }

        private long remainingOf(RestingOrder order) {
            return order.remaining() - consumed.getOrDefault(order.orderId(), 0L);
        }

        private BigDecimal takerPrice(BigDecimal makerPrice) {
            return type == TradeType.NORMAL ? makerPrice : Money.ONE.subtract(makerPrice);
        }

        /** 任何一方的成交价都不比自己限价差。 */
        private boolean eligible(BigDecimal takerPrice) {
            return takerSide == Side.BUY
                    ? takerPrice.compareTo(takerLimit) <= 0
                    : takerPrice.compareTo(takerLimit) >= 0;
        }
    }
}
