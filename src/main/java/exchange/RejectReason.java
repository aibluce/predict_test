package exchange;

/** 本题仅有的三种拒单原因。 */
public enum RejectReason {
    INSUFFICIENT_FUNDS,
    INSUFFICIENT_SHARES,
    DUPLICATE_ORDER_ID
}
