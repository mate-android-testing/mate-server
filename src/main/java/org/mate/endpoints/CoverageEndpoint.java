package org.mate.endpoints;

import org.mate.coverage.*;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;

import java.nio.file.Path;

public class CoverageEndpoint implements Endpoint {
    private final AndroidEnvironment androidEnvironment;
    private final Path resultsPath;
    private final Path appsDir;

    public CoverageEndpoint(AndroidEnvironment androidEnvironment, Path resultsPath, Path appsDir) {
        this.androidEnvironment = androidEnvironment;
        this.resultsPath = resultsPath;
        this.appsDir = appsDir;
    }

    @Override
    public Message handle(Message request) {

        if (request.getSubject().startsWith("/coverage/store")) {
            return storeCoverageData(request);
        } else if (request.getSubject().startsWith("/coverage/combined")) {
            return getCombinedCoverage(request);
        } else if (request.getSubject().startsWith("/coverage/lineCoveredPercentages")) {
            return getLineCoveredPercentages(request);
        } else if (request.getSubject().startsWith("/coverage/copy")) {
            return copyCoverageData(request);
        } else if (request.getSubject().startsWith("/coverage/getSourceLines")) {
            return getSourceLines(request);
        } else if (request.getSubject().startsWith("/coverage/getNumberOfSourceLines")) {
            return getNumberOfSourceLines(request);
        } else if (request.getSubject().startsWith("/coverage/get")) {
            return getCoverage(request);
        }
        throw new IllegalArgumentException("Message request with subject: "
                + request.getSubject() + " can't be handled by CoverageEndPoint!");
    }

    /**
     * Gets the coverage of a single test case within a test suite.
     *
     * @param request The request message.
     * @return Returns a response message containing the coverage information.
     */
    private Message getCoverage(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                return getLineCoverage(request);
            case BRANCH_COVERAGE:
                return getBranchCoverage(request);
            case METHOD_COVERAGE:
                return getMethodCoverage(request);
            case BASIC_BLOCK_LINE_COVERAGE:
                return getBasicBlockLineCoverage(request);
            case BASIC_BLOCK_BRANCH_COVERAGE:
                return getBasicBlockBranchCoverage(request);
            case ALL_COVERAGE:
                return getAllCoverage(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    /**
     * Gets the 'all coverage' of a single test case within a test suite.
     *
     * @param request The request message.
     * @return Returns a response message containing the coverage information.
     */
    private Message getAllCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testSuiteId = request.getParameter("testSuiteId");
        String testCaseId = request.getParameter("testCaseId");
        return AllCoverageManager.getCoverage(appsDir, packageName, testSuiteId, testCaseId);
    }

    /**
     * Gets the line coverage of a single test case within a test suite.
     *
     * @param request The request message.
     * @return Returns a response message containing the coverage information.
     */
    private Message getLineCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testSuiteId = request.getParameter("testSuiteId");
        String testCaseId = request.getParameter("testCaseId");
        return LineCoverageManager.getCoverage(appsDir, packageName, testSuiteId, testCaseId);
    }

