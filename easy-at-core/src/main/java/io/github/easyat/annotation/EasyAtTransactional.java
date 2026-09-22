package io.github.easyat.annotation;
import java.lang.annotation.*;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface EasyAtTransactional { String name() default ""; long timeout() default 30000L; }
