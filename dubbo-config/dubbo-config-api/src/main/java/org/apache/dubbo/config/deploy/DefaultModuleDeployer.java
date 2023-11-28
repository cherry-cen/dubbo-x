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
package org.apache.dubbo.config.deploy;

import org.apache.dubbo.common.config.ReferenceCache;
import org.apache.dubbo.common.constants.LoggerCodeConstants;
import org.apache.dubbo.common.constants.RegisterTypeEnum;
import org.apache.dubbo.common.deploy.AbstractDeployer;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.deploy.DeployListener;
import org.apache.dubbo.common.deploy.DeployState;
import org.apache.dubbo.common.deploy.ModuleDeployListener;
import org.apache.dubbo.common.deploy.ModuleDeployer;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.threadpool.manager.FrameworkExecutorRepository;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.ReferenceConfigBase;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.ServiceConfigBase;
import org.apache.dubbo.config.context.ModuleConfigManager;
import org.apache.dubbo.config.utils.SimpleReferenceCache;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ModuleServiceRepository;
import org.apache.dubbo.rpc.model.ProviderModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_EXPORT_SERVICE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_REFERENCE_MODEL;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_REFER_SERVICE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_START_MODEL;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_WAIT_EXPORT_REFER;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_UNABLE_DESTROY_MODEL;

/**
 * Export/refer services of module
 */
public class DefaultModuleDeployer extends AbstractDeployer<ModuleModel> implements ModuleDeployer {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(DefaultModuleDeployer.class);

    private final List<CompletableFuture<?>> asyncExportingFutures = new ArrayList<>();

    private final List<CompletableFuture<?>> asyncReferringFutures = new ArrayList<>();

    private final List<ServiceConfigBase<?>> exportedServices = new ArrayList<>();

    private final ModuleModel moduleModel;

    private final FrameworkExecutorRepository frameworkExecutorRepository;
    private final ExecutorRepository executorRepository;

    private final ModuleConfigManager configManager;

    private final SimpleReferenceCache referenceCache;

    private final ApplicationDeployer applicationDeployer;
    private CompletableFuture startFuture;
    private Boolean background;
    private Boolean exportAsync;
    private Boolean referAsync;
    private CompletableFuture<?> exportFuture;
    private CompletableFuture<?> referFuture;


    public DefaultModuleDeployer(ModuleModel moduleModel) {
        super(moduleModel);
        this.moduleModel = moduleModel;
        configManager = moduleModel.getConfigManager();
        frameworkExecutorRepository = moduleModel.getApplicationModel().getFrameworkModel().getBeanFactory().getBean(FrameworkExecutorRepository.class);
        executorRepository = ExecutorRepository.getInstance(moduleModel.getApplicationModel());
        referenceCache = SimpleReferenceCache.newCache();
        applicationDeployer = DefaultApplicationDeployer.get(moduleModel);

        //load spi listener
        Set<ModuleDeployListener> listeners = moduleModel.getExtensionLoader(ModuleDeployListener.class).getSupportedExtensionInstances();
        for (ModuleDeployListener listener : listeners) {
            this.addDeployListener(listener);
        }
    }

