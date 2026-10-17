package mdm.capstone;

public record RulPrediction(String method, int predictedEolCycle, String status,
        double fitRmse, double[] parameters) {
    public int predictedRul(int currentCycle) {
        return Math.max(0, predictedEolCycle - currentCycle);
    }
}
