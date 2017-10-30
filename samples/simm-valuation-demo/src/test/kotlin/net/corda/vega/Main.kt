package net.corda.vega

import net.corda.core.utilities.getOrThrow
import net.corda.testing.DUMMY_BANK_A
import net.corda.testing.DUMMY_BANK_B
import net.corda.testing.DUMMY_BANK_C
import net.corda.testing.driver.driver

/**
 * Sample main used for running within an IDE. Starts 4 nodes (A, B, C and Notary/Controller) as an alternative to running via gradle
 * This does not start any tests but has the nodes running in preparation for a live web demo or to receive commands
 * via the web api.
 */
fun main(args: Array<String>) {
    driver(isDebug = true) {
        val (nodeA, nodeB, nodeC) = listOf(
                startNode(providedName = DUMMY_BANK_A.name),
                startNode(providedName = DUMMY_BANK_B.name),
                startNode(providedName = DUMMY_BANK_C.name))
                .map { it.getOrThrow() }

        startWebserver(nodeA)
        startWebserver(nodeB)
        startWebserver(nodeC)

        waitForAllNodesToFinish()
    }
}
