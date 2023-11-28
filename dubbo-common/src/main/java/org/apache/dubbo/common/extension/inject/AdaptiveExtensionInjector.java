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
package org.apache.dubbo.common.extension.inject;

import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionInjector;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AdaptiveExtensionInjector
 *
 *
 * 自适应扩展注入器。初始化对象时，会从扩展加载类ExtensionLoader种获取扩展注入类型。注意这里也不知道是哪个扩展类型，
 * 可能是SpringExtensionInjector、SpiExtensionInjector、ScopeBeanExtensionInjector种任意一个。这个对象
 * 就是做个自动适配，从响应的扩展类型注入器中获取扩展类信息。起到自适应、适配器作用
 */
@Adaptive
public class AdaptiveExtensionInjector implements ExtensionInjector, Lifecycle {

    private Collection<ExtensionInjector> injectors = Collections.emptyList();
    private ExtensionAccessor extensionAccessor;

    public AdaptiveExtensionInjector() {
    }

    @Override
    public void setExtensionAccessor(final ExtensionAccessor extensionAccessor) {
        this.extensionAccessor = extensionAccessor;
    }

    @Override
    public void initialize() throws IllegalStateException {
        ExtensionLoader<ExtensionInjector> loader = extensionAccessor.getExtensionLoader(ExtensionInjector.class);
        injectors = loader.getSupportedExtensions().stream()
            .map(loader::getExtension)
            .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }

    @Override
    public <T> T getInstance(final Class<T> type, final String name) {
        return injectors.stream()
            .map(injector -> injector.getInstance(type, name))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    @Override
    public void start() throws IllegalStateException {
    }

    @Override
    public void destroy() throws IllegalStateException {
    }
}
