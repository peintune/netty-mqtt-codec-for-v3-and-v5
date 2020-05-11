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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.v3v5.codec.mqtt.MqttDecoder.DecoderState;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.List;

import static io.netty.v3v5.codec.mqtt.SubscriptionOption.onlyFromQos;

/**
 * Decodes Mqtt messages from bytes, following
 * <a href="http://public.dhe.ibm.com/software/dw/webservices/ws-mqtt/mqtt-v3r1.html">
 *     the MQTT protocol specification v3.1</a>
 */
public class MqttDecoder extends ReplayingDecoder<DecoderState> {

    private static final int DEFAULT_MAX_BYTES_IN_MESSAGE = 8092;

    /**
     * States of the decoder.
     * We start at READ_FIXED_HEADER, followed by
     * READ_VARIABLE_HEADER and finally READ_PAYLOAD.
     */
    enum DecoderState {
        READ_FIXED_HEADER,
        READ_VARIABLE_HEADER,
        READ_PAYLOAD,
        BAD_MESSAGE,
    }

    private MqttFixedHeader mqttFixedHeader;
    private Object variableHeader;
    private int bytesRemainingInVariablePart;

    private final int maxBytesInMessage;

    private final IVariableHeaderDecoder headerDecoder;
    private final AbstractMessageFactory messageFactory;

    public MqttDecoder(IVariableHeaderDecoder headerDecoder, AbstractMessageFactory messageFactory) {
      this(DEFAULT_MAX_BYTES_IN_MESSAGE, headerDecoder, messageFactory);
    }

    public MqttDecoder(int maxBytesInMessage, IVariableHeaderDecoder headerDecoder,
                       AbstractMessageFactory messageFactory) {
        super(DecoderState.READ_FIXED_HEADER);
        this.maxBytesInMessage = maxBytesInMessage;
        this.headerDecoder = headerDecoder;
        this.messageFactory = messageFactory;
    }

