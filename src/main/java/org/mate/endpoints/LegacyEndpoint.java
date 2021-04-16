package org.mate.endpoints;

import org.mate.accessibility.ImageHandler;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.network.message.Messages;
import org.mate.pdf.Report;
import org.mate.util.AndroidEnvironment;

public class LegacyEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;
    private final ImageHandler imageHandler;
    private final boolean generatePDFReport = false;

    public LegacyEndpoint(AndroidEnvironment androidEnvironment, ImageHandler imageHandler) {
        this.androidEnvironment = androidEnvironment;
        this.imageHandler = imageHandler;
    }

    @Override
    public Message handle(Message request) {
        final var response = handleRequest(request.getParameter("cmd"));
        if (response == null) {
            return Messages.errorMessage("legacy message was not understood");
        }
        return new Message.MessageBuilder("/legacy")
                .withParameter("response", response)
                .build();
    }

    private String handleRequest(String cmdStr) {
        System.out.println();
        System.out.println(cmdStr);

        if (cmdStr.startsWith("reportFlaw")){
            return Report.addFlaw(cmdStr, imageHandler);
        }

        // TODO: there is no corresponding method present in MATE
        if (cmdStr.startsWith("mark-image") && generatePDFReport)
            return imageHandler.markImage(cmdStr);

        // TODO: there is no corresponding method present in MATE
        if (cmdStr.startsWith("FINISH") && generatePDFReport) {
            try {
                Report.generateReport(cmdStr);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Finished PDF report";
        }
        return null;
    }
}
