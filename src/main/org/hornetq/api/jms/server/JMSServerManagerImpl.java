/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.api.jms.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.hornetq.api.Pair;
import org.hornetq.api.core.config.TransportConfiguration;
import org.hornetq.api.jms.HornetQConnectionFactory;
import org.hornetq.api.jms.HornetQQueue;
import org.hornetq.api.jms.HornetQTopic;
import org.hornetq.api.jms.config.ConnectionFactoryConfiguration;
import org.hornetq.api.jms.config.JMSConfiguration;
import org.hornetq.api.jms.config.QueueConfiguration;
import org.hornetq.api.jms.config.TopicConfiguration;
import org.hornetq.core.deployers.DeploymentManager;
import org.hornetq.core.deployers.impl.FileDeploymentManager;
import org.hornetq.core.deployers.impl.XmlDeployer;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.server.ActivateCallback;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.jms.client.SelectorTranslator;
import org.hornetq.jms.server.impl.JMSServerDeployer;
import org.hornetq.jms.server.management.JMSManagementService;
import org.hornetq.jms.server.management.impl.JMSManagementServiceImpl;

/**
 * A Deployer used to create and add to JNDI queues, topics and connection
 * factories. Typically this would only be used in an app server env.
 * 
 * JMS Connection Factories & Destinations can be configured either
 * using configuration files or using a JMSConfiguration object.
 * 
 * If configuration files are used, JMS resources are redeployed if the
 * files content is changed.
 * If a JMSConfiguration object is used, the JMS resources can not be
 * redeployed.
 *
 * @author <a href="ataylor@redhat.com">Andy Taylor</a>
 * @author <a href="jmesnil@redhat.com">Jeff Mesnil</a>
 * @author <a href="tim.fox@jboss.com">Tim Fox</a>
 */
public class JMSServerManagerImpl implements JMSServerManager, ActivateCallback
{
   private static final Logger log = Logger.getLogger(JMSServerManagerImpl.class);

   private static final String REJECT_FILTER = "__HQX=-1";

   /**
    * the context to bind to
    */
   private Context context;

   private final Map<String, List<String>> destinations = new HashMap<String, List<String>>();

   private final Map<String, HornetQConnectionFactory> connectionFactories = new HashMap<String, HornetQConnectionFactory>();

   private final Map<String, List<String>> connectionFactoryBindings = new HashMap<String, List<String>>();

   private final HornetQServer server;

   private JMSManagementService jmsManagementService;

   private XmlDeployer jmsDeployer;

   private boolean started;

   private boolean active;

   private DeploymentManager deploymentManager;

   private final String configFileName;

   private boolean contextSet;

   private JMSConfiguration config;

   public JMSServerManagerImpl(final HornetQServer server) throws Exception
   {
      this.server = server;

      configFileName = null;
   }

   public JMSServerManagerImpl(final HornetQServer server, final String configFileName) throws Exception
   {
      this.server = server;

      this.configFileName = configFileName;
   }

   public JMSServerManagerImpl(final HornetQServer server, final JMSConfiguration configuration) throws Exception
   {
      this.server = server;

      configFileName = null;

      config = configuration;
   }

   // ActivateCallback implementation -------------------------------------

   public synchronized void activated()
   {
      active = true;

      jmsManagementService = new JMSManagementServiceImpl(server.getManagementService());

      try
      {
         jmsManagementService.registerJMSServer(this);

         // start the JMS deployer only if the configuration is not done using the JMSConfiguration object
         if (config == null)
         {
            jmsDeployer = new JMSServerDeployer(this, deploymentManager, server.getConfiguration());

            if (configFileName != null)
            {
               jmsDeployer.setConfigFileNames(new String[] { configFileName });
            }

            jmsDeployer.start();

            deploymentManager.start();
         }
         else
         {
            deploy();
         }
      }
      catch (Exception e)
      {
         JMSServerManagerImpl.log.error("Failed to start jms deployer", e);
      }
   }