    @Override
    public void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        switch (state()) {
            case READ_FIXED_HEADER: try {
                mqttFixedHeader = decodeFixedHeader(buffer);
                bytesRemainingInVariablePart = mqttFixedHeader.remainingLength();
                checkpoint(DecoderState.READ_VARIABLE_HEADER);
                // fall through
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case READ_VARIABLE_HEADER:  try {
                if (bytesRemainingInVariablePart > maxBytesInMessage) {
                    throw new DecoderException("too large message: " + bytesRemainingInVariablePart + " bytes");
                }
                final Result<?> decodedVariableHeader = headerDecoder.decodeVariableHeader(buffer, mqttFixedHeader);
                variableHeader = decodedVariableHeader.value;
                bytesRemainingInVariablePart -= decodedVariableHeader.numberOfBytesConsumed;
                checkpoint(DecoderState.READ_PAYLOAD);
                // fall through
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case READ_PAYLOAD: try {
                final Result<?> decodedPayload =
                        decodePayload(
                                buffer,
                                mqttFixedHeader.messageType(),
                                bytesRemainingInVariablePart,
                                variableHeader);
                bytesRemainingInVariablePart -= decodedPayload.numberOfBytesConsumed;
                if (bytesRemainingInVariablePart != 0) {
                    throw new DecoderException(
                            "non-zero remaining payload bytes: " +
                                    bytesRemainingInVariablePart + " (" + mqttFixedHeader.messageType() + ')');
                }
                checkpoint(DecoderState.READ_FIXED_HEADER);
                MqttMessage message = this.messageFactory.newMessage(
                        mqttFixedHeader, variableHeader, decodedPayload.value);
                mqttFixedHeader = null;
                variableHeader = null;
                out.add(message);
                break;
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case BAD_MESSAGE:
                // Keep discarding until disconnection.
                buffer.skipBytes(actualReadableBytes());
                break;

            default:
                // Shouldn't reach here.
                throw new Error();
        }
    }

    public MqttMessage invalidMessage(Throwable cause) {
      checkpoint(DecoderState.BAD_MESSAGE);
      return this.messageFactory.newInvalidMessage(cause);
    }

    /**
     * Decodes the fixed header. It's one byte for the flags and then variable bytes for the remaining length.
     *
     * @param buffer the buffer to decode from
     * @return the fixed header
     */
    public MqttFixedHeader decodeFixedHeader(ByteBuf buffer) {
        short b1 = buffer.readUnsignedByte();
        final int type = b1 >> 4;
        if (type > MqttMessageType.DISCONNECT.value()) {
            throw new IllegalArgumentException("unknown message type: " + type);
        }

        MqttMessageType messageType = MqttMessageType.valueOf(type);
        boolean dupFlag = (b1 & 0x08) == 0x08;
        int qosLevel = (b1 & 0x06) >> 1;
        boolean retain = (b1 & 0x01) != 0;

        Result<Integer> remainingLength = decodeVariableByteInteger(buffer);
        // MQTT protocol limits Remaining Length to 4 bytes
        if (remainingLength == null) {
            throw new DecoderException("remaining length exceeds 4 digits (" + messageType + ')');
        }
        MqttFixedHeader decodedFixedHeader =
                new MqttFixedHeader(messageType, dupFlag, MqttQoS.valueOf(qosLevel), retain, remainingLength.value);
        return MqttCodecUtil.validateFixedHeader(MqttCodecUtil.resetUnusedFields(decodedFixedHeader));
    }

    static Result<Integer> decodeVariableByteInteger(ByteBuf buffer) {
        int remainingLength = 0;
        int multiplier = 1;
        short digit;
        int loops = 0;
        do {
            digit = buffer.readUnsignedByte();
            remainingLength += (digit & 127) * multiplier;
            multiplier *= 128;
            loops++;
        } while ((digit & 128) != 0 && loops < 4);

        // MQTT protocol limits Remaining Length to 4 bytes
        if (loops == 4 && (digit & 128) != 0) {
            return null;
        }
        return new Result<Integer>(remainingLength, loops);
    }

    /**
     * Decodes the payload.
     *
     * @param buffer the buffer to decode from
     * @param messageType  type of the message being decoded
     * @param bytesRemainingInVariablePart bytes remaining
     * @param variableHeader variable header of the same message
     * @return the payload
     */
    public Result<?> decodePayload(
            ByteBuf buffer,
            MqttMessageType messageType,
            int bytesRemainingInVariablePart,
            Object variableHeader) {
        switch (messageType) {
            case CONNECT:
                return decodeConnectionPayload(buffer, (MqttConnectVariableHeader) variableHeader);

            case SUBSCRIBE:
                return decodeSubscribePayload(buffer, bytesRemainingInVariablePart);

            case SUBACK:
                return decodeSubackPayload(buffer, bytesRemainingInVariablePart);

            case UNSUBSCRIBE:
                return decodeUnsubscribePayload(buffer, bytesRemainingInVariablePart);

            case PUBLISH:
                return decodePublishPayload(buffer, bytesRemainingInVariablePart);

            default:
                // unknown payload , no byte consumed
                return new Result<Object>(null, 0);
        }
    }

    public static Result<MqttConnectPayload> decodeConnectionPayload(
            ByteBuf buffer,
            MqttConnectVariableHeader mqttConnectVariableHeader) {
        final Result<String> decodedClientId = decodeString(buffer);
        final String decodedClientIdValue = decodedClientId.value;
        final MqttVersion mqttVersion = MqttVersion.fromProtocolNameAndLevel(mqttConnectVariableHeader.name(),
                (byte) mqttConnectVariableHeader.version());
        if (!MqttCodecUtil.isValidClientId(mqttVersion, decodedClientIdValue)) {
            throw new MqttIdentifierRejectedException("invalid clientIdentifier: " + decodedClientIdValue);
        }
        int numberOfBytesConsumed = decodedClientId.numberOfBytesConsumed;

        Result<String> decodedWillTopic = null;
        Result<byte[]> decodedWillMessage = null;
        if (mqttConnectVariableHeader.isWillFlag()) {
            decodedWillTopic = decodeString(buffer, 0, 32767);
            numberOfBytesConsumed += decodedWillTopic.numberOfBytesConsumed;
            decodedWillMessage = decodeByteArray(buffer);
            numberOfBytesConsumed += decodedWillMessage.numberOfBytesConsumed;
        }
        Result<String> decodedUserName = null;
        Result<byte[]> decodedPassword = null;
        if (mqttConnectVariableHeader.hasUserName()) {
            decodedUserName = decodeString(buffer);
            numberOfBytesConsumed += decodedUserName.numberOfBytesConsumed;
        }
        if (mqttConnectVariableHeader.hasPassword()) {
            decodedPassword = decodeByteArray(buffer);
            numberOfBytesConsumed += decodedPassword.numberOfBytesConsumed;
        }

        final MqttConnectPayload mqttConnectPayload =
                new MqttConnectPayload(
                        decodedClientId.value,
                        decodedWillTopic != null ? decodedWillTopic.value : null,
                        decodedWillMessage != null ? decodedWillMessage.value : null,
                        decodedUserName != null ? decodedUserName.value : null,
                        decodedPassword != null ? decodedPassword.value : null);
        return new Result<MqttConnectPayload>(mqttConnectPayload, numberOfBytesConsumed);
    }

    public Result<MqttSubscribePayload> decodeSubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<MqttTopicSubscription> subscribeTopics = new ArrayList<MqttTopicSubscription>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            int qos = buffer.readUnsignedByte() & 0x03;
            numberOfBytesConsumed++;
            subscribeTopics.add(new MqttTopicSubscription(decodedTopicName.value, onlyFromQos(MqttQoS.valueOf(qos))));
        }
        return new Result<MqttSubscribePayload>(new MqttSubscribePayload(subscribeTopics), numberOfBytesConsumed);
    }

