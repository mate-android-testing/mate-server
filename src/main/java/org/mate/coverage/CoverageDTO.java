package org.mate.coverage;

import java.util.Objects;

/**
 * A dto to transmit coverage information.
 */
public class CoverageDTO {

    /**
     * The current method coverage or {@code null} if not specified.
     */
    private Double methodCoverage;

    /**
     * The current branch coverage or {@code null} if not specified.
     */
    private Double branchCoverage;

    /**
     * The current line coverage or {@code null} if not specified.
     */
    private Double lineCoverage;

    /**
     * Returns the method coverage.
     *
     * @return Returns the method coverage.
     */
    public double getMethodCoverage() {
        return methodCoverage;
    }

    /**
     * Specifies the method coverage.
     *
     * @param methodCoverage The new method coverage.
     */
    public void setMethodCoverage(double methodCoverage) {
        this.methodCoverage = methodCoverage;
    }

    /**
     * Returns the branch coverage.
     *
     * @return Returns the branch coverage.
     */
    public double getBranchCoverage() {
        return branchCoverage;
    }

    /**
     * Specifies the branch coverage.
     *
     * @param branchCoverage The new branch coverage.
     */
    public void setBranchCoverage(double branchCoverage) {
        this.branchCoverage = branchCoverage;
    }

    /**
     * Returns the line coverage.
     *
     * @return Returns the line coverage.
     */
    public double getLineCoverage() {
        return lineCoverage;
    }

    /**
     * Specifies the line coverage.
     *
     * @param lineCoverage The new line coverage.
     */
    public void setLineCoverage(double lineCoverage) {
        this.lineCoverage = lineCoverage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (o == null || getClass() != o.getClass()) {
            return false;
        } else {
            CoverageDTO that = (CoverageDTO) o;
            return methodCoverage.equals(that.methodCoverage)
                    && branchCoverage.equals(that.branchCoverage)
                    && lineCoverage.equals(that.lineCoverage);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodCoverage, branchCoverage, lineCoverage);
    }

    /**
     * Returns a string representation of the coverage dto.
     *
     * @return Returns a textual representation of the coverage dto.
     */
    @Override
    public String toString() {
        return "Coverage{" +
                "methodCoverage=" + methodCoverage +
                ", branchCoverage=" + branchCoverage +
                ", lineCoverage=" + lineCoverage +
                '}';
    }
}
