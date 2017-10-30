package net.corda.netmap.simulation

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.utils.CityDatabase
import net.corda.irs.api.NodeInterestRates
import net.corda.node.internal.StartedNode
import net.corda.node.services.statemachine.StateMachineManager
import net.corda.testing.DUMMY_REGULATOR
import net.corda.testing.node.*
import net.corda.testing.node.MockNetwork.MockNode
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import rx.Observable
import rx.subjects.PublishSubject
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.allOf
import java.util.concurrent.Future

internal val MockNode.place get() = configuration.myLegalName.locality.let { CityDatabase[it] }!!

/**
 * Base class for network simulations that are based on the unit test / mock environment.
 *
 * Sets up some nodes that can run flows between each other, and exposes their progress trackers. Provides banks
 * in a few cities around the world.
 */
abstract class Simulation(val networkSendManuallyPumped: Boolean,
                          runAsync: Boolean,
                          latencyInjector: InMemoryMessagingNetwork.LatencyCalculator?) {
    companion object {
        private val defaultParams // The get() is necessary so that entropyRoot isn't shared.
            get() = MockNodeParameters(configOverrides = {
                doReturn(makeTestDataSourceProperties(it.myLegalName.organisation)).whenever(it).dataSourceProperties
            })
    }

    init {
        if (!runAsync && latencyInjector != null)
            throw IllegalArgumentException("The latency injector is only useful when using manual pumping.")
    }

    val bankLocations = listOf(Pair("London", "GB"), Pair("Frankfurt", "DE"), Pair("Rome", "IT"))

    object RatesOracleFactory : MockNetwork.Factory<MockNode> {
        // TODO: Make a more realistic legal name
        val RATES_SERVICE_NAME = CordaX500Name(organisation = "Rates Service Provider", locality = "Madrid", country = "ES")

        override fun create(args: MockNodeArgs): MockNode {
            return object : MockNode(args) {
                override fun start() = super.start().apply {
                    registerInitiatedFlow(NodeInterestRates.FixQueryHandler::class.java)
                    registerInitiatedFlow(NodeInterestRates.FixSignHandler::class.java)
                    javaClass.classLoader.getResourceAsStream("net/corda/irs/simulation/example.rates.txt").use {
                        database.transaction {
                            findTokenizableService(NodeInterestRates.Oracle::class.java)!!.uploadFixes(it.reader().readText())
                        }
                    }
                }
            }
        }
    }

    val mockNet = MockNetwork(
            networkSendManuallyPumped = networkSendManuallyPumped,
            threadPerNode = runAsync,
            cordappPackages = listOf("net.corda.irs.contract", "net.corda.finance.contract", "net.corda.irs"))
    val notary = mockNet.notaryNodes[0]
    // TODO: Regulatory nodes don't actually exist properly, this is a last minute demo request.
    //       So we just fire a message at a node that doesn't know how to handle it, and it'll ignore it.
    //       But that's fine for visualisation purposes.
    val regulators = listOf(mockNet.createUnstartedNode(defaultParams.copy(legalName = DUMMY_REGULATOR.name)))
    val ratesOracle = mockNet.createUnstartedNode(defaultParams.copy(legalName = RatesOracleFactory.RATES_SERVICE_NAME), RatesOracleFactory)
    // All nodes must be in one of these two lists for the purposes of the visualiser tool.
    val serviceProviders: List<MockNode> = listOf(notary.internals, ratesOracle)
    val banks: List<MockNode> = bankLocations.mapIndexed { i, (city, country) ->
        val legalName = CordaX500Name(organisation = "Bank ${'A' + i}", locality = city, country = country)
        // Use deterministic seeds so the simulation is stable. Needed so that party owning keys are stable.
        mockNet.createUnstartedNode(defaultParams.copy(legalName = legalName, entropyRoot = BigInteger.valueOf(i.toLong())))
    }
    val clocks = (serviceProviders + regulators + banks).map { it.platformClock as TestClock }

    // These are used from the network visualiser tool.
    private val _allFlowSteps = PublishSubject.create<Pair<MockNode, ProgressTracker.Change>>()
    private val _doneSteps = PublishSubject.create<Collection<MockNode>>()
    @Suppress("unused")
    val allFlowSteps: Observable<Pair<MockNode, ProgressTracker.Change>> = _allFlowSteps
    @Suppress("unused")
    val doneSteps: Observable<Collection<MockNode>> = _doneSteps

    private var pumpCursor = 0

    /**
     * The current simulated date. By default this never changes. If you want it to change, you should do so from
     * within your overridden [iterate] call. Changes in the current day surface in the [dateChanges] observable.
     */
    var currentDateAndTime: LocalDateTime = LocalDate.now().atStartOfDay()
        protected set(value) {
            field = value
            _dateChanges.onNext(value)
        }

    private val _dateChanges = PublishSubject.create<LocalDateTime>()
    val dateChanges: Observable<LocalDateTime> get() = _dateChanges

    init {
        // Advance node clocks when current time is changed
        dateChanges.subscribe {
            clocks.setTo(currentDateAndTime.toInstant(ZoneOffset.UTC))
        }
    }

    /**
     * A place for simulations to stash human meaningful text about what the node is "thinking", which might appear
     * in the UI somewhere.
     */
    val extraNodeLabels: MutableMap<MockNode, String> = Collections.synchronizedMap(HashMap())

    /**
     * Iterates the simulation by one step.
     *
     * The default implementation circles around the nodes, pumping until one of them handles a message. The next call
     * will carry on from where this one stopped. In an environment where you want to take actions between anything
     * interesting happening, or control the precise speed at which things operate (beyond the latency injector), this
     * is a useful way to do things.
     *
     * @return the message that was processed, or null if no node accepted a message in this round.
     */
    open fun iterate(): InMemoryMessagingNetwork.MessageTransfer? {
        if (networkSendManuallyPumped) {
            mockNet.messagingNetwork.pumpSend(false)
        }

        // Keep going until one of the nodes has something to do, or we have checked every node.
        val endpoints = mockNet.messagingNetwork.endpoints
        var countDown = endpoints.size
        while (countDown > 0) {
            val handledMessage = endpoints[pumpCursor].pumpReceive(false)
            if (handledMessage != null)
                return handledMessage
            // If this node had nothing to do, advance the cursor with wraparound and try again.
            pumpCursor = (pumpCursor + 1) % endpoints.size
            countDown--
        }
        return null
    }

    protected fun showProgressFor(nodes: List<StartedNode<MockNode>>) {
        nodes.forEach { node ->
            node.smm.changes.filter { it is StateMachineManager.Change.Add }.subscribe {
                linkFlowProgress(node.internals, it.logic)
            }
        }
    }

    private fun linkFlowProgress(node: MockNode, flow: FlowLogic<*>) {
        val pt = flow.progressTracker ?: return
        pt.changes.subscribe { change: ProgressTracker.Change ->
            // Runs on node thread.
            _allFlowSteps.onNext(Pair(node, change))
        }
    }


    protected fun showConsensusFor(nodes: List<MockNode>) {
        val node = nodes.first()
        node.started!!.smm.changes.filter { it is StateMachineManager.Change.Add }.first().subscribe {
            linkConsensus(nodes, it.logic)
        }
    }

    private fun linkConsensus(nodes: Collection<MockNode>, flow: FlowLogic<*>) {
        flow.progressTracker?.changes?.subscribe { _: ProgressTracker.Change ->
            // Runs on node thread.
            if (flow.progressTracker!!.currentStep == ProgressTracker.DONE) {
                _doneSteps.onNext(nodes)
            }
        }
    }

    val networkInitialisationFinished = allOf(*mockNet.nodes.map { it.nodeReadyFuture.toCompletableFuture() }.toTypedArray())

    fun start(): Future<Unit> {
        mockNet.startNodes()
        // Wait for all the nodes to have finished registering with the network map service.
        return networkInitialisationFinished.thenCompose { startMainSimulation() }
    }

    /**
     * Sub-classes should override this to trigger whatever they want to simulate. This method will be invoked once the
     * network bringup has been simulated.
     */
    protected abstract fun startMainSimulation(): CompletableFuture<Unit>

    fun stop() {
        mockNet.stopNodes()
    }
}
