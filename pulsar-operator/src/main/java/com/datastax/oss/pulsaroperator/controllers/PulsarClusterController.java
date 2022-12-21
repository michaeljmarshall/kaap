package com.datastax.oss.pulsaroperator.controllers;

import com.datastax.oss.pulsaroperator.autoscaler.AutoscalerDaemon;
import com.datastax.oss.pulsaroperator.controllers.utils.TokenAuthProvisioner;
import com.datastax.oss.pulsaroperator.crds.BaseComponentStatus;
import com.datastax.oss.pulsaroperator.crds.CRDConstants;
import com.datastax.oss.pulsaroperator.crds.SpecDiffer;
import com.datastax.oss.pulsaroperator.crds.autorecovery.Autorecovery;
import com.datastax.oss.pulsaroperator.crds.autorecovery.AutorecoveryFullSpec;
import com.datastax.oss.pulsaroperator.crds.bastion.Bastion;
import com.datastax.oss.pulsaroperator.crds.bastion.BastionFullSpec;
import com.datastax.oss.pulsaroperator.crds.bastion.BastionSpec;
import com.datastax.oss.pulsaroperator.crds.bookkeeper.BookKeeper;
import com.datastax.oss.pulsaroperator.crds.bookkeeper.BookKeeperFullSpec;
import com.datastax.oss.pulsaroperator.crds.broker.Broker;
import com.datastax.oss.pulsaroperator.crds.broker.BrokerFullSpec;
import com.datastax.oss.pulsaroperator.crds.cluster.PulsarCluster;
import com.datastax.oss.pulsaroperator.crds.cluster.PulsarClusterSpec;
import com.datastax.oss.pulsaroperator.crds.configs.AuthConfig;
import com.datastax.oss.pulsaroperator.crds.function.FunctionsWorker;
import com.datastax.oss.pulsaroperator.crds.function.FunctionsWorkerFullSpec;
import com.datastax.oss.pulsaroperator.crds.proxy.Proxy;
import com.datastax.oss.pulsaroperator.crds.proxy.ProxyFullSpec;
import com.datastax.oss.pulsaroperator.crds.zookeeper.ZooKeeper;
import com.datastax.oss.pulsaroperator.crds.zookeeper.ZooKeeperFullSpec;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.quarkus.runtime.ShutdownEvent;
import java.util.ArrayList;
import java.util.List;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import lombok.SneakyThrows;
import lombok.extern.jbosslog.JBossLog;

@ControllerConfiguration(namespaces = Constants.WATCH_CURRENT_NAMESPACE, name = "pulsar-cluster-app")
@JBossLog
@ApplicationScoped
public class PulsarClusterController extends AbstractController<PulsarCluster> {

    public static final String CUSTOM_RESOURCE_BROKER = "broker";
    public static final String CUSTOM_RESOURCE_BOOKKEEPER = "bookkeeper";
    public static final String CUSTOM_RESOURCE_ZOOKEEPER = "zookeeper";
    public static final String CUSTOM_RESOURCE_PROXY = "proxy";
    public static final String CUSTOM_RESOURCE_AUTORECOVERY = "autorecovery";
    public static final String CUSTOM_RESOURCE_BASTION = "bastion";
    public static final String CUSTOM_RESOURCE_FUNCTIONS_WORKER = "functionsworker";

    private final AutoscalerDaemon autoscaler;

    public PulsarClusterController(KubernetesClient client) {
        super(client);
        autoscaler = new AutoscalerDaemon(client);
    }

