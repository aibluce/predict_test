package exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import exchange.io.ScriptRunner;

/** 评分适配器：输出格式必须逐字节对得上。 */
class ScriptAdapterTest {

    @Test
    @DisplayName("走一遍例子的脚本_输出逐字节一致")
    void exampleScript_outputIsByteExact() {
        String script = """
                DEPOSIT A 100
                DEPOSIT B 100
                DEPOSIT C 100
                SUBMIT oA A YES BUY 0.60 10
                SUBMIT oB B YES SELL 0.60 4
                SUBMIT oC C NO BUY 0.40 8
                """;
        String expected = """
                DEPOSITED A 100.0000
                DEPOSITED B 100.0000
                DEPOSITED C 100.0000
                ACCEPTED oA
                REJECTED oB INSUFFICIENT_SHARES
                ACCEPTED oC
                TRADE MINT 8 oC NO BUY 0.40 3.2000 0.0000 oA YES BUY 0.60 4.8000 0.0000
                ACCOUNT A 94.0000 1.2000 0 0 8 0
                ACCOUNT B 100.0000 0.0000 0 0 0 0
                ACCOUNT C 96.8000 0.0000 0 0 0 8
                POOL 8.0000
                FEES 0.0000
                """;
        assertEquals(expected, ScriptRunner.run(script));
    }

    @Test
    @DisplayName("含铸造销毁撤单的脚本_输出逐字节一致")
    void mintMergeCancelScript_outputIsByteExact() {
        String script = """
                DEPOSIT A 100
                DEPOSIT B 100
                SUBMIT m1 A YES BUY 0.50 5
                SUBMIT m2 B NO BUY 0.50 5
                SUBMIT s1 A YES SELL 0.40 5
                SUBMIT s2 B NO SELL 0.60 5
                SUBMIT b1 A YES BUY 0.60 3
                CANCEL b1
                CANCEL b1
                """;
        // 手算：A 100 −2.50 +2.00 −1.80 +1.80 = 99.50；B 100 −2.50 +3.00 = 100.50；池 5 −5 = 0
        String expected = """
                DEPOSITED A 100.0000
                DEPOSITED B 100.0000
                ACCEPTED m1
                ACCEPTED m2
                TRADE MINT 5 m2 NO BUY 0.50 2.5000 0.0000 m1 YES BUY 0.50 2.5000 0.0000
                ACCEPTED s1
                ACCEPTED s2
                TRADE MERGE 5 s2 NO SELL 0.60 3.0000 0.0000 s1 YES SELL 0.40 2.0000 0.0000
                ACCEPTED b1
                CANCELLED b1 3
                CANCEL_REJECTED b1
                ACCOUNT A 99.5000 0.0000 0 0 0 0
                ACCOUNT B 100.5000 0.0000 0 0 0 0
                POOL 0.0000
                FEES 0.0000
                """;
        assertEquals(expected, ScriptRunner.run(script));
    }

    @Test
    @DisplayName("费率为零时可以设置_原样输出")
    void zeroFeeRateScript_isAcceptedAndPrintedVerbatim() {
        String out = ScriptRunner.run("FEE_RATE 0\nDEPOSIT A 100\n");
        assertEquals("""
                FEE_RATE_SET 0
                DEPOSITED A 100.0000
                ACCOUNT A 100.0000 0.0000 0 0 0 0
                POOL 0.0000
                FEES 0.0000
                """, out);
    }

    @Test
    @DisplayName("带费率的脚本_输出逐字节一致")
    void feeScript_outputIsByteExact() {
        String script = """
                FEE_RATE 0.002
                DEPOSIT A 100
                DEPOSIT B 100
                SUBMIT a1 A YES BUY 0.60 10
                SUBMIT b1 B NO BUY 0.40 8
                CANCEL a1
                """;
        // 手算：a1 冻结 6.00 + ceil4(0.012) = 6.0120；b1 冻结 3.20 + ceil4(0.0064) = 3.2064
        // 铸造 8 张：taker b1 付 3.2000、费 0.0064；maker a1 付 4.8000、费 0.0096
        // a1 剩余 2 张的冻结重定为 1.20 + ceil4(0.0024) = 1.2024，撤单释放 1.2024
        String expected = """
                FEE_RATE_SET 0.002
                DEPOSITED A 100.0000
                DEPOSITED B 100.0000
                ACCEPTED a1
                ACCEPTED b1
                TRADE MINT 8 b1 NO BUY 0.40 3.2000 0.0064 a1 YES BUY 0.60 4.8000 0.0096
                CANCELLED a1 2
                ACCOUNT A 95.1904 0.0000 0 0 8 0
                ACCOUNT B 96.7936 0.0000 0 0 0 8
                POOL 8.0000
                FEES 0.0160
                """;
        assertEquals(expected, ScriptRunner.run(script));
    }
}
