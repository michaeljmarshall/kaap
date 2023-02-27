/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.pulsaroperator.controllers;

import com.datastax.oss.pulsaroperator.common.SerializationUtil;
import com.datastax.oss.pulsaroperator.controllers.broker.BrokerResourcesFactory;
import com.datastax.oss.pulsaroperator.controllers.proxy.ProxyResourcesFactory;
import com.datastax.oss.pulsaroperator.crds.CRDConstants;
import com.datastax.oss.pulsaroperator.crds.GlobalSpec;
import com.datastax.oss.pulsaroperator.crds.configs.AdditionalVolumesConfig;
import com.datastax.oss.pulsaroperator.crds.configs.AntiAffinityConfig;
import com.datastax.oss.pulsaroperator.crds.configs.AuthConfig;
import com.datastax.oss.pulsaroperator.crds.configs.PodDisruptionBudgetConfig;
import com.datastax.oss.pulsaroperator.crds.configs.ProbesConfig;
import com.datastax.oss.pulsaroperator.crds.configs.StorageClassConfig;
import com.datastax.oss.pulsaroperator.crds.configs.VolumeConfig;
import com.datastax.oss.pulsaroperator.crds.configs.tls.TlsConfig;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NodeAffinity;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.fabric8.kubernetes.api.model.PodAffinityTermBuilder;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.PodAntiAffinityBuilder;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.WeightedPodAffinityTerm;
import io.fabric8.kubernetes.api.model.WeightedPodAffinityTermBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

@JBossLog
public abstract class BaseResourcesFactory<T> {

    protected final KubernetesClient client;
    protected final String namespace;
    protected final T spec;
    protected final GlobalSpec global;
    protected final String resourceName;
    protected final OwnerReference ownerReference;
    private VersionInfo version;

    public BaseResourcesFactory(KubernetesClient client, String namespace, String resourceName, T spec,
                                GlobalSpec global, OwnerReference ownerReference) {
        this.client = client;
        this.namespace = namespace;
        this.spec = spec;
        this.global = global;
        this.resourceName = resourceName;
        this.ownerReference = ownerReference;
    }



    protected abstract String getComponentBaseName();

    protected abstract boolean isComponentEnabled();

    private static boolean isImmutableResource(Class<? extends HasMetadata> resourceClass) {
        if (resourceClass.isAssignableFrom(Job.class)) {
            return true;
        }
        return false;
    }

    private static boolean isNonNamespacedResource(Class<? extends HasMetadata> resourceClass) {
        if (resourceClass.isAssignableFrom(StorageClass.class)) {
            return true;
        }
        return false;
    }

    protected <R extends HasMetadata> void patchResource(R resource) {
        if (ownerReference != null && !isNonNamespacedResource(resource.getClass())) {
            resource.getMetadata().setOwnerReferences(List.of(ownerReference));
        }
        final R current = (R) client.resources(resource.getClass())
                .inNamespace(namespace)
                .withName(resource.getMetadata().getName())
                .get();
        final boolean isImmutableResource = isImmutableResource(resource.getClass());
        if (current == null || isImmutableResource) {
            if (current != null && isImmutableResource) {
                client
                        .resource(current)
                        .inNamespace(namespace)
                        .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                        .delete();
            }
            if (isComponentEnabled()) {
                client.resource(resource)
                        .inNamespace(namespace)
                        .create();
            } else {
                log.infof("Skipping creating resource %s since component is disabled",
                        resource.getFullResourceName());
            }
        } else {
            client
                    .resource(current)
                    .inNamespace(namespace)
                    .patch(resource);
        }
    }

    public void deleteStatefulSet() {
        client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(resourceName)
                .delete();
    }

    public void deleteDeployment() {
        client.apps().deployments()
                .inNamespace(namespace)
                .withName(resourceName)
                .delete();
    }

    public void deleteService() {
        client.services()
                .inNamespace(namespace)
                .withName(resourceName)
                .delete();
    }

    public void deletePodDisruptionBudget() {
        client.policy().v1().podDisruptionBudget()
                .inNamespace(namespace)
                .withName(resourceName)
                .delete();
    }

    protected void deleteConfigMap(String resourceName) {
        client.services()
                .inNamespace(namespace)
                .withName(resourceName)
                .delete();
    }

    public void deleteConfigMap() {
        deleteConfigMap(resourceName);
    }

