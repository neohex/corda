package net.corda.membership.internal

import com.opencsv.CSVReaderBuilder
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.utilities.loggerFor
import net.corda.membership.MembershipList
import java.io.FileReader

/**
 * Implementation of a MembershipList that reads the content from CSV file.
 */
class CsvMembershipList(private val fileName: String, private val networkMapCache: NetworkMapCache) : MembershipList {

    companion object {
        private val logger = loggerFor<CsvMembershipList>()
    }

    override fun content(): Set<AbstractParty> {
        val reader = CSVReaderBuilder(FileReader(fileName)).withSkipLines(1).build()
        reader.use {
            val linesRead = reader.readAll()
            val commentsRemoved = linesRead.filterNot { line -> line.isEmpty() || line[0].startsWith("#") }
            val withPossibleNullParties = commentsRemoved.map { line -> createParty(CordaX500Name.parse(line[0])) }
            val nullPartiesRemoved = withPossibleNullParties.flatMap { party ->
                if (party == null) emptySet()
                else setOf(party)
            }
            return nullPartiesRemoved.toSet()
        }
    }

    private fun createParty(name: CordaX500Name): AbstractParty? {
        val nodeInfo = networkMapCache.getNodeByLegalName(name)
        if(nodeInfo == null) {
            logger.warn("Cannot find NodeInfo for: $name")
            return null
        }
        return nodeInfo.legalIdentities.first()
    }
}