    @Override
    protected ReconciliationResult patchResources(PulsarCluster resource, Context<PulsarCluster> context)
            throws Exception {

        final String currentNamespace = resource.getMetadata().getNamespace();
        final PulsarClusterSpec clusterSpec = resource.getSpec();

        final List<OwnerReference> ownerReference = List.of(getOwnerReference(resource));
        generateSecretsIfAbsent(currentNamespace, clusterSpec);

        if (!checkReadyOrPatchZooKeeper(currentNamespace, clusterSpec, ownerReference)) {
            log.info("waiting for zookeeper to become ready");
            return new ReconciliationResult(
                    true,
                    List.of(createNotReadyInitializingCondition(resource))
            );
        }

        if (!checkReadyOrPatchBookKeeper(currentNamespace, clusterSpec, ownerReference)) {
            log.info("waiting for bookkeeper to become ready");
            return new ReconciliationResult(
                    true,
                    List.of(createNotReadyInitializingCondition(resource))
            );
        }

        adjustBrokerReplicas(currentNamespace, clusterSpec);
        final boolean brokerReady = checkReadyOrPatchBroker(currentNamespace, clusterSpec, ownerReference);
        autoscaler.onSpecChange(clusterSpec, currentNamespace);

        adjustProxyFunctionsWorkerDeployment(clusterSpec);
        final boolean proxyReady = checkReadyOrPatchProxy(currentNamespace, clusterSpec, ownerReference);

        adjustBastionTarget(clusterSpec);
        final boolean bastionReady = checkReadyOrPatchBastion(currentNamespace, clusterSpec, ownerReference);
        boolean functionsWorkerReady = false;
        if (brokerReady) {
            functionsWorkerReady =
                    checkReadyOrPatchFunctionsWorker(currentNamespace, clusterSpec, ownerReference);
        }
        final boolean autorecoveryReady = checkReadyOrPatchAutorecovery(currentNamespace, clusterSpec, ownerReference);

        boolean allReady = autorecoveryReady
                && brokerReady
                && proxyReady
                && bastionReady
                && functionsWorkerReady;


        if (allReady) {
            log.info("all resources ready, setting cluster to ready state");
            return new ReconciliationResult(
                    false,
                    List.of(createReadyCondition(resource))
            );
        } else {
            List<String> notReady = new ArrayList<>();
            if (!autorecoveryReady) {
                notReady.add("autorecovery");
            }
            if (!brokerReady) {
                notReady.add("broker");
            }
            if (!proxyReady) {
                notReady.add("proxy");
            }
            if (!bastionReady) {
                notReady.add("bastion");
            }
            if (!functionsWorkerReady) {
                notReady.add("functionsworker");
            }

            log.infof("waiting for %s to become ready", notReady);

            return new ReconciliationResult(
                    true,
                    List.of(createNotReadyInitializingCondition(resource))
            );
        }

    }

    private void adjustBastionTarget(PulsarClusterSpec clusterSpec) {
        if (clusterSpec.getBastion() == null
                || clusterSpec.getBastion().getTargetProxy() == null) {
            boolean isProxyEnabled = clusterSpec.getProxy() != null
                    && clusterSpec.getProxy().getReplicas() > 0;
            if (clusterSpec.getBastion() == null) {
                clusterSpec.setBastion(BastionSpec.builder()
                        .targetProxy(isProxyEnabled)
                        .build()
                );
            } else {
                clusterSpec.getBastion().setTargetProxy(isProxyEnabled);
            }
        }
    }

    private void adjustProxyFunctionsWorkerDeployment(PulsarClusterSpec clusterSpec) {
        boolean isFunctionsWorkerStandaloneMode = clusterSpec.getFunctionsWorker() != null
                && clusterSpec.getFunctionsWorker().getReplicas() > 0;

        if (clusterSpec.getProxy() != null
                && isFunctionsWorkerStandaloneMode) {
            clusterSpec.getProxy().setStandaloneFunctionsWorker(true);
        }
    }

    private void adjustBrokerReplicas(String currentNamespace, PulsarClusterSpec clusterSpec) {
        if (clusterSpec.getBroker() != null
                && clusterSpec.getBroker().getAutoscaler() != null
                && clusterSpec.getBroker().getAutoscaler().getEnabled()) {

            final String crFullName = "%s-%s".formatted(clusterSpec.getGlobal().getName(), CUSTOM_RESOURCE_BROKER);
            final Broker current = client.resources(Broker.class)
                    .inNamespace(currentNamespace)
                    .withName(crFullName)
                    .get();
            if (current != null) {
                final Integer currentReplicas = current.getSpec().getBroker().getReplicas();
                // do not update replicas if patching, leave whatever the autoscaler have set
                clusterSpec.getBroker().setReplicas(currentReplicas);
            }
        }
    }

