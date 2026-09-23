package exchange;

/** 只有限价单。 */
public enum Side {
    BUY,
    SELL;

    public Side other() {
        return this == BUY ? SELL : BUY;
    }
}
