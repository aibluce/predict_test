package exchange.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 评分入口：读脚本文件路径，把结果打到标准输出。
 *
 * <pre>
 * ./gradlew runScript -Pscript=scripts/sample_nofee.txt
 * java -cp build/classes/java/main exchange.io.Main scripts/sample_nofee.txt
 * </pre>
 *
 * 不给参数时从标准输入读脚本。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String script = args.length > 0
                ? Files.readString(Path.of(args[0]), StandardCharsets.UTF_8)
                : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.print(ScriptRunner.run(script));
        System.out.flush();
    }
}
