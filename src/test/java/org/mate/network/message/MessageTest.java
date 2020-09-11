package org.mate.network.message;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mate.network.message.serialization.Lexer;
import org.mate.network.message.serialization.Parser;
import org.mate.network.message.serialization.Serializer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MessageTest {
    Message message;
    Message message2;
    Message message3;
    String subject;
    String subject2;
    String subject3;
    String k1;
    String k2;
    String k3;
    String v1;
    String v2;
    String v3;

    @Before
    public void setup() {
        subject = "sub\\;~";
        subject2 = "message2";
        subject3 = "sub3\\;~";
        k1 = "";
        k2 = "k2";
        k3 = ":\\;~";
        v1 = "v;\\~1";
        v2 = "";
        v3 = "t\\@34afad";


        message = new Message(subject);
        message.addParameter(k1, v1);
        message.addParameter(k2, v2);

        message2 = new Message(subject2);
        message2.addParameter(k1, v1);
        message2.addParameter(k3, v3);

        message3 = new Message(subject3);
    }
    @Test
    public void test_MessageBuilder() {
        Message.MessageBuilder messageBuilder = new Message.MessageBuilder(subject);
        Message builtMessage = messageBuilder.withParameter(k1, v1).withParameter(k2, v2).build();

        Assert.assertEquals(message, builtMessage);
    }

    @Test
    public void test_MessageSerializer() {
        StringBuilder sb = new StringBuilder(Serializer.escapeParameterValue(subject));
        sb.append(Lexer.END_PARAMETER_CHAR)
                .append(k1)
                .append(Lexer.RELATION_SEPARATOR_CHAR)
                .append(Serializer.escapeParameterValue(v1))
                .append(Lexer.END_PARAMETER_CHAR)
                .append(k2)
                .append(Lexer.RELATION_SEPARATOR_CHAR)
                .append(Serializer.escapeParameterValue(v2))
                .append(Lexer.END_MESSAGE_CHAR);
        byte[] serializedMessage = sb.toString().getBytes(Lexer.CHARSET);
        List<Byte> expected = new ArrayList<>();
        for (byte b : serializedMessage) {
            expected.add(b);
        }

        List<Byte> actual= new ArrayList<>();
        for (byte b : Serializer.serialize(message)) {
            actual.add(b);
        }

        Assert.assertEquals(expected, actual);
    }

    @Test
    public void test_MessageRoundTrip() {
        byte[] serializedMessage = Serializer.serialize(message);
        byte[] serializedMessage2 = Serializer.serialize(message2);
        byte[] serializedMessage3 = Serializer.serialize(message3);
        byte[] combined = new byte[serializedMessage.length + serializedMessage2.length + serializedMessage3.length];

        int i = 0;
        for (byte b : serializedMessage) {
            combined[i] = b;
            i++;
        }
        for (byte b : serializedMessage2) {
            combined[i] = b;
            i++;
        }

        for (byte b : serializedMessage3) {
            combined[i] = b;
            i++;
        }

        InputStream inputStream = new ByteArrayInputStream(combined);

        Parser parser = new Parser(inputStream);
        Message parsedMessage = parser.nextMessage();
        Message parsedMessage2 = parser.nextMessage();
        Message parsedMessage3 = parser.nextMessage();

        Assert.assertEquals(message, parsedMessage);
        Assert.assertEquals(message2, parsedMessage2);
        Assert.assertEquals(message3, parsedMessage3);
    }
}
