package mdm.capstone;

import java.util.List;

public interface RulPredictor {
    String name();

    RulPrediction predict(List<Observation> observed, double eolCapacityAh);
}
