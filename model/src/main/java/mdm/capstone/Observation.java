package mdm.capstone;

public record Observation(String batteryId, int cycle, double capacityAh,
        Integer eolCycle) {
}
