package net.corda.node.services

import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.flows.NotaryError
import net.corda.core.flows.NotaryException
import net.corda.core.flows.NotaryFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.map
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.node.services.transactions.RaftValidatingNotaryService
import net.corda.testing.DUMMY_BANK_A
import net.corda.testing.chooseIdentity
import net.corda.testing.contracts.DummyContract
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import net.corda.testing.dummyCommand
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RaftNotaryServiceTests {
    private val notaryName = CordaX500Name(RaftValidatingNotaryService.id, "RAFT Notary Service", "London", "GB")

    @Test
    fun `detect double spend`() {
        driver(startNodesInProcess = true, extraCordappPackagesToScan = listOf("net.corda.testing.contracts")) {
            val (notaryParty) = startNotaryCluster(notaryName, 3).getOrThrow()
            val bankA = startNode(providedName = DUMMY_BANK_A.name).map { (it as NodeHandle.InProcess).node }.getOrThrow()

            val inputState = issueState(bankA, notaryParty)

            val firstTxBuilder = TransactionBuilder(notaryParty)
                    .addInputState(inputState)
                    .addCommand(dummyCommand(bankA.services.myInfo.chooseIdentity().owningKey))
            val firstSpendTx = bankA.services.signInitialTransaction(firstTxBuilder)

            val firstSpend = bankA.services.startFlow(NotaryFlow.Client(firstSpendTx))
            firstSpend.resultFuture.getOrThrow()

            val secondSpendBuilder = TransactionBuilder(notaryParty).withItems(inputState).run {
                val dummyState = DummyContract.SingleOwnerState(0, bankA.info.chooseIdentity())
                addOutputState(dummyState, DummyContract.PROGRAM_ID)
                addCommand(dummyCommand(bankA.services.myInfo.chooseIdentity().owningKey))
                this
            }
            val secondSpendTx = bankA.services.signInitialTransaction(secondSpendBuilder)
            val secondSpend = bankA.services.startFlow(NotaryFlow.Client(secondSpendTx))

            val ex = assertFailsWith(NotaryException::class) { secondSpend.resultFuture.getOrThrow() }
            val error = ex.error as NotaryError.Conflict
            assertEquals(error.txId, secondSpendTx.id)
        }
    }

    private fun issueState(node: StartedNode<*>, notary: Party): StateAndRef<*> {
        return node.database.transaction {
            val builder = DummyContract.generateInitial(Random().nextInt(), notary, node.info.chooseIdentity().ref(0))
            val stx = node.services.signInitialTransaction(builder)
            node.services.recordTransactions(stx)
            StateAndRef(builder.outputStates().first(), StateRef(stx.id, 0))
        }
    }
}
