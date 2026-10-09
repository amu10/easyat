package io.github.easyat.spring;

/** 根 AT 事务的灰度决策 SPI。自定义配置中心可提供自己的 Spring Bean 覆盖默认实现。 决策器只决定是否创建新 XID，不能用于跳过已有 XID 的 undo 或分支处理。 */
public interface AtGrayDecider {
    AtGrayDecision decide(AtGrayRequest request);
}
