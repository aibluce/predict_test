package exchange.book;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import exchange.Outcome;
import exchange.Side;

/**
 * 两个 outcome × 两侧（BUY / SELL）的限价盘口。
 *
 * <ul>
 *   <li>价位用 TreeMap 排序：价格优先靠它；</li>
 *   <li>每个价位内的订单用 LinkedHashMap 按插入顺序排列：同价时间优先靠它，
 *       同时按 orderId 删除是 O(1)（撤单不必扫整个价位）；</li>
 *   <li>orderId -> 订单的索引用于 O(1) 定位。</li>
 * </ul>
 */
public final class OrderBook {

    private final EnumMap<Outcome, EnumMap<Side, TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>>>> books;
    private final Map<String, RestingOrder> byId = new HashMap<>();

    public OrderBook() {
        books = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            EnumMap<Side, TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>>> sides = new EnumMap<>(Side.class);
            for (Side side : Side.values()) {
                sides.put(side, new TreeMap<>());
            }
            books.put(outcome, sides);
        }
    }

    /** 某个 (outcome, side) 的价格 -> 该价位的订单（插入顺序）。 */
    public TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>> levels(Outcome outcome, Side side) {
        return books.get(outcome).get(side);
    }

    public void add(RestingOrder order) {
        if (byId.containsKey(order.orderId())) {
            throw new IllegalStateException("duplicate resting order " + order.orderId());
        }
        levels(order.outcome(), order.side())
                .computeIfAbsent(order.price(), p -> new LinkedHashMap<>())
                .put(order.orderId(), order);
        byId.put(order.orderId(), order);
    }

    public RestingOrder get(String orderId) {
        return byId.get(orderId);
    }

    /** 成交后扣减未成交张数，减到 0 就从盘口移除。 */
    public void reduce(RestingOrder order, long qty) {
        order.reduce(qty);
        if (order.remaining() == 0) {
            remove(order.orderId());
        }
    }

    public void remove(String orderId) {
        RestingOrder order = byId.remove(orderId);
        if (order == null) {
            return;
        }
        TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>> side = levels(order.outcome(), order.side());
        LinkedHashMap<String, RestingOrder> level = side.get(order.price());
        if (level != null) {
            level.remove(orderId);
            if (level.isEmpty()) {
                side.remove(order.price());
            }
        }
    }

    /** 该 outcome 上挂着的最高买价（不含铸造/销毁合成出来的价）。 */
    public BigDecimal rawBestBid(Outcome outcome) {
        TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>> bids = levels(outcome, Side.BUY);
        return bids.isEmpty() ? null : bids.lastKey();
    }

    /** 该 outcome 上挂着的最低卖价（不含铸造/销毁合成出来的价）。 */
    public BigDecimal rawBestAsk(Outcome outcome) {
        TreeMap<BigDecimal, LinkedHashMap<String, RestingOrder>> asks = levels(outcome, Side.SELL);
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** 所有未完结订单（不保证顺序），遍历用。 */
    public Collection<RestingOrder> openOrders() {
        return byId.values();
    }

    public int openOrderCount() {
        return byId.size();
    }

    /** 只为调试与测试用的一小段文本。 */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (Outcome outcome : Outcome.values()) {
            for (Side side : Side.values()) {
                for (Map.Entry<BigDecimal, LinkedHashMap<String, RestingOrder>> e : levels(outcome, side).entrySet()) {
                    sb.append(outcome).append(' ').append(side).append(' ').append(e.getKey()).append(" -> ");
                    sb.append(new ArrayDeque<>(e.getValue().values())).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
