package net.corda.notarydemo

import net.corda.cordform.CordformContext
import net.corda.cordform.CordformDefinition
import net.corda.core.internal.div
import net.corda.node.services.config.NotaryConfig
import net.corda.testing.*
import net.corda.testing.driver.internal.runNodes
import net.corda.testing.internal.name
import net.corda.testing.internal.node
import net.corda.testing.internal.notary
import net.corda.testing.internal.rpcUsers

fun main(args: Array<String>) = CustomNotaryCordform().runNodes()

class CustomNotaryCordform : CordformDefinition("build" / "notary-demo-nodes") {
    init {
        node {
            name(ALICE.name)
            p2pPort(10002)
            rpcPort(10003)
            rpcUsers(notaryDemoUser)
        }
        node {
            name(BOB.name)
            p2pPort(10005)
            rpcPort(10006)
        }
        node {
            name(DUMMY_NOTARY.name)
            p2pPort(10009)
            rpcPort(10010)
            notary(NotaryConfig(validating = true, custom = true))
        }
    }

    override fun setup(context: CordformContext) {}
}