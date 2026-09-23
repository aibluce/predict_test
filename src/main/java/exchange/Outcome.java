package exchange;

/** 单市场的两种份额。一张 YES 加一张 NO 合起来等于 1 U。 */
public enum Outcome {
    YES,
    NO;

    public Outcome other() {
        return this == YES ? NO : YES;
    }
}
