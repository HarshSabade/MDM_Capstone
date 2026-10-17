package mdm.capstone;

import java.util.List;

interface CapacityResidualProvider {
    CapacityFit fittedCapacity(List<Observation> observed);

    record CapacityFit(double[] fitted, double[] residuals) {
    }
}
