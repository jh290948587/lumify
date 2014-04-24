package io.lumify.core.bootstrap;

import com.altamiracorp.bigtable.model.ModelSession;
import com.google.inject.*;
import com.netflix.curator.RetryPolicy;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.CuratorFrameworkFactory;
import com.netflix.curator.retry.ExponentialBackoffRetry;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.fs.FileSystemSession;
import io.lumify.core.metrics.JmxMetricsManager;
import io.lumify.core.metrics.MetricsManager;
import io.lumify.core.model.artifactThumbnails.ArtifactThumbnailRepository;
import io.lumify.core.model.audit.AuditRepository;
import io.lumify.core.model.detectedObjects.DetectedObjectRepository;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.termMention.TermMentionRepository;
import io.lumify.core.model.user.AuthorizationRepository;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workQueue.WorkQueueRepository;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.security.VisibilityTranslator;
import io.lumify.core.user.User;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.core.util.ServiceLoaderUtil;
import io.lumify.core.version.VersionService;
import io.lumify.core.version.VersionServiceMXBean;
import org.securegraph.Graph;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The LumifyBootstrap is a Guice Module that configures itself by
 * discovering all available implementations of BootstrapBindingProvider
 * and invoking the addBindings() method.  If any discovered provider
 * cannot be instantiated, configuration of the Bootstrap Module will
 * fail and halt application initialization by throwing a BootstrapException.
 */
public class LumifyBootstrap extends AbstractModule {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(LumifyBootstrap.class);

    private static LumifyBootstrap lumifyBootstrap;

    public synchronized static LumifyBootstrap bootstrap(final Configuration configuration) {
        if (lumifyBootstrap == null) {
            LOGGER.debug("Initializing LumifyBootstrap with Configuration:\n%s", configuration);
            lumifyBootstrap = new LumifyBootstrap(configuration);
        }
        return lumifyBootstrap;
    }

    /**
     * Get a ModuleMaker that will return the LumifyBootstrap, initializing it with
     * the provided Configuration if it has not already been created.
     *
     * @param configuration the Lumify configuration
     * @return a ModuleMaker for use with the InjectHelper
     */
    public static InjectHelper.ModuleMaker bootstrapModuleMaker(final Configuration configuration) {
        return new InjectHelper.ModuleMaker() {
            @Override
            public Module createModule() {
                return LumifyBootstrap.bootstrap(configuration);
            }

            @Override
            public Configuration getConfiguration() {
                return configuration;
            }
        };
    }

    /**
     * The Lumify Configuration.
     */
    private final Configuration configuration;

    /**
     * Create a LumifyBootstrap with the provided Configuration.
     *
     * @param config the configuration for this bootstrap
     */
    private LumifyBootstrap(final Configuration config) {
        this.configuration = config;
    }

