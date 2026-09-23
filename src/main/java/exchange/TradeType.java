package exchange;

/**
 * 四种成交归成三类：
 * NORMAL：同一 outcome 一买一卖；
 * MINT：买 YES 和买 NO 撮合，铸造一对；
 * MERGE：卖 YES 和卖 NO 撮合，销毁一对。
 */
public enum TradeType {
    NORMAL,
    MINT,
    MERGE
}
