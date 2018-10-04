/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.config;

import org.axonframework.common.Assert;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.common.transaction.NoTransactionManager;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.DirectEventProcessingStrategy;
import org.axonframework.eventhandling.ErrorHandler;
import org.axonframework.eventhandling.EventHandlerInvoker;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.EventProcessor;
import org.axonframework.eventhandling.ListenerInvocationErrorHandler;
import org.axonframework.eventhandling.LoggingErrorHandler;
import org.axonframework.eventhandling.MultiEventHandlerInvoker;
import org.axonframework.eventhandling.PropagatingErrorHandler;
import org.axonframework.eventhandling.SimpleEventHandlerInvoker;
import org.axonframework.eventhandling.SubscribingEventProcessor;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackingEventProcessor;
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration;
import org.axonframework.eventhandling.async.SequencingPolicy;
import org.axonframework.eventhandling.async.SequentialPerAggregatePolicy;
import org.axonframework.eventhandling.saga.repository.SagaStore;
import org.axonframework.eventhandling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.SubscribableMessageSource;
import org.axonframework.messaging.interceptors.CorrelationDataInterceptor;
import org.axonframework.messaging.unitofwork.RollbackConfiguration;
import org.axonframework.messaging.unitofwork.RollbackConfigurationType;
import org.axonframework.monitoring.MessageMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Comparator.comparing;

/**
 * Event processing module configuration. Registers all configuration components within itself, builds the {@link
 * EventProcessingConfiguration} and takes care of module lifecycle.
 *
 * @author Milan Savic
 * @since 4.0
 */