    protected Map<String, String> getLabels(Map<String, String> customLabels) {
        Map<String, String> labels = new HashMap<>();
        labels.put(CRDConstants.LABEL_APP, CRDConstants.LABEL_APP_VALUE);
        labels.put(CRDConstants.LABEL_COMPONENT, getComponentBaseName());
        labels.put(CRDConstants.LABEL_CLUSTER, global.getName());
        if (customLabels != null) {
            labels.putAll(customLabels);
        }
        return labels;
    }

    protected Map<String, String> getPodLabels(Map<String, String> customLabels) {
        Map<String, String> labels = new HashMap<>();
        labels.put(CRDConstants.LABEL_APP, CRDConstants.LABEL_APP_VALUE);
        labels.put(CRDConstants.LABEL_COMPONENT, getComponentBaseName());
        labels.put(CRDConstants.LABEL_CLUSTER, global.getName());
        if (customLabels != null) {
            for (Map.Entry<String, String> customLabel : customLabels.entrySet()) {
                if (StringUtils.isBlank(customLabel.getValue())) {
                    labels.remove(customLabel.getKey());
                } else {
                    labels.put(customLabel.getKey(), customLabel.getValue());
                }
            }
        }
        return labels;
    }

    protected Map<String, String> getMatchLabels(Map<String, String> customMatchLabels) {
        Map<String, String> matchLabels = new HashMap<>();
        matchLabels.put(CRDConstants.LABEL_APP, CRDConstants.LABEL_APP_VALUE);
        matchLabels.put(CRDConstants.LABEL_COMPONENT, getComponentBaseName());
        matchLabels.put(CRDConstants.LABEL_CLUSTER, global.getName());
        if (customMatchLabels != null) {
            for (Map.Entry<String, String> customMatchLabel : customMatchLabels.entrySet()) {
                if (StringUtils.isBlank(customMatchLabel.getValue())) {
                    matchLabels.remove(customMatchLabel.getKey());
                } else {
                    matchLabels.put(customMatchLabel.getKey(), customMatchLabel.getValue());
                }
            }
        }
        return matchLabels;
    }

    protected boolean isPdbSupported() {
        final VersionInfo version = getVersion();
        if (version.getMajor().compareTo("1") >= 0
                && version.getMinor().compareTo("21") >= 0) {
            return true;
        }
        return false;
    }

    private VersionInfo getVersion() {
        if (version == null) {
            version = client.getKubernetesVersion();
        }
        return version;
    }

    protected boolean isTlsEnabledGlobally() {
        return global.getTls() != null && global.getTls().getEnabled();
    }

    protected boolean isTlsEnabledOnZooKeeper() {
        return isTlsEnabledGlobally()
                && global.getTls().getZookeeper() != null
                && global.getTls().getZookeeper().getEnabled();
    }

    protected boolean isTlsEnabledOnBookKeeper() {
        return isTlsEnabledGlobally()
                && global.getTls().getBookkeeper() != null
                && global.getTls().getBookkeeper().getEnabled();
    }

    protected boolean isTlsEnabledOnBroker() {
        return isTlsEnabledGlobally()
                && global.getTls().getBroker() != null
                && global.getTls().getBroker().getEnabled();
    }

    protected boolean isTlsEnabledOnProxy() {
        return isTlsEnabledGlobally()
                && global.getTls().getProxy() != null
                && global.getTls().getProxy().getEnabled();
    }

    protected boolean isTlsEnabledOnProxySet(String proxySet) {
        final boolean tlsEnabledGlobally = isTlsEnabledGlobally();
        if (!tlsEnabledGlobally) {
            return false;
        }
        final TlsConfig.ProxyTlsEntryConfig tlsConfigForProxySet = getTlsConfigForProxySet(proxySet);
        return tlsConfigForProxySet != null && tlsConfigForProxySet.getEnabled();
    }

    protected TlsConfig.ProxyTlsEntryConfig getTlsConfigForProxySet(String proxySet) {
        if (proxySet.equals(ProxyResourcesFactory.PROXY_DEFAULT_SET)
                || global.getTls().getProxy() == null
                || global.getTls().getProxyResourceSets() == null
                || !global.getTls().getProxyResourceSets().containsKey(proxySet)) {
            return global.getTls().getProxy();
        }
        return ObjectUtils.firstNonNull(
                global.getTls().getProxyResourceSets().get(proxySet),
                global.getTls().getProxy());
    }

    protected boolean isTlsEnabledOnFunctionsWorker() {
        return isTlsEnabledGlobally()
                && global.getTls().getFunctionsWorker() != null
                && global.getTls().getFunctionsWorker().getEnabled();
    }

