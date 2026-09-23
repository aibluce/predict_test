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
