package net.corda.membership.internal

import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.NetworkMapCache
import net.corda.membership.MembershipList

object MembershipListProvider {

    fun obtainMembershipList(listName: CordaX500Name, networkMapCache: NetworkMapCache): MembershipList {
        return CsvMembershipList("Z:\\corda\\tools\\explorer\\conf\\${listName.commonName}.csv", networkMapCache)
    }
}