    private boolean checkReadyOrPatchZooKeeper(String currentNamespace, PulsarClusterSpec clusterSpec,
                                               List<OwnerReference> ownerReference) {
        return checkReadyOrPatch(
                CUSTOM_RESOURCE_ZOOKEEPER,
                ZooKeeper.class,
                ZooKeeperFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .zookeeper(clusterSpec.getZookeeper())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    private boolean checkReadyOrPatchBookKeeper(String currentNamespace, PulsarClusterSpec clusterSpec,
                                                List<OwnerReference> ownerReference) {
        return checkReadyOrPatch(CUSTOM_RESOURCE_BOOKKEEPER,
                BookKeeper.class,
                BookKeeperFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .bookkeeper(clusterSpec.getBookkeeper())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    private boolean checkReadyOrPatchAutorecovery(String currentNamespace, PulsarClusterSpec clusterSpec,
                                                  List<OwnerReference> ownerReference) {
        return checkReadyOrPatch(CUSTOM_RESOURCE_AUTORECOVERY,
                Autorecovery.class,
                AutorecoveryFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .autorecovery(clusterSpec.getAutorecovery())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    private boolean checkReadyOrPatchBroker(String currentNamespace, PulsarClusterSpec clusterSpec,
                                            List<OwnerReference> ownerReference) {
        return checkReadyOrPatch(
                CUSTOM_RESOURCE_BROKER,
                Broker.class,
                BrokerFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .broker(clusterSpec.getBroker())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    private boolean checkReadyOrPatchProxy(String currentNamespace, PulsarClusterSpec clusterSpec,
                                           List<OwnerReference> ownerReference) {
        return checkReadyOrPatch(CUSTOM_RESOURCE_PROXY,
                Proxy.class,
                ProxyFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .proxy(clusterSpec.getProxy())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    private boolean checkReadyOrPatchBastion(String currentNamespace, PulsarClusterSpec clusterSpec,
                                             List<OwnerReference> ownerReference) {
        return checkReadyOrPatch(CUSTOM_RESOURCE_BASTION,
                Bastion.class,
                BastionFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .bastion(clusterSpec.getBastion())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    private boolean checkReadyOrPatchFunctionsWorker(String currentNamespace, PulsarClusterSpec clusterSpec,
                                                     List<OwnerReference> ownerReference) {
        return checkReadyOrPatch(CUSTOM_RESOURCE_FUNCTIONS_WORKER,
                FunctionsWorker.class,
                FunctionsWorkerFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .functionsWorker(clusterSpec.getFunctionsWorker())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    @SneakyThrows
    private <CR extends CustomResource<SPEC, ?>, SPEC> boolean checkReadyOrPatch(
            String customResourceName,
            Class<CR> resourceClass,
            SPEC spec,
            String namespace,
            PulsarClusterSpec clusterSpec,
            List<OwnerReference> ownerReferences) {
        final MixedOperation<CR, KubernetesResourceList<CR>, Resource<CR>> resourceClient =
                client.resources(resourceClass);
        if (resourceClient == null) {
            throw new IllegalStateException(customResourceName + " CRD not found");
        }

        final String crFullName = "%s-%s".formatted(clusterSpec.getGlobal().getName(), customResourceName);
        final CR current = getExistingCustomResource(resourceClass, namespace, crFullName);
        if (current != null) {
            final SPEC currentSpec = current.getSpec();
            final boolean specEquals = SpecDiffer.specsAreEquals(currentSpec, spec);
            if (specEquals) {
                final BaseComponentStatus currentStatus = (BaseComponentStatus) current.getStatus();
                final Condition readyCondition = currentStatus.getConditions().stream()
                        .filter(c -> c.getType().equals(CRDConstants.CONDITIONS_TYPE_READY))
                        .findFirst()
                        .orElse(null);
                if (readyCondition != null && readyCondition.getStatus().equals(CRDConstants.CONDITIONS_STATUS_TRUE)) {
                    return true;
                } else {
                    return false;
                }
            }
        }

        ObjectMeta meta = new ObjectMeta();
        meta.setName(crFullName);
        meta.setNamespace(namespace);
        meta.setOwnerReferences(ownerReferences);

        final CR resource = resourceClass.getConstructor().newInstance();
        resource.setMetadata(meta);
        resource.setSpec(spec);


        resourceClient
                .inNamespace(namespace)
                .resource(resource)
                .createOrReplace();
        log.infof("Patched custom resource %s with name %s ", customResourceName, crFullName);
        return false;
    }

    protected <CR extends CustomResource<SPEC, ?>, SPEC> CR getExistingCustomResource(
            Class<CR> resourceClass, String namespace,
            String crFullName) {
        return client.resources(resourceClass)
                .inNamespace(namespace)
                .withName(crFullName)
                .get();
    }


    @SneakyThrows
    private void generateSecretsIfAbsent(String namespace, PulsarClusterSpec clusterSpec) {
        final AuthConfig auth = clusterSpec.getGlobal().getAuth();
        if (auth == null) {
            return;
        }
        if (!auth.getEnabled()) {
            return;
        }
        getTokenAuthProvisioner(namespace).generateSecretsIfAbsent(auth.getToken());
    }

    protected TokenAuthProvisioner getTokenAuthProvisioner(String namespace) {
        return new TokenAuthProvisioner(client, namespace);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (autoscaler != null) {
            autoscaler.close();
        }
    }
}

