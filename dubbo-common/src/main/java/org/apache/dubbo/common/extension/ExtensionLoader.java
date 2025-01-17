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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.Extension;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.beans.support.InstantiationStrategy;
import org.apache.dubbo.common.compact.Dubbo2ActivateUtils;
import org.apache.dubbo.common.compact.Dubbo2CompactUtils;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.Disposable;
import org.apache.dubbo.common.utils.*;
import org.apache.dubbo.rpc.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.*;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_ERROR_LOAD_EXTENSION;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_LOAD_ENV_VARIABLE;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see <a href="https://blog.csdn.net/weixin_41947378/article/details/108431966">SPI机制中几种注解介绍（辅助理解）</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */

public class ExtensionLoader<T> {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(
        ExtensionLoader.class);

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    private static final String SPECIAL_SPI_PROPERTIES = "special_spi.properties";

    private final ConcurrentMap<Class<?>, Object> extensionInstances = new ConcurrentHashMap<>(64);

    private final Class<?> type;

    private final ExtensionInjector injector;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private final Map<String, Object> cachedActivates = Collections.synchronizedMap(
        new LinkedHashMap<>());
    private final Map<String, Set<String>> cachedActivateGroups = Collections.synchronizedMap(
        new LinkedHashMap<>());
    private final Map<String, String[][]> cachedActivateValues = Collections.synchronizedMap(
        new LinkedHashMap<>());
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private final Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    private static final Map<String, String> specialSPILoadingStrategyMap = getSpecialSPILoadingStrategyMap();

    /**
     * Map<java.net.URL, List<String>> 作用
     * TODO 为什么用到软引用，垃圾回收？
     */
    private static SoftReference<Map<java.net.URL, List<String>>> urlListMapCache = new SoftReference<>(
        new ConcurrentHashMap<>());

    private static final List<String> ignoredInjectMethodsDesc = getIgnoredInjectMethodsDesc();