   // HornetQComponent implementation -----------------------------------

   public synchronized void start() throws Exception
   {
      if (started)
      {
         return;
      }

      if (!contextSet)
      {
         context = new InitialContext();
      }

      deploymentManager = new FileDeploymentManager(server.getConfiguration().getFileDeployerScanPeriod());

      server.registerActivateCallback(this);

      server.start();

      started = true;
   }

   public synchronized void stop() throws Exception
   {
      if (!started)
      {
         return;
      }

      if (jmsDeployer != null)
      {
         jmsDeployer.stop();
      }

      if (deploymentManager != null)
      {
         deploymentManager.stop();
      }

      for (String destination : destinations.keySet())
      {
         undeployDestination(destination);
      }

      for (String connectionFactory : new HashSet<String>(connectionFactories.keySet()))
      {
         destroyConnectionFactory(connectionFactory);
      }

      destinations.clear();
      connectionFactories.clear();
      connectionFactoryBindings.clear();

      if (context != null)
      {
         context.close();
      }

      jmsManagementService.unregisterJMSServer();

      jmsManagementService.stop();

      server.stop();

      started = false;
   }

   public boolean isStarted()
   {
      return server.getHornetQServerControl().isStarted();
   }

   // JMSServerManager implementation -------------------------------

   public HornetQServer getHornetQServer()
   {
      return server;
   }

   public synchronized void setContext(final Context context)
   {
      this.context = context;

      contextSet = true;
   }

   public synchronized String getVersion()
   {
      checkInitialised();

      return server.getHornetQServerControl().getVersion();
   }

   public synchronized boolean createQueue(final String queueName,
                                           final String jndiBinding,
                                           final String selectorString,
                                           final boolean durable) throws Exception
   {
      checkInitialised();
      HornetQQueue jBossQueue = new HornetQQueue(queueName);

      // Convert from JMS selector to core filter
      String coreFilterString = null;

      if (selectorString != null)
      {
         coreFilterString = SelectorTranslator.convertToHornetQFilterString(selectorString);
      }

      server.getHornetQServerControl().deployQueue(jBossQueue.getAddress(),
                                                   jBossQueue.getAddress(),
                                                   coreFilterString,
                                                   durable);

      boolean added = bindToJndi(jndiBinding, jBossQueue);

      if (added)
      {
         addToDestinationBindings(queueName, jndiBinding);
      }

      jmsManagementService.registerQueue(jBossQueue, jndiBinding);
      return added;
   }

   public synchronized boolean createTopic(final String topicName, final String jndiBinding) throws Exception
   {
      checkInitialised();
      HornetQTopic jBossTopic = new HornetQTopic(topicName);
      // We create a dummy subscription on the topic, that never receives messages - this is so we can perform JMS
      // checks when routing messages to a topic that
      // does not exist - otherwise we would not be able to distinguish from a non existent topic and one with no
      // subscriptions - core has no notion of a topic
      server.getHornetQServerControl().deployQueue(jBossTopic.getAddress(),
                                                   jBossTopic.getAddress(),
                                                   JMSServerManagerImpl.REJECT_FILTER,
                                                   true);
      boolean added = bindToJndi(jndiBinding, jBossTopic);
      if (added)
      {
         addToDestinationBindings(topicName, jndiBinding);
      }
      jmsManagementService.registerTopic(jBossTopic, jndiBinding);
      return added;
   }

   public synchronized boolean undeployDestination(final String name) throws Exception
   {
      checkInitialised();
      List<String> jndiBindings = destinations.get(name);
      if (jndiBindings == null || jndiBindings.size() == 0)
      {
         return false;
      }
      if (context != null)
      {
         Iterator<String> iter = jndiBindings.iterator();
         while (iter.hasNext())
         {
            String jndiBinding = iter.next();
            context.unbind(jndiBinding);
            iter.remove();
         }
      }
      return true;
   }

