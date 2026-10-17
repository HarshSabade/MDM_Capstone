package mdm.capstone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/**
 * Singular Spectrum Analysis predictor with recurrent SSA forecasting.
 *
 * <p>The input sequence is sampled at capacity-observation points. Forecast
 * steps are mapped back to cycle numbers using the median observed cycle gap,
 * so missing capacity-cycle records are not silently treated as unit cycle
 * intervals.</p>
 */
public final class SsaPredictor implements RulPredictor, CapacityResidualProvider {
    private static final int MAX_FORECAST_STEPS = 5000;
    private static final int MIN_SERIES_LENGTH = 8;
    private static final int DEFAULT_MIN_WINDOW = 5;
    private static final int DEFAULT_MAX_WINDOW = 100;
    private static final double DEFAULT_WINDOW_FRACTION = 0.4;
    private static final double RECURRENCE_EPSILON = 1e-10;

    private final double windowFraction;
    private final int minimumWindow;
    private final int maximumWindow;
    private final int requestedComponentCount;

    public SsaPredictor() {
        this(DEFAULT_WINDOW_FRACTION, DEFAULT_MIN_WINDOW, DEFAULT_MAX_WINDOW, 0);
    }

    public SsaPredictor(double windowFraction, int minimumWindow, int maximumWindow,
            int componentCount) {
        if (!(windowFraction > 0.0 && windowFraction < 1.0)
                || minimumWindow < 2 || maximumWindow < minimumWindow
                || componentCount < 0) {
            throw new IllegalArgumentException("Invalid SSA configuration");
        }
        this.windowFraction = windowFraction;
        this.minimumWindow = minimumWindow;
        this.maximumWindow = maximumWindow;
        this.requestedComponentCount = componentCount;
    }

    @Override
    public String name() {
        return "ssa";
    }

    @Override
    public RulPrediction predict(List<Observation> observed, double eolCapacityAh) {
        if (observed == null || observed.isEmpty()) {
            return new RulPrediction(name(), 0, "insufficient_observations",
                    Double.NaN, new double[0]);
        }

        List<Observation> ordered = new ArrayList<>(observed);
        ordered.sort(Comparator.comparingInt(Observation::cycle));
        if (ordered.size() < MIN_SERIES_LENGTH) {
            return failure(ordered, "insufficient_observations");
        }

        double[] signal = ordered.stream().mapToDouble(Observation::capacityAh).toArray();
        int n = signal.length;
        int windowLength = computeWindowLength(n);
        int columns = n - windowLength + 1;
        if (windowLength < 2 || columns < 2) {
            return failure(ordered, "invalid_window");
        }

        RealMatrix trajectory = buildTrajectoryMatrix(signal, windowLength, columns);
        SingularValueDecomposition svd = new SingularValueDecomposition(trajectory);
        int maximumRank = Math.min(windowLength, columns);
        int rank = requestedComponentCount == 0
                ? selectRank(svd.getSingularValues())
                : requestedComponentCount;
        if (rank < 1 || rank > maximumRank) {
            return failure(ordered, "invalid_components");
        }

        double[] reconstructed = diagonalAverage(
                reconstruct(svd, rank), windowLength, columns, n);
        double fitRmse = rmse(signal, reconstructed);
        double[] recurrence = recurrentCoefficients(svd.getU(), rank, windowLength);
        if (recurrence == null) {
            return new RulPrediction(name(), currentCycle(ordered),
                    "invalid_recurrence", fitRmse,
                    diagnostics(windowLength, rank, Double.NaN, fitRmse));
        }

        int currentCycle = currentCycle(ordered);
        int cycleGap = medianCycleGap(ordered);
        double[] forecastHistory = signal.clone();
        for (int step = 1; step <= MAX_FORECAST_STEPS; step++) {
            double next = recurrentNext(forecastHistory, recurrence);
            if (!Double.isFinite(next)) {
                return new RulPrediction(name(), currentCycle, "forecast_failed",
                        fitRmse, diagnostics(windowLength, rank, Double.NaN, fitRmse));
            }
            forecastHistory = append(forecastHistory, next, windowLength);
            if (next <= eolCapacityAh) {
                int predictedCycle = currentCycle + step * cycleGap;
                return new RulPrediction(name(), predictedCycle, "ok", fitRmse,
                        diagnostics(windowLength, rank, recurrence[recurrence.length - 1],
                                fitRmse));
            }
        }
        return new RulPrediction(name(), currentCycle, "eol_not_reached", fitRmse,
                diagnostics(windowLength, rank, recurrence[recurrence.length - 1], fitRmse));
    }

    @Override
    public CapacityFit fittedCapacity(List<Observation> observed) {
        if (observed == null || observed.size() < MIN_SERIES_LENGTH) {
            return null;
        }
        List<Observation> ordered = new ArrayList<>(observed);
        ordered.sort(Comparator.comparingInt(Observation::cycle));
        double[] signal = ordered.stream().mapToDouble(Observation::capacityAh).toArray();
        int windowLength = computeWindowLength(signal.length);
        int columns = signal.length - windowLength + 1;
        if (columns < 2) {
            return null;
        }
        SingularValueDecomposition svd = new SingularValueDecomposition(
                buildTrajectoryMatrix(signal, windowLength, columns));
        int rank = requestedComponentCount == 0 ? selectRank(svd.getSingularValues())
                : requestedComponentCount;
        if (rank < 1 || rank > Math.min(windowLength, columns)) {
            return null;
        }
        double[] fitted = diagonalAverage(reconstruct(svd, rank), windowLength, columns,
                signal.length);
        double[] residuals = new double[signal.length];
        for (int index = 0; index < signal.length; index++) {
            residuals[index] = signal[index] - fitted[index];
        }
        return new CapacityFit(fitted, residuals);
    }

