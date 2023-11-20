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

/**
 * Uniform accessor for extension
 * 扩展的统一访问器，dubbo框架参考Java SPI定义了一套自己的SPI功能，这里就是对各种扩展类（能力）进行统一管理访问的接口
 */
public interface ExtensionAccessor {

    /**
     * 定义的获取方法，由每个实现类去设置值。这里实现类包含：
     * ScopeModel（FrameworkModel、ApplicationModel、ModulModel）、ExtensionDirector，括号
     * 中的三个域对象在执行构造函数的时候，有调用了父类ScopeModel的初始化方法，完成了ExtensionDirector
     * 的构造初始化。
     * <p>
     * 三大域对象在调用的时候，其实就是用父类{@link ScopeModel#getExtensionDirector()}获取ExtensionLoader
     *
     * @return
     */
    ExtensionDirector getExtensionDirector();

    /**
     * type是我们想要加载的扩展类型，每个想要加载的类型在dubbo框架都有一个相应的ExtensionLoader。
     * 而ExtendsionDirector就是负责管理这些ExtensionLoader的。
     *
     * @param type 带有SPI注解的扩展类型
     * @param <T>  扩展类型
     * @return 管理扩展类型的ExtensionLoader
     */
    default <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return this.getExtensionDirector().getExtensionLoader(type);
    }

    default <T> T getExtension(Class<T> type, String name) {
        ExtensionLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? extensionLoader.getExtension(name) : null;
    }

    default <T> T getAdaptiveExtension(Class<T> type) {
        ExtensionLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? extensionLoader.getAdaptiveExtension() : null;
    }

    default <T> T getDefaultExtension(Class<T> type) {
        ExtensionLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? extensionLoader.getDefaultExtension() : null;
    }

}