    protected boolean isTlsEnabledOnAutorecovery() {
        return isTlsEnabledGlobally()
                && global.getTls().getFunctionsWorker() != null
                && global.getTls().getFunctionsWorker().getEnabled();
    }

    protected boolean isTlsGenerateSelfSignedCertEnabled() {
        final TlsConfig tls = global.getTls();
        return tls != null
                && tls.getEnabled()
                && tls.getCertProvisioner() != null
                && tls.getCertProvisioner().getSelfSigned() != null
                && tls.getCertProvisioner().getSelfSigned().getEnabled();
    }

    protected String getServiceDnsSuffix() {
        return "%s.svc.%s".formatted(namespace, global.getKubernetesClusterDomain());
    }

    protected String getZkServers() {
        return "%s-%s-ca.%s:%d".formatted(global.getName(),
                global.getComponents().getZookeeperBaseName(),
                getServiceDnsSuffix(),
                isTlsEnabledOnZooKeeper() ? 2281 : 2181);
    }

    private String getBrokerWebServiceUrl(boolean tls) {
        final String brokerServiceName = BrokerResourcesFactory.getResourceName(global.getName(),
                BrokerResourcesFactory.BROKER_DEFAULT_SET,
                global.getComponents().getBrokerBaseName());
        return "%s://%s.%s:%d/".formatted(
                tls ? "https" : "http",
                brokerServiceName,
                getServiceDnsSuffix(), tls ? 8443 : 8080);
    }

    protected String getBrokerWebServiceUrl() {
        final boolean tls = isTlsEnabledOnBroker();
        return getBrokerWebServiceUrl(tls);
    }

    protected String getBrokerWebServiceUrlTls() {
        return getBrokerWebServiceUrl(true);
    }

    protected String getBrokerWebServiceUrlPlain() {
        return getBrokerWebServiceUrl(false);
    }

    private String getBrokerServiceUrl(boolean tls) {
        final String brokerServiceName = BrokerResourcesFactory.getResourceName(global.getName(),
                BrokerResourcesFactory.BROKER_DEFAULT_SET,
                global.getComponents().getBrokerBaseName());
        return "%s://%s.%s:%d/".formatted(
                tls ? "pulsar+ssl" : "pulsar",
                brokerServiceName,
                getServiceDnsSuffix(), tls ? 6651 : 6650);
    }

    protected String getBrokerServiceUrl() {
        final boolean tls = isTlsEnabledOnBroker();
        return getBrokerServiceUrl(tls);
    }

    protected String getBrokerServiceUrlTls() {
        return getBrokerServiceUrl(true);
    }

    protected String getBrokerServiceUrlPlain() {
        return getBrokerServiceUrl(false);
    }


    private String getProxyServiceUrl(boolean tls) {
        return "%s://%s-%s.%s:%d/".formatted(
                tls ? "pulsar+ssl" : "pulsar",
                global.getName(),
                global.getComponents().getProxyBaseName(),
                getServiceDnsSuffix(), tls ? 6651 : 6650);
    }

    protected String getProxyServiceUrl() {
        final boolean tls = isTlsEnabledOnProxy();
        return getProxyServiceUrl(tls);
    }

    protected String getProxyServiceUrlTls() {
        return getProxyServiceUrl(true);
    }

    protected String getProxyServiceUrlPlain() {
        return getProxyServiceUrl(false);
    }

    private String getProxyWebServiceUrl(boolean tls) {
        return "%s://%s-%s.%s:%d/".formatted(
                tls ? "https" : "http",
                global.getName(),
                global.getComponents().getProxyBaseName(),
                getServiceDnsSuffix(), tls ? 8443 : 8080);
    }

    protected String getProxyWebServiceUrl() {
        final boolean tls = isTlsEnabledOnProxy();
        return getProxyWebServiceUrl(tls);
    }

    protected String getProxyWebServiceUrlTls() {
        return getProxyWebServiceUrl(true);
    }

    protected String getProxyWebServiceUrlPlain() {
        return getProxyWebServiceUrl(false);
    }

    protected String getFunctionsWorkerServiceUrl() {
        if (isTlsEnabledOnFunctionsWorker()) {
            return "https://%s-%s-ca.%s:6751".formatted(
                    global.getName(),
                    global.getComponents().getFunctionsWorkerBaseName(),
                    getServiceDnsSuffix()
            );
        } else {
            return "http://%s-%s-ca.%s:6750".formatted(
                    global.getName(),
                    global.getComponents().getFunctionsWorkerBaseName(),
                    getServiceDnsSuffix()
            );
        }
    }

