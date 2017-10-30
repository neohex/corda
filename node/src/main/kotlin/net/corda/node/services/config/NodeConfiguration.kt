package net.corda.node.services.config

import com.typesafe.config.Config
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import net.corda.node.services.messaging.CertificateChainCheckPolicy
import net.corda.nodeapi.User
import net.corda.nodeapi.config.NodeSSLConfiguration
import net.corda.nodeapi.config.parseAs
import java.net.URL
import java.nio.file.Path
import java.util.*

data class DevModeOptions(val disableCheckpointChecker: Boolean = false)

interface NodeConfiguration : NodeSSLConfiguration {
    // myLegalName should be only used in the initial network registration, we should use the name from the certificate instead of this.
    // TODO: Remove this so we don't accidentally use this identity in the code?
    val myLegalName: CordaX500Name
    val minimumPlatformVersion: Int
    val emailAddress: String
    val exportJMXto: String
    val dataSourceProperties: Properties
    val database: Properties?
    val rpcUsers: List<User>
    val devMode: Boolean
    val devModeOptions: DevModeOptions?
    val certificateSigningService: URL
    val certificateChainCheckPolicies: List<CertChainPolicyConfig>
    val verifierType: VerifierType
    val messageRedeliveryDelaySeconds: Int
    val notary: NotaryConfig?
    val activeMQServer: ActiveMqServerConfiguration
    val additionalNodeInfoPollingFrequencyMsec: Long
    val useHTTPS: Boolean
    val p2pAddress: NetworkHostAndPort
    val rpcAddress: NetworkHostAndPort?
    val messagingServerAddress: NetworkHostAndPort?
    // TODO Move into DevModeOptions
    val useTestClock: Boolean get() = false
    val detectPublicIp: Boolean get() = true
}

data class NotaryConfig(val validating: Boolean,
                        val raft: RaftConfig? = null,
                        val bftSMaRt: BFTSMaRtConfiguration? = null,
                        val custom: Boolean = false
) {
    init {
        require(raft == null || bftSMaRt == null || !custom) {
            "raft, bftSMaRt, and custom configs cannot be specified together"
        }
    }
}

data class RaftConfig(val nodeAddress: NetworkHostAndPort, val clusterAddresses: List<NetworkHostAndPort>)

/** @param exposeRaces for testing only, so its default is not in reference.conf but here. */
data class BFTSMaRtConfiguration(
        val replicaId: Int,
        val clusterAddresses: List<NetworkHostAndPort>,
        val debug: Boolean = false,
        val exposeRaces: Boolean = false
) {
    init {
        require(replicaId >= 0) { "replicaId cannot be negative" }
    }
}

data class BridgeConfiguration(val retryIntervalMs: Long,
                               val maxRetryIntervalMin: Long,
                               val retryIntervalMultiplier: Double)

data class ActiveMqServerConfiguration(val bridge: BridgeConfiguration)

fun Config.parseAsNodeConfiguration(): NodeConfiguration = this.parseAs<NodeConfigurationImpl>()

data class NodeConfigurationImpl(
        /** This is not retrieved from the config file but rather from a command line argument. */
        override val baseDirectory: Path,
        override val myLegalName: CordaX500Name,
        override val emailAddress: String,
        override val keyStorePassword: String,
        override val trustStorePassword: String,
        override val dataSourceProperties: Properties,
        override val database: Properties?,
        override val certificateSigningService: URL,
        override val minimumPlatformVersion: Int = 1,
        override val rpcUsers: List<User>,
        override val verifierType: VerifierType,
        // TODO typesafe config supports the notion of durations. Make use of that by mapping it to java.time.Duration.
        // Then rename this to messageRedeliveryDelay and make it of type Duration
        override val messageRedeliveryDelaySeconds: Int = 30,
        override val useHTTPS: Boolean,
        override val p2pAddress: NetworkHostAndPort,
        override val rpcAddress: NetworkHostAndPort?,
        // TODO This field is slightly redundant as p2pAddress is sufficient to hold the address of the node's MQ broker.
        // Instead this should be a Boolean indicating whether that broker is an internal one started by the node or an external one
        override val messagingServerAddress: NetworkHostAndPort?,
        override val notary: NotaryConfig?,
        override val certificateChainCheckPolicies: List<CertChainPolicyConfig>,
        override val devMode: Boolean = false,
        override val devModeOptions: DevModeOptions? = null,
        override val useTestClock: Boolean = false,
        override val detectPublicIp: Boolean = true,
        override val activeMQServer: ActiveMqServerConfiguration,
        // TODO See TODO above. Rename this to nodeInfoPollingFrequency and make it of type Duration
        override val additionalNodeInfoPollingFrequencyMsec: Long = 5.seconds.toMillis()
) : NodeConfiguration {
    override val exportJMXto: String get() = "http"

    init {
        // This is a sanity feature do not remove.
        require(!useTestClock || devMode) { "Cannot use test clock outside of dev mode" }
        require(devModeOptions == null || devMode) { "Cannot use devModeOptions outside of dev mode" }
        require(minimumPlatformVersion >= 1) { "minimumPlatformVersion cannot be less than 1" }
    }
}

enum class VerifierType {
    InMemory,
    OutOfProcess
}

enum class CertChainPolicyType {
    Any,
    RootMustMatch,
    LeafMustMatch,
    MustContainOneOf
}

data class CertChainPolicyConfig(val role: String, private val policy: CertChainPolicyType, private val trustedAliases: Set<String>) {
    val certificateChainCheckPolicy: CertificateChainCheckPolicy
        get() {
            return when (policy) {
                CertChainPolicyType.Any -> CertificateChainCheckPolicy.Any
                CertChainPolicyType.RootMustMatch -> CertificateChainCheckPolicy.RootMustMatch
                CertChainPolicyType.LeafMustMatch -> CertificateChainCheckPolicy.LeafMustMatch
                CertChainPolicyType.MustContainOneOf -> CertificateChainCheckPolicy.MustContainOneOf(trustedAliases)
            }
        }
}
