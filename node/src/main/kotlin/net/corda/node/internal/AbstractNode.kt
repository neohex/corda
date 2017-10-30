package net.corda.node.internal

import com.codahale.metrics.MetricRegistry
import com.google.common.collect.MutableClassToInstanceMap
import com.google.common.util.concurrent.MoreExecutors
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.confidential.SwapIdentitiesHandler
import net.corda.core.CordaException
import net.corda.core.concurrent.CordaFuture
import net.corda.core.crypto.SignedData
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.*
import net.corda.core.internal.concurrent.map
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.messaging.*
import net.corda.core.node.*
import net.corda.core.node.services.*
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.serialization.deserialize
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.debug
import net.corda.core.utilities.getOrThrow
import net.corda.node.VersionInfo
import net.corda.node.internal.classloading.requireAnnotation
import net.corda.node.internal.cordapp.CordappLoader
import net.corda.node.internal.cordapp.CordappProviderImpl
import net.corda.node.internal.cordapp.CordappProviderInternal
import net.corda.node.services.ContractUpgradeHandler
import net.corda.node.services.FinalityHandler
import net.corda.node.services.NotaryChangeHandler
import net.corda.node.services.api.*
import net.corda.node.services.config.BFTSMaRtConfiguration
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.NotaryConfig
import net.corda.node.services.config.configureWithDevSSLCertificate
import net.corda.node.services.events.NodeSchedulerService
import net.corda.node.services.events.ScheduledActivityObserver
import net.corda.node.services.identity.PersistentIdentityService
import net.corda.node.services.keys.PersistentKeyManagementService
import net.corda.node.services.messaging.MessagingService
import net.corda.node.services.messaging.sendRequest
import net.corda.node.services.network.*
import net.corda.node.services.network.NetworkMapService.RegistrationRequest
import net.corda.node.services.network.NetworkMapService.RegistrationResponse
import net.corda.node.services.persistence.DBCheckpointStorage
import net.corda.node.services.persistence.DBTransactionMappingStorage
import net.corda.node.services.persistence.DBTransactionStorage
import net.corda.node.services.persistence.NodeAttachmentService
import net.corda.node.services.schema.HibernateObserver
import net.corda.node.services.schema.NodeSchemaService
import net.corda.node.services.statemachine.*
import net.corda.node.services.transactions.*
import net.corda.node.services.upgrade.ContractUpgradeServiceImpl
import net.corda.node.services.vault.NodeVaultService
import net.corda.node.services.vault.VaultSoftLockManager
import net.corda.node.utilities.*
import net.corda.node.utilities.AddOrRemove.ADD
import org.apache.activemq.artemis.utils.ReusableLatch
import org.slf4j.Logger
import rx.Observable
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.security.KeyPair
import java.security.KeyStoreException
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.sql.Connection
import java.time.Clock
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.collections.set
import kotlin.reflect.KClass
import net.corda.core.crypto.generateKeyPair as cryptoGenerateKeyPair

/**
 * A base node implementation that can be customised either for production (with real implementations that do real
 * I/O), or a mock implementation suitable for unit test environments.
 *
 * Marked as SingletonSerializeAsToken to prevent the invisible reference to AbstractNode in the ServiceHub accidentally
 * sweeping up the Node into the Kryo checkpoint serialization via any flows holding a reference to ServiceHub.
 */
// TODO Log warning if this node is a notary but not one of the ones specified in the network parameters, both for core and custom

