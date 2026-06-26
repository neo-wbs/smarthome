package monitoring;

import java.time.Instant;

public record Alarm (Instant zeitstempel, double wert, double schwellenwert, String typ) {
    @Override
    public String toString() {
        return "Typ: " + typ + "(" + zeitstempel + ", " + wert + ", " + schwellenwert + ")";
    }
}
