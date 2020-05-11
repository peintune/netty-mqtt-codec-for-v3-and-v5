/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.v3v5.codec.mqtt;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Used to decode only the Variable Header part of MQTT packets, which is what differs between v3.1.1 and v5
 */
public class VariableHeaderDecoderV5  implements IVariableHeaderDecoder {
    /**
     * Decodes the variable header (if any)
     * @param buffer the buffer to decode from
     * @param mqttFixedHeader MqttFixedHeader of the same message
     * @return the variable header
     */
    @Override
    public MqttDecoder.Result<?> decodeVariableHeader(ByteBuf buffer, MqttFixedHeader mqttFixedHeader) {
        switch (mqttFixedHeader.messageType()) {
            case CONNECT:
                return decodeConnectionVariableHeader(buffer);

            case CONNACK:
                return decodeConnAckVariableHeader(buffer);

            case UNSUBSCRIBE:
                return decodeMessageIdVariableHeader(buffer);

            case SUBSCRIBE:
            case SUBACK:
            case UNSUBACK:
                return decodeMessageIdPlusPropertiesVariableHeader(buffer);

            case PUBACK:
            case PUBREC:
            case PUBCOMP:
            case PUBREL:
                return decodePubReplyMessage(buffer);

            case PUBLISH:
                return decodePublishVariableHeader(buffer, mqttFixedHeader);

            case DISCONNECT:
                return decodeReasonCodePlusPropertiesVariableHeader(buffer);

            case AUTH:
                return decodeReasonCodePlusPropertiesVariableHeader(buffer);

            case PINGREQ:
            case PINGRESP:
                // Empty variable header
                return new MqttDecoder.Result<Object>(null, 0);
        }
        return new MqttDecoder.Result<Object>(null, 0); //should never reach here
    }

    private static MqttDecoder.Result<MqttConnectVariableHeader> decodeConnectionVariableHeader(ByteBuf buffer) {
        final MqttDecoder.Result<String> protoString = MqttDecoder.decodeString(buffer);
        int numberOfBytesConsumed = protoString.numberOfBytesConsumed;

        final byte protocolLevel = buffer.readByte();
        numberOfBytesConsumed += 1;

        final MqttVersion mqttVersion = MqttVersion.fromProtocolNameAndLevel(protoString.value, protocolLevel);

        final int b1 = buffer.readUnsignedByte();
        numberOfBytesConsumed += 1;

        final MqttDecoder.Result<Integer> keepAlive = MqttDecoder.decodeMsbLsb(buffer);
        numberOfBytesConsumed += keepAlive.numberOfBytesConsumed;

        final boolean hasUserName = (b1 & 0x80) == 0x80;
        final boolean hasPassword = (b1 & 0x40) == 0x40;
        final boolean willRetain = (b1 & 0x20) == 0x20;
        final int willQos = (b1 & 0x18) >> 3;
        final boolean willFlag = (b1 & 0x04) == 0x04;
        final boolean cleanSession = (b1 & 0x02) == 0x02;
        if (mqttVersion == MqttVersion.MQTT_3_1_1) {
            final boolean zeroReservedFlag = (b1 & 0x01) == 0x0;
            if (!zeroReservedFlag) {
                // MQTT v3.1.1: The Server MUST validate that the reserved flag in the CONNECT Control Packet is
                // set to zero and disconnect the Client if it is not zero.
                // See http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc385349230
                throw new DecoderException("non-zero reserved flag");
            }
        }

        final MqttDecoder.Result<MqttProperties> properties = decodeProperties(buffer);
        numberOfBytesConsumed += properties.numberOfBytesConsumed;

        final MqttConnectVariableHeader mqttConnectVariableHeader = new MqttConnectVariableHeader(
                mqttVersion.protocolName(),
                mqttVersion.protocolLevel(),
                hasUserName,
                hasPassword,
                willRetain,
                willQos,
                willFlag,
                cleanSession,
                keepAlive.value,
                properties.value);
        return new MqttDecoder.Result<MqttConnectVariableHeader>(mqttConnectVariableHeader, numberOfBytesConsumed);
    }