// In theory the NodeInfo for the node should be passed in, instead, however currently this is constructed by the
// AbstractNode. It should be possible to generate the NodeInfo outside of AbstractNode, so it can be passed in.
abstract class AbstractNode(config: NodeConfiguration,
                            val platformClock: Clock,
                            protected val versionInfo: VersionInfo,
                            protected val cordappLoader: CordappLoader,
                            @VisibleForTesting private val busyNodeLatch: ReusableLatch = ReusableLatch()) : SingletonSerializeAsToken() {
    open val configuration = config.apply {
        require(minimumPlatformVersion <= versionInfo.platformVersion) {
            "minimumPlatformVersion cannot be greater than the node's own version"
        }
    }

    private class StartedNodeImpl<out N : AbstractNode>(
            override val internals: N,
            services: ServiceHubInternalImpl,
            override val info: NodeInfo,
            override val checkpointStorage: CheckpointStorage,
            override val smm: StateMachineManager,
            override val attachments: NodeAttachmentService,
            override val network: MessagingService,
            override val database: CordaPersistence,
            override val rpcOps: CordaRPCOps,
            flowStarter: FlowStarter,
            internal val schedulerService: NodeSchedulerService) : StartedNode<N> {
        override val services: StartedNodeServices = object : StartedNodeServices, ServiceHubInternal by services, FlowStarter by flowStarter {}
    }

    protected abstract val log: Logger

    // We will run as much stuff in this single thread as possible to keep the risk of thread safety bugs low during the
    // low-performance prototyping period.
    protected abstract val serverThread: AffinityExecutor

    protected lateinit var networkParameters: NetworkParameters
    private val cordappServices = MutableClassToInstanceMap.create<SerializeAsToken>()
    private val flowFactories = ConcurrentHashMap<Class<out FlowLogic<*>>, InitiatedFlowFactory<*>>()
    protected val partyKeys = mutableSetOf<KeyPair>()

    protected val services: ServiceHubInternal get() = _services
    private lateinit var _services: ServiceHubInternalImpl
    protected lateinit var legalIdentity: PartyAndCertificate
    private lateinit var allIdentities: List<PartyAndCertificate>
    protected lateinit var info: NodeInfo
    protected var myNotaryIdentity: PartyAndCertificate? = null
    protected lateinit var checkpointStorage: CheckpointStorage
    protected lateinit var smm: StateMachineManager
    private lateinit var tokenizableServices: List<Any>
    protected lateinit var attachments: NodeAttachmentService
    protected lateinit var network: MessagingService
    protected val runOnStop = ArrayList<() -> Any?>()
    protected lateinit var database: CordaPersistence
    protected val _nodeReadyFuture = openFuture<Unit>()
    /** Completes once the node has successfully registered with the network map service
     * or has loaded network map data from local database */
    val nodeReadyFuture: CordaFuture<Unit>
        get() = _nodeReadyFuture
    /** A [CordaX500Name] with null common name. */
    protected val myLegalName: CordaX500Name by lazy {
        val cert = loadKeyStore(configuration.nodeKeystore, configuration.keyStorePassword).getX509Certificate(X509Utilities.CORDA_CLIENT_CA)
        CordaX500Name.build(cert.subjectX500Principal).copy(commonName = null)
    }

    open val serializationWhitelists: List<SerializationWhitelist> by lazy {
        cordappLoader.cordapps.flatMap { it.serializationWhitelists }
    }

    /** Set to non-null once [start] has been successfully called. */
    open val started get() = _started
    @Volatile private var _started: StartedNode<AbstractNode>? = null

    /** The implementation of the [CordaRPCOps] interface used by this node. */
    open fun makeRPCOps(flowStarter: FlowStarter): CordaRPCOps {
        return CordaRPCOpsImpl(services, smm, database, flowStarter)
    }

    private fun saveOwnNodeInfo() {
        NodeInfoWatcher.saveToFile(configuration.baseDirectory, info, services.keyManagementService)
    }

    private fun initCertificate() {
        if (configuration.devMode) {
            log.warn("Corda node is running in dev mode.")
            configuration.configureWithDevSSLCertificate()
        }
        validateKeystore()
    }

    private fun makeSchemaService() = NodeSchemaService(cordappLoader)
    open fun generateNodeInfo() {
        check(started == null) { "Node has already been started" }
        initCertificate()
        log.info("Generating nodeInfo ...")
        val schemaService = makeSchemaService()
        initialiseDatabasePersistence(schemaService) {
            val transactionStorage = makeTransactionStorage()
            makeServices(schemaService, transactionStorage, StateLoaderImpl(transactionStorage))
            saveOwnNodeInfo()
        }
    }

    open fun start(): StartedNode<AbstractNode> {
        check(started == null) { "Node has already been started" }
        log.info("Node starting up ...")
        initCertificate()
        readNetworkParameters()
        val schemaService = makeSchemaService()
        // Do all of this in a database transaction so anything that might need a connection has one.
        val startedImpl = initialiseDatabasePersistence(schemaService) {
            val transactionStorage = makeTransactionStorage()
            val stateLoader = StateLoaderImpl(transactionStorage)
            val services = makeServices(schemaService, transactionStorage, stateLoader)
            saveOwnNodeInfo()
            smm = makeStateMachineManager()
            val flowStarter = FlowStarterImpl(serverThread, smm)
            val schedulerService = NodeSchedulerService(platformClock, this@AbstractNode.database, flowStarter, stateLoader, unfinishedSchedules = busyNodeLatch, serverThread = serverThread)
            if (serverThread is ExecutorService) {
                runOnStop += {
                    // We wait here, even though any in-flight messages should have been drained away because the
                    // server thread can potentially have other non-messaging tasks scheduled onto it. The timeout value is
                    // arbitrary and might be inappropriate.
                    MoreExecutors.shutdownAndAwaitTermination(serverThread as ExecutorService, 50, SECONDS)
                }
            }
            makeVaultObservers(schedulerService)
            val rpcOps = makeRPCOps(flowStarter)
            startMessagingService(rpcOps)
            installCoreFlows()
            val cordaServices = installCordaServices(flowStarter)
            tokenizableServices = services + cordaServices + schedulerService
            registerCordappFlows()
            _services.rpcFlows += cordappLoader.cordapps.flatMap { it.rpcFlows }
            FlowLogicRefFactoryImpl.classloader = cordappLoader.appClassLoader

            runOnStop += network::stop
            StartedNodeImpl(this, _services, info, checkpointStorage, smm, attachments, network, database, rpcOps, flowStarter, schedulerService)
        }
        // If we successfully  loaded network data from database, we set this future to Unit.
        services.networkMapCache.addNode(info)
        _nodeReadyFuture.captureLater(services.networkMapCache.nodeReady.map { Unit })

        return startedImpl.apply {
            database.transaction {
                smm.start(tokenizableServices)
                // Shut down the SMM so no Fibers are scheduled.
                runOnStop += { smm.stop(acceptableLiveFiberCountOnStop()) }
                schedulerService.start()
            }
            _started = this
        }
    }

    protected open fun makeStateMachineManager(): StateMachineManager {
        return StateMachineManagerImpl(
                services,
                checkpointStorage,
                serverThread,
                database,
                busyNodeLatch,
                cordappLoader.appClassLoader
        )
    }

    private class ServiceInstantiationException(cause: Throwable?) : CordaException("Service Instantiation Error", cause)

    private fun installCordaServices(flowStarter: FlowStarter): List<SerializeAsToken> {
        val loadedServices = cordappLoader.cordapps.flatMap { it.services }
        return filterServicesToInstall(loadedServices).mapNotNull {
            try {
                installCordaService(flowStarter, it)
            } catch (e: NoSuchMethodException) {
                log.error("${it.name}, as a Corda service, must have a constructor with a single parameter of type " +
                        ServiceHub::class.java.name)
                null
            } catch (e: ServiceInstantiationException) {
                log.error("Corda service ${it.name} failed to instantiate", e.cause)
                null
            } catch (e: Exception) {
                log.error("Unable to install Corda service ${it.name}", e)
                null
            }
        }
    }

    private fun filterServicesToInstall(loadedServices: List<Class<out SerializeAsToken>>): List<Class<out SerializeAsToken>> {
        val customNotaryServiceList = loadedServices.filter { isNotaryService(it) }
        if (customNotaryServiceList.isNotEmpty()) {
            if (configuration.notary?.custom == true) {
                require(customNotaryServiceList.size == 1) {
                    "Attempting to install more than one notary service: ${customNotaryServiceList.joinToString()}"
                }
            } else return loadedServices - customNotaryServiceList
        }
        return loadedServices
    }

    /**
     * If the [serviceClass] is a notary service, it will only be enable if the "custom" flag is set in
     * the notary configuration.
     */
    private fun isNotaryService(serviceClass: Class<*>) = NotaryService::class.java.isAssignableFrom(serviceClass)

    /**
     * This customizes the ServiceHub for each CordaService that is initiating flows
     */
    private class AppServiceHubImpl<T : SerializeAsToken>(private val serviceHub: ServiceHub, private val flowStarter: FlowStarter) : AppServiceHub, ServiceHub by serviceHub {
        lateinit var serviceInstance: T
        override fun <T> startTrackedFlow(flow: FlowLogic<T>): FlowProgressHandle<T> {
            val stateMachine = startFlowChecked(flow)
            return FlowProgressHandleImpl(
                    id = stateMachine.id,
                    returnValue = stateMachine.resultFuture,
                    progress = stateMachine.logic.track()?.updates ?: Observable.empty()
            )
        }

        override fun <T> startFlow(flow: FlowLogic<T>): FlowHandle<T> {
            val stateMachine = startFlowChecked(flow)
            return FlowHandleImpl(id = stateMachine.id, returnValue = stateMachine.resultFuture)
        }

        private fun <T> startFlowChecked(flow: FlowLogic<T>): FlowStateMachine<T> {
            val logicType = flow.javaClass
            require(logicType.isAnnotationPresent(StartableByService::class.java)) { "${logicType.name} was not designed for starting by a CordaService" }
            val currentUser = FlowInitiator.Service(serviceInstance.javaClass.name)
            return flowStarter.startFlow(flow, currentUser).getOrThrow()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AppServiceHubImpl<*>) return false
            return serviceHub == other.serviceHub
                    && flowStarter == other.flowStarter
                    && serviceInstance == other.serviceInstance
        }

        override fun hashCode() = Objects.hash(serviceHub, flowStarter, serviceInstance)
    }

    private fun <T : SerializeAsToken> installCordaService(flowStarter: FlowStarter, serviceClass: Class<T>): T {
        serviceClass.requireAnnotation<CordaService>()
        val service = try {
            val serviceContext = AppServiceHubImpl<T>(services, flowStarter)
            if (isNotaryService(serviceClass)) {
                check(myNotaryIdentity != null) { "Trying to install a notary service but no notary identity specified" }
                val constructor = serviceClass.getDeclaredConstructor(AppServiceHub::class.java, PublicKey::class.java).apply { isAccessible = true }
                serviceContext.serviceInstance = constructor.newInstance(serviceContext, myNotaryIdentity!!.owningKey)
                serviceContext.serviceInstance
            } else {
                try {
                    val extendedServiceConstructor = serviceClass.getDeclaredConstructor(AppServiceHub::class.java).apply { isAccessible = true }
                    serviceContext.serviceInstance = extendedServiceConstructor.newInstance(serviceContext)
                    serviceContext.serviceInstance
                } catch (ex: NoSuchMethodException) {
                    val constructor = serviceClass.getDeclaredConstructor(ServiceHub::class.java).apply { isAccessible = true }
                    log.warn("${serviceClass.name} is using legacy CordaService constructor with ServiceHub parameter. Upgrade to an AppServiceHub parameter to enable updated API features.")
                    constructor.newInstance(services)
                }
            }
        } catch (e: InvocationTargetException) {
            throw ServiceInstantiationException(e.cause)
        }
        cordappServices.putInstance(serviceClass, service)

        if (service is NotaryService) handleCustomNotaryService(service)

        log.info("Installed ${serviceClass.name} Corda service")
        return service
    }

    fun <T : Any> findTokenizableService(clazz: Class<T>): T? {
        return tokenizableServices.firstOrNull { clazz.isAssignableFrom(it.javaClass) }?.let { uncheckedCast(it) }
    }

    inline fun <reified T : Any> findTokenizableService() = findTokenizableService(T::class.java)

    private fun handleCustomNotaryService(service: NotaryService) {
        runOnStop += service::stop
        service.start()
        installCoreFlow(NotaryFlow.Client::class, service::createServiceFlow)
    }

    private fun registerCordappFlows() {
        cordappLoader.cordapps.flatMap { it.initiatedFlows }
                .forEach {
                    try {
                        registerInitiatedFlowInternal(it, track = false)
                    } catch (e: NoSuchMethodException) {
                        log.error("${it.name}, as an initiated flow, must have a constructor with a single parameter " +
                                "of type ${Party::class.java.name}")
                    } catch (e: Exception) {
                        log.error("Unable to register initiated flow ${it.name}", e)
                    }
                }
    }

    /**
     * Use this method to register your initiated flows in your tests. This is automatically done by the node when it
     * starts up for all [FlowLogic] classes it finds which are annotated with [InitiatedBy].
     * @return An [Observable] of the initiated flows started by counter-parties.
     */
    fun <T : FlowLogic<*>> registerInitiatedFlow(initiatedFlowClass: Class<T>): Observable<T> {
        return registerInitiatedFlowInternal(initiatedFlowClass, track = true)
    }

    // TODO remove once not needed
    private fun deprecatedFlowConstructorMessage(flowClass: Class<*>): String {
        return "Installing flow factory for $flowClass accepting a ${Party::class.java.simpleName}, which is deprecated. " +
                "It should accept a ${FlowSession::class.java.simpleName} instead"
    }

    private fun <F : FlowLogic<*>> registerInitiatedFlowInternal(initiatedFlow: Class<F>, track: Boolean): Observable<F> {
        val constructors = initiatedFlow.declaredConstructors.associateBy { it.parameterTypes.toList() }
        val flowSessionCtor = constructors[listOf(FlowSession::class.java)]?.apply { isAccessible = true }
        val ctor: (FlowSession) -> F = if (flowSessionCtor == null) {
            // Try to fallback to a Party constructor
            val partyCtor = constructors[listOf(Party::class.java)]?.apply { isAccessible = true }
            if (partyCtor == null) {
                throw IllegalArgumentException("$initiatedFlow must have a constructor accepting a ${FlowSession::class.java.name}")
            } else {
                log.warn(deprecatedFlowConstructorMessage(initiatedFlow))
            }
            { flowSession: FlowSession -> uncheckedCast(partyCtor.newInstance(flowSession.counterparty)) }
        } else {
            { flowSession: FlowSession -> uncheckedCast(flowSessionCtor.newInstance(flowSession)) }
        }
        val initiatingFlow = initiatedFlow.requireAnnotation<InitiatedBy>().value.java
        val (version, classWithAnnotation) = initiatingFlow.flowVersionAndInitiatingClass
        require(classWithAnnotation == initiatingFlow) {
            "${InitiatedBy::class.java.name} must point to ${classWithAnnotation.name} and not ${initiatingFlow.name}"
        }
        val flowFactory = InitiatedFlowFactory.CorDapp(version, initiatedFlow.appName, ctor)
        val observable = internalRegisterFlowFactory(initiatingFlow, flowFactory, initiatedFlow, track)
        log.info("Registered ${initiatingFlow.name} to initiate ${initiatedFlow.name} (version $version)")
        return observable
    }

    @VisibleForTesting
    fun <F : FlowLogic<*>> internalRegisterFlowFactory(initiatingFlowClass: Class<out FlowLogic<*>>,
                                                       flowFactory: InitiatedFlowFactory<F>,
                                                       initiatedFlowClass: Class<F>,
                                                       track: Boolean): Observable<F> {
        val observable = if (track) {
            smm.changes.filter { it is StateMachineManager.Change.Add }.map { it.logic }.ofType(initiatedFlowClass)
        } else {
            Observable.empty()
        }
        flowFactories[initiatingFlowClass] = flowFactory
        return observable
    }

    /**
     * Installs a flow that's core to the Corda platform. Unlike CorDapp flows which are versioned individually using
     * [InitiatingFlow.version], core flows have the same version as the node's platform version. To cater for backwards
     * compatibility [flowFactory] provides a second parameter which is the platform version of the initiating party.
     * @suppress
     */
    @VisibleForTesting
    fun installCoreFlow(clientFlowClass: KClass<out FlowLogic<*>>, flowFactory: (FlowSession) -> FlowLogic<*>) {
        require(clientFlowClass.java.flowVersionAndInitiatingClass.first == 1) {
            "${InitiatingFlow::class.java.name}.version not applicable for core flows; their version is the node's platform version"
        }
        flowFactories[clientFlowClass.java] = InitiatedFlowFactory.Core(flowFactory)
        log.debug { "Installed core flow ${clientFlowClass.java.name}" }
    }


    private fun installCoreFlows() {
        installCoreFlow(FinalityFlow::class, ::FinalityHandler)
        installCoreFlow(NotaryChangeFlow::class, ::NotaryChangeHandler)
        installCoreFlow(ContractUpgradeFlow.Initiate::class, ::ContractUpgradeHandler)
        installCoreFlow(SwapIdentitiesFlow::class, ::SwapIdentitiesHandler)
    }

    /**
     * Builds node internal, advertised, and plugin services.
     * Returns a list of tokenizable services to be added to the serialisation context.
     */
    private fun makeServices(schemaService: SchemaService, transactionStorage: WritableTransactionStorage, stateLoader: StateLoader): MutableList<Any> {
        checkpointStorage = DBCheckpointStorage()
        val metrics = MetricRegistry()
        attachments = NodeAttachmentService(metrics)
        val cordappProvider = CordappProviderImpl(cordappLoader, attachments)
        _services = ServiceHubInternalImpl(schemaService, transactionStorage, stateLoader, MonitoringService(metrics), cordappProvider)
        legalIdentity = obtainIdentity(notaryConfig = null)
        // TODO  We keep only distributed notary identity as additional legalIdentity if we run it on a node . Multiple identities need more design thinking.
        myNotaryIdentity = getNotaryIdentity(legalIdentity) // In case of single notary service myNotaryIdentity will be node's single identity.
        allIdentities = setOf(legalIdentity, myNotaryIdentity).filterNotNull()
        network = makeMessagingService(legalIdentity)
        val addresses = myAddresses() // TODO There is no support for multiple IP addresses yet.
        info = NodeInfo(addresses, allIdentities, versionInfo.platformVersion, platformClock.instant().toEpochMilli())
        val tokenizableServices = mutableListOf(attachments, network, services.vaultService,
                services.keyManagementService, services.identityService, platformClock,
                services.auditService, services.monitoringService, services.networkMapCache, services.schemaService,
                services.transactionVerifierService, services.validatedTransactions, services.contractUpgradeService,
                services, cordappProvider, this)
        makeNetworkServices(tokenizableServices)
        return tokenizableServices
    }

    protected open fun makeTransactionStorage(): WritableTransactionStorage = DBTransactionStorage()

    private fun makeVaultObservers(schedulerService: SchedulerService) {
        VaultSoftLockManager.install(services.vaultService, smm)
        ScheduledActivityObserver.install(services.vaultService, schedulerService)
        HibernateObserver.install(services.vaultService.rawUpdates, database.hibernateConfig)
    }

    /**
     * Obtain the node's notary identity if it's configured to be one. If part of a distributed notary then this will be
     * the distributed identity shared across all the nodes of the cluster.
     */
    protected fun getNotaryIdentity(legalIdentity: PartyAndCertificate): PartyAndCertificate? {
        return configuration.notary?.let {
            if (it.bftSMaRt == null && it.raft == null) legalIdentity else obtainIdentity(it)
        }
    }

    @VisibleForTesting
    protected open fun acceptableLiveFiberCountOnStop(): Int = 0

    private fun validateKeystore() {
        val containCorrectKeys = try {
            // This will throw IOException if key file not found or KeyStoreException if keystore password is incorrect.
            val sslKeystore = loadKeyStore(configuration.sslKeystore, configuration.keyStorePassword)
            val identitiesKeystore = loadKeyStore(configuration.nodeKeystore, configuration.keyStorePassword)
            sslKeystore.containsAlias(X509Utilities.CORDA_CLIENT_TLS) && identitiesKeystore.containsAlias(X509Utilities.CORDA_CLIENT_CA)
        } catch (e: KeyStoreException) {
            log.warn("Certificate key store found but key store password does not match configuration.")
            false
        } catch (e: IOException) {
            false
        }
        require(containCorrectKeys) {
            "Identity certificate not found. " +
                    "Please either copy your existing identity key and certificate from another node, " +
                    "or if you don't have one yet, fill out the config file and run corda.jar --initial-registration. " +
                    "Read more at: https://docs.corda.net/permissioning.html"
        }
    }

    // Specific class so that MockNode can catch it.
    class DatabaseConfigurationException(msg: String) : CordaException(msg)

    protected open fun <T> initialiseDatabasePersistence(schemaService: SchemaService, insideTransaction: () -> T): T {
        val props = configuration.dataSourceProperties
        if (props.isNotEmpty()) {
            this.database = configureDatabase(props, configuration.database, { _services.identityService }, schemaService)
            // Now log the vendor string as this will also cause a connection to be tested eagerly.
            database.transaction {
                log.info("Connected to ${database.dataSource.connection.metaData.databaseProductName} database.")
            }
            runOnStop += database::close
            return database.transaction {
                insideTransaction()
            }
        } else {
            throw DatabaseConfigurationException("There must be a database configured.")
        }
    }

    private fun makeNetworkServices(tokenizableServices: MutableList<Any>) {
        configuration.notary?.let {
            val notaryService = makeCoreNotaryService(it)
            tokenizableServices.add(notaryService)
            runOnStop += notaryService::stop
            installCoreFlow(NotaryFlow.Client::class, notaryService::createServiceFlow)
            log.info("Running core notary: ${notaryService.javaClass.name}")
            notaryService.start()
        }
    }

    private fun sendNetworkMapRegistration(networkMapAddress: SingleMessageRecipient): CordaFuture<RegistrationResponse> {
        // Register this node against the network
        val instant = platformClock.instant()
        val expires = instant + NetworkMapService.DEFAULT_EXPIRATION_PERIOD
        val reg = NodeRegistration(info, info.serial, ADD, expires)
        val request = RegistrationRequest(reg.toWire(services.keyManagementService, info.legalIdentitiesAndCerts[0].owningKey), network.myAddress)
        return network.sendRequest(NetworkMapService.REGISTER_TOPIC, request, networkMapAddress)
    }

    /** Return list of node's addresses. It's overridden in MockNetwork as we don't have real addresses for MockNodes. */
    protected abstract fun myAddresses(): List<NetworkHostAndPort>

    open protected fun checkNetworkMapIsInitialized() {
        if (!services.networkMapCache.loadDBSuccess) {
            // TODO: There should be a consistent approach to configuration error exceptions.
            throw NetworkMapCacheEmptyException()
        }
    }

    protected open fun makeKeyManagementService(identityService: IdentityService): KeyManagementService {
        return PersistentKeyManagementService(identityService, partyKeys)
    }

    private fun readNetworkParameters() {
        val file = configuration.baseDirectory / "network-parameters"
        networkParameters = file.readAll().deserialize<SignedData<NetworkParameters>>().verified()
        check(configuration.minimumPlatformVersion >= networkParameters.minimumPlatformVersion)
    }

    abstract protected fun makeNetworkMapService(network: MessagingService, networkMapCache: NetworkMapCacheInternal): NetworkMapService

    private fun makeCoreNotaryService(notaryConfig: NotaryConfig): NotaryService {
        val notaryKey = myNotaryIdentity?.owningKey ?: throw IllegalArgumentException("No notary identity initialized when creating a notary service")
        return if (notaryConfig.validating) {
            if (notaryConfig.raft != null) {
                RaftValidatingNotaryService(services, notaryKey, notaryConfig.raft)
            } else if (notaryConfig.bftSMaRt != null) {
                throw IllegalArgumentException("Validating BFTSMaRt notary not supported")
            } else {
                ValidatingNotaryService(services, notaryKey)
            }
        } else {
            if (notaryConfig.raft != null) {
                RaftNonValidatingNotaryService(services, notaryKey, notaryConfig.raft)
            } else if (notaryConfig.bftSMaRt != null) {
                val cluster = makeBFTCluster(notaryKey, notaryConfig.bftSMaRt)
                BFTNonValidatingNotaryService(services, notaryKey, notaryConfig.bftSMaRt, cluster)
            } else {
                SimpleNotaryService(services, notaryKey)
            }
        }
    }

    protected open fun makeBFTCluster(notaryKey: PublicKey, bftSMaRtConfig: BFTSMaRtConfiguration): BFTSMaRt.Cluster {
        return object : BFTSMaRt.Cluster {
            override fun waitUntilAllReplicasHaveInitialized() {
                log.warn("A BFT replica may still be initializing, in which case the upcoming consensus change may cause it to spin.")
            }
        }
    }

    protected open fun makeIdentityService(trustRoot: X509Certificate,
                                           clientCa: CertificateAndKeyPair?,
                                           legalIdentity: PartyAndCertificate): IdentityService {
        val caCertificates: Array<X509Certificate> = listOf(legalIdentity.certificate, clientCa?.certificate?.cert)
                .filterNotNull()
                .toTypedArray()
        return PersistentIdentityService(allIdentities, trustRoot = trustRoot, caCertificates = *caCertificates)
    }

    protected abstract fun makeTransactionVerifierService(): TransactionVerifierService

    open fun stop() {
        // TODO: We need a good way of handling "nice to have" shutdown events, especially those that deal with the
        // network, including unsubscribing from updates from remote services. Possibly some sort of parameter to stop()
        // to indicate "Please shut down gracefully" vs "Shut down now".
        // Meanwhile, we let the remote service send us updates until the acknowledgment buffer overflows and it
        // unsubscribes us forcibly, rather than blocking the shutdown process.

        // Run shutdown hooks in opposite order to starting
        for (toRun in runOnStop.reversed()) {
            toRun()
        }
        runOnStop.clear()
        _started = null
    }

    protected abstract fun makeMessagingService(legalIdentity: PartyAndCertificate): MessagingService

    protected abstract fun startMessagingService(rpcOps: RPCOps)

    private fun obtainIdentity(notaryConfig: NotaryConfig?): PartyAndCertificate {
        val keyStore = KeyStoreWrapper(configuration.nodeKeystore, configuration.keyStorePassword)

        val (id, singleName) = if (notaryConfig == null || (notaryConfig.bftSMaRt == null && notaryConfig.raft == null)) {
            // Node's main identity or if it's single notary
            Pair("identity", myLegalName)
        } else {
            val notaryId = notaryConfig.run {
                NotaryService.constructId(validating, raft != null, bftSMaRt != null, custom)
            }
            // The node is part of a distributed notary whose identity must already be generated beforehand.
            Pair(notaryId, null)
        }
        // TODO: Integrate with Key management service?
        val privateKeyAlias = "$id-private-key"

        if (!keyStore.containsAlias(privateKeyAlias)) {
            singleName ?: throw IllegalArgumentException(
                    "Unable to find in the key store the identity of the distributed notary ($id) the node is part of")
            // TODO: Remove use of [ServiceIdentityGenerator.generateToDisk].
            log.info("$privateKeyAlias not found in key store ${configuration.nodeKeystore}, generating fresh key!")
            keyStore.signAndSaveNewKeyPair(singleName, privateKeyAlias, generateKeyPair())
        }

        val (x509Cert, keys) = keyStore.certificateAndKeyPair(privateKeyAlias)

        // TODO: Use configuration to indicate composite key should be used instead of public key for the identity.
        val compositeKeyAlias = "$id-composite-key"
        val certificates = if (keyStore.containsAlias(compositeKeyAlias)) {
            // Use composite key instead if it exists
            val certificate = keyStore.getCertificate(compositeKeyAlias)
            // We have to create the certificate chain for the composite key manually, this is because we don't have a keystore
            // provider that understand compositeKey-privateKey combo. The cert chain is created using the composite key certificate +
            // the tail of the private key certificates, as they are both signed by the same certificate chain.
            listOf(certificate) + keyStore.getCertificateChain(privateKeyAlias).drop(1)
        } else {
            keyStore.getCertificateChain(privateKeyAlias).let {
                check(it[0].toX509CertHolder() == x509Cert) { "Certificates from key store do not line up!" }
                it.asList()
            }
        }

        val nodeCert = certificates[0] as? X509Certificate ?: throw ConfigurationException("Node certificate must be an X.509 certificate")
        val subject = CordaX500Name.build(nodeCert.subjectX500Principal)
        // TODO Include the name of the distributed notary, which the node is part of, in the notary config so that we
        // can cross-check the identity we get from the key store
        if (singleName != null && subject != singleName) {
            throw ConfigurationException("The name '$singleName' for $id doesn't match what's in the key store: $subject")
        }

        partyKeys += keys
        return PartyAndCertificate(CertificateFactory.getInstance("X509").generateCertPath(certificates))
    }

    protected open fun generateKeyPair() = cryptoGenerateKeyPair()
    protected open fun makeVaultService(keyManagementService: KeyManagementService, stateLoader: StateLoader): VaultServiceInternal {
        return NodeVaultService(platformClock, keyManagementService, stateLoader, database.hibernateConfig)
    }

    private inner class ServiceHubInternalImpl(
            override val schemaService: SchemaService,
            override val validatedTransactions: WritableTransactionStorage,
            private val stateLoader: StateLoader,
            override val monitoringService: MonitoringService,
            override val cordappProvider: CordappProviderInternal
    ) : SingletonSerializeAsToken(), ServiceHubInternal, StateLoader by stateLoader {
        override val rpcFlows = ArrayList<Class<out FlowLogic<*>>>()
        override val stateMachineRecordedTransactionMapping = DBTransactionMappingStorage()
        override val auditService = DummyAuditService()
        override val transactionVerifierService by lazy { makeTransactionVerifierService() }
        override val networkMapCache by lazy {
            NetworkMapCacheImpl(PersistentNetworkMapCache(this@AbstractNode.database, this@AbstractNode.configuration, networkParameters), identityService)
        }
        override val vaultService by lazy { makeVaultService(keyManagementService, stateLoader) }
        override val contractUpgradeService by lazy { ContractUpgradeServiceImpl() }

        // Place the long term identity key in the KMS. Eventually, this is likely going to be separated again because
        // the KMS is meant for derived temporary keys used in transactions, and we're not supposed to sign things with
        // the identity key. But the infrastructure to make that easy isn't here yet.
        override val keyManagementService by lazy { makeKeyManagementService(identityService) }
        override val identityService by lazy {
            val trustStore = KeyStoreWrapper(configuration.trustStoreFile, configuration.trustStorePassword)
            val caKeyStore = KeyStoreWrapper(configuration.nodeKeystore, configuration.keyStorePassword)
            makeIdentityService(
                    trustStore.getX509Certificate(X509Utilities.CORDA_ROOT_CA),
                    caKeyStore.certificateAndKeyPair(X509Utilities.CORDA_CLIENT_CA),
                    legalIdentity)
        }
        override val attachments: AttachmentStorage get() = this@AbstractNode.attachments
        override val networkService: MessagingService get() = network
        override val clock: Clock get() = platformClock
        override val myInfo: NodeInfo get() = info
        override val database: CordaPersistence get() = this@AbstractNode.database
        override val configuration: NodeConfiguration get() = this@AbstractNode.configuration
        override fun <T : SerializeAsToken> cordaService(type: Class<T>): T {
            require(type.isAnnotationPresent(CordaService::class.java)) { "${type.name} is not a Corda service" }
            return cordappServices.getInstance(type) ?: throw IllegalArgumentException("Corda service ${type.name} does not exist")
        }

        override fun getFlowFactory(initiatingFlowClass: Class<out FlowLogic<*>>): InitiatedFlowFactory<*>? {
            return flowFactories[initiatingFlowClass]
        }

        override fun recordTransactions(statesToRecord: StatesToRecord, txs: Iterable<SignedTransaction>) {
            database.transaction {
                super.recordTransactions(statesToRecord, txs)
            }
        }

        override fun jdbcSession(): Connection = database.createSession()
    }
}

internal class FlowStarterImpl(private val serverThread: AffinityExecutor, private val smm: StateMachineManager) : FlowStarter {
    override fun <T> startFlow(logic: FlowLogic<T>, flowInitiator: FlowInitiator, ourIdentity: Party?): CordaFuture<FlowStateMachine<T>> {
        return serverThread.fetchFrom { smm.startFlow(logic, flowInitiator, ourIdentity) }
    }
}

/**
 * Thrown when a node is about to start and its network map cache doesn't contain any node.
 */
internal class NetworkMapCacheEmptyException: Exception()