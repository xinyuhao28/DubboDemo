/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.beans.factory.annotation;

import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import org.apache.dubbo.config.spring.util.AnnotationUtils;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.AnnotationAttributes;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.lang.reflect.Proxy.newProxyInstance;
import static org.apache.dubbo.config.spring.beans.factory.annotation.ServiceBeanNameBuilder.create;
import static org.apache.dubbo.config.spring.util.AnnotationUtils.getAttribute;
import static org.springframework.util.StringUtils.hasText;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link Reference} annotated fields
 *
 * @since 2.5.7
 */
public class ReferenceAnnotationBeanPostProcessor extends AnnotationInjectedBeanPostProcessor implements
        ApplicationContextAware, ApplicationListener {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    /**
     * Cache size
     */
    private static final int CACHE_SIZE = Integer.getInteger(BEAN_NAME + ".cache.size", 32);

    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeanCache =
            new ConcurrentHashMap<>(CACHE_SIZE);

    private final ConcurrentHashMap<String, ReferenceBeanInvocationHandler> localReferenceBeanInvocationHandlerCache =
            new ConcurrentHashMap<>(CACHE_SIZE);

    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedFieldReferenceBeanCache =
            new ConcurrentHashMap<>(CACHE_SIZE);

    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedMethodReferenceBeanCache =
            new ConcurrentHashMap<>(CACHE_SIZE);

    private ApplicationContext applicationContext;

    /**
     * To support the legacy annotation that is @com.alibaba.dubbo.config.annotation.Reference since 2.7.3
     */
    public ReferenceAnnotationBeanPostProcessor() {
        super(Reference.class, com.alibaba.dubbo.config.annotation.Reference.class);
    }

    /**
     * Gets all beans of {@link ReferenceBean}
     *
     * @return non-null read-only {@link Collection}
     * @since 2.5.9
     */
    public Collection<ReferenceBean<?>> getReferenceBeans() {
        return referenceBeanCache.values();
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected field.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedFieldReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedFieldReferenceBeanCache);
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedMethodReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedMethodReferenceBeanCache);
    }

    // 该方法得到的对象会赋值给@ReferenceBean注解的属性
    //
    @Override
    protected Object doGetInjectedBean(AnnotationAttributes attributes, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {

        /**
         * The name of bean that annotated Dubbo's {@link Service @Service} in local Spring {@link ApplicationContext}
         */
        // 得到引入服务的beanName
        // attributes里存的是@Reference注解中的所配置的属性与值
        // injectedType表示引入的是哪个服务接口
        // referencedBeanName的值为  ServiceBean:org.apache.dubbo.demo.DemoService  表示得到该服务Bean的beanName
        // referencedBeanName表示 我现在要引用的这个服务，它导出时对应的ServiceBean的beanName是什么，可以用来判断现在我引用的这个服务是不是我自己导出的
        String referencedBeanName = buildReferencedBeanName(attributes, injectedType);

        /**
         * The name of bean that is declared by {@link Reference @Reference} annotation injection
         */
        // @Reference(methods=[Lorg.apache.dubbo.config.annotation.Method;@39b43d60) org.apache.dubbo.demo.DemoService
        // 我要生成一个RefrenceBean，对应的beanName， 根据@Reference注解来标识不同
        String referenceBeanName = getReferenceBeanName(attributes, injectedType);

        // 生成一个ReferenceBean对象
        ReferenceBean referenceBean = buildReferenceBeanIfAbsent(referenceBeanName, attributes, injectedType);

        // 把referenceBean添加到Spring容器中去
        registerReferenceBean(referencedBeanName, referenceBean, attributes, injectedType);

        cacheInjectedReferenceBean(referenceBean, injectedElement);

        // 创建一个代理对象，Service中的属性被注入的就是这个代理对象
        // 内部会调用referenceBean.get();
        return getOrCreateProxy(referencedBeanName, referenceBeanName, referenceBean, injectedType);
    }

    /**
     * Register an instance of {@link ReferenceBean} as a Spring Bean
     *
     * @param referencedBeanName The name of bean that annotated Dubbo's {@link Service @Service} in the Spring {@link ApplicationContext}
     * @param referenceBean      the instance of {@link ReferenceBean} is about to register into the Spring {@link ApplicationContext}
     * @param attributes         the {@link AnnotationAttributes attributes} of {@link Reference @Reference}
     * @param interfaceClass     the {@link Class class} of Service interface
     * @since 2.7.3
     */
    private void registerReferenceBean(String referencedBeanName, ReferenceBean referenceBean,
                                       AnnotationAttributes attributes,
                                       Class<?> interfaceClass) {

        ConfigurableListableBeanFactory beanFactory = getBeanFactory();

        // @Reference(parameters=[Ljava.lang.String;@72ef8d15) org.apache.dubbo.demo.DemoService
        // ReferenceBean的beanName，注意这个beanName，它是直接取的@Reference的全信息
        // 所以，就算引用的是同一个服务，如果@Reference注解上的信息不同，那么就会生成不同的ReferenceBean
        String beanName = getReferenceBeanName(attributes, interfaceClass);

        // 要引入的服务就是本地提供的一个服务
        if (existsServiceBean(referencedBeanName)) { // If @Service bean is local one
            /**
             * Get  the @Service's BeanDefinition from {@link BeanFactory}
             * Refer to {@link ServiceAnnotationBeanPostProcessor#buildServiceBeanDefinition}
             */
            AbstractBeanDefinition beanDefinition = (AbstractBeanDefinition) beanFactory.getBeanDefinition(referencedBeanName);
            RuntimeBeanReference runtimeBeanReference = (RuntimeBeanReference) beanDefinition.getPropertyValues().get("ref"); // ServiceBean --- ref
            // The name of bean annotated @Service
            String serviceBeanName = runtimeBeanReference.getBeanName();
            // register Alias rather than a new bean name, in order to reduce duplicated beans
            // 如果是本地提供的一个服务，那么就@Reference(parameters=[Ljava.lang.String;@72ef8d15) org.apache.dubbo.demo.DemoService
            // 的别名是demoService，不需要是ServiceBean的名字
            beanFactory.registerAlias(serviceBeanName, beanName);
        } else { // Remote @Service Bean
            if (!beanFactory.containsBean(beanName)) {
                beanFactory.registerSingleton(beanName, referenceBean);
            }
        }
    }

    /**
     * Get the bean name of {@link ReferenceBean} if {@link Reference#id() id attribute} is present,
     * or {@link #generateReferenceBeanName(AnnotationAttributes, Class) generate}.
     *
     * @param attributes     the {@link AnnotationAttributes attributes} of {@link Reference @Reference}
     * @param interfaceClass the {@link Class class} of Service interface
     * @return non-null
     * @since 2.7.3
     */
    private String getReferenceBeanName(AnnotationAttributes attributes, Class<?> interfaceClass) {
        // id attribute appears since 2.7.3
        String beanName = getAttribute(attributes, "id");

        // beanName为null时会进入if判断
        if (!hasText(beanName)) {
            beanName = generateReferenceBeanName(attributes, interfaceClass);
        }
        return beanName;
    }

    /**
     * Build the bean name of {@link ReferenceBean}
     *
     * @param attributes     the {@link AnnotationAttributes attributes} of {@link Reference @Reference}
     * @param interfaceClass the {@link Class class} of Service interface
     * @return
     * @since 2.7.3
     */
    private String generateReferenceBeanName(AnnotationAttributes attributes, Class<?> interfaceClass) {
        StringBuilder beanNameBuilder = new StringBuilder("@Reference");

        if (!attributes.isEmpty()) {
            beanNameBuilder.append('(');
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                beanNameBuilder.append(entry.getKey())
                        .append('=')
                        .append(entry.getValue())
                        .append(',');
            }
            // replace the latest "," to be ")"
            beanNameBuilder.setCharAt(beanNameBuilder.lastIndexOf(","), ')');
        }

        beanNameBuilder.append(" ").append(interfaceClass.getName());

        return beanNameBuilder.toString();
    }

    private boolean existsServiceBean(String referencedBeanName) {
        return applicationContext.containsBean(referencedBeanName);
    }

    /**
     * Get or Create a proxy of {@link ReferenceBean} for the specified the type of Dubbo service interface
     *
     * @param referencedBeanName   The name of bean that annotated Dubbo's {@link Service @Service} in the Spring {@link ApplicationContext}
     * @param referenceBeanName    the bean name of {@link ReferenceBean}
     * @param referenceBean        the instance of {@link ReferenceBean}
     * @param serviceInterfaceType the type of Dubbo service interface
     * @return non-null
     * @since 2.7.4
     */
    private Object getOrCreateProxy(String referencedBeanName, String referenceBeanName, ReferenceBean referenceBean, Class<?> serviceInterfaceType) {
        if (existsServiceBean(referencedBeanName)) { // If the local @Service Bean exists, build a proxy of ReferenceBean
            return newProxyInstance(getClassLoader(), new Class[]{serviceInterfaceType},
                    wrapInvocationHandler(referenceBeanName, referenceBean));
        } else {                                    // ReferenceBean should be initialized and get immediately
            // 重点
            return referenceBean.get();
        }
    }

    /**
     * Wrap an instance of {@link InvocationHandler} that is used to get the proxy of {@link ReferenceBean} after
     * the specified local referenced bean that annotated {@link @Service} exported.
     *
     * @param referenceBeanName the bean name of {@link ReferenceBean}
     * @param referenceBean     the instance of {@link ReferenceBean}
     * @return non-null
     * @since 2.7.4
     */
    private InvocationHandler wrapInvocationHandler(String referenceBeanName, ReferenceBean referenceBean) {
        return localReferenceBeanInvocationHandlerCache.computeIfAbsent(referenceBeanName, name ->
                new ReferenceBeanInvocationHandler(referenceBean));
    }

    private static class ReferenceBeanInvocationHandler implements InvocationHandler {

        private final ReferenceBean referenceBean;

        private Object bean;

        private ReferenceBeanInvocationHandler(ReferenceBean referenceBean) {
            this.referenceBean = referenceBean;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                if (bean == null) { // If the bean is not initialized, invoke init()
                    // issue: https://github.com/apache/dubbo/issues/3429
                    init();
                }
                result = method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                // re-throws the actual Exception.
                throw e.getTargetException();
            }
            return result;
        }

        private void init() {
            this.bean = referenceBean.get();
        }
    }

    @Override
    protected String buildInjectedObjectCacheKey(AnnotationAttributes attributes, Object bean, String beanName,
                                                 Class<?> injectedType, InjectionMetadata.InjectedElement injectedElement) {
        return buildReferencedBeanName(attributes, injectedType) +
                "#source=" + (injectedElement.getMember()) +
                "#attributes=" + AnnotationUtils.resolvePlaceholders(attributes, getEnvironment());
    }

    /**
     * @param attributes           the attributes of {@link Reference @Reference}
     * @param serviceInterfaceType the type of Dubbo's service interface
     * @return The name of bean that annotated Dubbo's {@link Service @Service} in local Spring {@link ApplicationContext}
     */
    private String buildReferencedBeanName(AnnotationAttributes attributes, Class<?> serviceInterfaceType) {
        ServiceBeanNameBuilder serviceBeanNameBuilder = create(attributes, serviceInterfaceType, getEnvironment());
        return serviceBeanNameBuilder.build();
    }

    private ReferenceBean buildReferenceBeanIfAbsent(String referenceBeanName, AnnotationAttributes attributes,
                                                     Class<?> referencedType)
            throws Exception {

        ReferenceBean<?> referenceBean = referenceBeanCache.get(referenceBeanName);

        if (referenceBean == null) {

            // 生成了一个ReferenceBean对象，attributes是@Reference注解的参数值
            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(attributes, applicationContext)
                    .interfaceClass(referencedType);
            referenceBean = beanBuilder.build();

            referenceBeanCache.put(referenceBeanName, referenceBean);
        } else if (!referencedType.isAssignableFrom(referenceBean.getInterfaceClass())) {
            throw new IllegalArgumentException("reference bean name " + referenceBeanName + " has been duplicated, but interfaceClass " +
                    referenceBean.getInterfaceClass().getName() + " cannot be assigned to " + referencedType.getName());
        }
        return referenceBean;
    }

    private void cacheInjectedReferenceBean(ReferenceBean referenceBean,
                                            InjectionMetadata.InjectedElement injectedElement) {
        if (injectedElement.getMember() instanceof Field) {
            injectedFieldReferenceBeanCache.put(injectedElement, referenceBean);
        } else if (injectedElement.getMember() instanceof Method) {
            injectedMethodReferenceBeanCache.put(injectedElement, referenceBean);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ServiceBeanExportedEvent) {
            onServiceBeanExportEvent((ServiceBeanExportedEvent) event);
        } else if (event instanceof ContextRefreshedEvent) {
            onContextRefreshedEvent((ContextRefreshedEvent) event);
        }
    }

    private void onServiceBeanExportEvent(ServiceBeanExportedEvent event) {
        ServiceBean serviceBean = event.getServiceBean();
        initReferenceBeanInvocationHandler(serviceBean);
    }

    private void initReferenceBeanInvocationHandler(ServiceBean serviceBean) {
        String serviceBeanName = serviceBean.getBeanName();
        // Remove ServiceBean when it's exported
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.remove(serviceBeanName);
        // Initialize
        if (handler != null) {
            handler.init();
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {

    }


    @Override
    public void destroy() throws Exception {
        super.destroy();
        this.referenceBeanCache.clear();
        this.localReferenceBeanInvocationHandlerCache.clear();
        this.injectedFieldReferenceBeanCache.clear();
        this.injectedMethodReferenceBeanCache.clear();
    }
}
