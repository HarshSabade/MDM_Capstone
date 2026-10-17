# NASA Battery RUL Project

Java-first exploratory data analysis and baseline remaining useful life (RUL)
estimation for the NASA battery aging dataset.

The repository intentionally does not contain the NASA dataset, raw MATLAB
files, ZIP archives, papers, or local Maven installations. Place the converted
NASA CSV files in the dataset directory described below before running the
pipeline.

## Project contents

- `eda/` - Java data validation, capacity/RUL table generation, signal summaries,
  and SVG EDA plots
- `model/` - Maven Java project containing leakage-safe linear, similarity,
  double-exponential, and SSA-style recurrent RUL baselines plus SVG plot generation
- `model-output/` - generated baseline predictions, metrics, and plots
- `.github/` - existing project workflow metadata

## Expected input files

Place these files in:

```text
NASA dataset/5. Battery Data Set/1. BatteryAgingARC-FY08Q4/
```

```text
B0005_capacity.csv
B0005_cycles.csv
B0006_capacity.csv
B0006_cycles.csv
B0007_capacity.csv
B0007_cycles.csv
B0018_capacity.csv
B0018_cycles.csv
```

The raw files are ignored by Git and must be obtained separately.

## Java EDA

Compile and run the validation tools from the `eda` directory:

```powershell
javac DataQualityReport.java FieldQualityReport.java CycleSignalSummary.java CapacityEda.java

java DataQualityReport `
  "..\NASA dataset\5. Battery Data Set\1. BatteryAgingARC-FY08Q4" `
  "quality_report.csv"

java FieldQualityReport `
  "..\NASA dataset\5. Battery Data Set\1. BatteryAgingARC-FY08Q4" `
  "field_quality_report.csv"

java CycleSignalSummary `
  "..\NASA dataset\5. Battery Data Set\1. BatteryAgingARC-FY08Q4" `
  "cycle_signal_summary.csv"

java CapacityEda `
  "..\NASA dataset\5. Battery Data Set\1. BatteryAgingARC-FY08Q4" `
  "capacity"
```

The capacity EDA generates:

- `capacity/canonical_capacity_rul.csv`
- `capacity/capacity_summary.csv`
- `capacity/capacity_soh_trajectories.svg`

## Java baseline modeling

The model uses Maven and Apache Commons Math:

```powershell
cd model
mvn compile
mvn dependency:build-classpath "-Dmdep.outputFile=classpath.txt"

$cp = (Get-Content -Raw ".\classpath.txt").Trim() + ";target\classes"
java -cp $cp mdm.capstone.RulBaselineRunner `
  "..\eda\capacity\canonical_capacity_rul.csv" `
  "..\model-output"

java -cp $cp mdm.capstone.BaselinePlotGenerator `
  "..\model-output\rul_predictions.csv" `
  "..\model-output\rul_metrics.csv" `
  "..\model-output\plots"

java -cp $cp mdm.capstone.RulReportGenerator `
  "..\model-output" `
  "..\model-output\rul_report.html"

Remove-Item ".\classpath.txt"
```

The baseline outputs include:

- Linear and similarity RUL predictions
- MAE, RMSE, and MAPE metrics
- Actual-versus-predicted RUL plot
- Absolute RUL error plot
- MAE, RMSE, and MAPE comparison plots
- MAE, RMSE, and MAPE comparisons include both logistic fusion variants
- Bootstrap RUL interval plot
- Standalone HTML report with prediction, uncertainty, metric, and footprint tables

The current modeling stage includes:

- A linear degradation baseline
- A leave-one-battery-out expanding-window evaluation
- An RMSE-plus-offset similarity predictor
- A bounded double-exponential trend predictor using Apache Commons Math
- A recurrent SSA-style capacity forecasting baseline using singular-value reconstruction
- Stage-based logistic fusion of similarity with the double-exponential and SSA predictors

For each test battery, the observed prefixes are 20%, 30%, 40%, 50%, 60%,
70%, and 80% of its observed pre-EOL trajectory. The test battery is excluded
from the reference database. Each reference prefix is shifted by:

```text
offset = target_latest_capacity - reference_latest_capacity
```

The reference with the lowest RMSE between the target prefix and shifted
reference prefix is selected. Its post-prefix trajectory is mapped to the test
battery to estimate the EOL cycle and RUL.

The prediction output contains one row per battery, observation fraction, and
predictor. Double-exponential and SSA rows include fit diagnostics such as
parameters, fit RMSE, and explicit status flags. Metrics report both total
cases and successful cases; failed or non-crossing fits are not silently scored
as valid forecasts.

The fusion predictors use the chronological observed fraction as their stage
variable. Their similarity weight is:

```text
w = 1 / (1 + exp(k * (observed_fraction - inflection)))
```

The default configuration is `k = 10` and `inflection = 0.5`. The fused RUL is
rounded from `w * similarity_rul + (1 - w) * trend_rul`. A fusion row is only
marked successful when both component predictors succeed.

Every successful point prediction also receives a reproducible residual-bootstrap
interval. The default is 200 refits at 95% confidence using seed `20261017`.
Residuals are estimated from the fitted model when supported by the predictor
(linear, double exponential, SSA, and fusion trend components); the linear
prefix fit is the fallback. Resampled residuals are added to fitted synthetic
prefixes and passed through the complete predictor again. Bootstrap failures
are recorded explicitly and are excluded from interval coverage and width
metrics. Prediction output includes lower, median, and upper RUL bounds;
metrics include interval coverage and width.

The evaluator also writes `predictor_profiles.csv`, containing elapsed time and
heap measurements for each predictor case. `RulReportGenerator` creates a
dependency-free HTML report that links the SVG plots and renders all generated
CSV tables.

## EOL convention

The current pipeline uses:

```text
EOL capacity = 1.4 Ah
Nominal capacity = 2.0 Ah
SOH = capacity / 2.0
```

B0007 does not cross the 1.4 Ah threshold in the available data and is treated
as EOL-censored rather than being assigned a fabricated RUL target.

## Reproducibility

Generated CSV and SVG outputs are retained for inspection. They can be
regenerated from the ignored NASA CSV inputs using the commands above.