    /**
     * Gets the method coverage of a single test case within a test suite.
     *
     * @param request The request message.
     * @return Returns a response message containing the coverage information.
     */
    private Message getMethodCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testSuiteId = request.getParameter("testSuiteId");
        String testCaseId = request.getParameter("testCaseId");
        return MethodCoverageManager.getCoverage(appsDir, packageName, testSuiteId, testCaseId);
    }

    /**
     * Gets the basic block branch coverage of a single test case within a test suite.
     *
     * @param request The request message.
     * @return Returns a response message containing the coverage information.
     */
    private Message getBasicBlockBranchCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testSuiteId = request.getParameter("testSuiteId");
        String testCaseId = request.getParameter("testCaseId");
        return BasicBlockCoverageManager.getBranchCoverage(appsDir, packageName, testSuiteId, testCaseId);
    }

    /**
     * Gets the basic block line coverage of a single test case within a test suite.
     *
     * @param request The request message.
     * @return Returns a response message containing the coverage information.
     */
    private Message getBasicBlockLineCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testSuiteId = request.getParameter("testSuiteId");
        String testCaseId = request.getParameter("testCaseId");
        return BasicBlockCoverageManager.getLineCoverage(appsDir, packageName, testSuiteId, testCaseId);
    }

    /**
     * Gets the branch coverage of a single test case within a test suite.
     *
     * @param request The request message.
     * @return Returns a response message containing the coverage information.
     */
    private Message getBranchCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testSuiteId = request.getParameter("testSuiteId");
        String testCaseId = request.getParameter("testCaseId");
        return BranchCoverageManager.getCoverage(appsDir, packageName, testSuiteId, testCaseId);
    }

    private Message copyCoverageData(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                return copyLineCoverageData(request);
            case BRANCH_COVERAGE:
                return copyBranchCoverageData(request);
            case METHOD_COVERAGE:
                return copyMethodCoverageData(request);
            case BASIC_BLOCK_LINE_COVERAGE:
            case BASIC_BLOCK_BRANCH_COVERAGE:
                return copyBasicBlockCoverageData(request);
            case ALL_COVERAGE:
                return copyAllCoverageData(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message copyAllCoverageData(Message request) {
        var packageName = request.getParameter("packageName");
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        return AllCoverageManager.copyCoverageData(appsDir, packageName, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message copyLineCoverageData(Message request) {
        String packageName = request.getParameter("packageName");
        String chromosomeSrc = request.getParameter("chromosome_src");
        String chromosomeTarget = request.getParameter("chromosome_target");
        String[] entities = request.getParameter("entities").split(",");
        return LineCoverageManager.copyLineCoverageData(appsDir, packageName, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message copyMethodCoverageData(Message request) {
        var packageName = request.getParameter("packageName");
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        return MethodCoverageManager.copyCoverageData(appsDir, packageName, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message copyBranchCoverageData(Message request) {
        var packageName = request.getParameter("packageName");
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        return BranchCoverageManager.copyCoverageData(appsDir, packageName, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message copyBasicBlockCoverageData(Message request) {
        var packageName = request.getParameter("packageName");
        var chromosomeSrc = request.getParameter("chromosome_src");
        var chromosomeTarget = request.getParameter("chromosome_target");
        var entities = request.getParameter("entities").split(",");
        return BasicBlockCoverageManager.copyCoverageData(appsDir, packageName, chromosomeSrc, chromosomeTarget, entities);
    }

    private Message storeCoverageData(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                return storeLineCoverageData(request);
            case BRANCH_COVERAGE:
                return storeBranchCoverageData(request);
            case METHOD_COVERAGE:
                return storeMethodCoverageData(request);
            case BASIC_BLOCK_LINE_COVERAGE:
            case BASIC_BLOCK_BRANCH_COVERAGE:
                return storeBasicBlockCoverageData(request);
            case ALL_COVERAGE:
                return storeAllCoverageData(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message storeAllCoverageData(Message request) {
        String packageName = request.getParameter("packageName");
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return AllCoverageManager.storeCoverageData(androidEnvironment, deviceID, packageName, chromosome, entity);
    }

    private Message storeLineCoverageData(Message request) {
        String packageName = request.getParameter("packageName");
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return LineCoverageManager.storeCoverageData(androidEnvironment, appsDir, deviceID, packageName, chromosome, entity);
    }

    private Message storeMethodCoverageData(Message request) {
        String packageName = request.getParameter("packageName");
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return MethodCoverageManager.storeCoverageData(androidEnvironment, deviceID, packageName, chromosome, entity);
    }

    private Message storeBranchCoverageData(Message request) {
        String packageName = request.getParameter("packageName");
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return BranchCoverageManager.storeCoverageData(androidEnvironment, deviceID, packageName, chromosome, entity);
    }

    private Message storeBasicBlockCoverageData(Message request) {
        String packageName = request.getParameter("packageName");
        String deviceID = request.getParameter("deviceId");
        String chromosome = request.getParameter("chromosome");
        String entity = request.getParameter("entity");
        return BasicBlockCoverageManager.storeCoverageData(androidEnvironment, deviceID, packageName, chromosome, entity);
    }

    private Message getCombinedCoverage(Message request) {

        // get the coverage type, e.g. BRANCH_COVERAGE
        Coverage coverage = Coverage.valueOf(request.getParameter("coverage_type"));

        switch (coverage) {
            case LINE_COVERAGE:
                return getCombinedLineCoverage(request);
            case BRANCH_COVERAGE:
                return getCombinedBranchCoverage(request);
            case METHOD_COVERAGE:
                return getCombinedMethodCoverage(request);
            case BASIC_BLOCK_LINE_COVERAGE:
                return getCombinedBasicBlockLineCoverage(request);
            case BASIC_BLOCK_BRANCH_COVERAGE:
                return getCombinedBasicBlockBranchCoverage(request);
            case ALL_COVERAGE:
                return getCombinedAllCoverage(request);
            default:
                throw new UnsupportedOperationException("Coverage type not yet supported!");
        }
    }

    private Message getCombinedAllCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return AllCoverageManager.getCombinedCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getCombinedLineCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String chromosomes = request.getParameter("chromosomes");
        return LineCoverageManager.getCombinedCoverage(appsDir, packageName, chromosomes);
    }

    private Message getCombinedMethodCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return MethodCoverageManager.getCombinedCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getCombinedBranchCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return BranchCoverageManager.getCombinedCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getCombinedBasicBlockBranchCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return BasicBlockCoverageManager.getCombinedBranchCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getCombinedBasicBlockLineCoverage(Message request) {
        String packageName = request.getParameter("packageName");
        String testcaseIds = request.getParameter("chromosomes");
        return BasicBlockCoverageManager.getCombinedLineCoverage(appsDir, packageName, testcaseIds);
    }

    private Message getLineCoveredPercentages(Message request) {
        String packageName = request.getParameter("packageName");
        String chromosomes = request.getParameter("chromosomes");
        return LineCoverageManager.getLineCoveredPercentages(appsDir, packageName, chromosomes);
    }

    private Message getSourceLines(Message request) {
        String packageName = request.getParameter("packageName");
        return LineCoverageManager.getSourceLines(appsDir, packageName);
    }

    private Message getNumberOfSourceLines(Message request) {
        String packageName = request.getParameter("packageName");
        return LineCoverageManager.getNumberOfSourceLines(appsDir, packageName);
    }

}