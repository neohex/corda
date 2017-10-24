package net.corda.membership

import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.NetworkMapCache

object MembershipListProvider {

    fun obtainMembershipList(listName: CordaX500Name, networkMapCache: NetworkMapCache): MembershipList {
        return CsvMembershipList("Z:\\corda\\tools\\explorer\\conf\\${listName.commonName}.csv", networkMapCache)
    }
}