    private static MqttDecoder.Result<MqttConnAckVariableHeader> decodeConnAckVariableHeader(ByteBuf buffer) {
        final boolean sessionPresent = (buffer.readUnsignedByte() & 0x01) == 0x01;
        byte returnCode = buffer.readByte();
        int numberOfBytesConsumed = 2;

        final MqttDecoder.Result<MqttProperties> properties = decodeProperties(buffer);
        numberOfBytesConsumed += properties.numberOfBytesConsumed;

        final MqttConnAckVariableHeader mqttConnAckVariableHeader =
                new MqttConnAckVariableHeader(MqttConnectReturnCode.valueOf(returnCode), sessionPresent,
                        properties.value);

        return new MqttDecoder.Result<MqttConnAckVariableHeader>(mqttConnAckVariableHeader, numberOfBytesConsumed);
    }

    private static MqttDecoder.Result<MqttMessageIdVariableHeader> decodeMessageIdVariableHeader(ByteBuf buffer) {
        final MqttDecoder.Result<Integer> messageId = decodeMessageId(buffer);
        return new MqttDecoder.Result<MqttMessageIdVariableHeader>(
                MqttMessageIdVariableHeader.from(messageId.value),
                messageId.numberOfBytesConsumed);
    }

    private static MqttDecoder.Result<MqttPublishVariableHeader> decodePublishVariableHeader(
            ByteBuf buffer,
            MqttFixedHeader mqttFixedHeader) {
        final MqttDecoder.Result<String> decodedTopic = MqttDecoder.decodeString(buffer);
        if (!MqttCodecUtil.isValidPublishTopicName(decodedTopic.value)) {
            throw new DecoderException("invalid publish topic name: " + decodedTopic.value + " (contains wildcards)");
        }
        int numberOfBytesConsumed = decodedTopic.numberOfBytesConsumed;

        int messageId = -1;
        if (mqttFixedHeader.qosLevel().value() > 0) {
            final MqttDecoder.Result<Integer> decodedMessageId = decodeMessageId(buffer);
            messageId = decodedMessageId.value;
            numberOfBytesConsumed += decodedMessageId.numberOfBytesConsumed;
        }
        final MqttDecoder.Result<MqttProperties> properties = decodeProperties(buffer);
        numberOfBytesConsumed += properties.numberOfBytesConsumed;

        final MqttPublishVariableHeader mqttPublishVariableHeader =
                new MqttPublishVariableHeader(decodedTopic.value, messageId, properties.value);
        return new MqttDecoder.Result<MqttPublishVariableHeader>(mqttPublishVariableHeader, numberOfBytesConsumed);
    }

