package net.corda.node.serialization

import net.corda.core.serialization.SerializationContext
import net.corda.core.utilities.ByteSequence
import net.corda.nodeapi.internal.serialization.amqp.AbstractAMQPSerializationScheme

class AMQPServerSerializationScheme : AbstractAMQPSerializationScheme() {
    override fun rpcClientSerializerFactory(context: SerializationContext) = getSerializerFactory(context)

    override fun rpcServerSerializerFactory(context: SerializationContext) = getSerializerFactory(context)

    override fun canDeserializeVersion(byteSequence: ByteSequence, target: SerializationContext.UseCase) =
        canDeserializeVersion(byteSequence) && (target != SerializationContext.UseCase.Checkpoint)
}

