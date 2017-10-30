package net.corda.core.flows

import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.node.services.api.StartedNodeServices
import net.corda.testing.*
import net.corda.testing.node.MockNetwork
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FinalityFlowTests {
    private lateinit var mockNet: MockNetwork
    private lateinit var aliceServices: StartedNodeServices
    private lateinit var bobServices: StartedNodeServices
    private lateinit var alice: Party
    private lateinit var bob: Party
    private lateinit var notary: Party

    @Before
    fun setup() {
        mockNet = MockNetwork(cordappPackages = listOf("net.corda.finance.contracts.asset"))
        val aliceNode = mockNet.createPartyNode(ALICE_NAME)
        val bobNode = mockNet.createPartyNode(BOB_NAME)
        mockNet.runNetwork()
        aliceServices = aliceNode.services
        bobServices = bobNode.services
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()
        notary = aliceServices.getDefaultNotary()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
    }

    @Test
    fun `finalise a simple transaction`() {
        val amount = 1000.POUNDS.issuedBy(alice.ref(0))
        val builder = TransactionBuilder(notary)
        Cash().generateIssue(builder, amount, bob, notary)
        val stx = aliceServices.signInitialTransaction(builder)
        val flow = aliceServices.startFlow(FinalityFlow(stx))
        mockNet.runNetwork()
        val notarisedTx = flow.resultFuture.getOrThrow()
        notarisedTx.verifyRequiredSignatures()
        val transactionSeenByB = bobServices.database.transaction {
            bobServices.validatedTransactions.getTransaction(notarisedTx.id)
        }
        assertEquals(notarisedTx, transactionSeenByB)
    }

    @Test
    fun `reject a transaction with unknown parties`() {
        val amount = 1000.POUNDS.issuedBy(alice.ref(0))
        val fakeIdentity = CHARLIE // Charlie isn't part of this network, so node A won't recognise them
        val builder = TransactionBuilder(notary)
        Cash().generateIssue(builder, amount, fakeIdentity, notary)
        val stx = aliceServices.signInitialTransaction(builder)
        val flow = aliceServices.startFlow(FinalityFlow(stx))
        mockNet.runNetwork()
        assertFailsWith<IllegalArgumentException> {
            flow.resultFuture.getOrThrow()
        }
    }
}