package io.github.easyat.spring;

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/** 安全边界较小的灰度 Key SpEL 解析器：只暴露方法参数，不注册 BeanResolver。 */
public final class AtGrayKeyResolver {
    private final ExpressionParser parser = new SpelExpressionParser();

    public String resolve(ProceedingJoinPoint point, String expression) {
        if (expression == null || expression.trim().isEmpty()) return null;
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        Object[] args = point.getArgs();
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        String[] names = signature.getParameterNames();
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
            if (names != null && i < names.length && names[i] != null)
                context.setVariable(names[i], args[i]);
        }
        Object value = parser.parseExpression(expression).getValue(context);
        return value == null ? null : String.valueOf(value);
    }
}