public class EventProcessingModule
        implements ModuleConfiguration, EventProcessingConfiguration, EventProcessingConfigurer {


    //<editor-fold desc="configuration state">
    private static class ProcessorSelector {

        private final int priority;
        private final Function<Object, Optional<String>> function;

        private ProcessorSelector(int priority, Function<Object, Optional<String>> selectorFunction) {
            this.priority = priority;
            this.function = selectorFunction;
        }

        private ProcessorSelector(String name, int priority, Predicate<Object> criteria) {
            this(priority, handler -> {
                if (criteria.test(handler)) {
                    return Optional.of(name);
                }
                return Optional.empty();
            });
        }

        public Optional<String> select(Object handler) {
            return function.apply(handler);
        }

        public int getPriority() {
            return priority;
        }
    }

    private Configuration configuration;

    private final List<Component<SagaConfiguration<?>>> sagaConfigurations = new ArrayList<>();
    private final List<Component<Object>> eventHandlerBuilders = new ArrayList<>();

    private final Map<String, Component<ListenerInvocationErrorHandler>> listenerInvocationErrorHandlers = new HashMap<>();
    private final Component<ListenerInvocationErrorHandler> defaultListenerInvocationErrorHandler = new Component<>(
            () -> configuration,
            "listenerInvocationErrorHandler",
            c -> c.getComponent(ListenerInvocationErrorHandler.class, LoggingErrorHandler::new)
    );
    private final Map<String, Component<ErrorHandler>> errorHandlers = new HashMap<>();
    private final Component<ErrorHandler> defaultErrorHandler = new Component<>(
            () -> configuration,
            "errorHandler",
            c -> c.getComponent(ErrorHandler.class, PropagatingErrorHandler::instance)
    );

    private final Map<String, EventProcessorBuilder> eventProcessorBuilders = new HashMap<>();
    private EventProcessorBuilder defaultEventProcessorBuilder = this::defaultEventProcessor;
    private final Map<String, Component<EventProcessor>> eventProcessors = new HashMap<>();

    private final List<BiFunction<Configuration, String, MessageHandlerInterceptor<? super EventMessage<?>>>> defaultHandlerInterceptors = new ArrayList<>();
    private final Map<String, List<Function<Configuration, MessageHandlerInterceptor<? super EventMessage<?>>>>> handlerInterceptorsBuilders = new HashMap<>();

    private final Map<String, String> processingGroupsAssignments = new HashMap<>();
    private Function<String, String> defaultProcessingGroupAssignment = Function.identity();
    private final Map<String, Component<SequencingPolicy<? super EventMessage<?>>>> sequencingPolicies = new HashMap<>();
    private final Component<SequencingPolicy<? super EventMessage<?>>> defaultSequencingPolicy = new Component<>(
            () -> configuration,
            "sequencingPolicy",
            c -> SequentialPerAggregatePolicy.instance()
    );
    private final Map<String, MessageMonitorFactory> messageMonitorFactories = new HashMap<>();
    private final List<ProcessorSelector> selectors = new ArrayList<>();
    // Set up the default selector that determines the processing group by inspecting the @ProcessingGroup annotation;
    // if no annotation is present, the package name is used
    private Function<Object, String> fallback = (o) -> o.getClass().getPackage().getName();
    private final ProcessorSelector defaultSelector = new ProcessorSelector(
            Integer.MIN_VALUE,
            o -> {
                Class<?> handlerType = o.getClass();
                Optional<Map<String, Object>> annAttr = AnnotationUtils.findAnnotationAttributes(handlerType,
                                                                                                 ProcessingGroup.class);
                return Optional.of(annAttr.map(attr -> (String) attr.get("processingGroup"))
                                          .orElseGet(() -> fallback.apply(o)));
            });
    private final Map<String, Component<TokenStore>> tokenStore = new HashMap<>();
    private final Component<TokenStore> defaultTokenStore = new Component<>(
            () -> configuration,
            "tokenStore",
            c -> c.getComponent(TokenStore.class, InMemoryTokenStore::new)
    );
    private final Map<String, Component<RollbackConfiguration>> rollbackConfigurations = new HashMap<>();
    private final Component<RollbackConfiguration> defaultRollbackConfiguration = new Component<>(
            () -> configuration,
            "rollbackConfiguration",
            c -> c.getComponent(RollbackConfiguration.class, () -> RollbackConfigurationType.ANY_THROWABLE));

    private final Component<SagaStore> sagaStore = new Component<>(
            () -> configuration,
            "sagaStore",
            c -> c.getComponent(SagaStore.class, InMemorySagaStore::new)
    );
    private final Map<String, Component<TransactionManager>> transactionManagers = new HashMap<>();
    private final Component<TransactionManager> defaultTransactionManager = new Component<>(
            () -> configuration,
            "transactionManager",
            c -> c.getComponent(TransactionManager.class, NoTransactionManager::instance)
    );
    //</editor-fold>

    //<editor-fold desc="module configuration methods">
    @Override
    public void initialize(Configuration configuration) {
        this.configuration = configuration;
        eventProcessors.clear();
        selectors.sort(comparing(ProcessorSelector::getPriority).reversed());
        Map<String, List<Function<Configuration, EventHandlerInvoker>>> handlerInvokers = new HashMap<>();
        registerSimpleEventHandlerInvokers(handlerInvokers);
        registerSagaManagers(handlerInvokers);

        handlerInvokers.forEach((processorName, invokers) -> {
            Component<EventProcessor> eventProcessorComponent =
                    new Component<>(configuration, processorName, c -> buildEventProcessor(invokers, processorName));
            eventProcessors.put(processorName, eventProcessorComponent);
        });
    }

    @Override
    public void start() {
        eventProcessors.forEach((name, component) -> component.get().start());
    }

    @Override
    public void shutdown() {
        eventProcessors.forEach((name, component) -> component.get().shutDown());
    }

    private void registerSimpleEventHandlerInvokers(
            Map<String, List<Function<Configuration, EventHandlerInvoker>>> handlerInvokers) {
        Map<String, List<Object>> assignments = new HashMap<>();
        eventHandlerBuilders.stream()
                            .map(Component::get)
                            .forEach(handler -> {
                                String processor =
                                        selectors.stream()
                                                 .map(s -> s.select(handler))
                                                 .filter(Optional::isPresent)
                                                 .map(Optional::get)
                                                 .findFirst()
                                                 .orElseGet(() -> defaultSelector.select(handler)
                                                                                 .orElseThrow(IllegalStateException::new));
                                assignments.computeIfAbsent(processor, k -> new ArrayList<>()).add(handler);
                            });
        assignments.forEach((processingGroup, handlers) -> {
            String processorName = processorNameForProcessingGroup(processingGroup);
            handlerInvokers.computeIfAbsent(processorName, k -> new ArrayList<>())
                           .add(c -> new SimpleEventHandlerInvoker(handlers,
                                                                   configuration.parameterResolverFactory(),
                                                                   listenerInvocationErrorHandler(processingGroup),
                                                                   sequencingPolicy(processingGroup)));
        });
    }

    private void registerSagaManagers(Map<String, List<Function<Configuration, EventHandlerInvoker>>> handlerInvokers) {
        sagaConfigurations.stream().map(Component::get).forEach(sc -> {
            sc.initialize(configuration);
            String processorName = processorNameForProcessingGroup(sc.processingGroup());
            handlerInvokers.computeIfAbsent(processorName, k -> new ArrayList<>())
                           .add(c -> sc.manager().get());
        });
    }

    private EventProcessor buildEventProcessor(List<Function<Configuration, EventHandlerInvoker>> builderFunctions,
                                               String processorName) {
        List<EventHandlerInvoker> invokers = builderFunctions
                .stream()
                .map(invokerBuilder -> invokerBuilder.apply(configuration))
                .collect(Collectors.toList());
        MultiEventHandlerInvoker multiEventHandlerInvoker = new MultiEventHandlerInvoker(invokers);

        EventProcessor eventProcessor = eventProcessorBuilders
                .getOrDefault(processorName, defaultEventProcessorBuilder)
                .build(processorName, configuration, multiEventHandlerInvoker);

        handlerInterceptorsBuilders.getOrDefault(processorName, new ArrayList<>())
                                   .stream()
                                   .map(hi -> hi.apply(configuration))
                                   .forEach(eventProcessor::registerHandlerInterceptor);

        defaultHandlerInterceptors.stream()
                                  .map(f -> f.apply(configuration, processorName))
                                  .filter(Objects::nonNull)
                                  .forEach(eventProcessor::registerHandlerInterceptor);

        eventProcessor.registerHandlerInterceptor(new CorrelationDataInterceptor<>(configuration.correlationDataProviders()));

        return eventProcessor;
    }
    //</editor-fold>

    //<editor-fold desc="configuration methods">
    @SuppressWarnings("unchecked")
    @Override
    public <T extends EventProcessor> Optional<T> eventProcessorByProcessingGroup(String processingGroup) {
        ensureInitialized();
        return (Optional<T>) Optional.ofNullable(eventProcessors()
                                                         .get(processorNameForProcessingGroup(processingGroup)));
    }

    @Override
    public Map<String, EventProcessor> eventProcessors() {
        ensureInitialized();
        Map<String, EventProcessor> result = new HashMap<>(eventProcessors.size());
        eventProcessors.forEach((name, component) -> result.put(name, component.get()));
        return result;
    }

    @Override
    public List<MessageHandlerInterceptor<? super EventMessage<?>>> interceptorsFor(String processorName) {
        ensureInitialized();
        return eventProcessor(processorName).map(EventProcessor::getHandlerInterceptors)
                                            .orElse(Collections.emptyList());
    }

    @Override
    public ListenerInvocationErrorHandler listenerInvocationErrorHandler(String processingGroup) {
        ensureInitialized();
        return listenerInvocationErrorHandlers.containsKey(processingGroup)
                ? listenerInvocationErrorHandlers.get(processingGroup).get()
                : defaultListenerInvocationErrorHandler.get();
    }

    @Override
    public SequencingPolicy sequencingPolicy(String processingGroup) {
        ensureInitialized();
        return sequencingPolicies.containsKey(processingGroup)
                ? sequencingPolicies.get(processingGroup).get()
                : defaultSequencingPolicy.get();
    }

    @Override
    public RollbackConfiguration rollbackConfiguration(String componentName) {
        ensureInitialized();
        return rollbackConfigurations.containsKey(componentName)
                ? rollbackConfigurations.get(componentName).get()
                : defaultRollbackConfiguration.get();
    }

    @Override
    public ErrorHandler errorHandler(String componentName) {
        ensureInitialized();
        return errorHandlers.containsKey(componentName)
                ? errorHandlers.get(componentName).get()
                : defaultErrorHandler.get();
    }

    @Override
    public SagaStore sagaStore() {
        ensureInitialized();
        return sagaStore.get();
    }

    @Override
    public List<SagaConfiguration<?>> sagaConfigurations() {
        ensureInitialized();
        return sagaConfigurations.stream()
                                 .map(Component::get)
                                 .collect(Collectors.toList());
    }

    private String processorNameForProcessingGroup(String processingGroup) {
        ensureInitialized();
        return processingGroupsAssignments.getOrDefault(processingGroup,
                                                        defaultProcessingGroupAssignment
                                                                .apply(processingGroup));
    }

    @Override
    public MessageMonitor<? super Message<?>> messageMonitor(Class<?> componentType,
                                                             String componentName) {
        ensureInitialized();
        if (messageMonitorFactories.containsKey(componentName)) {
            return messageMonitorFactories.get(componentName).create(configuration, componentType, componentName);
        } else {
            return configuration.messageMonitor(componentType, componentName);
        }
    }

    @Override
    public TokenStore tokenStore(String processingGroup) {
        ensureInitialized();
        return tokenStore.containsKey(processingGroup)
                ? tokenStore.get(processingGroup).get()
                : defaultTokenStore.get();
    }

    @Override
    public TransactionManager transactionManager(String processingGroup) {
        ensureInitialized();
        return transactionManagers.containsKey(processingGroup)
                ? transactionManagers.get(processingGroup).get()
                : defaultTransactionManager.get();
    }

    private void ensureInitialized() {
        Assert.state(configuration != null, () -> "Configuration is not initialized yet");
    }

    //</editor-fold>

    //<editor-fold desc="configurer methods">
    @Override
    public EventProcessingConfigurer registerSagaConfiguration(
            Function<Configuration, SagaConfiguration<?>> sagaConfiguration) {
        this.sagaConfigurations.add(new Component<>(() -> configuration, "sagaConfiguration", sagaConfiguration));
        return this;
    }

    @Override
    public EventProcessingConfigurer registerSagaStore(
            Function<Configuration, SagaStore> sagaStoreBuilder) {
        this.sagaStore.update(sagaStoreBuilder);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerEventHandler(
            Function<Configuration, Object> eventHandlerBuilder) {
        this.eventHandlerBuilders.add(new Component<>(() -> configuration,
                                                      "eventHandler",
                                                      eventHandlerBuilder));
        return this;
    }

    @Override
    public EventProcessingConfigurer registerDefaultListenerInvocationErrorHandler(
            Function<Configuration, ListenerInvocationErrorHandler> listenerInvocationErrorHandlerBuilder) {
        defaultListenerInvocationErrorHandler.update(listenerInvocationErrorHandlerBuilder);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerListenerInvocationErrorHandler(String processingGroup,
                                                                            Function<Configuration, ListenerInvocationErrorHandler> listenerInvocationErrorHandlerBuilder) {
        listenerInvocationErrorHandlers.put(processingGroup, new Component<>(() -> configuration,
                                                                             "listenerInvocationErrorHandler",
                                                                             listenerInvocationErrorHandlerBuilder));
        return this;
    }

    @Override
    public EventProcessingConfigurer registerTrackingEventProcessor(String name,
                                                                    Function<Configuration, StreamableMessageSource<TrackedEventMessage<?>>> source,
                                                                    Function<Configuration, TrackingEventProcessorConfiguration> processorConfiguration) {
        registerEventProcessor(name, (n, c, ehi) -> trackingEventProcessor(c, n, ehi, processorConfiguration, source));
        return this;
    }

    @Override
    public EventProcessingConfigurer registerEventProcessorFactory(
            EventProcessorBuilder eventProcessorBuilder) {
        this.defaultEventProcessorBuilder = eventProcessorBuilder;
        return this;
    }

    @Override
    public EventProcessingConfigurer registerEventProcessor(String name,
                                                            EventProcessorBuilder eventProcessorBuilder) {
        if (this.eventProcessorBuilders.containsKey(name)) {
            throw new IllegalArgumentException(format("Event processor with name %s already exists", name));
        }
        this.eventProcessorBuilders.put(name, eventProcessorBuilder);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerTokenStore(String processingGroup,
                                                        Function<Configuration, TokenStore> tokenStore) {
        this.tokenStore.put(processingGroup, new Component<>(() -> configuration,
                                                             "tokenStore",
                                                             tokenStore));
        return this;
    }

    @Override
    public EventProcessingConfigurer usingSubscribingEventProcessors() {
        this.defaultEventProcessorBuilder = (name, conf, eventHandlerInvoker) ->
                subscribingEventProcessor(name, conf, eventHandlerInvoker, Configuration::eventBus);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerSubscribingEventProcessor(String name,
                                                                       Function<Configuration, SubscribableMessageSource<? extends EventMessage<?>>> messageSource) {
        registerEventProcessor(name, (n, c, ehi) -> subscribingEventProcessor(n, c, ehi, messageSource));
        return this;
    }

    @Override
    public EventProcessingConfigurer registerDefaultErrorHandler(
            Function<Configuration, ErrorHandler> errorHandlerBuilder) {
        this.defaultErrorHandler.update(errorHandlerBuilder);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerErrorHandler(String eventProcessorName,
                                                          Function<Configuration, ErrorHandler> errorHandlerBuilder) {
        this.errorHandlers.put(eventProcessorName, new Component<>(() -> configuration,
                                                                   "errorHandler",
                                                                   errorHandlerBuilder));
        return this;
    }

    @Override
    public EventProcessingConfigurer byDefaultAssignTo(Function<Object, String> assignmentFunction) {
        this.fallback = assignmentFunction;
        return this;
    }

    @Override
    public EventProcessingConfigurer assignHandlersMatching(String processingGroup, int priority,
                                                            Predicate<Object> criteria) {
        this.selectors.add(new ProcessorSelector(processingGroup, priority, criteria));
        return this;
    }

    @Override
    public EventProcessingConfigurer assignProcessingGroup(String processingGroup, String processorName) {
        this.processingGroupsAssignments.put(processingGroup, processorName);
        return this;
    }

    @Override
    public EventProcessingConfigurer assignProcessingGroup(Function<String, String> assignmentRule) {
        this.defaultProcessingGroupAssignment = assignmentRule;
        return this;
    }

    @Override
    public EventProcessingConfigurer registerHandlerInterceptor(String processorName,
                                                                Function<Configuration, MessageHandlerInterceptor<? super EventMessage<?>>> interceptorBuilder) {
        if (configuration != null) {
            eventProcessor(processorName).ifPresent(eventProcessor -> eventProcessor
                    .registerHandlerInterceptor(interceptorBuilder.apply(configuration)));
        }
        this.handlerInterceptorsBuilders.computeIfAbsent(processorName, k -> new ArrayList<>())
                                        .add(interceptorBuilder);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerDefaultHandlerInterceptor(
            BiFunction<Configuration, String, MessageHandlerInterceptor<? super EventMessage<?>>> interceptorBuilder) {
        this.defaultHandlerInterceptors.add(interceptorBuilder);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerSequencingPolicy(String processingGroup,
                                                              Function<Configuration, SequencingPolicy<? super EventMessage<?>>> policyBuilder) {
        this.sequencingPolicies.put(processingGroup, new Component<>(() -> configuration,
                                                                     "sequencingPolicy",
                                                                     policyBuilder));
        return this;
    }

    @Override
    public EventProcessingConfigurer registerDefaultSequencingPolicy(
            Function<Configuration, SequencingPolicy<? super EventMessage<?>>> policyBuilder) {
        this.defaultSequencingPolicy.update(policyBuilder);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerMessageMonitorFactory(String name,
                                                                   MessageMonitorFactory messageMonitorFactory) {
        this.messageMonitorFactories.put(name, messageMonitorFactory);
        return this;
    }

    @Override
    public EventProcessingConfigurer registerRollbackConfiguration(String name,
                                                                   Function<Configuration, RollbackConfiguration> rollbackConfigurationBuilder) {
        this.rollbackConfigurations.put(name, new Component<>(() -> configuration,
                                                              "rollbackConfiguration",
                                                              rollbackConfigurationBuilder));
        return this;
    }

    @Override
    public EventProcessingConfigurer registerTransactionManager(String name,
                                                                Function<Configuration, TransactionManager> transactionManagerBuilder) {
        this.transactionManagers.put(name, new Component<>(() -> configuration,
                                                           "transactionManager",
                                                           transactionManagerBuilder));
        return this;
    }

    @Override
    public EventProcessingConfiguration configure() {
        return this;
    }

    private TrackingEventProcessor defaultEventProcessor(String name, Configuration conf,
                                                         EventHandlerInvoker eventHandlerInvoker) {
        return trackingEventProcessor(conf,
                                      name,
                                      eventHandlerInvoker,
                                      c -> c.getComponent(
                                              TrackingEventProcessorConfiguration.class,
                                              TrackingEventProcessorConfiguration::forSingleThreadedProcessing),
                                      Configuration::eventBus);
    }

    private SubscribingEventProcessor subscribingEventProcessor(String name, Configuration conf,
                                                                EventHandlerInvoker eventHandlerInvoker,
                                                                Function<Configuration, SubscribableMessageSource<? extends EventMessage<?>>> messageSource) {
        return new SubscribingEventProcessor(name,
                                             eventHandlerInvoker,
                                             rollbackConfiguration(name),
                                             messageSource.apply(conf),
                                             DirectEventProcessingStrategy.INSTANCE,
                                             errorHandler(name),
                                             messageMonitor(SubscribingEventProcessor.class, name));
    }

    private TrackingEventProcessor trackingEventProcessor(Configuration conf, String name,
                                                          EventHandlerInvoker eventHandlerInvoker,
                                                          Function<Configuration, TrackingEventProcessorConfiguration> config,
                                                          Function<Configuration, StreamableMessageSource<TrackedEventMessage<?>>> source) {
        return new TrackingEventProcessor(name,
                                          eventHandlerInvoker,
                                          source.apply(conf),
                                          tokenStore(name),
                                          transactionManager(name),
                                          messageMonitor(TrackingEventProcessor.class, name),
                                          rollbackConfiguration(name),
                                          errorHandler(name),
                                          config.apply(conf));
    }
    //</editor-fold>
}