    public static Result<MqttSubAckPayload> decodeSubackPayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<Integer> grantedQos = new ArrayList<Integer>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            int qos = buffer.readUnsignedByte() & 0x03;
            numberOfBytesConsumed++;
            grantedQos.add(qos);
        }
        return new Result<MqttSubAckPayload>(new MqttSubAckPayload(grantedQos), numberOfBytesConsumed);
    }

    public static Result<MqttUnsubscribePayload> decodeUnsubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<String> unsubscribeTopics = new ArrayList<String>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            unsubscribeTopics.add(decodedTopicName.value);
        }
        return new Result<MqttUnsubscribePayload>(
                new MqttUnsubscribePayload(unsubscribeTopics),
                numberOfBytesConsumed);
    }

    public static Result<ByteBuf> decodePublishPayload(ByteBuf buffer, int bytesRemainingInVariablePart) {
        ByteBuf b = buffer.readRetainedSlice(bytesRemainingInVariablePart);
        return new Result<ByteBuf>(b, bytesRemainingInVariablePart);
    }

    static Result<String> decodeString(ByteBuf buffer) {
        return decodeString(buffer, 0, Integer.MAX_VALUE);
    }

    public static Result<String> decodeString(ByteBuf buffer, int minBytes, int maxBytes) {
        final Result<Integer> decodedSize = decodeMsbLsb(buffer);
        int size = decodedSize.value;
        int numberOfBytesConsumed = decodedSize.numberOfBytesConsumed;
        if (size < minBytes || size > maxBytes) {
            buffer.skipBytes(size);
            numberOfBytesConsumed += size;
            return new Result<String>(null, numberOfBytesConsumed);
        }
        String s = buffer.toString(buffer.readerIndex(), size, CharsetUtil.UTF_8);
        buffer.skipBytes(size);
        numberOfBytesConsumed += size;
        return new Result<String>(s, numberOfBytesConsumed);
    }

    static Result<byte[]> decodeByteArray(ByteBuf buffer) {
        final Result<Integer> decodedSize = decodeMsbLsb(buffer);
        int size = decodedSize.value;
        byte[] bytes = new byte[size];
        buffer.readBytes(bytes);
        return new Result<byte[]>(bytes, decodedSize.numberOfBytesConsumed + size);
    }

    static Result<Integer> decodeMsbLsb(ByteBuf buffer) {
        return decodeMsbLsb(buffer, 0, 65535);
    }

    public static Result<Integer> decodeMsbLsb(ByteBuf buffer, int min, int max) {
        short msbSize = buffer.readUnsignedByte();
        short lsbSize = buffer.readUnsignedByte();
        final int numberOfBytesConsumed = 2;
        int result = msbSize << 8 | lsbSize;
        if (result < min || result > max) {
            result = -1;
        }
        return new Result<Integer>(result, numberOfBytesConsumed);
    }

    static Result<Integer> decode4bytesInteger(ByteBuf buffer) {
        short msb = buffer.readUnsignedByte();
        short secondByte = buffer.readUnsignedByte();
        short thirdByte = buffer.readUnsignedByte();
        short lsbSize = buffer.readUnsignedByte();
        final int numberOfBytesConsumed = 4;
        int result = msb << 24 | secondByte << 16 | thirdByte << 8 | lsbSize;

        return new Result<Integer>(result, numberOfBytesConsumed);
    }

    public static final class Result<T> {

        public final T value;
        public final int numberOfBytesConsumed;

        Result(T value, int numberOfBytesConsumed) {
            this.value = value;
            this.numberOfBytesConsumed = numberOfBytesConsumed;
        }
    }
}
