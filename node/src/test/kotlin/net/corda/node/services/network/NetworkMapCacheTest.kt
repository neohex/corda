package net.corda.node.services.network

import net.corda.core.node.services.NetworkMapCache
import net.corda.testing.ALICE_NAME
import net.corda.testing.BOB_NAME
import net.corda.testing.chooseIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.singleIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import java.math.BigInteger
import kotlin.test.assertEquals

class NetworkMapCacheTest {
    val mockNet: MockNetwork = MockNetwork()

    @After
    fun teardown() {
        mockNet.stopNodes()
    }

    @Test
    fun `key collision`() {
        val entropy = BigInteger.valueOf(24012017L)
        val aliceNode = mockNet.createNode(MockNodeParameters(legalName = ALICE_NAME, entropyRoot = entropy))
        val alice = aliceNode.info.singleIdentity()
        mockNet.runNetwork()

        // Node A currently knows only about itself, so this returns node A
        assertEquals(aliceNode.services.networkMapCache.getNodesByLegalIdentityKey(aliceNode.info.chooseIdentity().owningKey).singleOrNull(), aliceNode.info)
        val bobNode = mockNet.createNode(MockNodeParameters(legalName = BOB_NAME, entropyRoot = entropy))
        val bob = bobNode.info.singleIdentity()
        assertEquals(alice, bob)

        aliceNode.services.networkMapCache.addNode(bobNode.info)
        // The details of node B write over those for node A
        assertEquals(aliceNode.services.networkMapCache.getNodesByLegalIdentityKey(alice.owningKey).singleOrNull(), bobNode.info)
    }

    @Test
    fun `getNodeByLegalIdentity`() {
        val notaryNode = mockNet.createNotaryNode()
        val aliceNode = mockNet.createPartyNode(ALICE_NAME)
        val alice = aliceNode.info.singleIdentity()
        val notaryCache: NetworkMapCache = notaryNode.services.networkMapCache
        val expected = aliceNode.info

        mockNet.runNetwork()
        val actual = notaryNode.database.transaction { notaryCache.getNodeByLegalIdentity(alice) }
        assertEquals(expected, actual)

        // TODO: Should have a test case with anonymous lookup
    }

    @Test
    fun `getPeerByLegalName`() {
        val notaryNode = mockNet.createNotaryNode()
        val aliceNode = mockNet.createPartyNode(ALICE_NAME)
        val notaryCache: NetworkMapCache = notaryNode.services.networkMapCache
        val expected = aliceNode.info.legalIdentities.single()

        mockNet.runNetwork()
        val actual = notaryNode.database.transaction { notaryCache.getPeerByLegalName(ALICE_NAME) }
        assertEquals(expected, actual)
    }

    @Test
    fun `remove node from cache`() {
        val notaryNode = mockNet.createNotaryNode()
        val aliceNode = mockNet.createPartyNode(ALICE_NAME)
        val notaryLegalIdentity = notaryNode.info.chooseIdentity()
        val alice = aliceNode.info.singleIdentity()
        val notaryCache = notaryNode.services.networkMapCache
        mockNet.runNetwork()
        notaryNode.database.transaction {
            assertThat(notaryCache.getNodeByLegalIdentity(alice) != null)
            notaryCache.removeNode(aliceNode.info)
            assertThat(notaryCache.getNodeByLegalIdentity(alice) == null)
            assertThat(notaryCache.getNodeByLegalIdentity(notaryLegalIdentity) != null)
            assertThat(notaryCache.getNodeByLegalName(ALICE_NAME) == null)
        }
    }
}
