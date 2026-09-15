package cn.deepassistant.tool;

import cn.deepassistant.util.SafeMathEval;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 注册进 Harness {@code Toolkit} 的通用工具。HTTP 接口不会直接调这些方法。
 *
 * <p><b>何时调用（框架）：</b>大模型在 ReAct 循环里决定用某个工具后，
 * {@code io.agentscope.core.tool.ToolExecutor#callTool} 按工具名找到
 * {@code ReflectiveFunctionTool}，再反射调用下面带 {@code @Tool} 的方法。
 * 只在 {@code harnessAgent.streamEvents} 进行中发生。
 */
@Component
public class CommonTools {

    /**
     * 返回本机当前日期时间（中文星期）。
     *
     * <p><b>何时调用（框架）：</b>模型认为需要「现在几点/今天星期几」时发出 {@code getCurrentDateTime} 工具调用。
     */
    @Tool(name = "getCurrentDateTime", description = "获取当前精确日期、时间与星期。",
            readOnly = true, concurrencySafe = true)
    public String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss")) + "，" + weekday;
    }

    /**
     * 安全求值四则运算（无 ScriptEngine）。
     *
     * <p><b>何时调用（框架）：</b>模型发出 {@code calculate}，参数 {@code expression} 由框架从 ToolUseBlock 填入。
     */
    @Tool(name = "calculate", description = "计算数学表达式，支持加减乘除与括号。",
            readOnly = true, concurrencySafe = true)
    public String calculate(
            @ToolParam(name = "expression", description = "数学表达式，如 (3+5)*2")
            String expression) {
        try {
            return expression + " = " + SafeMathEval.evalToString(expression);
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }
}
