package net.corda.nodeapi

import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.Try
import net.corda.nodeapi.RPCApi.ClientToServer
import net.corda.nodeapi.RPCApi.ObservableId
import net.corda.nodeapi.RPCApi.RPC_CLIENT_BINDING_REMOVALS
import net.corda.nodeapi.RPCApi.RPC_CLIENT_BINDING_REMOVAL_FILTER_EXPRESSION
import net.corda.nodeapi.RPCApi.RPC_CLIENT_QUEUE_NAME_PREFIX
import net.corda.nodeapi.RPCApi.RPC_SERVER_QUEUE_NAME
import net.corda.nodeapi.RPCApi.RpcRequestId
import net.corda.nodeapi.RPCApi.ServerToClient
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.client.*
import org.apache.activemq.artemis.api.core.management.CoreNotificationType
import org.apache.activemq.artemis.api.core.management.ManagementHelper
import org.apache.activemq.artemis.reader.MessageUtil
import rx.Notification
import java.util.*

/**
 * The RPC protocol:
 *
 * The server consumes the queue "[RPC_SERVER_QUEUE_NAME]" and receives RPC requests ([ClientToServer.RpcRequest]) on it.
 * When a client starts up it should create a queue for its inbound messages, this should be of the form
 * "[RPC_CLIENT_QUEUE_NAME_PREFIX].$username.$nonce". Each RPC request contains this address (in
 * [ClientToServer.RpcRequest.clientAddress]), this is where the server will send the reply to the request as well as
 * subsequent Observations rooted in the RPC. The requests/replies are muxed using a unique [RpcRequestId] generated by
 * the client for each request.
 *
 * If an RPC reply's payload ([ServerToClient.RpcReply.result]) contains [Observable]s then the server will generate a
 * unique [ObservableId] for each and serialise them in place of the [Observable]s themselves. Subsequently the client
 * should be prepared to receive observations ([ServerToClient.Observation]), muxed by the relevant [ObservableId].
 * In addition each observation itself may contain further [Observable]s, this case should behave the same as before.
 *
 * Additionally the client may send [ClientToServer.ObservablesClosed] messages indicating that certain observables
 * aren't consumed anymore, which should subsequently stop the stream from the server. Note that some observations may
 * already be in flight when this is sent, the client should handle this gracefully.
 *
 * An example session:
 * Client                              Server
 *   ----------RpcRequest(RID0)----------->   // Client makes RPC request with ID "RID0"
 *   <----RpcReply(RID0, Payload(OID0))----   // Server sends reply containing an observable with ID "OID0"
 *   <---------Observation(OID0)-----------   // Server sends observation onto "OID0"
 *   <---Observation(OID0, Payload(OID1))--   // Server sends another observation, this time containing another observable
 *   <---------Observation(OID1)-----------   // Observation onto new "OID1"
 *   <---------Observation(OID0)-----------
 *   -----ObservablesClosed(OID0, OID1)--->   // Client indicates it stopped consuming the Observables.
 *   <---------Observation(OID1)-----------   // Observation was already in-flight before the previous message was processed
 *                  (FIN)
 *
 * Note that multiple sessions like the above may interleave in an arbitrary fashion.
 *
 * Additionally the server may listen on client binding removals for cleanup using [RPC_CLIENT_BINDING_REMOVALS]. This
 * requires the server to create a filter on the artemis notification address using [RPC_CLIENT_BINDING_REMOVAL_FILTER_EXPRESSION]
 */
object RPCApi {
    private val TAG_FIELD_NAME = "tag"
    private val RPC_ID_FIELD_NAME = "rpc-id"
    private val OBSERVABLE_ID_FIELD_NAME = "observable-id"
    private val METHOD_NAME_FIELD_NAME = "method-name"

    val RPC_SERVER_QUEUE_NAME = "rpc.server"
    val RPC_CLIENT_QUEUE_NAME_PREFIX = "rpc.client"
    val RPC_CLIENT_BINDING_REMOVALS = "rpc.clientqueueremovals"
    val RPC_CLIENT_BINDING_ADDITIONS = "rpc.clientqueueadditions"

    val RPC_CLIENT_BINDING_REMOVAL_FILTER_EXPRESSION =
            "${ManagementHelper.HDR_NOTIFICATION_TYPE} = '${CoreNotificationType.BINDING_REMOVED.name}' AND " +
            "${ManagementHelper.HDR_ROUTING_NAME} LIKE '$RPC_CLIENT_QUEUE_NAME_PREFIX.%'"
    val RPC_CLIENT_BINDING_ADDITION_FILTER_EXPRESSION =
            "${ManagementHelper.HDR_NOTIFICATION_TYPE} = '${CoreNotificationType.BINDING_ADDED.name}' AND " +
                    "${ManagementHelper.HDR_ROUTING_NAME} LIKE '$RPC_CLIENT_QUEUE_NAME_PREFIX.%'"

    data class RpcRequestId(val toLong: Long)
    data class ObservableId(val toLong: Long)

    object RpcRequestOrObservableIdKey

