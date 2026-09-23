package exchange.io;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import exchange.AccountView;
import exchange.Event;
import exchange.Money;
import exchange.Order;
import exchange.Outcome;
import exchange.Side;
import exchange.core.EventSourcedExchange;

/**
 * 评分适配器：把操作脚本跑成逐字节可比的输出。
 *
 * <p>脚本每行一条（空行与 # 开头的注释行忽略）：
 * <pre>
 * FEE_RATE &lt;rate&gt;
 * DEPOSIT &lt;accountId&gt; &lt;amount&gt;
 * SUBMIT &lt;orderId&gt; &lt;accountId&gt; &lt;YES|NO&gt; &lt;BUY|SELL&gt; &lt;price&gt; &lt;qty&gt;
 * CANCEL &lt;orderId&gt;
 * </pre>
 *
 * <p>输出先按事件发生顺序逐行，再输出全部 deposit 过的账户（accountId 字典序），
 * 然后一行抵押池，最后一行手续费账户。价格 2 位小数、金额一律 4 位小数、
 * qty 与份额是不带小数点的整数、rate 原样输出，文件以换行结尾。
 */
public final class ScriptRunner {

    private ScriptRunner() {
    }

    public static String run(String script) {
        EventSourcedExchange exchange = new EventSourcedExchange();
        StringBuilder out = new StringBuilder();
        int lineNo = 0;
        for (String rawLine : script.split("\n", -1)) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] token = line.split("\\s+");
            try {
                switch (token[0]) {
                    case "FEE_RATE" -> {
                        requireTokens(token, 2, line);
                        BigDecimal rate = new BigDecimal(token[1]);
                        exchange.setFeeRate(rate);
                        out.append("FEE_RATE_SET ").append(rate.toPlainString()).append('\n');
                    }
                    case "DEPOSIT" -> {
                        requireTokens(token, 3, line);
                        BigDecimal amount = new BigDecimal(token[2]);
                        exchange.deposit(token[1], amount);
                        out.append("DEPOSITED ").append(token[1]).append(' ')
                                .append(Money.formatAmount(amount)).append('\n');
                    }
                    case "SUBMIT" -> {
                        requireTokens(token, 7, line);
                        printEvents(out, exchange.submit(parseOrder(token)));
                    }
                    case "CANCEL" -> {
                        requireTokens(token, 2, line);
                        printEvents(out, exchange.cancel(token[1]));
                    }
                    default -> throw new IllegalArgumentException("未知指令：" + token[0]);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("脚本第 " + lineNo + " 行处理失败：" + line, e);
            }
        }
        for (String accountId : exchange.accountIds()) {
            AccountView view = exchange.account(accountId);
            out.append("ACCOUNT ").append(accountId)
                    .append(' ').append(Money.formatAmount(view.available()))
                    .append(' ').append(Money.formatAmount(view.frozen()))
                    .append(' ').append(view.frozenYes())
                    .append(' ').append(view.frozenNo())
                    .append(' ').append(view.posYes())
                    .append(' ').append(view.posNo())
                    .append('\n');
        }
        out.append("POOL ").append(Money.formatAmount(exchange.collateralPool())).append('\n');
        out.append("FEES ").append(Money.formatAmount(exchange.feeAccount())).append('\n');
        return out.toString();
    }

    private static Order parseOrder(String[] token) {
        BigDecimal price = new BigDecimal(token[5]);
        long qty = Long.parseLong(token[6]);
        if (qty <= 0) {
            throw new IllegalArgumentException("qty 必须是正整数：" + token[6]);
        }
        return new Order(token[1], token[2],
                Outcome.valueOf(token[3].toUpperCase(Locale.ROOT)),
                Side.valueOf(token[4].toUpperCase(Locale.ROOT)),
                price, qty);
    }

    private static void printEvents(StringBuilder out, List<Event> events) {
        for (Event event : events) {
            if (event instanceof Event.OrderAccepted accepted) {
                out.append("ACCEPTED ").append(accepted.orderId()).append('\n');
            } else if (event instanceof Event.OrderRejected rejected) {
                out.append("REJECTED ").append(rejected.orderId()).append(' ')
                        .append(rejected.reason()).append('\n');
            } else if (event instanceof Event.Trade trade) {
                out.append("TRADE ").append(trade.type())
                        .append(' ').append(trade.qty())
                        .append(' ').append(trade.takerOrderId())
                        .append(' ').append(trade.takerOutcome())
                        .append(' ').append(trade.takerSide())
                        .append(' ').append(Money.formatPrice(trade.takerPrice()))
                        .append(' ').append(Money.formatAmount(trade.takerAmount()))
                        .append(' ').append(Money.formatAmount(trade.takerFee()))
                        .append(' ').append(trade.makerOrderId())
                        .append(' ').append(trade.makerOutcome())
                        .append(' ').append(trade.makerSide())
                        .append(' ').append(Money.formatPrice(trade.makerPrice()))
                        .append(' ').append(Money.formatAmount(trade.makerAmount()))
                        .append(' ').append(Money.formatAmount(trade.makerFee()))
                        .append('\n');
            } else if (event instanceof Event.OrderCancelled cancelled) {
                out.append("CANCELLED ").append(cancelled.orderId()).append(' ')
                        .append(cancelled.remainingQty()).append('\n');
            } else if (event instanceof Event.CancelRejected cancelRejected) {
                out.append("CANCEL_REJECTED ").append(cancelRejected.orderId()).append('\n');
            } else {
                throw new IllegalStateException("不该出现在 submit / cancel 返回值里的事件：" + event);
            }
        }
    }

    private static void requireTokens(String[] token, int expected, String line) {
        if (token.length < expected) {
            throw new IllegalArgumentException("字段数不足（需要 " + expected + "）：" + line);
        }
    }
}