    @Override
    public void initialize() throws IllegalStateException {
        if (initialized) {
            return;
        }
        // Ensure that the initialization is completed when concurrent calls
        synchronized (this) {
            if (initialized) {
                return;
            }

            onInitialize();

            // 加载模块配置
            loadConfigs();

            // read ModuleConfig
            ModuleConfig moduleConfig = moduleModel.getConfigManager().getModule().orElseThrow(() -> new IllegalStateException("Default module config is not initialized"));
            exportAsync = Boolean.TRUE.equals(moduleConfig.getExportAsync());
            referAsync = Boolean.TRUE.equals(moduleConfig.getReferAsync());

            // start in background
            background = moduleConfig.getBackground();
            if (background == null) {
                // compatible with old usages
                background = isExportBackground() || isReferBackground();
            }

            initialized = true;
            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " has been initialized!");
            }
        }
    }

    @Override
    public Future start() throws IllegalStateException {
        // initialize，maybe deadlock applicationDeployer lock & moduleDeployer lock
        applicationDeployer.initialize();

        return startSync();
    }

    private synchronized Future startSync() throws IllegalStateException {
        if (isStopping() || isStopped() || isFailed()) {
            // 做状态检查，模块发布器正停止、已停止、启动失败，直接抛异常
            throw new IllegalStateException(getIdentifier() + " is stopping or stopped, can not start again");
        }

        try {
            if (isStarting() || isStarted()) {
                // 启动中、已启动，直接返回
                return startFuture;
            }

            // 切换模块启动状态
            onModuleStarting();

            // 初始化
            initialize();

            // export services
            // 暴露服务
            exportServices();

            // prepare application instance
            // exclude internal module to avoid wait itself
            if (moduleModel != moduleModel.getApplicationModel().getInternalModule()) {
                applicationDeployer.prepareInternalModule();
            }

            // refer services
            // 引用服务
            referServices();

            // if no async export/refer services, just set started
            // 如果非异步发布或引用服务，直接切换状态为STARTED
            if (asyncExportingFutures.isEmpty() && asyncReferringFutures.isEmpty()) {
                // publish module started event
                // 发布模块启动事件
                onModuleStarted();

                // register services to registry
                // 注册服务到注册中心？
                registerServices();

                // check reference config
                //
                checkReferences();

                // complete module start future after application state changed
                completeStartFuture(true);
            } else {
                // 异步提交到线程池执行
                frameworkExecutorRepository.getSharedExecutor().submit(() -> {
                    try {
                        // wait for export finish
                        // 等待服务发布完成
                        waitExportFinish();
                        // wait for refer finish
                        // 等待服务引用完成
                        waitReferFinish();

                        // publish module started event
                        // 发布模块启动事件
                        onModuleStarted();

                        // register services to registry
                        // 注册服务到注册中心
                        registerServices();

                        // check reference config
                        checkReferences();
                    } catch (Throwable e) {
                        logger.warn(CONFIG_FAILED_WAIT_EXPORT_REFER, "", "", "wait for export/refer services occurred an exception", e);
                        onModuleFailed(getIdentifier() + " start failed: " + e, e);
                    } finally {
                        // complete module start future after application state changed
                        // 异步回调完成 所有服务都启动了，再切换状态
                        completeStartFuture(true);
                    }
                });
            }

        } catch (Throwable e) {
            onModuleFailed(getIdentifier() + " start failed: " + e, e);
            throw e;
        }

        return startFuture;
    }

    @Override
    public Future getStartFuture() {
        return startFuture;
    }

    private boolean hasExportedServices() {
        return configManager.getServices().size() > 0;
    }

    @Override
    public void stop() throws IllegalStateException {
        moduleModel.destroy();
    }

    @Override
    public void preDestroy() throws IllegalStateException {
        if (isStopping() || isStopped()) {
            return;
        }
        onModuleStopping();

        offline();
    }

    private void offline() {
        try {
            ModuleServiceRepository serviceRepository = moduleModel.getServiceRepository();
            List<ProviderModel> exportedServices = serviceRepository.getExportedServices();
            for (ProviderModel exportedService : exportedServices) {
                List<ProviderModel.RegisterStatedURL> statedUrls = exportedService.getStatedUrl();
                for (ProviderModel.RegisterStatedURL statedURL : statedUrls) {
                    if (statedURL.isRegistered()) {
                        doOffline(statedURL);
                    }
                }
            }
        } catch (Throwable t) {
            logger.error(LoggerCodeConstants.INTERNAL_ERROR, "", "", "Exceptions occurred when unregister services.", t);
        }
    }

    private void doOffline(ProviderModel.RegisterStatedURL statedURL) {
        RegistryFactory registryFactory =
            statedURL.getRegistryUrl().getOrDefaultApplicationModel().getExtensionLoader(RegistryFactory.class).getAdaptiveExtension();
        Registry registry = registryFactory.getRegistry(statedURL.getRegistryUrl());
        registry.unregister(statedURL.getProviderUrl());
        statedURL.setRegistered(false);
    }

    @Override
    public synchronized void postDestroy() throws IllegalStateException {
        if (isStopped()) {
            return;
        }
        unexportServices();
        unreferServices();

        ModuleServiceRepository serviceRepository = moduleModel.getServiceRepository();
        if (serviceRepository != null) {
            List<ConsumerModel> consumerModels = serviceRepository.getReferredServices();

            for (ConsumerModel consumerModel : consumerModels) {
                try {
                    if (consumerModel.getDestroyRunner() != null) {
                        consumerModel.getDestroyRunner().run();
                    }
                } catch (Throwable t) {
                    logger.error(CONFIG_UNABLE_DESTROY_MODEL, "there are problems with the custom implementation.", "", "Unable to destroy model: consumerModel.", t);
                }
            }

            List<ProviderModel> exportedServices = serviceRepository.getExportedServices();
            for (ProviderModel providerModel : exportedServices) {
                try {
                    if (providerModel.getDestroyRunner() != null) {
                        providerModel.getDestroyRunner().run();
                    }
                } catch (Throwable t) {
                    logger.error(CONFIG_UNABLE_DESTROY_MODEL, "there are problems with the custom implementation.", "", "Unable to destroy model: providerModel.", t);
                }
            }
            serviceRepository.destroy();
        }
        onModuleStopped();
    }

    private void onInitialize() {
        for (DeployListener<ModuleModel> listener : listeners) {
            try {
                listener.onInitialize(moduleModel);
            } catch (Throwable e) {
                logger.error(CONFIG_FAILED_START_MODEL, "", "", getIdentifier() + " an exception occurred when handle initialize event", e);
            }
        }
    }

    private void onModuleStarting() {
        setStarting();
        startFuture = new CompletableFuture();
        logger.info(getIdentifier() + " is starting.");
        applicationDeployer.notifyModuleChanged(moduleModel, DeployState.STARTING);
    }

    private void onModuleStarted() {
            if (isStarting()) {
                setStarted();
                logger.info(getIdentifier() + " has started.");
                applicationDeployer.notifyModuleChanged(moduleModel, DeployState.STARTED);
            }
    }

    private void onModuleFailed(String msg, Throwable ex) {
        try {
            try {
                // un-export all services if start failure
                unexportServices();
            } catch (Throwable t) {
                logger.info("Failed to un-export services after module failed.", t);
            }

            setFailed(ex);
            logger.error(CONFIG_FAILED_START_MODEL, "", "", "Model start failed: " + msg, ex);
            applicationDeployer.notifyModuleChanged(moduleModel, DeployState.FAILED);
        } finally {
            completeStartFuture(false);
        }
    }

    private void completeStartFuture(boolean value) {
        if (startFuture != null && !startFuture.isDone()) {
            startFuture.complete(value);
        }
        if (exportFuture != null && !exportFuture.isDone()) {
            exportFuture.cancel(true);
        }
        if (referFuture != null && !referFuture.isDone()) {
            referFuture.cancel(true);
        }
    }

    private void onModuleStopping() {
        try {
            setStopping();
            logger.info(getIdentifier() + " is stopping.");
            applicationDeployer.notifyModuleChanged(moduleModel, DeployState.STOPPING);
        } finally {
            completeStartFuture(false);
        }
    }

    private void onModuleStopped() {
        try {
            setStopped();
            logger.info(getIdentifier() + " has stopped.");
            applicationDeployer.notifyModuleChanged(moduleModel, DeployState.STOPPED);
        } finally {
            completeStartFuture(false);
        }
    }

    private void loadConfigs() {
        // load module configs
        moduleModel.getConfigManager().loadConfigs();
        moduleModel.getConfigManager().refreshAll();
    }

    private void exportServices() {
        for (ServiceConfigBase sc : configManager.getServices()) {
            exportServiceInternal(sc);
        }
    }

    private void registerServices() {
        for (ServiceConfigBase sc : configManager.getServices()) {
            if (!Boolean.FALSE.equals(sc.isRegister())) {
                registerServiceInternal(sc);
            }
        }
        applicationDeployer.refreshServiceInstance();
    }

    private void checkReferences() {
        Optional<ModuleConfig> module = configManager.getModule();
        long timeout = module.map(ModuleConfig::getCheckReferenceTimeout).orElse(30000L);
        for (ReferenceConfigBase<?> rc : configManager.getReferences()) {
            referenceCache.check(rc, timeout);
        }
    }

    private void exportServiceInternal(ServiceConfigBase sc) {
        ServiceConfig<?> serviceConfig = (ServiceConfig<?>) sc;
        if (!serviceConfig.isRefreshed()) {
            serviceConfig.refresh();
        }
        if (sc.isExported()) {
            return;
        }
        if (exportAsync || sc.shouldExportAsync()) {
            ExecutorService executor = executorRepository.getServiceExportExecutor();
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    if (!sc.isExported()) {
                        sc.export();
                        exportedServices.add(sc);
                    }
                } catch (Throwable t) {
                    logger.error(CONFIG_FAILED_EXPORT_SERVICE, "", "", "Failed to async export service config: " + getIdentifier() + " , catch error : " + t.getMessage(), t);
                }
            }, executor);

            asyncExportingFutures.add(future);
        } else {
            if (!sc.isExported()) {
                sc.export(RegisterTypeEnum.AUTO_REGISTER_BY_DEPLOYER);
                exportedServices.add(sc);
            }
        }
    }

    private void registerServiceInternal(ServiceConfigBase sc) {
        ServiceConfig<?> serviceConfig = (ServiceConfig<?>) sc;
        if (!serviceConfig.isRefreshed()) {
            serviceConfig.refresh();
        }
        if (!sc.isExported()) {
            return;
        }
        sc.register(true);
    }

    private void unexportServices() {
        exportedServices.forEach(sc -> {
            try {
                configManager.removeConfig(sc);
                sc.unexport();
            } catch (Throwable t) {
                logger.info("Failed to un-export service. Service Key: " + sc.getUniqueServiceName(), t);
            }
        });
        exportedServices.clear();

        asyncExportingFutures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        asyncExportingFutures.clear();
    }

    private void referServices() {
        configManager.getReferences().forEach(rc -> {
            try {
                ReferenceConfig<?> referenceConfig = (ReferenceConfig<?>) rc;
                if (!referenceConfig.isRefreshed()) {
                    referenceConfig.refresh();
                }

                if (rc.shouldInit()) {
                    if (referAsync || rc.shouldReferAsync()) {
                        ExecutorService executor = executorRepository.getServiceReferExecutor();
                        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                            try {
                                referenceCache.get(rc, false);
                            } catch (Throwable t) {
                                logger.error(CONFIG_FAILED_EXPORT_SERVICE, "", "", "Failed to async export service config: " + getIdentifier() + " , catch error : " + t.getMessage(), t);
                            }
                        }, executor);

                        asyncReferringFutures.add(future);
                    } else {
                        referenceCache.get(rc, false);
                    }
                }
            } catch (Throwable t) {
                logger.error(CONFIG_FAILED_REFERENCE_MODEL, "", "", "Model reference failed: " + getIdentifier() + " , catch error : " + t.getMessage(), t);
                referenceCache.destroy(rc);
                throw t;
            }
        });
    }

    private void unreferServices() {
        try {
            asyncReferringFutures.forEach(future -> {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            });
            asyncReferringFutures.clear();
            referenceCache.destroyAll();
            for (ReferenceConfigBase<?> rc : configManager.getReferences()) {
                rc.destroy();
            }
        } catch (Exception ignored) {
        }
    }

    private void waitExportFinish() {
        try {
            logger.info(getIdentifier() + " waiting services exporting ...");
            exportFuture = CompletableFuture.allOf(asyncExportingFutures.toArray(new CompletableFuture[0]));
            exportFuture.get();
        } catch (Throwable e) {
            logger.warn(CONFIG_FAILED_EXPORT_SERVICE, "", "", getIdentifier() + " export services occurred an exception: " + e.toString());
        } finally {
            logger.info(getIdentifier() + " export services finished.");
            asyncExportingFutures.clear();
        }
    }

    private void waitReferFinish() {
        try {
            logger.info(getIdentifier() + " waiting services referring ...");
            referFuture = CompletableFuture.allOf(asyncReferringFutures.toArray(new CompletableFuture[0]));
            referFuture.get();
        } catch (Throwable e) {
            logger.warn(CONFIG_FAILED_REFER_SERVICE, "", "", getIdentifier() + " refer services occurred an exception: " + e.toString());
        } finally {
            logger.info(getIdentifier() + " refer services finished.");
            asyncReferringFutures.clear();
        }
    }

    @Override
    public boolean isBackground() {
        return background;
    }

    private boolean isExportBackground() {
        return moduleModel.getConfigManager().getProviders()
            .stream()
            .map(ProviderConfig::getExportBackground)
            .anyMatch(k -> k != null && k);
    }

    private boolean isReferBackground() {
        return moduleModel.getConfigManager().getConsumers()
            .stream()
            .map(ConsumerConfig::getReferBackground)
            .anyMatch(k -> k != null && k);
    }

    @Override
    public ReferenceCache getReferenceCache() {
        return referenceCache;
    }

    /**
     * Prepare for export/refer service, trigger initializing application and module
     */
    @Override
    public void prepare() {
        applicationDeployer.initialize();
        this.initialize();
    }

}
