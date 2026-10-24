# Spring 语料

> MockVerse RAG 语料库 · 分类 SPRING · 共 7 篇

## Spring Bean 的生命周期

关键阶段（按顺序）：

1. **实例化**：反射调用构造器创建对象。
2. **属性填充**：依赖注入。
3. **Aware 回调**：BeanNameAware → BeanFactoryAware → ApplicationContextAware。
4. **BeanPostProcessor.postProcessBeforeInitialization**。
5. **初始化**：@PostConstruct → InitializingBean.afterPropertiesSet → init-method。
6. **BeanPostProcessor.postProcessAfterInitialization**：AOP 代理在这一步生成。
7. **使用**。
8. **销毁**：@PreDestroy → DisposableBean.destroy → destroy-method。

面试高频追问：`@Transactional` 在同类内部方法调用时为什么失效？因为自调用走的是原始对象引用，不经过代理，事务增强根本没机会生效。解决方案：注入自身代理、`AopContext.currentProxy()`、或拆到另一个 Bean。

## Spring 的三级缓存与循环依赖

Spring 用三级缓存解决单例 Bean 的**字段注入**循环依赖：

- 一级 `singletonObjects`：成品 Bean。
- 二级 `earlySingletonObjects`：提前暴露的半成品。
- 三级 `singletonFactories`：ObjectFactory，用于生成早期引用（可能是 AOP 代理）。

流程：A 实例化后先把 ObjectFactory 放入三级缓存，注入 B 时发现 B 依赖 A，从三级缓存拿到 A 的早期引用（必要时提前创建代理）放入二级缓存，B 完成创建，A 再完成属性填充与初始化。

**无法解决的场景**：构造器注入的循环依赖（对象还没实例化就要对方）、prototype 作用域的循环依赖。Spring Boot 2.6 起默认禁止循环依赖，需要显式开启 `spring.main.allow-circular-references=true`。

## AOP 的实现原理

Spring AOP 基于**动态代理**：

- 目标类实现了接口 → JDK 动态代理（`java.lang.reflect.Proxy`），代理类实现同一接口。
- 目标类没有接口 → CGLIB 生成子类（Spring Boot 2.x 起默认 `proxyTargetClass=true`，统一用 CGLIB）。

调用链：代理对象 → 拦截器链（MethodInterceptor）→ 目标方法。多个切面按 `@Order` 排序组成责任链。

**局限**：只能拦截 public 方法的外部调用；同类自调用不生效；final 类和 final 方法无法被 CGLIB 代理。

AspectJ 是编译期/加载期织入，能力更强（可拦截 private、构造器），但需要额外配置，Spring 默认不用。

## @Transactional 的失效场景

1. **同类内部调用**：不走代理。
2. **方法不是 public**：Spring 5.x 之前代理不拦截非 public 方法。
3. **异常被 catch 吞掉**：容器感知不到异常，不会回滚。
4. **抛出受检异常**：默认只对 RuntimeException 和 Error 回滚，需 `rollbackFor = Exception.class`。
5. **类没有被 Spring 管理**：没有 @Component/@Service，代理不生成。
6. **数据库引擎不支持事务**：如 MyISAM。
7. **多数据源未配置事务管理器**。
8. **传播行为设置为 NOT_SUPPORTED / NEVER**。

## Bean 的作用域

- `singleton`（默认）：容器内单实例。
- `prototype`：每次获取创建新实例，容器不管理销毁。
- `request` / `session` / `application`：Web 环境下的作用域。
- `websocket`：WebSocket 会话作用域。

**注意**：单例 Bean 注入 prototype Bean 时，prototype 只会在单例创建时被注入一次，之后一直是同一个对象。要每次拿新的，需要注入 `ObjectProvider` 或使用 `@Lookup`。

## Spring MVC 的请求处理流程

1. 请求到达 **DispatcherServlet**（前端控制器）。
2. 遍历 **HandlerMapping** 找到匹配的 Handler（Controller 方法）及拦截器链。
3. **HandlerAdapter** 调用 Handler，期间完成参数绑定（`HandlerMethodArgumentResolver`）与数据校验。
4. 返回 **ModelAndView**（或 `@ResponseBody` 时由 `HttpMessageConverter` 直接写响应）。
5. **ViewResolver** 解析视图名，渲染视图。
6. 响应返回，执行拦截器的 `afterCompletion`。

面试常问：`@RequestBody` 由 `RequestResponseBodyMethodProcessor` + `HttpMessageConverter` 处理；参数名不匹配时用 `@RequestParam` / `@PathVariable` 显式指定。

## Spring Boot 自动配置的原理

入口是 `@SpringBootApplication`，它组合了三个注解：

- `@SpringBootConfiguration`：标记配置类。
- `@EnableAutoConfiguration`：核心，导入 `AutoConfigurationImportSelector`。
- `@ComponentScan`：扫描当前包及子包。

自动配置流程：`@EnableAutoConfiguration` 通过 `@Import(AutoConfigurationImportSelector.class)` 读取 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（Boot 2.7+ 的新位置，之前是 `spring.factories`），拿到候选配置类列表，再经 `@ConditionalOnClass`、`@ConditionalOnMissingBean` 等条件注解过滤，最终把符合条件的配置类注册进容器。

**自定义 starter** 的要点：写一个 `@Configuration` + `@ConditionalOnMissingBean`，在 imports 文件里登记全限定名，业务方引入依赖即自动生效。