    private fun ClientMessage.getBodyAsByteArray(): ByteArray {
        return ByteArray(bodySize).apply { bodyBuffer.readBytes(this) }
    }

    sealed class ClientToServer {
        private enum class Tag {
            RPC_REQUEST,
            OBSERVABLES_CLOSED
        }

        data class RpcRequest(
                val clientAddress: SimpleString,
                val id: RpcRequestId,
                val methodName: String,
                val arguments: List<Any?>
        ) : ClientToServer() {
            fun writeToClientMessage(context: SerializationContext, message: ClientMessage) {
                MessageUtil.setJMSReplyTo(message, clientAddress)
                message.putIntProperty(TAG_FIELD_NAME, Tag.RPC_REQUEST.ordinal)
                message.putLongProperty(RPC_ID_FIELD_NAME, id.toLong)
                message.putStringProperty(METHOD_NAME_FIELD_NAME, methodName)
                message.bodyBuffer.writeBytes(arguments.serialize(context = context).bytes)
            }
        }

        data class ObservablesClosed(
                val ids: List<ObservableId>
        ) : ClientToServer() {
            fun writeToClientMessage(message: ClientMessage) {
                message.putIntProperty(TAG_FIELD_NAME, Tag.OBSERVABLES_CLOSED.ordinal)
                val buffer = message.bodyBuffer
                buffer.writeInt(ids.size)
                ids.forEach {
                    buffer.writeLong(it.toLong)
                }
            }
        }

        companion object {
            fun fromClientMessage(context: SerializationContext, message: ClientMessage): ClientToServer {
                val tag = Tag.values()[message.getIntProperty(TAG_FIELD_NAME)]
                return when (tag) {
                    RPCApi.ClientToServer.Tag.RPC_REQUEST -> RpcRequest(
                            clientAddress = MessageUtil.getJMSReplyTo(message),
                            id = RpcRequestId(message.getLongProperty(RPC_ID_FIELD_NAME)),
                            methodName = message.getStringProperty(METHOD_NAME_FIELD_NAME),
                            arguments = message.getBodyAsByteArray().deserialize(context = context)
                    )
                    RPCApi.ClientToServer.Tag.OBSERVABLES_CLOSED -> {
                        val ids = ArrayList<ObservableId>()
                        val buffer = message.bodyBuffer
                        val numberOfIds = buffer.readInt()
                        for (i in 1 .. numberOfIds) {
                            ids.add(ObservableId(buffer.readLong()))
                        }
                        ObservablesClosed(ids)
                    }
                }
            }
        }
    }

    sealed class ServerToClient {
        private enum class Tag {
            RPC_REPLY,
            OBSERVATION
        }

        abstract fun writeToClientMessage(context: SerializationContext, message: ClientMessage)

        data class RpcReply(
                val id: RpcRequestId,
                val result: Try<Any?>
        ) : ServerToClient() {
            override fun writeToClientMessage(context: SerializationContext, message: ClientMessage) {
                message.putIntProperty(TAG_FIELD_NAME, Tag.RPC_REPLY.ordinal)
                message.putLongProperty(RPC_ID_FIELD_NAME, id.toLong)
                message.bodyBuffer.writeBytes(result.serialize(context = context).bytes)
            }
        }

        data class Observation(
                val id: ObservableId,
                val content: Notification<Any>
        ) : ServerToClient() {
            override fun writeToClientMessage(context: SerializationContext, message: ClientMessage) {
                message.putIntProperty(TAG_FIELD_NAME, Tag.OBSERVATION.ordinal)
                message.putLongProperty(OBSERVABLE_ID_FIELD_NAME, id.toLong)
                message.bodyBuffer.writeBytes(content.serialize(context = context).bytes)
            }
        }

        companion object {
            fun fromClientMessage(context: SerializationContext, message: ClientMessage): ServerToClient {
                val tag = Tag.values()[message.getIntProperty(TAG_FIELD_NAME)]
                return when (tag) {
                    RPCApi.ServerToClient.Tag.RPC_REPLY -> {
                        val id = RpcRequestId(message.getLongProperty(RPC_ID_FIELD_NAME))
                        val poolWithIdContext = context.withProperty(RpcRequestOrObservableIdKey, id.toLong)
                        RpcReply(
                                id = id,
                                result = message.getBodyAsByteArray().deserialize(context = poolWithIdContext)
                        )
                    }
                    RPCApi.ServerToClient.Tag.OBSERVATION -> {
                        val id = ObservableId(message.getLongProperty(OBSERVABLE_ID_FIELD_NAME))
                        val poolWithIdContext = context.withProperty(RpcRequestOrObservableIdKey, id.toLong)
                        Observation(
                                id = id,
                                content = message.getBodyAsByteArray().deserialize(context = poolWithIdContext)
                        )
                    }
                }
            }
        }
    }
}

data class ArtemisProducer(
        val sessionFactory: ClientSessionFactory,
        val session: ClientSession,
        val producer: ClientProducer
)

data class ArtemisConsumer(
        val sessionFactory: ClientSessionFactory,
        val session: ClientSession,
        val consumer: ClientConsumer
)