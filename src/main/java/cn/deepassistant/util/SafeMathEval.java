package cn.deepassistant.util;

/**
 * 只支持 {@code + - * / ()} 和小数的表达式求值，给 {@code calculate} 工具用。
 * 不用 {@code ScriptEngine} / 反射，避免模型把任意 Java 表达式塞进来。
 *
 * <p>文法：expression → term {(+|-) term}*；term → factor {(*|/) factor}*；
 * factor → +factor | -factor | (expression) | number。
 */
public final class SafeMathEval {

    private SafeMathEval() {
    }

    /**
     * 求值成 double。空白抛错；除以零在 {@link Parser#parseTerm()} 里单独拦。
     *
     * <p><b>何时调用：</b>{@link #evalToString}；单测。框架不直接调。
     */
    public static double eval(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式为空");
        }
        // 去掉空白后再解析，"( 1 + 2 )" 和 "(1+2)" 等价
        return new Parser(expression.replaceAll("\\s+", "")).parse();
    }

    /**
     * 给人看的结果字符串：能表示成整数就去掉小数点；NaN / Inf 当成非法（例如 0/0）。
     *
     * <p><b>何时调用（框架间接）：</b>{@code CommonTools.calculate} ← {@code ToolExecutor}。
     */
    public static String evalToString(String expression) {
        double v = eval(expression);
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new ArithmeticException("计算结果非法（可能除以零）");
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** 递归下降解析器，一次扫完整个去掉空白的表达式。 */
    private static final class Parser {
        private final String s;
        /** 当前字符下标；{@link #next()} 会先 ++ 再读。 */
        private int pos = -1;
        /** 当前字符的码点；扫完后为 -1。 */
        private int ch;

        Parser(String s) {
            this.s = s;
        }

        void next() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        /** 若当前字符是 c 则吃掉并返回 true。 */
        boolean eat(int c) {
            if (ch == c) {
                next();
                return true;
            }
            return false;
        }

        double parse() {
            next();
            double x = parseExpression();
            if (pos < s.length()) {
                throw new IllegalArgumentException("意外字符: " + (char) ch);
            }
            return x;
        }

        /** 加减，优先级最低。 */
        double parseExpression() {
            double x = parseTerm();
            for (; ; ) {
                if (eat('+')) {
                    x += parseTerm();
                } else if (eat('-')) {
                    x -= parseTerm();
                } else {
                    return x;
                }
            }
        }

        /** 乘除。除数为 0 立刻抛，不让 Inf 漏到外层才发现。 */
        double parseTerm() {
            double x = parseFactor();
            for (; ; ) {
                if (eat('*')) {
                    x *= parseFactor();
                } else if (eat('/')) {
                    double d = parseFactor();
                    if (d == 0.0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    x /= d;
                } else {
                    return x;
                }
            }
        }

        /** 一元正负、括号、字面量数字。不支持函数名，遇到字母直接失败。 */
        double parseFactor() {
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }
            double x;
            int start = pos;
            if (eat('(')) {
                x = parseExpression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("缺少右括号");
                }
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    next();
                }
                x = Double.parseDouble(s.substring(start, pos));
            } else {
                throw new IllegalArgumentException("无法解析: " + s);
            }
            return x;
        }
    }
}
