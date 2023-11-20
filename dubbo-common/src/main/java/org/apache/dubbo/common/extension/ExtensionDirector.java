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

import org.apache.dubbo.rpc.model.ScopeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExtensionDirector is a scoped extension loader manager.
 * 作用域扩展加载程序<管理器>
 *
 * <p></p>
 * <p>ExtensionDirector supports multiple levels, and the child can inherit the parent's extension instances. </p>
 * <p>The way to find and create an extension instance is similar to Java classloader.</p>
 *
 * <p>
 * 相对独立的类：用来管理扩展加载类、扩展域类
 */
public class ExtensionDirector implements ExtensionAccessor {

    /**
     * 很显然一个ExtensionDirection管理多个ExtensionLoader，而每个ExtensionLoader又负责一个扩展类型
     */
    private final ConcurrentMap<Class<?>, ExtensionLoader<?>> extensionLoadersMap = new ConcurrentHashMap<>(64);

    /**
     * 每个扩展类型支持的域范围
     */
    private final ConcurrentMap<Class<?>, ExtensionScope> extensionScopeMap = new ConcurrentHashMap<>(64);

    /**
     * 该类支持父子继承，子类可以继承父类的扩展加载实例
     */
    private final ExtensionDirector parent;

    /**
     * 扩展支持的域模型类型（我理解有很多扩展，无论框架自带或用户自建，每个扩展都有支持的作用域级别，
     * 如框架级别-FrameworkModel、应用级别-ApplicationModel、模块级别-ModuleNodel）
     * 扩展配置的域只有和当前匹配了，才可以使用
     */
    private final ExtensionScope scope;

    /**
     * 在扩展初始化之前或之后调用的的《前后置处理器》
     */
    private final List<ExtensionPostProcessor> extensionPostProcessors = new ArrayList<>();
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    public ExtensionDirector(ExtensionDirector parent, ExtensionScope scope, ScopeModel scopeModel) {
        this.parent = parent;
        this.scope = scope;
        this.scopeModel = scopeModel;
    }

    public void addExtensionPostProcessor(ExtensionPostProcessor processor) {
        if (!this.extensionPostProcessors.contains(processor)) {
            this.extensionPostProcessors.add(processor);
        }
    }

    public List<ExtensionPostProcessor> getExtensionPostProcessors() {
        return extensionPostProcessors;
    }

    @Override
    public ExtensionDirector getExtensionDirector() {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 检查作用域扩展加载程序管理器是否已经销毁
        checkDestroyed();

        // 参数合法性检查：只支持带有SPI注解的接口！！！
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 获取获取扩展加载器的顺序

        // 1. find in local cache
        // 1）从缓存中获取
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);

        ExtensionScope scope = extensionScopeMap.get(type);
        if (scope == null) {
            SPI annotation = type.getAnnotation(SPI.class);
            scope = annotation.scope();
            extensionScopeMap.put(type, scope);
        }

        // 2）如果前面没找到则查询扩展类型的scope所属域,如果是当前域扩展则从直接创建扩展加载器
        if (loader == null && scope == ExtensionScope.SELF) {
            // create an instance in self scope
            loader = createExtensionLoader0(type);
        }

        // 2. find in parent
        // 3）从父扩展访问器查询
        if (loader == null) {
            if (this.parent != null) {
                loader = this.parent.getExtensionLoader(type);
            }
        }

        // 3. create it
        // 4）经过以上三步仍未找到，则创建
        if (loader == null) {
            loader = createExtensionLoader(type);
        }

        return loader;
    }

    private <T> ExtensionLoader<T> createExtensionLoader(Class<T> type) {
        ExtensionLoader<T> loader = null;
        // 检查当前带有SPI注解的接口，设置的的scope和当前作用域扩展管理器的scope是否一致
        // 当前作用域扩展器程序管理器的作用域{@link ExtensionDirector#scope}，是在初始化域模型的通过构造函数设置
        if (isScopeMatched(type)) {
            // if scope is matched, just create it
            loader = createExtensionLoader0(type);
        }

        // 这里看着可能返回null（但目前dubbo源码中并没有在使用地方做判空处理，猜测自定义的话要注意）
        return loader;
    }

    @SuppressWarnings("unchecked")
    private <T> ExtensionLoader<T> createExtensionLoader0(Class<T> type) {
        // 检查当前作用域扩展加载程序管理器是否销毁
        checkDestroyed();
        ExtensionLoader<T> loader;
        // 为当前扩展类型创建一个扩展访问器并缓存到，当前成员变量extensionLoadersMap中
        extensionLoadersMap.putIfAbsent(type, new ExtensionLoader<T>(type, this, scopeModel));
        loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);
        return loader;
    }

    private boolean isScopeMatched(Class<?> type) {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        return defaultAnnotation.scope().equals(scope);
    }

    private static boolean withExtensionAnnotation(Class<?> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    public ExtensionDirector getParent() {
        return parent;
    }

    public void removeAllCachedLoader() {
    }

    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            for (ExtensionLoader<?> extensionLoader : extensionLoadersMap.values()) {
                extensionLoader.destroy();
            }
            extensionLoadersMap.clear();
            extensionScopeMap.clear();
            extensionPostProcessors.clear();
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionDirector is destroyed");
        }
    }
}