   public synchronized boolean destroyQueue(final String name) throws Exception
   {
      checkInitialised();
      undeployDestination(name);

      destinations.remove(name);
      jmsManagementService.unregisterQueue(name);
      server.getHornetQServerControl().destroyQueue(HornetQQueue.createAddressFromName(name).toString());

      return true;
   }

   public synchronized boolean destroyTopic(final String name) throws Exception
   {
      checkInitialised();
      undeployDestination(name);

      destinations.remove(name);
      jmsManagementService.unregisterTopic(name);
      server.getHornetQServerControl().destroyQueue(HornetQTopic.createAddressFromName(name).toString());

      return true;
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final List<Pair<TransportConfiguration, TransportConfiguration>> connectorConfigs,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(connectorConfigs);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final List<Pair<TransportConfiguration, TransportConfiguration>> connectorConfigs,
                                                    final String clientID,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(connectorConfigs);
         cf.setClientID(clientID);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final List<Pair<TransportConfiguration, TransportConfiguration>> connectorConfigs,
                                                    final String clientID,
                                                    final long clientFailureCheckPeriod,
                                                    final long connectionTTL,
                                                    final long callTimeout,
                                                    final boolean cacheLargeMessagesClient,
                                                    final int minLargeMessageSize,
                                                    final int consumerWindowSize,
                                                    final int consumerMaxRate,
                                                    final int confirmationWindowSize,
                                                    final int producerWindowSize,
                                                    final int producerMaxRate,
                                                    final boolean blockOnAcknowledge,
                                                    final boolean blockOnDurableSend,
                                                    final boolean blockOnNonDurableSend,
                                                    final boolean autoGroup,
                                                    final boolean preAcknowledge,
                                                    final String loadBalancingPolicyClassName,
                                                    final int transactionBatchSize,
                                                    final int dupsOKBatchSize,
                                                    final boolean useGlobalPools,
                                                    final int scheduledThreadPoolMaxSize,
                                                    final int threadPoolMaxSize,
                                                    final long retryInterval,
                                                    final double retryIntervalMultiplier,
                                                    final long maxRetryInterval,
                                                    final int reconnectAttempts,
                                                    final boolean failoverOnServerShutdown,
                                                    final String groupId,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(connectorConfigs);
         cf.setClientID(clientID);
         cf.setClientFailureCheckPeriod(clientFailureCheckPeriod);
         cf.setConnectionTTL(connectionTTL);
         cf.setCallTimeout(callTimeout);
         cf.setCacheLargeMessagesClient(cacheLargeMessagesClient);
         cf.setMinLargeMessageSize(minLargeMessageSize);
         cf.setConsumerWindowSize(consumerWindowSize);
         cf.setConsumerMaxRate(consumerMaxRate);
         cf.setConfirmationWindowSize(confirmationWindowSize);
         cf.setProducerWindowSize(producerWindowSize);
         cf.setProducerMaxRate(producerMaxRate);
         cf.setBlockOnAcknowledge(blockOnAcknowledge);
         cf.setBlockOnDurableSend(blockOnDurableSend);
         cf.setBlockOnNonDurableSend(blockOnNonDurableSend);
         cf.setAutoGroup(autoGroup);
         cf.setPreAcknowledge(preAcknowledge);
         cf.setConnectionLoadBalancingPolicyClassName(loadBalancingPolicyClassName);
         cf.setTransactionBatchSize(transactionBatchSize);
         cf.setDupsOKBatchSize(dupsOKBatchSize);
         cf.setUseGlobalPools(useGlobalPools);
         cf.setScheduledThreadPoolMaxSize(scheduledThreadPoolMaxSize);
         cf.setThreadPoolMaxSize(threadPoolMaxSize);
         cf.setRetryInterval(retryInterval);
         cf.setRetryIntervalMultiplier(retryIntervalMultiplier);
         cf.setMaxRetryInterval(maxRetryInterval);
         cf.setReconnectAttempts(reconnectAttempts);
         cf.setFailoverOnServerShutdown(failoverOnServerShutdown);
         cf.setGroupID(groupId);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final String discoveryAddress,
                                                    final int discoveryPort,
                                                    final String clientID,
                                                    final long discoveryRefreshTimeout,
                                                    final long clientFailureCheckPeriod,
                                                    final long connectionTTL,
                                                    final long callTimeout,
                                                    final boolean cacheLargeMessagesClient,
                                                    final int minLargeMessageSize,
                                                    final int consumerWindowSize,
                                                    final int consumerMaxRate,
                                                    final int confirmationWindowSize,
                                                    final int producerWindowSize,
                                                    final int producerMaxRate,
                                                    final boolean blockOnAcknowledge,
                                                    final boolean blockOnDurableSend,
                                                    final boolean blockOnNonDurableSend,
                                                    final boolean autoGroup,
                                                    final boolean preAcknowledge,
                                                    final String loadBalancingPolicyClassName,
                                                    final int transactionBatchSize,
                                                    final int dupsOKBatchSize,
                                                    final long initialWaitTimeout,
                                                    final boolean useGlobalPools,
                                                    final int scheduledThreadPoolMaxSize,
                                                    final int threadPoolMaxSize,
                                                    final long retryInterval,
                                                    final double retryIntervalMultiplier,
                                                    final long maxRetryInterval,
                                                    final int reconnectAttempts,
                                                    final boolean failoverOnServerShutdown,
                                                    final String groupId,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(discoveryAddress, discoveryPort);
         cf.setClientID(clientID);
         cf.setDiscoveryRefreshTimeout(discoveryRefreshTimeout);
         cf.setClientFailureCheckPeriod(clientFailureCheckPeriod);
         cf.setConnectionTTL(connectionTTL);
         cf.setCallTimeout(callTimeout);
         cf.setCacheLargeMessagesClient(cacheLargeMessagesClient);
         cf.setMinLargeMessageSize(minLargeMessageSize);
         cf.setConsumerWindowSize(consumerWindowSize);
         cf.setConsumerMaxRate(consumerMaxRate);
         cf.setConfirmationWindowSize(confirmationWindowSize);
         cf.setProducerWindowSize(producerWindowSize);
         cf.setProducerMaxRate(producerMaxRate);
         cf.setBlockOnAcknowledge(blockOnAcknowledge);
         cf.setBlockOnDurableSend(blockOnDurableSend);
         cf.setBlockOnNonDurableSend(blockOnNonDurableSend);
         cf.setAutoGroup(autoGroup);
         cf.setPreAcknowledge(preAcknowledge);
         cf.setConnectionLoadBalancingPolicyClassName(loadBalancingPolicyClassName);
         cf.setTransactionBatchSize(transactionBatchSize);
         cf.setDupsOKBatchSize(dupsOKBatchSize);
         cf.setDiscoveryInitialWaitTimeout(initialWaitTimeout);
         cf.setUseGlobalPools(useGlobalPools);
         cf.setScheduledThreadPoolMaxSize(scheduledThreadPoolMaxSize);
         cf.setThreadPoolMaxSize(threadPoolMaxSize);
         cf.setRetryInterval(retryInterval);
         cf.setRetryIntervalMultiplier(retryIntervalMultiplier);
         cf.setMaxRetryInterval(maxRetryInterval);
         cf.setReconnectAttempts(reconnectAttempts);
         cf.setFailoverOnServerShutdown(failoverOnServerShutdown);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final String discoveryAddress,
                                                    final int discoveryPort,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(discoveryAddress, discoveryPort);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final String discoveryAddress,
                                                    final int discoveryPort,
                                                    final String clientID,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(discoveryAddress, discoveryPort);
         cf.setClientID(clientID);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final TransportConfiguration liveTC,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(liveTC);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final TransportConfiguration liveTC,
                                                    final String clientID,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(liveTC);
         cf.setClientID(clientID);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final TransportConfiguration liveTC,
                                                    final TransportConfiguration backupTC,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(liveTC, backupTC);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized void createConnectionFactory(final String name,
                                                    final TransportConfiguration liveTC,
                                                    final TransportConfiguration backupTC,
                                                    final String clientID,
                                                    final List<String> jndiBindings) throws Exception
   {
      checkInitialised();
      HornetQConnectionFactory cf = connectionFactories.get(name);
      if (cf == null)
      {
         cf = new HornetQConnectionFactory(liveTC, backupTC);
         cf.setClientID(clientID);
      }

      bindConnectionFactory(cf, name, jndiBindings);
   }

   public synchronized boolean destroyConnectionFactory(final String name) throws Exception
   {
      checkInitialised();
      List<String> jndiBindings = connectionFactoryBindings.get(name);
      if (jndiBindings == null || jndiBindings.size() == 0)
      {
         return false;
      }
      if (context != null)
      {
         for (String jndiBinding : jndiBindings)
         {
            try
            {
               context.unbind(jndiBinding);
            }
            catch (NameNotFoundException e)
            {
               // this is ok.
            }
         }
      }
      connectionFactoryBindings.remove(name);
      connectionFactories.remove(name);

      jmsManagementService.unregisterConnectionFactory(name);

      return true;
   }

   public String[] listRemoteAddresses() throws Exception
   {
      checkInitialised();
      return server.getHornetQServerControl().listRemoteAddresses();
   }

   public String[] listRemoteAddresses(final String ipAddress) throws Exception
   {
      checkInitialised();
      return server.getHornetQServerControl().listRemoteAddresses(ipAddress);
   }

   public boolean closeConnectionsForAddress(final String ipAddress) throws Exception
   {
      checkInitialised();
      return server.getHornetQServerControl().closeConnectionsForAddress(ipAddress);
   }

   public String[] listConnectionIDs() throws Exception
   {
      return server.getHornetQServerControl().listConnectionIDs();
   }

   public String[] listSessions(final String connectionID) throws Exception
   {
      checkInitialised();
      return server.getHornetQServerControl().listSessions(connectionID);
   }

   // Public --------------------------------------------------------

   // Private -------------------------------------------------------

   private synchronized void checkInitialised()
   {
      if (!active)
      {
         throw new IllegalStateException("Cannot access JMS Server, core server is not yet active");
      }
   }

   private void bindConnectionFactory(final HornetQConnectionFactory cf,
                                      final String name,
                                      final List<String> jndiBindings) throws Exception
   {
      for (String jndiBinding : jndiBindings)
      {
         bindToJndi(jndiBinding, cf);

         if (connectionFactoryBindings.get(name) == null)
         {
            connectionFactoryBindings.put(name, new ArrayList<String>());
         }
         connectionFactoryBindings.get(name).add(jndiBinding);
      }

      jmsManagementService.registerConnectionFactory(name, cf, jndiBindings);
   }

   private boolean bindToJndi(final String jndiName, final Object objectToBind) throws NamingException
   {
      if (context != null)
      {
         String parentContext;
         String jndiNameInContext;
         int sepIndex = jndiName.lastIndexOf('/');
         if (sepIndex == -1)
         {
            parentContext = "";
         }
         else
         {
            parentContext = jndiName.substring(0, sepIndex);
         }
         jndiNameInContext = jndiName.substring(sepIndex + 1);
         try
         {
            context.lookup(jndiName);

            JMSServerManagerImpl.log.warn("Binding for " + jndiName + " already exists");
            return false;
         }
         catch (Throwable e)
         {
            // OK
         }

         Context c = org.hornetq.utils.JNDIUtil.createContext(context, parentContext);

         c.rebind(jndiNameInContext, objectToBind);
      }
      return true;
   }

   private void addToDestinationBindings(final String destination, final String jndiBinding)
   {
      if (destinations.get(destination) == null)
      {
         destinations.put(destination, new ArrayList<String>());
      }
      destinations.get(destination).add(jndiBinding);
   }

   private void deploy() throws Exception
   {
      if (config == null)
      {
         return;
      }

      if (config.getContext() != null)
      {
         setContext(config.getContext());
      }

      List<ConnectionFactoryConfiguration> connectionFactoryConfigurations = config.getConnectionFactoryConfigurations();
      for (ConnectionFactoryConfiguration config : connectionFactoryConfigurations)
      {
         if (config.getDiscoveryAddress() != null)
         {
            createConnectionFactory(config.getName(),
                                    config.getDiscoveryAddress(),
                                    config.getDiscoveryPort(),
                                    config.getClientID(),
                                    config.getDiscoveryRefreshTimeout(),
                                    config.getClientFailureCheckPeriod(),
                                    config.getConnectionTTL(),
                                    config.getCallTimeout(),
                                    config.isCacheLargeMessagesClient(),
                                    config.getMinLargeMessageSize(),
                                    config.getConsumerWindowSize(),
                                    config.getConsumerMaxRate(),
                                    config.getConfirmationWindowSize(),
                                    config.getProducerWindowSize(),
                                    config.getProducerMaxRate(),
                                    config.isBlockOnAcknowledge(),
                                    config.isBlockOnDurableSend(),
                                    config.isBlockOnNonDurableSend(),
                                    config.isAutoGroup(),
                                    config.isPreAcknowledge(),
                                    config.getLoadBalancingPolicyClassName(),
                                    config.getTransactionBatchSize(),
                                    config.getDupsOKBatchSize(),
                                    config.getInitialWaitTimeout(),
                                    config.isUseGlobalPools(),
                                    config.getScheduledThreadPoolMaxSize(),
                                    config.getThreadPoolMaxSize(),
                                    config.getRetryInterval(),
                                    config.getRetryIntervalMultiplier(),
                                    config.getMaxRetryInterval(),
                                    config.getReconnectAttempts(),
                                    config.isFailoverOnServerShutdown(),
                                    config.getGroupID(),
                                    Arrays.asList(config.getBindings()));
         }
         else
         {
            createConnectionFactory(config.getName(),
                                    config.getConnectorConfigs(),
                                    config.getClientID(),
                                    config.getClientFailureCheckPeriod(),
                                    config.getConnectionTTL(),
                                    config.getCallTimeout(),
                                    config.isCacheLargeMessagesClient(),
                                    config.getMinLargeMessageSize(),
                                    config.getConsumerWindowSize(),
                                    config.getConsumerMaxRate(),
                                    config.getConfirmationWindowSize(),
                                    config.getProducerWindowSize(),
                                    config.getProducerMaxRate(),
                                    config.isBlockOnAcknowledge(),
                                    config.isBlockOnDurableSend(),
                                    config.isBlockOnNonDurableSend(),
                                    config.isAutoGroup(),
                                    config.isPreAcknowledge(),
                                    config.getLoadBalancingPolicyClassName(),
                                    config.getTransactionBatchSize(),
                                    config.getDupsOKBatchSize(),
                                    config.isUseGlobalPools(),
                                    config.getScheduledThreadPoolMaxSize(),
                                    config.getThreadPoolMaxSize(),
                                    config.getRetryInterval(),
                                    config.getRetryIntervalMultiplier(),
                                    config.getMaxRetryInterval(),
                                    config.getReconnectAttempts(),
                                    config.isFailoverOnServerShutdown(),
                                    config.getGroupID(),
                                    Arrays.asList(config.getBindings()));
         }
      }

      List<QueueConfiguration> queueConfigs = config.getQueueConfigurations();
      for (QueueConfiguration config : queueConfigs)
      {
         String[] bindings = config.getBindings();
         for (String binding : bindings)
         {
            createQueue(config.getName(), binding, config.getSelector(), config.isDurable());
         }
      }

      List<TopicConfiguration> topicConfigs = config.getTopicConfigurations();
      for (TopicConfiguration config : topicConfigs)
      {
         String[] bindings = config.getBindings();
         for (String binding : bindings)
         {
            createTopic(config.getName(), binding);
         }
      }
   }

}