package io.github.easyat.annotation;

import java.lang.annotation.*;

/**
 * 标记一个方法需要运行在 easyAt 的 AT（Automatic Transaction，自动事务）全局事务中。
 *
 * <p>被该注解修饰的方法在 AOP 拦截下会：开启（或加入）一个全局事务、绑定到当前线程上下文、 在方法成功返回时提交、在抛出异常时回滚，并自动处理 undo log
 * 的写入与全局锁的获取/释放。
 *
 * <p>只作用于方法（{@code @Target(METHOD)}），并在运行时保留（{@code RUNTIME}）， 以便 AOP 框架在运行期读取其属性。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EasyAtTransactional {
    /** 事务名称，仅用于观测/日志，可为空（默认空字符串）。 */
    String name() default "";

    /** 全局事务超时时间（毫秒）。超过该时长未提交且未被恢复调度接管的事务将被判定为超时回滚。默认 30000 毫秒（30 秒）。 */
    long timeout() default 30000L;
}
