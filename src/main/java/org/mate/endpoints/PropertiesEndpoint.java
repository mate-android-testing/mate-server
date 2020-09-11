package org.mate.endpoints;

import org.mate.network.message.Message;
import org.mate.network.Endpoint;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class PropertiesEndpoint implements Endpoint {
    @Override
    public Message handle(Message request) {
        Properties properties = new Properties();
        try {
            properties.load(new FileReader(new File("mate.properties")));
        } catch (IOException e) {
            System.out.println("WARNING: Failed to load mate.properties file: " + e.getLocalizedMessage());
        }
        Message.MessageBuilder mb = new Message.MessageBuilder("/properties");
        for (Map.Entry<Object, Object> propertyEntry : properties.entrySet()) {
            mb.withParameter((String) propertyEntry.getKey(), (String) propertyEntry.getValue());
        }

        return mb.build();
    }
}
