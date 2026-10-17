package mdm.capstone;

import java.util.List;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresProblem;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.fitting.leastsquares.ParameterValidator;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

public final class DoubleExponentialPredictor implements RulPredictor, CapacityResidualProvider {
    private static final int MAX_FORECAST_CYCLES = 5000;
    private static final double MIN_RATE = 1e-8;
    private static final double MAX_PARAMETER = 10.0;

    @Override
    public String name() {
        return "double_exponential";
    }

    @Override
    public RulPrediction predict(List<Observation> observed, double eolCapacityAh) {
        if (observed.size() < 5) {
            return failure(observed, "insufficient_observations");
        }
        double origin = observed.get(0).cycle();
        double[] x = observed.stream().mapToDouble(o -> o.cycle() - origin).toArray();
        double[] y = observed.stream().mapToDouble(Observation::capacityAh).toArray();
        double maxCapacity = observed.stream().mapToDouble(Observation::capacityAh).max().orElse(2.0);
        double minCapacity = observed.stream().mapToDouble(Observation::capacityAh).min().orElse(1.0);
        double[] initial = {maxCapacity * 0.6, 0.01, maxCapacity * 0.3, 0.001, minCapacity * 0.1};
        try {
            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(initial)
                    .model(point -> valueAndJacobian(point, x))
                    .target(y)
                    .lazyEvaluation(false)
                    .maxIterations(2000)
                    .maxEvaluations(4000)
                    .parameterValidator(new ClampingValidator())
                    .build();
            LeastSquaresOptimizer.Optimum optimum =
                    new LevenbergMarquardtOptimizer().optimize(problem);
            double[] parameters = optimum.getPoint().toArray();
            int eol = findEol(parameters, origin, observed.get(observed.size() - 1).cycle(),
                    eolCapacityAh);
            if (eol < 0) {
                return new RulPrediction(name(), observed.get(observed.size() - 1).cycle(),
                        "eol_not_reached", rmse(optimum), parameters);
            }
            return new RulPrediction(name(), eol, "ok", rmse(optimum), parameters);
        } catch (RuntimeException exception) {
            return failure(observed, "fit_failed:" + exception.getClass().getSimpleName());
        }
    }

    @Override
    public CapacityFit fittedCapacity(List<Observation> observed) {
        Fit fit = fit(observed);
        return fit == null ? null : new CapacityFit(fit.fitted(), fit.residuals());
    }

    private static Fit fit(List<Observation> observed) {
        if (observed == null || observed.size() < 5) {
            return null;
        }
        double origin = observed.get(0).cycle();
        double[] x = observed.stream().mapToDouble(o -> o.cycle() - origin).toArray();
        double[] y = observed.stream().mapToDouble(Observation::capacityAh).toArray();
        double maxCapacity = observed.stream().mapToDouble(Observation::capacityAh).max().orElse(2.0);
        double minCapacity = observed.stream().mapToDouble(Observation::capacityAh).min().orElse(1.0);
        double[] initial = {maxCapacity * 0.6, 0.01, maxCapacity * 0.3, 0.001,
            minCapacity * 0.1};
        try {
            LeastSquaresProblem problem = new LeastSquaresBuilder()
                    .start(initial)
                    .model(point -> valueAndJacobian(point, x))
                    .target(y)
                    .lazyEvaluation(false)
                    .maxIterations(2000)
                    .maxEvaluations(4000)
                    .parameterValidator(new ClampingValidator())
                    .build();
            LeastSquaresOptimizer.Optimum optimum =
                    new LevenbergMarquardtOptimizer().optimize(problem);
            double[] fitted = values(optimum.getPoint().toArray(), x);
            double[] residuals = new double[y.length];
            for (int index = 0; index < y.length; index++) {
                residuals[index] = y[index] - fitted[index];
            }
            return new Fit(fitted, residuals);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static double[] values(double[] parameters, double[] x) {
        double[] result = new double[x.length];
        for (int index = 0; index < x.length; index++) {
            result[index] = parameters[0] * Math.exp(-parameters[1] * x[index])
                    + parameters[2] * Math.exp(-parameters[3] * x[index]) + parameters[4];
        }
        return result;
    }

    private record Fit(double[] fitted, double[] residuals) {
    }

    private static Pair<RealVector, RealMatrix> valueAndJacobian(
            RealVector parameters, double[] x) {
        double a = parameters.getEntry(0);
        double b = Math.max(MIN_RATE, parameters.getEntry(1));
        double c = parameters.getEntry(2);
        double d = Math.max(MIN_RATE, parameters.getEntry(3));
        double e = parameters.getEntry(4);
        double[] values = new double[x.length];
        double[][] jacobian = new double[x.length][5];
        for (int i = 0; i < x.length; i++) {
            double first = Math.exp(-b * x[i]);
            double second = Math.exp(-d * x[i]);
            values[i] = a * first + c * second + e;
            jacobian[i][0] = first;
            jacobian[i][1] = -a * x[i] * first;
            jacobian[i][2] = second;
            jacobian[i][3] = -c * x[i] * second;
            jacobian[i][4] = 1.0;
        }
        return new Pair<>(new ArrayRealVector(values),
                new org.apache.commons.math3.linear.Array2DRowRealMatrix(jacobian));
    }

    private static int findEol(double[] p, double origin, int currentCycle, double threshold) {
        for (int cycle = currentCycle; cycle <= currentCycle + MAX_FORECAST_CYCLES; cycle++) {
            double x = cycle - origin;
            double value = p[0] * Math.exp(-p[1] * x)
                    + p[2] * Math.exp(-p[3] * x) + p[4];
            if (value <= threshold) {
                return cycle;
            }
        }
        return -1;
    }

    private static double rmse(LeastSquaresOptimizer.Optimum optimum) {
        return Math.sqrt(optimum.getResiduals().dotProduct(optimum.getResiduals())
                / optimum.getResiduals().getDimension());
    }

    private static RulPrediction failure(List<Observation> observed, String status) {
        return new RulPrediction("double_exponential",
                observed.get(observed.size() - 1).cycle(), status, Double.NaN, new double[0]);
    }

    private static final class ClampingValidator implements ParameterValidator {
        @Override
        public RealVector validate(RealVector params) {
            double[] values = params.toArray();
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i])) {
                    values[i] = 0.0;
                }
                values[i] = Math.max(0.0, Math.min(MAX_PARAMETER, values[i]));
            }
            values[1] = Math.max(MIN_RATE, values[1]);
            values[3] = Math.max(MIN_RATE, values[3]);
            return new ArrayRealVector(values, false);
        }
    }
}