    /**
     * Record all unacceptable exceptions when using SPI
     */
    private final Set<String> unacceptableExceptions = new ConcurrentHashSet<>();
    private final ExtensionDirector extensionDirector;
    private final List<ExtensionPostProcessor> extensionPostProcessors;
    private InstantiationStrategy instantiationStrategy;
    private final ActivateComparator activateComparator;
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        return stream(load(LoadingStrategy.class).spliterator(), false).sorted()
            .toArray(LoadingStrategy[]::new);
    }

    /**
     * some spi are implements by dubbo framework only and scan multi classloaders resources may cause
     * application startup very slow
     *
     * @return
     */
    private static Map<String, String> getSpecialSPILoadingStrategyMap() {
        Map map = new ConcurrentHashMap<>();
        Properties properties = loadProperties(ExtensionLoader.class.getClassLoader(),
            SPECIAL_SPI_PROPERTIES);
        map.putAll(properties);
        return map;
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    private static List<String> getIgnoredInjectMethodsDesc() {
        List<String> ignoreInjectMethodsDesc = new ArrayList<>();
        Arrays.stream(ScopeModelAware.class.getMethods()).map(ReflectUtils::getDesc)
            .forEach(ignoreInjectMethodsDesc::add);
        Arrays.stream(ExtensionAccessorAware.class.getMethods()).map(ReflectUtils::getDesc)
            .forEach(ignoreInjectMethodsDesc::add);
        return ignoreInjectMethodsDesc;
    }

    /**
     * 扩展加载器构造函数
     *
     * @param type
     * @param extensionDirector
     * @param scopeModel
     */
    ExtensionLoader(Class<?> type, ExtensionDirector extensionDirector, ScopeModel scopeModel) {
        // 当前扩展器需要加载的扩展类型，如{@link TypeBuilder}，带有SPI注解的接口
        this.type = type;
        // 创建扩展器的作用域扩展加载管理器对象
        this.extensionDirector = extensionDirector;
        // 从扩展访问器中获取扩展执行前后的回调器
        this.extensionPostProcessors = extensionDirector.getExtensionPostProcessors();
        // 创建实例化对象的策略对象
        initInstantiationStrategy();
        // 如果当前扩展类型为扩展注入器类型则设置当前注入器变量为空,否则的话获取一个扩展注入器扩展对象
        // TODO 为什么要单独判断ExtensionInjector
        this.injector = (type == ExtensionInjector.class ?
            null :
            extensionDirector.getExtensionLoader(ExtensionInjector.class).getAdaptiveExtension());
        // 创建Activate注解的排序器
        this.activateComparator = new ActivateComparator(extensionDirector);
        // 为扩展加载器下的域模型对象赋值
        this.scopeModel = scopeModel;
    }

    /**
     * 初始化实例化对象的策略对象
     */
    private void initInstantiationStrategy() {
        instantiationStrategy = extensionPostProcessors.stream()
            .filter(extensionPostProcessor -> extensionPostProcessor instanceof ScopeModelAccessor)
            .map(extensionPostProcessor -> new InstantiationStrategy(
                (ScopeModelAccessor) extensionPostProcessor)).findFirst()
            .orElse(new InstantiationStrategy());
    }

    /**
     * @see ApplicationModel#getExtensionDirector()
     * @see FrameworkModel#getExtensionDirector()
     * @see ModuleModel#getExtensionDirector()
     * @see ExtensionDirector#getExtensionLoader(java.lang.Class)
     * @deprecated get extension loader from extension director of some module.
     */
    @Deprecated
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return ApplicationModel.defaultModel().getDefaultModule().getExtensionLoader(type);
    }

    @Deprecated
    public static void resetExtensionLoader(Class type) {
    }

    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        // destroy raw extension instance
        extensionInstances.forEach((type, instance) -> {
            if (instance instanceof Disposable) {
                Disposable disposable = (Disposable) instance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "",
                        "Error destroying extension " + disposable, e);
                }
            }
        });
        extensionInstances.clear();

        // destroy wrapped extension instance
        for (Holder<Object> holder : cachedInstances.values()) {
            Object wrappedInstance = holder.get();
            if (wrappedInstance instanceof Disposable) {
                Disposable disposable = (Disposable) wrappedInstance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "",
                        "Error destroying extension " + disposable, e);
                }
            }
        }
        cachedInstances.clear();
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionLoader is destroyed: " + type);
        }
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url,
            StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    @SuppressWarnings("deprecation")
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        checkDestroyed();
        // solve the bug of using @SPI's wrapper method to report a null pointer exception.
        Map<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        List<String> names = values == null ?
            new ArrayList<>(0) :
            Arrays.stream(values).map(StringUtils::trim).collect(Collectors.toList());
        Set<String> namesSet = new HashSet<>(names);
        if (!namesSet.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            if (cachedActivateGroups.size() == 0) {
                synchronized (cachedActivateGroups) {
                    // cache all extensions
                    if (cachedActivateGroups.size() == 0) {
                        getExtensionClasses();
                        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                            String name = entry.getKey();
                            Object activate = entry.getValue();

                            String[] activateGroup, activateValue;

                            if (activate instanceof Activate) {
                                activateGroup = ((Activate) activate).group();
                                activateValue = ((Activate) activate).value();
                            } else if (Dubbo2CompactUtils.isEnabled() && Dubbo2ActivateUtils.isActivateLoaded()
                                && Dubbo2ActivateUtils.getActivateClass().isAssignableFrom(activate.getClass())) {
                                activateGroup = Dubbo2ActivateUtils.getGroup((Annotation) activate);
                                activateValue = Dubbo2ActivateUtils.getValue((Annotation) activate);
                            } else {
                                continue;
                            }
                            cachedActivateGroups.put(name,
                                new HashSet<>(Arrays.asList(activateGroup)));
                            String[][] keyPairs = new String[activateValue.length][];
                            for (int i = 0; i < activateValue.length; i++) {
                                if (activateValue[i].contains(":")) {
                                    keyPairs[i] = new String[2];
                                    String[] arr = activateValue[i].split(":");
                                    keyPairs[i][0] = arr[0];
                                    keyPairs[i][1] = arr[1];
                                } else {
                                    keyPairs[i] = new String[1];
                                    keyPairs[i][0] = activateValue[i];
                                }
                            }
                            cachedActivateValues.put(name, keyPairs);
                        }
                    }
                }
            }

            // traverse all cached extensions
            cachedActivateGroups.forEach((name, activateGroup) -> {
                if (isMatchGroup(group, activateGroup) && !namesSet.contains(
                    name) && !namesSet.contains(REMOVE_VALUE_PREFIX + name) && isActive(
                    cachedActivateValues.get(name), url)) {

                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            });
        }

        if (namesSet.contains(DEFAULT_KEY)) {
            // will affect order
            // `ext1,default,ext2` means ext1 will happens before all of the default extensions while ext2 will after them
            ArrayList<T> extensionsResult = new ArrayList<>(
                activateExtensionsMap.size() + names.size());
            for (String name : names) {
                if (name.startsWith(REMOVE_VALUE_PREFIX) || namesSet.contains(
                    REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                if (DEFAULT_KEY.equals(name)) {
                    extensionsResult.addAll(activateExtensionsMap.values());
                    continue;
                }
                if (containsExtension(name)) {
                    extensionsResult.add(getExtension(name));
                }
            }
            return extensionsResult;
        } else {
            // add extensions, will be sorted by its order
            for (String name : names) {
                if (name.startsWith(REMOVE_VALUE_PREFIX) || namesSet.contains(
                    REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                if (DEFAULT_KEY.equals(name)) {
                    continue;
                }
                if (containsExtension(name)) {
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            }
            return new ArrayList<>(activateExtensionsMap.values());
        }
    }

    public List<T> getActivateExtensions() {
        checkDestroyed();
        List<T> activateExtensions = new ArrayList<>();
        TreeMap<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        getExtensionClasses();
        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
            String name = entry.getKey();
            Object activate = entry.getValue();
            if (!(activate instanceof Activate)) {
                continue;
            }
            activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
        }
        if (!activateExtensionsMap.isEmpty()) {
            activateExtensions.addAll(activateExtensionsMap.values());
        }

        return activateExtensions;
    }

    private boolean isMatchGroup(String group, Set<String> groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (CollectionUtils.isNotEmpty(groups)) {
            return groups.contains(group);
        }
        return false;
    }

    private boolean isActive(String[][] keyPairs, URL url) {
        if (keyPairs.length == 0) {
            return true;
        }
        for (String[] keyPair : keyPairs) {
            // @Active(value="key1:value1, key2:value2")
            String key;
            String keyValue = null;
            if (keyPair.length > 1) {
                key = keyPair[0];
                keyValue = keyPair[1];
            } else {
                key = keyPair[0];
            }

            String realValue = url.getParameter(key);
            if (StringUtils.isEmpty(realValue)) {
                realValue = url.getAnyMethodParameter(key);
            }
            if ((keyValue != null && keyValue.equals(
                realValue)) || (keyValue == null && ConfigUtils.isNotEmpty(realValue))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    @SuppressWarnings("unchecked")
    public List<T> getLoadedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    /**
     * Find the extension with the given name.
     *
     * @throws IllegalStateException If the specified extension is not found.
     */
    public T getExtension(String name) {
        T extension = getExtension(name, true);
        if (extension == null) {
            throw new IllegalArgumentException("Not find extension: " + name);
        }
        return extension;
    }

    @SuppressWarnings("unchecked")
    public T getExtension(String name, boolean wrap) {
        // 检查扩展加载器是否已被销毁
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            return getDefaultExtension();
        }

        // 非wrap类型则将缓存的扩展名字key加上_origin后缀
        // wrap是aop机制 俗称切面,这个origin在aop里面可以称为切点,下面的wrap扩展可以称为增强通知的类型,普通扩展和wrap扩展的扩展名字是一样的
        String cacheKey = name;
        if (!wrap) {
            cacheKey += "_origin";
        }
        final Holder<Object> holder = getOrCreateHolder(cacheKey);
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name, wrap);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        checkDestroyed();
        Map<String, Class<?>> classes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(classes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        instances.sort(Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException(
                "Input type " + clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException(
                    "Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException(
                    "Extension name " + name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException(
                    "Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException(
                "Input type " + clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException(
                    "Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException(
                    "Extension name " + name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException(
                    "Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 自适应扩展对象创建
     *
     * <p>
     * 用于获取扩展对象，帮助我们通过SPI机制从扩展文件中找到需要的扩展类型并创建它的对象
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 检查对象是否销毁
        checkDestroyed();
        // 从缓存中获取
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            // 缓存中没有，则创建自适应扩展对象
            if (createAdaptiveInstanceError != null) {
                // 创建自适应扩展对象失败，直接抛出异常
                throw new IllegalStateException(
                    "Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(),
                    createAdaptiveInstanceError);
            }

            // 经典双重检查锁
            synchronized (cachedAdaptiveInstance) {
                // 再次从本地缓存中获取
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        // 创建自适应扩展对象
                        instance = createAdaptiveExtension();
                        // 将创建对象缓存起来
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        // 记录创建自适应扩展对象失败的异常，用于后续抛出异常，这样做后续就不用重复操作了，因为肯定也是失败的
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException(
                            "Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        StringBuilder buf = new StringBuilder(
            "No such extension " + type.getName() + " by name " + name);

        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(name.toLowerCase())) {
                if (i == 1) {
                    buf.append(", possible causes: ");
                }
                buf.append("\r\n(");
                buf.append(i++);
                buf.append(") ");
                buf.append(entry.getKey());
                buf.append(":\r\n");
                buf.append(StringUtils.toString(entry.getValue()));
            }
        }

        if (i == 1) {
            buf.append(
                ", no related exception was found, please check whether related SPI module is missing.");
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        // 扩展的创建的第一步扫描所有jar中的扩展实现,这里扫描完之后获取对应扩展名字的扩展实现类型的Class对象
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null || unacceptableExceptions.contains(name)) {
            // 未找到扩展实现类或不可接受异常，转换下异常信息后，抛出异常
            throw findException(name);
        }
        try {
            // 扩展对象缓存中获取
            T instance = (T) extensionInstances.get(clazz);
            if (instance == null) {
                // 第一次获取肯定没有，直接创建扩展对象，并放入缓存
                // createExtensionInstance创建扩展对象实例，和扫描文件创建不同
                extensionInstances.putIfAbsent(clazz, createExtensionInstance(clazz));
                instance = (T) extensionInstances.get(clazz);
                instance = postProcessBeforeInitialization(instance, name);
                injectExtension(instance);
                instance = postProcessAfterInitialization(instance, name);
            }

            if (wrap) {
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                // 在扫描资源文件，将扩展类型带有Wrapper注解的扩展实现类加入缓存cachedWrapperClasses
                //
                if (cachedWrapperClasses != null) {
                    // 带有包装类型注解的扩展实现类，排序
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    Collections.reverse(wrapperClassesList);
                }

                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    // wrapper扩展列表不为空，则循环处理进行筛选
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        // 判断当前扩展是否符合wrapper注解的匹配条件
                        // wrapper为空或matches为空，或者matches中包含当前扩展，并且mismatches中不包含当前扩展
                        boolean match = (wrapper == null) || ((ArrayUtils.isEmpty(
                            wrapper.matches()) || ArrayUtils.contains(wrapper.matches(),
                            name)) && !ArrayUtils.contains(wrapper.mismatches(), name));
                        if (match) {
                            // 匹配，创建相应的wrapper类型对象并将构造器类型设置为当前类型
                            instance = injectExtension(
                                (T) wrapperClass.getConstructor(type).newInstance(instance));
                            instance = postProcessAfterInitialization(instance, name);
                        }
                    }
                }
            }

            // Warning: After an instance of Lifecycle is wrapped by cachedWrapperClasses, it may not still be Lifecycle instance, this application may not invoke the lifecycle.initialize hook.
            // 初始化扩展，如果当前包装类型是Lifecycle类型，则调用initialize方法
            initExtension(instance);
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException(
                "Extension instance (name: " + name + ", class: " + type + ") couldn't be instantiated: " + t.getMessage(),
                t);
        }
    }

    private Object createExtensionInstance(Class<?> type) throws ReflectiveOperationException {
        // ExtensionLoader构造实例化时，设置的初始化策略
        return instantiationStrategy.instantiate(type);
    }

    @SuppressWarnings("unchecked")
    private T postProcessBeforeInitialization(T instance, String name) throws Exception {
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                instance = (T) processor.postProcessBeforeInitialization(instance, name);
            }
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    private T postProcessAfterInitialization(T instance, String name) throws Exception {
        if (instance instanceof ExtensionAccessorAware) {
            ((ExtensionAccessorAware) instance).setExtensionAccessor(extensionDirector);
        }
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                instance = (T) processor.postProcessAfterInitialization(instance, name);
            }
        }
        return instance;
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    /**
     * 通过setter方法注入属性
     *
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        if (injector == null) {
            return instance;
        }

        try {
            for (Method method : instance.getClass().getMethods()) {
                if (!isSetter(method)) {
                    continue;
                }

                // Check {@link DisableInject} to see if we need auto-injection for this property
                // 检查是否关闭注入
                if (method.isAnnotationPresent(DisableInject.class)) {
                    continue;
                }

                // When spiXXX implements ScopeModelAware, ExtensionAccessorAware,
                // the setXXX of ScopeModelAware and ExtensionAccessorAware does not need to be injected
                // 构造初始化的时候已经设置属性了
                if (method.getDeclaringClass() == ScopeModelAware.class) {
                    continue;
                }

                if (instance instanceof ScopeModelAware || instance instanceof ExtensionAccessorAware) {
                    if (ignoredInjectMethodsDesc.contains(ReflectUtils.getDesc(method))) {
                        continue;
                    }
                }

                // setter方法入参类型pt
                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    String property = getSetterProperty(method);
                    // 获取setter方法入参类型，获取类型对象实例，并设置到扩展类中。用于填充扩展类依赖属性
                    Object object = injector.getInstance(pt, property);
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "",
                        "Failed to inject via method " + method.getName() + " of interface " + type.getName() + ": " + e.getMessage(),
                        e);
                }
            }
        } catch (Exception e) {
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ?
            method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) :
            "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName()
            .startsWith("set") && method.getParameterTypes().length == 1 && Modifier.isPublic(
            method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * 扫描文件加载所有的扩展类信息，并放入成员变量cachedClasses
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 先从缓存中获取类信息
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            // 缓存中不存在
            synchronized (cachedClasses) {
                // 再次从缓存中获取，可能其他线程已经执行，放入缓存
                classes = cachedClasses.get();
                if (classes == null) {
                    try {
                        // 加载扩展类信息
                        classes = loadExtensionClasses();
                    } catch (InterruptedException e) {
                        logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "",
                            "Exception occurred when loading extension class (interface: " + type + ")",
                            e);
                        throw new IllegalStateException(
                            "Exception occurred when loading extension class (interface: " + type + ")",
                            e);
                    }
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     */
    @SuppressWarnings("deprecation")
    private Map<String, Class<?>> loadExtensionClasses() throws InterruptedException {
        // 检查loader是否已销毁
        checkDestroyed();
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        // 使用Java ServiceLoader（Java SPI）加载扩展配置文件读取策略
        // LoadingStrategy实现类，从不同目录加载
        for (LoadingStrategy strategy : strategies) {
            loadDirectory(extensionClasses, strategy, type.getName());

            // compatible with old ExtensionFactory
            // 兼容
            if (this.type == ExtensionInjector.class) {
                loadDirectory(extensionClasses, strategy, ExtensionFactory.class.getName());
            }
        }

        return extensionClasses;
    }

    /**
     * 加载目录
     *
     * @param extensionClasses 扩展类信息
     * @param strategy         文件加载策略
     * @param type             带有SPI注解的扩展类型{名称}，如{@link org.apache.dubbo.metadata.definition.builder.TypeBuilder}
     * @throws InterruptedException
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, LoadingStrategy strategy,
                               String type) throws InterruptedException {
        // 开始加载扩展类信息，放入extensionClasses
        loadDirectoryInternal(extensionClasses, strategy, type);

        if (Dubbo2CompactUtils.isEnabled()) {
            // dubbo交由apacha孵化，做版本兼容
            try {
                String oldType = type.replace("org.apache", "com.alibaba");
                if (oldType.equals(type)) {
                    return;
                }
                //if class not found,skip try to load resources
                ClassUtils.forName(oldType);
                loadDirectoryInternal(extensionClasses, strategy, oldType);
            } catch (ClassNotFoundException classNotFoundException) {

            }
        }
    }

    /**
     * extract and cache default extension name if exists
     * <p>
     * 扩展类型名称{@link SPI#value()}，示例：
     * javassist
     * isolation
     * metadata
     * accesskey
     */
    private void cacheDefaultExtensionName() {
        // 检查是否SPI注解
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException(
                    "More than 1 default extension name on extension " + type.getName() + ": " + Arrays.toString(
                        names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    /**
     * @param extensionClasses
     * @param loadingStrategy
     * @param type
     * @throws InterruptedException
     */
    private void loadDirectoryInternal(Map<String, Class<?>> extensionClasses,
                                       LoadingStrategy loadingStrategy, String type)
        throws InterruptedException {
        // 路径 + 扩展类名称，示例：META-INF/dubbo/org.apache.dubbo.metadata.definition.builder.TypeBuilder
        String fileName = loadingStrategy.directory() + type;
        try {
            // 声明用于加载类的类加载器列表
            List<ClassLoader> classLoadersToLoad = new LinkedList<>();

            // try to load from ExtensionLoader's ClassLoader first
            // 判断是否优先使用ExtensionLoader类加载器
            if (loadingStrategy.preferExtensionClassLoader()) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    // 非系统类加载，放入类加载器列表，用于之后类加载
                    classLoadersToLoad.add(extensionLoaderClassLoader);
                }
            }

            if (specialSPILoadingStrategyMap.containsKey(type)) {
                // 跳过只有dubbo框架实现的特定SPI，减少资源扫描，加快启动
                String internalDirectoryType = specialSPILoadingStrategyMap.get(type);
                // skip to load spi when name don't match
                if (!LoadingStrategy.ALL.equals(
                    internalDirectoryType) && !internalDirectoryType.equals(
                    loadingStrategy.getName())) {
                    return;
                }
                classLoadersToLoad.clear();
                classLoadersToLoad.add(ExtensionLoader.class.getClassLoader());
            } else {
                // 处理普通的扩展类
                // load from scope model
                // 在ExtensionLoader构造函数赋值的scopeModel（就是对应的FrameworkModel、ApplicationModel、ModuleModel）
                // 这里的classLoaders是各个各作用域对象调用父类initialize方法后，设置的classLoaders
                Set<ClassLoader> classLoaders = scopeModel.getClassLoaders();

                if (CollectionUtils.isEmpty(classLoaders)) {
                    // 如果加载域对象的类加载器为空，则直接加载文件，获取扩展类信息统一资源定位符，之后再加载类信息
                    Enumeration<java.net.URL> resources = ClassLoader.getSystemResources(fileName);
                    if (resources != null) {
                        while (resources.hasMoreElements()) {
                            // 从统一资源定位符加载类信息
                            loadResource(extensionClasses, null, resources.nextElement(),
                                loadingStrategy.overridden(), loadingStrategy.includedPackages(),
                                loadingStrategy.excludedPackages(),
                                loadingStrategy.onlyExtensionClassLoaderPackages());
                        }
                    }
                } else {
                    classLoadersToLoad.addAll(classLoaders);
                }
            }

            // 还是从收集的类加载器加载类信息
            Map<ClassLoader, Set<java.net.URL>> resources = ClassLoaderResourceLoader.loadResources(
                fileName, classLoadersToLoad);
            resources.forEach(((classLoader, urls) -> {
                // 从类加载器加载类信息
                loadFromClass(extensionClasses, loadingStrategy.overridden(), urls, classLoader,
                    loadingStrategy.includedPackages(), loadingStrategy.excludedPackages(),
                    loadingStrategy.onlyExtensionClassLoaderPackages());
            }));
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "",
                "Exception occurred when loading extension class (interface: " + type + ", description file: " + fileName + ").",
                t);
        }
    }

    private void loadFromClass(Map<String, Class<?>> extensionClasses, boolean overridden,
                               Set<java.net.URL> urls, ClassLoader classLoader,
                               String[] includedPackages, String[] excludedPackages,
                               String[] onlyExtensionClassLoaderPackages) {
        if (CollectionUtils.isNotEmpty(urls)) {
            for (java.net.URL url : urls) {
                loadResource(extensionClasses, classLoader, url, overridden, includedPackages,
                    excludedPackages, onlyExtensionClassLoaderPackages);
            }
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader,
                              java.net.URL resourceURL, boolean overridden,
                              String[] includedPackages, String[] excludedPackages,
                              String[] onlyExtensionClassLoaderPackages) {
        try {
            List<String> newContentList = getResourceContent(resourceURL);
            String clazz;
            for (String line : newContentList) {
                try {
                    String name = null;
                    int i = line.indexOf('=');
                    if (i > 0) {
                        // 等号前：定义名称
                        name = line.substring(0, i).trim();
                        // 等号后：类全限定名
                        clazz = line.substring(i + 1).trim();
                    } else {
                        // org.apache.dubbo.common.extension.DubboLoadingStrategy
                        clazz = line;
                    }
                    if (StringUtils.isNotEmpty(clazz) && !isExcluded(clazz,
                        excludedPackages) && isIncluded(clazz,
                        includedPackages) && !isExcludedByClassLoader(clazz, classLoader,
                        onlyExtensionClassLoaderPackages)) {

                        loadClass(classLoader, extensionClasses, resourceURL,
                            // 这里已经生成类信息，loadClass为了做激活检查并将各种信息作缓存
                            Class.forName(clazz, true, classLoader), name, overridden);
                    }
                } catch (Throwable t) {
                    IllegalStateException e = new IllegalStateException(
                        "Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(),
                        t);
                    exceptions.put(line, e);
                }
            }
        } catch (Throwable t) {
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "",
                "Exception occurred when loading extension class (interface: " + type + ", class file: " + resourceURL + ") in " + resourceURL,
                t);
        }
    }

    /**
     * 获取资源内容，
     * 示例：dubbo-common包下resources/META-INF/service/org.apache.dubbo.common.extension.LoadingStrategy，定义了《三行》扩展加载策略
     *
     * @param resourceURL dubbo-common包下resources/META-INF/service/org.apache.dubbo.common.extension.LoadingStrategy
     * @return [
     * 'org.apache.dubbo.common.extension.DubboInternalLoadingStrategy',
     * 'org.apache.dubbo.common.extension.DubboLoadingStrategy',
     * 'org.apache.dubbo.common.extension.ServicesLoadingStrategy'
     * ]
     * @throws IOException
     */
    private List<String> getResourceContent(java.net.URL resourceURL) throws IOException {
        // 没有就创建
        Map<java.net.URL, List<String>> urlListMap = urlListMapCache.get();
        if (urlListMap == null) {
            synchronized (ExtensionLoader.class) {
                if ((urlListMap = urlListMapCache.get()) == null) {
                    urlListMap = new ConcurrentHashMap<>();
                    urlListMapCache = new SoftReference<>(urlListMap);
                }
            }
        }

        List<String> contentList = urlListMap.computeIfAbsent(resourceURL, key -> {
            List<String> newContentList = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        newContentList.add(line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            return newContentList;
        });
        return contentList;
    }

    private boolean isIncluded(String className, String... includedPackages) {
        if (includedPackages != null && includedPackages.length > 0) {
            for (String includedPackage : includedPackages) {
                if (className.startsWith(includedPackage + ".")) {
                    // one match, return true
                    return true;
                }
            }
            // none matcher match, return false
            return false;
        }
        // matcher is empty, return true
        return true;
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExcludedByClassLoader(String className, ClassLoader classLoader,
                                            String... onlyExtensionClassLoaderPackages) {
        if (onlyExtensionClassLoaderPackages != null) {
            for (String excludePackage : onlyExtensionClassLoaderPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    // if target classLoader is not ExtensionLoader's classLoader should be excluded
                    return !Objects.equals(ExtensionLoader.class.getClassLoader(), classLoader);
                }
            }
        }
        return false;
    }

    private void loadClass(ClassLoader classLoader, Map<String, Class<?>> extensionClasses,
                           java.net.URL resourceURL, Class<?> clazz, String name,
                           boolean overridden) {
        // 检查是否扩展类型的实现类
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException(
                "Error occurred when loading extension class (interface: " + type + ", class line: " + clazz.getName() + "), class " + clazz.getName() + " is not subtype of interface.");
        }

        // 检查扩展类是否被激活
        boolean isActive = loadClassIfActive(classLoader, clazz);
        if (!isActive) {
            return;
        }

        if (clazz.isAnnotationPresent(Adaptive.class)) {
            // 缓存适配类型（做依赖注入）
            cacheAdaptiveClass(clazz, overridden);
        } else if (isWrapperClass(clazz)) {
            // 缓存包装类型（）
            cacheWrapperClass(clazz);
        } else {
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException(
                        "No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 缓存激活类型
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    cacheName(clazz, n);
                    // 将类信息放入映射
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * 检查扩展类是否激活
     *
     * @param classLoader 扩展类加载器
     * @param clazz       扩展类
     * @return true: 激活 false: 未激活
     */
    private boolean loadClassIfActive(ClassLoader classLoader, Class<?> clazz) {
        // 检查扩展类是否激活
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate == null) {
            // 没有该注解，则认为该扩展类是激活的
            return true;
        }

        // 获取激活条件
        String[] onClass = null;
        if (activate instanceof Activate) {
            onClass = ((Activate) activate).onClass();
        } else if (Dubbo2CompactUtils.isEnabled() && Dubbo2ActivateUtils.isActivateLoaded()
            && Dubbo2ActivateUtils.getActivateClass().isAssignableFrom(activate.getClass())) {
            onClass = Dubbo2ActivateUtils.getOnClass(activate);
        }

        boolean isActive = true;
        if (null != onClass && onClass.length > 0) {
            // 如果激活条件不满足，则认为该扩展类是未激活的
            // 这里个onClass指定的所有类，在类加载器中存在，才认为扩展类是激活的
            // TODO 目的是控制类加载，但为什么要这么做
            isActive = Arrays.stream(onClass).filter(StringUtils::isNotBlank)
                .allMatch(className -> ClassUtils.isPresent(className, classLoader));
        }

        return isActive;
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz,
                                      String name, boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        if (c == null || overridden) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // duplicate implementation is unacceptable
            unacceptableExceptions.add(name);
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    @SuppressWarnings("deprecation")
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else if (Dubbo2CompactUtils.isEnabled() && Dubbo2ActivateUtils.isActivateLoaded()) {
            // support com.alibaba.dubbo.common.extension.Activate
            Annotation oldActivate = clazz.getAnnotation(
                Dubbo2ActivateUtils.getActivateClass());
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException(
                "More than 1 adaptive class found: " + cachedAdaptiveClass.getName() + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    protected boolean isWrapperClass(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] == type) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        Extension extension = clazz.getAnnotation(Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 这一步已经拿到扩展类的实例了
            T instance = (T) getAdaptiveExtensionClass().newInstance();

            // 扩展前置处理器
            instance = postProcessBeforeInitialization(instance, null);

            // 注入扩展信息
            injectExtension(instance);

            // 扩展后置处理器
            instance = postProcessAfterInitialization(instance, null);
            initExtension(instance);
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获取自适应扩展类信息
     */
    private Class<?> getAdaptiveExtensionClass() {
        // 已经把扩展类信息放入本地缓存cachedClasses
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            // 如果扩展实现类有 Adaptive注解，则直接返回
            return cachedAdaptiveClass;
        }

        // 扩展实现类型没有一个这个自适应注解Adaptive时候会走到这里
        // 刚刚我们扫描到了扩展类型然后将其存入cachedClasses集合中了 接下来我们看下如何创建扩展类型
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 创建自适应扩展类
     */
    private Class<?> createAdaptiveExtensionClass() {
        // Adaptive Classes' ClassLoader should be the same with Real SPI interface classes' ClassLoader
        ClassLoader classLoader = type.getClassLoader();
        try {
            if (NativeUtils.isNative()) {
                return classLoader.loadClass(type.getName() + "$Adaptive");
            }
        } catch (Throwable ignore) {

        }

        // 代码生成器生成代码
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();

        // 获取编译器
        org.apache.dubbo.common.compiler.Compiler compiler = extensionDirector.getExtensionLoader(
            org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();

        // 编译代码，返回类信息
        return compiler.compile(type, code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

    private static Properties loadProperties(ClassLoader classLoader, String resourceName) {
        Properties properties = new Properties();
        if (classLoader != null) {
            try {
                Enumeration<java.net.URL> resources = classLoader.getResources(resourceName);
                while (resources.hasMoreElements()) {
                    java.net.URL url = resources.nextElement();
                    Properties props = loadFromUrl(url);
                    for (Map.Entry<Object, Object> entry : props.entrySet()) {
                        String key = entry.getKey().toString();
                        if (properties.containsKey(key)) {
                            continue;
                        }
                        properties.put(key, entry.getValue().toString());
                    }
                }
            } catch (IOException ex) {
                logger.error(CONFIG_FAILED_LOAD_ENV_VARIABLE, "", "", "load properties failed.",
                    ex);
            }
        }

        return properties;
    }

    private static Properties loadFromUrl(java.net.URL url) {
        Properties properties = new Properties();
        InputStream is = null;
        try {
            is = url.openStream();
            properties.load(is);
        } catch (IOException e) {
            // ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return properties;
    }

}