    private static MqttDecoder.Result<MqttProperties> decodeProperties(ByteBuf buffer) {
        final MqttDecoder.Result<Integer> propertiesLength = MqttDecoder.decodeVariableByteInteger(buffer);
        int totalPropertiesLength = propertiesLength.value;
        int numberOfBytesConsumed = propertiesLength.numberOfBytesConsumed;

        MqttProperties decodedProperties = new MqttProperties();
        while (numberOfBytesConsumed < totalPropertiesLength) {
            MqttDecoder.Result<Integer> propertyId = MqttDecoder.decodeVariableByteInteger(buffer);
            numberOfBytesConsumed += propertyId.numberOfBytesConsumed;

            switch (propertyId.value) {
                case 0x01: // Payload Format Indicator => Byte
                case 0x17: // Request Problem Information
                case 0x19: // Request Response Information
                case 0x24: // Maximum QoS
                case 0x25: // Retain Available
                case 0x28: // Wildcard Subscription Available
                case 0x29: // Subscription Identifier Available
                case 0x2A: // Shared Subscription Available
                    final int b1 = buffer.readUnsignedByte();
                    numberOfBytesConsumed ++;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, b1));
                    break;
                case 0x13: // Server Keep Alive => Two Byte Integer
                case 0x21: // Receive Maximum
                case 0x22: // Topic Alias Maximum
                case 0x23: // Topic Alias
                    final MqttDecoder.Result<Integer> int2BytesResult = MqttDecoder.decodeMsbLsb(buffer);
                    numberOfBytesConsumed += int2BytesResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, int2BytesResult.value));
                    break;
                case 0x02: // Publication Expiry Interval => Four Byte Integer
                case 0x11: // Session Expiry Interval
                case 0x18: // Will Delay Interval
                case 0x27: // Maximum Packet Size
                    final MqttDecoder.Result<Integer> int4BytesResult = MqttDecoder.decode4bytesInteger(buffer);
                    numberOfBytesConsumed += int4BytesResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, int4BytesResult.value));
                    break;
                case 0x0B: // Subscription Identifier => Variable Byte Integer
                    MqttDecoder.Result<Integer> vbIntegerResult = MqttDecoder.decodeVariableByteInteger(buffer);
                    numberOfBytesConsumed += vbIntegerResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, vbIntegerResult.value));
                    break;
                case 0x03: // Content Type => UTF-8 Encoded String
                case 0x08: // Response Topic
                case 0x12: // Assigned Client Identifier
                case 0x15: // Authentication Method
                case 0x1A: // Response Information
                case 0x1C: // Server Reference
                case 0x1F: // Reason String
                case 0x26: // User Property
                    final MqttDecoder.Result<String> stringResult = MqttDecoder.decodeString(buffer);
                    numberOfBytesConsumed += stringResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.StringProperty(propertyId.value, stringResult.value));
                    break;
                case 0x09: // Correlation Data => Binary Data
                case 0x16: // Authentication Data
                    final MqttDecoder.Result<byte[]> binaryDataResult = MqttDecoder.decodeByteArray(buffer);
                    numberOfBytesConsumed += binaryDataResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.BinaryProperty(propertyId.value, binaryDataResult.value));
                    break;
            }
        }

        return new MqttDecoder.Result<MqttProperties>(decodedProperties, numberOfBytesConsumed);
    }

    private static MqttDecoder.Result<Integer> decodeMessageId(ByteBuf buffer) {
        final MqttDecoder.Result<Integer> messageId = MqttDecoder.decodeMsbLsb(buffer);
        if (!MqttCodecUtil.isValidMessageId(messageId.value)) {
            throw new DecoderException("invalid messageId: " + messageId.value);
        }
        return messageId;
    }

    private static MqttDecoder.Result<MqttPubReplyMessageVariableHeader> decodePubReplyMessage(ByteBuf buffer) {
        final MqttDecoder.Result<Integer> packetId = decodeMessageId(buffer);
        final byte reasonCode = (byte) buffer.readUnsignedByte();
        final MqttDecoder.Result<MqttProperties> properties = decodeProperties(buffer);
        final MqttPubReplyMessageVariableHeader mqttPubAckVariableHeader =
                new MqttPubReplyMessageVariableHeader(packetId.value, reasonCode, properties.value);

        return new MqttDecoder.Result<MqttPubReplyMessageVariableHeader>(
                mqttPubAckVariableHeader,
                packetId.numberOfBytesConsumed + 1 + properties.numberOfBytesConsumed);
    }

    private MqttDecoder.Result<MqttMessageIdPlusPropertiesVariableHeader> decodeMessageIdPlusPropertiesVariableHeader(
            ByteBuf buffer) {
        final MqttDecoder.Result<Integer> packetId = decodeMessageId(buffer);
        final MqttDecoder.Result<MqttProperties> properties = decodeProperties(buffer);
        final MqttMessageIdPlusPropertiesVariableHeader mqttVariableHeader =
                new MqttMessageIdPlusPropertiesVariableHeader(packetId.value, properties.value);

        return new MqttDecoder.Result<MqttMessageIdPlusPropertiesVariableHeader>(mqttVariableHeader,
                packetId.numberOfBytesConsumed + properties.numberOfBytesConsumed);
    }

    private static MqttDecoder.Result<MqttReasonCodePlusPropertiesVariableHeader> decodeReasonCodePlusPropertiesVariableHeader(
            ByteBuf buffer) {
        final short reasonCode = buffer.readUnsignedByte();
        final MqttDecoder.Result<MqttProperties> properties = decodeProperties(buffer);
        final MqttReasonCodePlusPropertiesVariableHeader mqttDisconnecrVariableHeader =
                new MqttReasonCodePlusPropertiesVariableHeader(reasonCode, properties.value);

        return new MqttDecoder.Result<MqttReasonCodePlusPropertiesVariableHeader>(
                mqttDisconnecrVariableHeader,
                 1 + properties.numberOfBytesConsumed);
    }
}
