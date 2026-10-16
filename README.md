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
- `model/` - Maven Java project containing leakage-safe linear and similarity
  RUL baselines and SVG plot generation
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

Remove-Item ".\classpath.txt"
```

The baseline outputs include:

- Linear and similarity RUL predictions
- MAE, RMSE, and MAPE metrics
- Actual-versus-predicted RUL plot
- Absolute RUL error plot
- MAE, RMSE, and MAPE comparison plots

The current modeling stage is a baseline implementation. The paper-faithful
RSSA/CWS-RSSA implementation is not yet included.

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