    private int computeWindowLength(int n) {
        int candidate = (int) Math.round(n * windowFraction);
        return Math.max(minimumWindow, Math.min(Math.min(candidate, maximumWindow), n - 2));
    }

    private static RealMatrix buildTrajectoryMatrix(double[] signal, int windowLength,
            int columns) {
        RealMatrix trajectory = new Array2DRowRealMatrix(windowLength, columns);
        for (int row = 0; row < windowLength; row++) {
            for (int column = 0; column < columns; column++) {
                trajectory.setEntry(row, column, signal[row + column]);
            }
        }
        return trajectory;
    }

    private static RealMatrix reconstruct(SingularValueDecomposition svd, int rank) {
        RealMatrix u = svd.getU().getSubMatrix(0, svd.getU().getRowDimension() - 1,
                0, rank - 1);
        RealMatrix v = svd.getV().getSubMatrix(0, svd.getV().getRowDimension() - 1,
                0, rank - 1);
        double[][] diagonal = new double[rank][rank];
        double[] singularValues = svd.getSingularValues();
        for (int index = 0; index < rank; index++) {
            diagonal[index][index] = singularValues[index];
        }
        return u.multiply(new Array2DRowRealMatrix(diagonal)).multiply(v.transpose());
    }

    private static int selectRank(double[] singularValues) {
        if (singularValues.length == 0 || singularValues[0] <= 0.0) {
            return 1;
        }
        int rank = 0;
        double total = 0.0;
        for (double singularValue : singularValues) {
            total += singularValue * singularValue;
        }
        double retained = 0.0;
        for (double singularValue : singularValues) {
            retained += singularValue * singularValue;
            rank++;
            if (rank >= 5 || retained / total >= 0.95) {
                break;
            }
        }
        return Math.max(1, rank);
    }

    private static double[] diagonalAverage(RealMatrix reconstructed, int windowLength,
            int columns, int signalLength) {
        double[] result = new double[signalLength];
        int[] counts = new int[signalLength];
        for (int row = 0; row < windowLength; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row + column;
                result[index] += reconstructed.getEntry(row, column);
                counts[index]++;
            }
        }
        for (int index = 0; index < signalLength; index++) {
            result[index] /= counts[index];
        }
        return result;
    }

    /**
     * Computes the recurrent SSA coefficients:
     * R = (1 - ||pi_L(U)||^2)^-1 U_(1:L-1) pi_L(U).
     */
    private static double[] recurrentCoefficients(RealMatrix u, int rank, int windowLength) {
        double denominator = 1.0;
        for (int component = 0; component < rank; component++) {
            double lastLoading = u.getEntry(windowLength - 1, component);
            denominator -= lastLoading * lastLoading;
        }
        if (Math.abs(denominator) <= RECURRENCE_EPSILON) {
            return null;
        }
        double[] coefficients = new double[windowLength - 1];
        for (int lag = 0; lag < coefficients.length; lag++) {
            for (int component = 0; component < rank; component++) {
                coefficients[lag] += u.getEntry(lag, component)
                        * u.getEntry(windowLength - 1, component);
            }
            coefficients[lag] /= denominator;
        }
        return coefficients;
    }

    private static double recurrentNext(double[] history, double[] coefficients) {
        int start = history.length - coefficients.length;
        double next = 0.0;
        for (int lag = 0; lag < coefficients.length; lag++) {
            next += coefficients[lag] * history[start + lag];
        }
        return next;
    }

    private static double[] append(double[] history, double value, int windowLength) {
        int length = Math.min(history.length + 1, Math.max(windowLength, history.length + 1));
        double[] result = new double[length];
        int sourceStart = Math.max(0, history.length + 1 - length);
        int copied = history.length - sourceStart;
        System.arraycopy(history, sourceStart, result, 0, copied);
        result[copied] = value;
        return result;
    }

    private static int medianCycleGap(List<Observation> observations) {
        List<Integer> gaps = new ArrayList<>();
        for (int index = 1; index < observations.size(); index++) {
            int gap = observations.get(index).cycle() - observations.get(index - 1).cycle();
            if (gap > 0) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 1;
        }
        gaps.sort(Integer::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    private static int currentCycle(List<Observation> observations) {
        return observations.get(observations.size() - 1).cycle();
    }

    private static double[] diagnostics(int windowLength, int rank, double recurrenceTail,
            double fitRmse) {
        return new double[] {windowLength, rank, recurrenceTail, fitRmse};
    }

    private static double rmse(double[] actual, double[] rebuilt) {
        double total = 0.0;
        for (int index = 0; index < actual.length; index++) {
            double difference = actual[index] - rebuilt[index];
            total += difference * difference;
        }
        return Math.sqrt(total / actual.length);
    }

    private RulPrediction failure(List<Observation> observed, String status) {
        int cycle = observed.isEmpty() ? 0 : currentCycle(observed);
        return new RulPrediction(name(), cycle, status, Double.NaN, new double[0]);
    }
}
