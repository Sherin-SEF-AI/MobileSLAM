package com.mappilot.recording.mcap

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors

/**
 * Builds the self-describing schema payload embedded in each MCAP Schema record:
 * a serialized `google.protobuf.FileDescriptorSet` containing the message's own
 * `.proto` file plus every dependency, ordered deps-first. With this embedded, a
 * reader (Foxglove, the `mcap` protobuf reader) decodes messages with no
 * external schema — the file is fully self-contained.
 */
internal object ProtoSchemas {

    fun fileDescriptorSet(messageType: Descriptors.Descriptor): ByteArray {
        val files = LinkedHashMap<String, DescriptorProtos.FileDescriptorProto>()
        collect(messageType.file, files)
        val builder = DescriptorProtos.FileDescriptorSet.newBuilder()
        files.values.forEach { builder.addFile(it) }
        return builder.build().toByteArray()
    }

    private fun collect(
        file: Descriptors.FileDescriptor,
        acc: LinkedHashMap<String, DescriptorProtos.FileDescriptorProto>,
    ) {
        if (acc.containsKey(file.name)) return
        for (dep in file.dependencies) collect(dep, acc)
        acc[file.name] = file.toProto()
    }
}
