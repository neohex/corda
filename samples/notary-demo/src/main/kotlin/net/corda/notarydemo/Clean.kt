package net.corda.notarydemo

import net.corda.testing.driver.internal.clean

fun main(args: Array<String>) {
    listOf(SingleNotaryCordform(), RaftNotaryCordform(), BFTNotaryCordform()).forEach {
        it.clean()
    }
}