    protected String getTlsSecretNameForZookeeper() {
        final String name = global.getTls().getZookeeper() == null
                ? null : global.getTls().getZookeeper().getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                global.getTls().getDefaultSecretName());
    }

    protected String getTlsSecretNameForBookkeeper() {
        final String name = global.getTls().getBookkeeper() == null
                ? null : global.getTls().getBookkeeper().getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                global.getTls().getDefaultSecretName());
    }

    protected String getTlsSecretNameForBroker() {
        final String name = global.getTls().getBroker() == null
                ? null : global.getTls().getBroker().getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                global.getTls().getDefaultSecretName());
    }

    protected String getTlsSecretNameForProxy() {
        final String name = global.getTls().getProxy() == null
                ? null : global.getTls().getProxy().getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                global.getTls().getDefaultSecretName());
    }

    protected String getTlsSecretNameForProxySet(String proxySet) {
        final TlsConfig.ProxyTlsEntryConfig tlsConfigForProxySet = getTlsConfigForProxySet(proxySet);
        final String name = tlsConfigForProxySet.getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                global.getTls().getDefaultSecretName());
    }

    protected String getTlsSecretNameForFunctionsWorker() {
        final String name = global.getTls().getFunctionsWorker() == null
                ? null : global.getTls().getFunctionsWorker().getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                global.getTls().getDefaultSecretName());
    }

    public static String getTlsSsCaSecretName(GlobalSpec global) {
        final String name = global.getTls().getSsCa() == null
                ? null : global.getTls().getSsCa().getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                "%s-ss-ca".formatted(global.getName()));
    }

    protected String getTlsSsCaSecretName() {
        return getTlsSsCaSecretName(global);
    }

    protected String getTlsSecretNameForAutorecovery() {
        final String name = global.getTls().getAutorecovery() == null
                ? null : global.getTls().getAutorecovery().getSecretName();
        return ObjectUtils.firstNonNull(
                name,
                global.getTls().getDefaultSecretName());
    }

    protected void addTlsVolumesIfEnabled(List<VolumeMount> volumeMounts, List<Volume> volumes,
                                          String secretName) {
        if (!isTlsEnabledGlobally()) {
            return;
        }
        Objects.requireNonNull("secretName cannot be null", secretName);
        volumeMounts.add(createTlsCertsVolumeMount());
        volumes.add(
                new VolumeBuilder()
                        .withName("certs")
                        .withNewSecret().withSecretName(secretName)
                        .endSecret()
                        .build()
        );
    }

    protected VolumeMount createTlsCertsVolumeMount() {
        return new VolumeMountBuilder()
                .withName("certs")
                .withReadOnly(true)
                .withMountPath("/pulsar/certs")
                .build();
    }

    protected boolean createStorageClassIfNeeded(VolumeConfig volumeConfig,
                                                 Map<String, String> customAnnotations,
                                                 Map<String, String> customLabels) {
        if (!global.getPersistence()) {
            return false;
        }
        if (volumeConfig.getExistingStorageClassName() != null) {
            return false;
        }
        if (volumeConfig.getStorageClass() == null) {
            return false;
        }
        final String volumeFullName = resourceName + "-" + volumeConfig.getName();
        final StorageClassConfig storageClass = volumeConfig.getStorageClass();
        if (storageClass == null) {
            throw new IllegalStateException("StorageClass is not defined");
        }

        Map<String, String> parameters = new HashMap<>();
        if (storageClass.getType() != null) {
            parameters.put("type", storageClass.getType());
        }
        if (storageClass.getFsType() != null) {
            parameters.put("fsType", storageClass.getFsType());
        }
        if (storageClass.getExtraParams() != null) {
            parameters.putAll(storageClass.getExtraParams());
        }

        final StorageClass storage = new StorageClassBuilder()
                .withNewMetadata()
                .withName(volumeFullName)
                .withNamespace(namespace)
                .withAnnotations(getAnnotations(customAnnotations))
                .withLabels(getLabels(customLabels))
                .endMetadata()
                .withAllowVolumeExpansion(true)
                .withVolumeBindingMode("WaitForFirstConsumer")
                .withReclaimPolicy(storageClass.getReclaimPolicy())
                .withProvisioner(storageClass.getProvisioner())
                .withParameters(parameters)
                .build();
        patchResource(storage);
        return true;
    }

    protected void createPodDisruptionBudgetIfEnabled(PodDisruptionBudgetConfig pdb,
                                                      Map<String, String> customAnnotations,
                                                      Map<String, String> customLabels,
                                                      Map<String, String> customMatchLabels) {
        if (!pdb.getEnabled()) {
            return;
        }
        final boolean pdbSupported = isPdbSupported();

        final PodDisruptionBudget pdbResource = new PodDisruptionBudgetBuilder()
                .withNewMetadata()
                .withName(resourceName)
                .withNamespace(namespace)
                .withAnnotations(getAnnotations(customAnnotations))
                .withLabels(getLabels(customLabels))
                .endMetadata()
                .withNewSpec()
                .withNewSelector()
                .withMatchLabels(getMatchLabels(customMatchLabels))
                .endSelector()
                .withNewMaxUnavailable(pdb.getMaxUnavailable())
                .endSpec()
                .build();

        if (!pdbSupported) {
            pdbResource.setApiVersion("policy/v1beta1");
        }

        patchResource(pdbResource);
    }

    protected Map<String, String> getAnnotations(Map<String, String> customAnnotations) {
        Map<String, String> annotations = new HashMap<>();
        if (customAnnotations != null) {
            annotations.putAll(customAnnotations);
        }
        return annotations;
    }

    protected Map<String, String> getPodAnnotations(
            Map<String, String> customPodAnnotations,
            ConfigMap configMap) {
        Map<String, String> annotations = new HashMap<>();
        annotations.put("prometheus.io/scrape", "true");
        annotations.put("prometheus.io/port", "8080");
        if (customPodAnnotations != null) {
            annotations.putAll(customPodAnnotations);
        }
        if (configMap != null) {
            addConfigMapChecksumAnnotation(configMap, annotations);
        }
        return annotations;
    }

    protected void addConfigMapChecksumAnnotation(ConfigMap configMap,
                                                  Map<String, String> annotations) {
        if (!global.getRestartOnConfigMapChange()) {
            return;
        }
        String checksum = genChecksum(configMap.getData());
        annotations.put(
                "%s/configmap-%s".formatted(CRDConstants.GROUP, configMap.getMetadata().getName()),
                checksum
        );
    }

    protected String genChecksum(Object object) {
        return DigestUtils.sha256Hex(SerializationUtil.writeAsJsonBytes(object));
    }

    protected PersistentVolumeClaim createPersistentVolumeClaim(String name,
                                                                VolumeConfig volumeConfig) {
        String storageClassName = null;
        if (volumeConfig.getExistingStorageClassName() != null) {
            if (!volumeConfig.getExistingStorageClassName().equals("default")) {
                storageClassName = volumeConfig.getExistingStorageClassName();
            }
        } else if (volumeConfig.getStorageClass() != null) {
            storageClassName = name;
        }

        return new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewSpec()
                .withAccessModes(List.of("ReadWriteOnce"))
                .withNewResources()
                .withRequests(Map.of("storage", Quantity.parse(volumeConfig.getSize())))
                .endResources()
                .withStorageClassName(storageClassName)
                .endSpec()
                .build();
    }

    public StatefulSet getStatefulSet() {
        return client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(resourceName)
                .get();
    }

    public Deployment getDeployment() {
        return client.apps().deployments()
                .inNamespace(namespace)
                .withName(resourceName)
                .get();
    }

    public Job getJob(String name) {
        return client
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withName(name)
                .get();
    }

    public Job getJob() {
        return getJob(resourceName);
    }

    public boolean isJobCompleted(String name) {
        return isJobCompleted(getJob(name));
    }

    public boolean isJobCompleted() {
        return isJobCompleted(getJob(resourceName));
    }

    public static boolean isJobCompleted(Job job) {
        if (job == null) {
            return false;
        }
        final Integer succeeded = job.getStatus().getSucceeded();
        return succeeded != null && succeeded > 0;
    }

    public static boolean isStatefulSetReady(StatefulSet sts) {
        final StatefulSetStatus status = sts.getStatus();
        if (status.getReplicas() == null || status.getReadyReplicas() == null) {
            return false;
        }
        return status.getReplicas().intValue() == status.getReadyReplicas().intValue();
    }

    public static boolean isDeploymentReady(Deployment deployment) {
        final DeploymentStatus deploymentStatus = deployment.getStatus();
        if (deploymentStatus.getReplicas() == null || deploymentStatus.getReadyReplicas() == null) {
            return false;
        }
        return deploymentStatus.getReplicas().intValue() == deploymentStatus.getReadyReplicas().intValue();
    }

    protected void patchServiceAccountSingleRole(boolean namespaced, List<PolicyRule> rules,
                                                 String serviceAccountName) {
        final ServiceAccount serviceAccount = new ServiceAccountBuilder()
                .withNewMetadata()
                .withName(serviceAccountName)
                .withNamespace(namespace)
                .endMetadata()
                .build();
        patchResource(serviceAccount);
        if (namespaced) {
            final Role role = new RoleBuilder()
                    .withNewMetadata()
                    .withName(resourceName)
                    .withNamespace(namespace)
                    .endMetadata()
                    .withRules(rules)
                    .build();

            final RoleBinding roleBinding = new RoleBindingBuilder()
                    .withNewMetadata()
                    .withName(resourceName)
                    .withNamespace(namespace)
                    .endMetadata()
                    .withNewRoleRef()
                    .withKind("Role")
                    .withName(resourceName)
                    .endRoleRef()
                    .withSubjects(new SubjectBuilder()
                            .withKind("ServiceAccount")
                            .withName(serviceAccountName)
                            .withNamespace(namespace)
                            .build()
                    )
                    .build();
            patchResource(role);
            patchResource(roleBinding);
        } else {
            final ClusterRole role = new ClusterRoleBuilder()
                    .withNewMetadata()
                    .withName(resourceName)
                    .endMetadata()
                    .withRules(rules)
                    .build();
            final ClusterRoleBinding roleBinding = new ClusterRoleBindingBuilder()
                    .withNewMetadata()
                    .withName(resourceName)
                    .endMetadata()
                    .withNewRoleRef()
                    .withKind("ClusterRole")
                    .withName(resourceName)
                    .endRoleRef()
                    .withSubjects(new SubjectBuilder()
                            .withKind("ServiceAccount")
                            .withName(serviceAccountName)
                            .withNamespace(namespace)
                            .build()
                    )
                    .build();
            patchResource(role);
            patchResource(roleBinding);
        }
    }

    protected boolean isAuthTokenEnabled() {
        final AuthConfig auth = global.getAuth();
        return auth != null
                && auth.getEnabled()
                && auth.getToken() != null;
    }

    protected void addSecretTokenVolume(List<VolumeMount> volumeMounts, List<Volume> volumes, String role) {
        final String tokenName = "token-%s".formatted(role);
        volumes.add(
                new VolumeBuilder()
                        .withName(tokenName)
                        .withNewSecret().withSecretName(tokenName).endSecret()
                        .build()
        );
        volumeMounts.add(
                new VolumeMountBuilder()
                        .withName(tokenName)
                        .withMountPath("/pulsar/%s".formatted(tokenName))
                        .withReadOnly(true)
                        .build()
        );
    }

    public static <T> Map<String, T> handleConfigPulsarPrefix(Map<String, T> data) {
        if (data == null) {
            return null;
        }
        Map<String, T> newData = new HashMap<>();
        data.forEach((k, v) -> {
            final String newKey;
            // don't modify PULSAR_XX
            if (k.startsWith("PULSAR_") || k.startsWith("BOOKIE_")) {
                newKey = k;
            } else {
                newKey = "PULSAR_PREFIX_%s".formatted(k);
            }
            newData.put(newKey, v);
        });
        return newData;
    }

    protected void appendConfigData(Map<String, String> data, Map<String, Object> config) {
        if (config == null) {
            return;
        }
        config.forEach((k, v) -> {
            if (v != null) {
                data.put(k, v.toString());
            }
        });
    }

    protected String generateCertConverterScript() {
        String script = """
                certconverter() {
                    local name=pulsar
                    local crtFile=/pulsar/certs/tls.crt
                    local keyFile=/pulsar/certs/tls.key
                    caFile=%s
                    p12File=/pulsar/tls.p12
                    keyStoreFile=/pulsar/tls.keystore.jks
                    trustStoreFile=/pulsar/tls.truststore.jks
                                
                    head /dev/urandom | base64 | head -c 24 > /pulsar/keystoreSecret.txt
                    export tlsTrustStorePassword=$(cat /pulsar/keystoreSecret.txt)
                    export PF_tlsTrustStorePassword=$(cat /pulsar/keystoreSecret.txt)
                    export tlsKeyStorePassword=$(cat /pulsar/keystoreSecret.txt)
                    export PF_tlsKeyStorePassword=$(cat /pulsar/keystoreSecret.txt)
                    export PULSAR_PREFIX_brokerClientTlsTrustStorePassword=$(cat /pulsar/keystoreSecret.txt)
                                
                    openssl pkcs12 \\
                        -export \\
                        -in ${crtFile} \\
                        -inkey ${keyFile} \\
                        -out ${p12File} \\
                        -name ${name} \\
                        -passout "file:/pulsar/keystoreSecret.txt"
                                
                    keytool -importkeystore \\
                        -srckeystore ${p12File} \\
                        -srcstoretype PKCS12 -srcstorepass:file "/pulsar/keystoreSecret.txt" \\
                        -alias ${name} \\
                        -destkeystore ${keyStoreFile} \\
                        -deststorepass:file "/pulsar/keystoreSecret.txt"
                                
                    keytool -import \\
                        -file ${caFile} \\
                        -storetype JKS \\
                        -alias ${name} \\
                        -keystore ${trustStoreFile} \\
                        -storepass:file "/pulsar/keystoreSecret.txt" \\
                        -trustcacerts -noprompt
                } &&
                certconverter &&
                """.formatted(getFullCaPath());
        if (isTlsEnabledOnZooKeeper()) {
            final String keyStoreLocation = "${keyStoreFile}";
            final String trustStoreLocation = "${trustStoreFile}";

            final Map<String, String> sslClientOpts = new HashMap<>();
            sslClientOpts.put("zookeeper.clientCnxnSocket", "org.apache.zookeeper.ClientCnxnSocketNetty");
            sslClientOpts.put("zookeeper.client.secure", "true");
            sslClientOpts.put("zookeeper.ssl.keyStore.location", keyStoreLocation);
            sslClientOpts.put("zookeeper.ssl.keyStore.passwordPath", "/pulsar/keystoreSecret.txt");
            sslClientOpts.put("zookeeper.ssl.trustStore.location", trustStoreLocation);
            sslClientOpts.put("zookeeper.ssl.trustStore.passwordPath", "/pulsar/keystoreSecret.txt");
            sslClientOpts.put("zookeeper.ssl.hostnameVerification", "true");

            final Map<String, String> sslClientAndQuorumOpts = new HashMap<>(sslClientOpts);
            sslClientAndQuorumOpts.put("zookeeper.sslQuorum", "true");
            sslClientAndQuorumOpts.put("zookeeper.serverCnxnFactory", "org.apache.zookeeper.server.NettyServerCnxnFactory");
            sslClientAndQuorumOpts.put("zookeeper.ssl.quorum.keyStore.location", keyStoreLocation);
            sslClientAndQuorumOpts.put("zookeeper.ssl.quorum.keyStore.passwordPath", "/pulsar/keystoreSecret.txt");
            sslClientAndQuorumOpts.put("zookeeper.ssl.quorum.trustStore.location", trustStoreLocation);
            sslClientAndQuorumOpts.put("zookeeper.ssl.quorum.trustStore.passwordPath", "/pulsar/keystoreSecret.txt");
            sslClientAndQuorumOpts.put("zookeeper.ssl.quorum.hostnameVerification", "true");
            final String pulsarOptsStr = sslClientAndQuorumOpts.entrySet()
                    .stream()
                    .map(e -> "-D%s=%s".formatted(e.getKey(), e.getValue()))
                    .collect(Collectors.joining(" "));

            final String bkOptsStr = sslClientOpts.entrySet()
                    .stream()
                    .map(e -> "-D%s=%s".formatted(e.getKey(), e.getValue()))
                    .collect(Collectors.joining(" "));
            script += """
                    passwordArg="passwordPath=/pulsar/keystoreSecret.txt" && (
                    cat >> conf/pulsar_env.sh << EOF
                                        
                    PULSAR_EXTRA_OPTS="\\${PULSAR_EXTRA_OPTS} %s"
                    EOF
                    ) && (
                    cat >> conf/bkenv.sh << EOF
                                        
                    BOOKIE_EXTRA_OPTS="\\${BOOKIE_EXTRA_OPTS} %s"
                    EOF
                    ) &&
                    """.formatted(pulsarOptsStr, bkOptsStr);
        }
        script += "echo ''";
        return script;
    }

    protected void addAdditionalVolumes(AdditionalVolumesConfig additionalVolumesConfig,
                                        List<VolumeMount> volumeMounts, List<Volume> volumes) {
        if (additionalVolumesConfig != null) {
            if (additionalVolumesConfig.getVolumes() != null) {
                volumes.addAll(additionalVolumesConfig.getVolumes());
            }
            if (additionalVolumesConfig.getMounts() != null) {
                volumeMounts.addAll(additionalVolumesConfig.getMounts());
            }
        }
    }

    protected Affinity getAffinity(NodeAffinity nodeAffinity,
                                   AntiAffinityConfig overrideGlobalAntiAffinity,
                                   Map<String, String> customMatchLabels) {

        List<PodAffinityTerm> requiredTerms = new ArrayList<>();
        List<WeightedPodAffinityTerm> preferredTerms = new ArrayList<>();

        final AntiAffinityConfig antiAffinityConfig =
                ObjectUtils.firstNonNull(overrideGlobalAntiAffinity, global.getAntiAffinity());

        if (antiAffinityConfig != null) {
            final AntiAffinityConfig.HostAntiAffinityConfig host = antiAffinityConfig.getHost();

            if (host != null
                    && host.getEnabled() != null
                    && host.getEnabled()) {

                final PodAffinityTerm podAffinityTerm = createPodAffinityTerm(
                        "kubernetes.io/hostname",
                        customMatchLabels);
                if (host.getRequired() != null && host.getRequired()) {
                    requiredTerms.add(podAffinityTerm);
                } else {
                    preferredTerms.add(createWeightedPodAffinityTerm(
                            "kubernetes.io/hostname",
                            customMatchLabels));
                }
            }
            final AntiAffinityConfig.ZoneAntiAffinityConfig zone = antiAffinityConfig.getZone();
            if (zone != null
                    && zone.getEnabled() != null
                    && zone.getEnabled()) {
                final WeightedPodAffinityTerm weightedPodAffinityTerm =
                        createWeightedPodAffinityTerm(
                                "failure-domain.beta.kubernetes.io/zone",
                                customMatchLabels);
                preferredTerms.add(weightedPodAffinityTerm);
            }
        }

        PodAntiAffinity podAntiAffinity = null;
        if (!preferredTerms.isEmpty() || !requiredTerms.isEmpty()) {
            podAntiAffinity = new PodAntiAffinityBuilder()
                    .withPreferredDuringSchedulingIgnoredDuringExecution(preferredTerms)
                    .withRequiredDuringSchedulingIgnoredDuringExecution(requiredTerms)
                    .build();
        }
        if (podAntiAffinity != null || nodeAffinity != null) {
            return new AffinityBuilder()
                    .withNodeAffinity(nodeAffinity)
                    .withPodAntiAffinity(podAntiAffinity)
                    .build();
        }
        return null;
    }

    private PodAffinityTerm createPodAffinityTerm(String topologyKey, Map<String, String> customMatchLabels) {
        final PodAffinityTerm podAffinityTerm = new PodAffinityTermBuilder()
                .withNewLabelSelector()
                .withMatchLabels(getMatchLabels(customMatchLabels))
                .endLabelSelector()
                .withTopologyKey(topologyKey)
                .build();
        return podAffinityTerm;
    }

    private WeightedPodAffinityTerm createWeightedPodAffinityTerm(String topologyKey,
                                                                  Map<String, String> customMatchLabels) {
        final PodAffinityTerm podAffinityTerm = createPodAffinityTerm(topologyKey, customMatchLabels);
        return new WeightedPodAffinityTermBuilder()
                .withWeight(100)
                .withPodAffinityTerm(podAffinityTerm)
                .build();
    }

    protected String getFullCaPath() {
        if (!isTlsEnabledGlobally()) {
            return null;
        }
        if (isTlsGenerateSelfSignedCertEnabled()) {
            return "/pulsar/certs/ca.crt";
        } else {
            return global.getTls().getCaPath();
        }
    }

    protected ProbeBuilder newProbeBuilder(ProbesConfig.ProbeConfig probeConfig) {
        return new ProbeBuilder()
                .withInitialDelaySeconds(probeConfig.getInitialDelaySeconds())
                .withTimeoutSeconds(probeConfig.getTimeoutSeconds())
                .withPeriodSeconds(probeConfig.getPeriodSeconds())
                .withFailureThreshold(probeConfig.getFailureThreshold())
                .withSuccessThreshold(probeConfig.getSuccessThreshold());
    }

    protected static void checkEnvListNotContains(List<EnvVar> list, List<String> forbidden) {
        if (list == null) {
            return;
        }
        for (EnvVar envVar : list) {
            if (forbidden.contains(envVar.getName())) {
                throw new IllegalArgumentException("Env list contains forbidden env var: " + envVar.getName());
            }
        }
    }

    protected static List<Container> getInitContainers(List<Container> containers) {
        if (containers == null) {
            return new ArrayList<>();
        }
        return containers;
    }

    protected static List<Container> getSidecars(List<Container> containers) {
        if (containers == null) {
            return new ArrayList<>();
        }
        return containers;
    }
}