    @Override
    protected void configure() {
        LOGGER.info("Configuring LumifyBootstrap.");

        MetricsManager metricsManager = new JmxMetricsManager();

        bind(Configuration.class).toInstance(configuration);
        bind(MetricsManager.class).toInstance(metricsManager);
        bind(VersionServiceMXBean.class).to(VersionService.class);

        bind(CuratorFramework.class)
                .toProvider(new CuratorFrameworkProvider(configuration))
                .in(Scopes.SINGLETON);

        bind(ModelSession.class)
                .toProvider(getConfigurableProvider(ModelSession.class, configuration, Configuration.MODEL_PROVIDER))
                .in(Scopes.SINGLETON);
        bind(FileSystemSession.class)
                .toProvider(getConfigurableProvider(FileSystemSession.class, configuration, Configuration.FILESYSTEM_PROVIDER))
                .in(Scopes.SINGLETON);
        bind(Graph.class)
                .toProvider(getGraphProvider(configuration, Configuration.GRAPH_PROVIDER))
                .in(Scopes.SINGLETON);
        bind(WorkQueueRepository.class)
                .toProvider(getConfigurableProvider(WorkQueueRepository.class, configuration, Configuration.WORK_QUEUE_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(VisibilityTranslator.class)
                .toProvider(getConfigurableProvider(VisibilityTranslator.class, configuration, Configuration.VISIBILITY_TRANSLATOR))
                .in(Scopes.SINGLETON);
        bind(UserRepository.class)
                .toProvider(getConfigurableProvider(UserRepository.class, configuration, Configuration.USER_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(WorkspaceRepository.class)
                .toProvider(getConfigurableProvider(WorkspaceRepository.class, configuration, Configuration.WORKSPACE_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(AuthorizationRepository.class)
                .toProvider(getConfigurableProvider(AuthorizationRepository.class, configuration, Configuration.AUTHORIZATION_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(OntologyRepository.class)
                .toProvider(getConfigurableProvider(OntologyRepository.class, configuration, Configuration.ONTOLOGY_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(AuditRepository.class)
                .toProvider(getConfigurableProvider(AuditRepository.class, configuration, Configuration.AUDIT_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(TermMentionRepository.class)
                .toProvider(getConfigurableProvider(TermMentionRepository.class, configuration, Configuration.TERM_MENTION_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(DetectedObjectRepository.class)
                .toProvider(getConfigurableProvider(DetectedObjectRepository.class, configuration, Configuration.DETECTED_OBJECT_REPOSITORY))
                .in(Scopes.SINGLETON);
        bind(ArtifactThumbnailRepository.class)
                .toProvider(getConfigurableProvider(ArtifactThumbnailRepository.class, configuration, Configuration.ARTIFACT_THUMBNAIL_REPOSITORY))
                .in(Scopes.SINGLETON);

        injectProviders();
    }

    private Provider<? extends Graph> getGraphProvider(Configuration configuration, String configurationPrefix) {
        // TODO change to use org.securegraph.GraphFactory
        String graphClassName = configuration.get(configurationPrefix);
        final Map<String, String> configurationSubset = configuration.getSubset(configurationPrefix);

        final Class<?> graphClass;
        try {
            graphClass = Class.forName(graphClassName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Could not find graph class with name: " + graphClassName, e);
        }

        final Method createMethod;
        try {
            createMethod = graphClass.getDeclaredMethod("create", Map.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Could not find create(Map) method on class: " + graphClass.getName(), e);
        }

        return new Provider<Graph>() {
            @Override
            public Graph get() {
                try {
                    return (Graph) createMethod.invoke(null, configurationSubset);
                } catch (Exception e) {
                    throw new RuntimeException("Could not create graph " + graphClass.getName(), e);
                }
            }
        };
    }

    private void injectProviders() {
        LOGGER.info("Running BootstrapBindingProviders");
        ServiceLoader<BootstrapBindingProvider> bindingProviders = ServiceLoaderUtil.load(BootstrapBindingProvider.class);
        Binder binder = binder();
        for (BootstrapBindingProvider provider : bindingProviders) {
            LOGGER.debug("Configuring bindings from BootstrapBindingProvider: %s", provider.getClass().getName());
            provider.addBindings(binder, configuration);
        }
    }

    private static class CuratorFrameworkProvider implements Provider<CuratorFramework> {
        private String zookeeperConnectionString;
        private RetryPolicy retryPolicy;

        public CuratorFrameworkProvider(Configuration configuration) {
            zookeeperConnectionString = configuration.get(Configuration.ZK_SERVERS);
            retryPolicy = new ExponentialBackoffRetry(1000, 3);
        }

        @Override
        public CuratorFramework get() {
            try {
                CuratorFramework client = CuratorFrameworkFactory.newClient(zookeeperConnectionString, retryPolicy);
                client.start();
                return client;
            } catch (IOException ex) {
                throw new LumifyException("Could not create curator: " + zookeeperConnectionString, ex);
            }
        }
    }

    private <T> Provider<T> getConfigurableProvider(final Class<T> clazz, final Configuration config, final String key) {
        Class<? extends T> configuredClass = config.getClass(key);
        return configuredClass != null ? new ConfigurableProvider<T>(configuredClass, config, key, null) : new NullProvider<T>();
    }

    private static class NullProvider<T> implements Provider<T> {
        @Override
        public T get() {
            return (T) null;
        }
    }

    private static class ConfigurableProvider<T> implements Provider<T> {
        private final Class<? extends T> clazz;
        private final Method initMethod;
        private final Object[] initMethodArgs;
        private final Configuration config;
        private final String keyPrefix;

        public ConfigurableProvider(final Class<? extends T> clazz, final Configuration config, String keyPrefix, final User user) {
            this.config = config;
            this.keyPrefix = keyPrefix;
            boolean checkInit = true;
            Method init = null;
            Object[] initArgs = null;
            if (checkInit) {
                init = findInit(clazz, Configuration.class, User.class);
                if (init != null) {
                    initArgs = new Object[]{config, user};
                } else {
                    init = findInit(clazz, Map.class, User.class);
                    if (init != null) {
                        initArgs = new Object[]{config.toMap(), user};
                    } else {
                        init = findInit(clazz, Configuration.class);
                        if (init != null) {
                            initArgs = new Object[]{config};
                        } else {
                            init = findInit(clazz, Map.class);
                            if (init != null) {
                                initArgs = new Object[]{config.toMap()};
                            }
                        }
                    }
                }
            }
            this.clazz = clazz;
            this.initMethod = init;
            this.initMethodArgs = initArgs;
        }

        private Method findInit(Class<? extends T> target, Class<?>... paramTypes) {
            try {
                return target.getMethod("init", paramTypes);
            } catch (NoSuchMethodException nsme) {
                return null;
            } catch (SecurityException se) {
                List<String> paramNames = new ArrayList<String>();
                for (Class<?> pc : paramTypes) {
                    paramNames.add(pc.getSimpleName());
                }
                throw new BootstrapException(se, "Error accessing init(%s) method in %s.", paramNames, clazz.getName());
            }
        }

        @Override
        public T get() {
            Throwable error;
            try {
                LOGGER.debug("creating %s", this.clazz.getName());
                T impl = InjectHelper.getInstance(this.clazz);
                if (initMethod != null) {
                    initMethod.invoke(impl, initMethodArgs);
                }
                config.setConfigurables(impl, this.keyPrefix);
                return impl;
            } catch (IllegalAccessException iae) {
                LOGGER.error("Unable to access default constructor for %s", clazz.getName(), iae);
                error = iae;
            } catch (IllegalArgumentException iae) {
                LOGGER.error("Unable to initialize instance of %s.", clazz.getName(), iae);
                error = iae;
            } catch (InvocationTargetException ite) {
                LOGGER.error("Error initializing instance of %s.", clazz.getName(), ite);
                error = ite;
            }
            throw new BootstrapException(error, "Unable to initialize instance of %s", clazz.getName());
        }
    }
}